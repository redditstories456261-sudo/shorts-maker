package com.shortsmaker.app.pipeline

import android.content.Context
import com.shortsmaker.app.model.ClipResult
import com.shortsmaker.app.model.PipelineProgress
import com.shortsmaker.app.model.Stage
import java.io.File

class PipelineConfig(
    val clipCount: Int = 5,
    val minClipSec: Double = 10.0,
    val maxClipSec: Double = 15.0,          // "12 sec"-ish range; tune freely
    val languageHint: String? = null,       // "en" | "ur" | "hi" | null = auto
    val whisperModelFile: File,
    val faceDetectorFile: File,
    val faceLandmarkerFile: File?,
    val llmProvider: String = "none",
    val llmApiKey: String? = null,
    val llmBaseUrl: String = "https://api.openai.com/v1",
    val llmModel: String = "gpt-4o-mini"
)

class ShortsPipeline(private val context: Context) {

    suspend fun run(input: String, config: PipelineConfig, onProgress: (PipelineProgress) -> Unit): List<ClipResult> {
        val videoPath: String = if (Downloader.isUrl(input)) {
            onProgress(PipelineProgress(Stage.DOWNLOAD, "Downloading video…", 0f))
            Downloader.download(context, input) { frac, line ->
                onProgress(PipelineProgress(Stage.DOWNLOAD, line.ifBlank { "Downloading…" }, frac))
            }.absolutePath
        } else input

        onProgress(PipelineProgress(Stage.TRANSCRIBE, "Loading Whisper model…", 0f))
        val whisper = WhisperBridge(context)
        whisper.loadModel(config.whisperModelFile)
        val transcript = try {
            whisper.transcribe(videoPath, config.languageHint) { msg ->
                onProgress(PipelineProgress(Stage.TRANSCRIBE, msg, 0.3f))
            }
        } finally {
            whisper.release()
        }

        onProgress(PipelineProgress(Stage.DETECT_MOMENTS, "Finding the best moments…", 0f))
        val units = MomentDetector.buildUnits(transcript.words)
        val totalSec = transcript.words.lastOrNull()?.endSec ?: 0.0
        val candidates = MomentDetector.findCandidates(units, config.minClipSec, config.maxClipSec)
        val top = MomentDetector.pickTop(candidates, config.clipCount)
        for (c in top) {
            MomentDetector.finalizeBounds(c, units, transcript.words, totalSec, config.maxClipSec)
            MomentDetector.heuristicMeta(c, units)
        }

        val scorer = TitleScorer(config.llmProvider, config.llmBaseUrl, config.llmModel, config.llmApiKey)
        val languageName = mapOf("en" to "English", "ur" to "Urdu", "hi" to "Hindi")[transcript.language] ?: transcript.language

        val outDir = File(context.getExternalFilesDir(null), "clips").apply { mkdirs() }
        val fontsDir = File(context.filesDir, "fonts").apply { mkdirs() }  // populate per NATIVE_SETUP.md / README

        val faceTracker = FaceTracker(context, config.faceDetectorFile, config.faceLandmarkerFile)
        val results = mutableListOf<ClipResult>()
        try {
            for ((i, c) in top.sortedBy { it.startSec }.withIndex()) {
                onProgress(PipelineProgress(Stage.FACE_TRACK, "Tracking faces (clip ${i + 1}/${top.size})…", 0f, i, top.size))
                val faces = faceTracker.analyze(videoPath, c.startSec, c.endSec - c.startSec) { frac ->
                    onProgress(PipelineProgress(Stage.FACE_TRACK, "Tracking faces (clip ${i + 1}/${top.size})…", frac, i, top.size))
                }
                val plan = ReframePlanner.plan(faces.tracks, faces.fps, c.endSec - c.startSec, faces.srcW, faces.srcH)

                onProgress(PipelineProgress(Stage.CAPTION, "Building captions (clip ${i + 1}/${top.size})…", 0f, i, top.size))
                val assFile = File(context.cacheDir, "cap_${i}_${System.currentTimeMillis()}.ass")
                CaptionBuilder.build(transcript.words, c.startSec, c.endSec, assFile)

                onProgress(PipelineProgress(Stage.RENDER, "Rendering clip ${i + 1}/${top.size}…", 0f, i, top.size))
                val outFile = File(outDir, "clip_${i + 1}.mp4")
                Renderer(context).render(videoPath, c.startSec, c.endSec, plan, assFile, fontsDir, outFile) { }
                assFile.delete()

                onProgress(PipelineProgress(Stage.TITLE, "Writing title (clip ${i + 1}/${top.size})…", 0f, i, top.size))
                scorer.score(c, languageName)

                results.add(
                    ClipResult(
                        index = i + 1, sourceStart = c.startSec, sourceEnd = c.endSec,
                        title = c.title, hookLine = c.hookLine, viralityScore = c.viralityScore.takeIf { it > 0 } ?: estimateScore(c),
                        reason = c.reason, outputPath = outFile.absolutePath
                    )
                )
            }
        } finally {
            faceTracker.close()
        }

        onProgress(PipelineProgress(Stage.DONE, "Done", 1f))
        return results
    }

    private fun estimateScore(c: com.shortsmaker.app.model.Candidate): Int = c.score.toInt().coerceIn(0, 100)
}
