package com.shortsmaker.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.shortsmaker.app.model.ClipResult
import com.shortsmaker.app.model.PipelineProgress
import com.shortsmaker.app.pipeline.PipelineConfig
import com.shortsmaker.app.pipeline.ShortsPipeline
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {

    private var inputState: MutableState<String> = mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Handle "Share -> ShortsMaker" from the YouTube app / browser.
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { inputState.value = it }
        }

        setContent {
            MaterialTheme {
                AppScreen(inputState, intentProvider = { intent })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScreen(inputState: MutableState<String>, intentProvider: () -> Intent?) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var pickedFilePath by remember { mutableStateOf<String?>(null) }
    var clipCount by remember { mutableStateOf("5") }
    var language by remember { mutableStateOf("Auto") }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<PipelineProgress?>(null) }
    var results by remember { mutableStateOf<List<ClipResult>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            pickedFilePath = it.toString()
            inputState.value = ""
        }
    }

    // No PC/adb? Import each downloaded model file straight from the phone's Downloads via the
    // system file picker - copies it into the app's private storage at a fixed path. See
    // GITHUB_BUILD.md step 6.
    val modelsDir = remember { File(context.filesDir, "models").apply { mkdirs() } }
    val whisperFile = remember { File(modelsDir, "whisper.bin") }
    val faceDetFile = remember { File(modelsDir, "face_detector.task") }
    val faceLmFile = remember { File(modelsDir, "face_landmarker.task") }
    var modelsVersion by remember { mutableStateOf(0) }  // bump to force the ✓/… labels to refresh

    fun importPicker(target: File) = { uri: Uri? ->
        uri?.let {
            context.contentResolver.openInputStream(it)?.use { input -> target.outputStream().use { out -> input.copyTo(out) } }
            modelsVersion++
        }
        Unit
    }
    val pickWhisper = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { importPicker(whisperFile)(it) }
    val pickFaceDet = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { importPicker(faceDetFile)(it) }
    val pickFaceLm = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { importPicker(faceLmFile)(it) }

    Scaffold(topBar = { TopAppBar(title = { Text("Shorts Maker") }) }) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Paste a video link, or pick a file from your phone.", style = MaterialTheme.typography.bodyMedium)

            OutlinedTextField(
                value = inputState.value,
                onValueChange = { inputState.value = it; pickedFilePath = null },
                label = { Text("Video URL (YouTube, etc.)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !running
            )

            Button(onClick = { pickVideo.launch(arrayOf("video/*")) }, enabled = !running) {
                Text(if (pickedFilePath != null) "File selected ✓ (tap to change)" else "Pick a video file instead")
            }

            HorizontalDivider()
            Text("One-time: import AI model files (see GITHUB_BUILD.md step 6)", style = MaterialTheme.typography.labelMedium)
            @Suppress("UNUSED_EXPRESSION") modelsVersion  // read so these labels recompose after an import
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickWhisper.launch(arrayOf("*/*")) }, enabled = !running) {
                    Text(if (whisperFile.exists()) "Whisper ✓" else "Import Whisper (.bin)")
                }
                OutlinedButton(onClick = { pickFaceDet.launch(arrayOf("*/*")) }, enabled = !running) {
                    Text(if (faceDetFile.exists()) "Face ✓" else "Import face_detector.task")
                }
                OutlinedButton(onClick = { pickFaceLm.launch(arrayOf("*/*")) }, enabled = !running) {
                    Text(if (faceLmFile.exists()) "Lips ✓" else "Import face_landmarker.task (optional)")
                }
            }
            HorizontalDivider()

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = clipCount, onValueChange = { clipCount = it.filter(Char::isDigit) },
                    label = { Text("Clips") }, modifier = Modifier.width(100.dp), enabled = !running
                )
                var langMenu by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { langMenu = true }, enabled = !running) { Text(language) }
                    DropdownMenu(expanded = langMenu, onDismissRequest = { langMenu = false }) {
                        listOf("Auto", "English", "Urdu", "Hindi").forEach { opt ->
                            DropdownMenuItem(text = { Text(opt) }, onClick = { language = opt; langMenu = false })
                        }
                    }
                }
            }

            Button(
                enabled = !running && (inputState.value.isNotBlank() || pickedFilePath != null),
                onClick = {
                    running = true; error = null; results = emptyList()
                    scope.launch {
                        try {
                            if (!whisperFile.exists() || !faceDetFile.exists()) {
                                error = "Import the Whisper model and face_detector.task first (buttons above) - see GITHUB_BUILD.md step 6."
                                running = false
                                return@launch
                            }
                            val langHint = when (language) { "English" -> "en"; "Urdu" -> "ur"; "Hindi" -> "hi"; else -> null }
                            val input = pickedFilePath ?: inputState.value.trim()
                            val resolvedInput = if (pickedFilePath != null) copyUriToCache(context, Uri.parse(pickedFilePath)) else input
                            val config = PipelineConfig(
                                clipCount = clipCount.toIntOrNull()?.coerceIn(1, 20) ?: 5,
                                languageHint = langHint,
                                whisperModelFile = whisperFile,
                                faceDetectorFile = faceDetFile,
                                faceLandmarkerFile = faceLmFile.takeIf { it.exists() }
                            )
                            results = ShortsPipeline(context).run(resolvedInput, config) { p -> progress = p }
                        } catch (e: Exception) {
                            error = e.message ?: e.toString()
                        } finally {
                            running = false
                        }
                    }
                }
            ) { Text("Make shorts") }

            if (running) {
                val p = progress
                Column {
                    LinearProgressIndicator(progress = { p?.fraction ?: 0f }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    Text(p?.let { "${it.stage}: ${it.message}" } ?: "Starting…", style = MaterialTheme.typography.bodySmall)
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(results) { clip -> ClipCard(clip, context) }
            }
        }
    }
}

@Composable
private fun ClipCard(clip: ClipResult, context: android.content.Context) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("#${clip.index}  ${clip.title}", style = MaterialTheme.typography.titleMedium)
            Text(clip.hookLine, style = MaterialTheme.typography.bodyMedium)
            Text("Virality score: ${clip.viralityScore}/100", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        context, "${context.packageName}.fileprovider", File(clip.outputPath)
                    )
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "video/mp4"; putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(share, "Share clip"))
                }) { Text("Share") }
                OutlinedButton(onClick = {
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        context, "${context.packageName}.fileprovider", File(clip.outputPath)
                    )
                    val view = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "video/mp4"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(view)
                }) { Text("Play") }
            }
        }
    }
}

private fun copyUriToCache(context: android.content.Context, uri: Uri): String {
    val out = File(context.cacheDir, "input_${System.currentTimeMillis()}.mp4")
    context.contentResolver.openInputStream(uri)!!.use { input -> out.outputStream().use { input.copyTo(it) } }
    return out.absolutePath
}
