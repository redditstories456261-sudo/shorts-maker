package com.shortsmaker.app.pipeline

import android.content.Context
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.shortsmaker.app.model.Transcript
import com.shortsmaker.app.model.Word
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * On-device speech-to-text with word-level timestamps, via whisper.cpp (JNI, see
 * app/src/main/cpp/). Auto-detects language; works for English/Urdu/Hindi out of the box since
 * that's native Whisper behaviour - no extra setup needed per language.
 *
 * NOTE: whisper.cpp's "word-level" timestamps are really per-token (sub-word for some scripts,
 * especially Urdu/Hindi). [MomentDetector] only needs reasonably-timed chunks of text to build
 * sentences from, so this is fine in practice, but don't expect perfectly word-aligned captions
 * the way the desktop (faster-whisper) pipeline gets them - test on your own content and bump
 * the model size (base -> small) if caption timing looks off.
 */
class WhisperBridge(private val context: Context) {

    private var ctxPtr: Long = 0

    companion object {
        init {
            System.loadLibrary("whisper_android")
        }
    }

    // --- JNI declarations (implemented in app/src/main/cpp/jni_bridge.cpp) ---
    private external fun nativeInitContext(modelPath: String): Long
    private external fun nativeFreeContext(ctxPtr: Long)
    private external fun nativeFullTranscribe(ctxPtr: Long, audio: FloatArray, language: String?, numThreads: Int): String
    private external fun nativeDetectedLanguage(ctxPtr: Long): String

    fun loadModel(modelFile: File) {
        check(modelFile.exists()) { "Whisper model not found at ${modelFile.path}. See README: download a ggml *.bin model." }
        ctxPtr = nativeInitContext(modelFile.absolutePath)
        check(ctxPtr != 0L) { "Failed to load Whisper model (corrupt download?)." }
    }

    fun release() {
        if (ctxPtr != 0L) { nativeFreeContext(ctxPtr); ctxPtr = 0 }
    }

    /**
     * @param languageHint "en" | "ur" | "hi", or null to auto-detect.
     */
    fun transcribe(videoPath: String, languageHint: String?, onProgress: (String) -> Unit): Transcript {
        check(ctxPtr != 0L) { "Call loadModel() first." }
        onProgress("Extracting audio…")
        val wav = extractMono16kWav(videoPath)
        try {
            onProgress("Reading audio…")
            val samples = readWavAsFloat32(wav)
            onProgress("Running Whisper (this can take a few minutes on-device)…")
            val threads = (Runtime.getRuntime().availableProcessors() - 1).coerceAtLeast(2)
            val raw = nativeFullTranscribe(ctxPtr, samples, languageHint, threads)
            val words = parseWords(raw)
            if (words.isEmpty()) {
                throw IllegalStateException("No speech was detected in this video.")
            }
            val lang = languageHint ?: nativeDetectedLanguage(ctxPtr)
            return Transcript(lang, words)
        } finally {
            wav.delete()
        }
    }

    private fun extractMono16kWav(videoPath: String): File {
        val out = File(context.cacheDir, "audio_${System.currentTimeMillis()}.wav")
        val cmd = "-y -i \"$videoPath\" -vn -ac 1 -ar 16000 -c:a pcm_s16le \"${out.absolutePath}\""
        val session = FFmpegKit.execute(cmd)
        if (!ReturnCode.isSuccess(session.returnCode)) {
            throw IllegalStateException("Audio extraction failed: ${session.failStackTrace ?: session.state}")
        }
        return out
    }

    /** Minimal PCM16 mono WAV reader -> float32 [-1, 1]. */
    private fun readWavAsFloat32(wav: File): FloatArray {
        RandomAccessFile(wav, "r").use { raf ->
            val header = ByteArray(44)
            raf.readFully(header)
            val dataSize = (raf.length() - 44).toInt()
            val bytes = ByteArray(dataSize)
            raf.readFully(bytes)
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val n = dataSize / 2
            val out = FloatArray(n)
            for (i in 0 until n) out[i] = buf.short / 32768f
            return out
        }
    }

    /** Parses the "\x01"-record / "\x02"-field string produced by nativeFullTranscribe. */
    private fun parseWords(raw: String): List<Word> {
        if (raw.isEmpty()) return emptyList()
        val out = mutableListOf<Word>()
        for (rec in raw.split('\u0001')) {
            if (rec.isEmpty()) continue
            val f = rec.split('\u0002')
            if (f.size < 4) continue
            val text = f[0].trim()
            if (text.isEmpty()) continue
            val start = f[1].toDoubleOrNull() ?: continue
            val end = f[2].toDoubleOrNull() ?: (start + 0.2)
            val prob = f[3].toFloatOrNull() ?: 1f
            out.add(Word(text, start, max(end, start + 0.02), prob))
        }
        return out
    }

    private fun max(a: Double, b: Double) = if (a > b) a else b
}
