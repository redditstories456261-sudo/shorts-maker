package com.shortsmaker.app.model

/** One word with timestamps, as produced by WhisperBridge. */
data class Word(
    val text: String,
    val startSec: Double,
    val endSec: Double,
    val prob: Float
)

/** Full on-device transcript. */
data class Transcript(
    val language: String,       // "en" | "ur" | "hi" (whisper's own code)
    val words: List<Word>
)

/** A sentence-like chunk built from consecutive words (mirrors the desktop pipeline's Unit). */
data class TextUnit(
    val startSec: Double,
    val endSec: Double,
    val text: String,
    val normText: String,
    val wordStart: Int,
    val wordEnd: Int,           // inclusive
    val nWords: Int,
    val isQuestion: Boolean,
    val hookScore: Double,
    val emotionScore: Double,
    val hasPayoff: Boolean,
    val endsSentence: Boolean
)

/** A scored window of the transcript that could become a short clip. */
data class Candidate(
    val unitStart: Int,
    val unitEnd: Int,
    var startSec: Double,
    var endSec: Double,
    val score: Double,
    val features: Map<String, Double> = emptyMap(),
    var text: String = "",
    var title: String = "",
    var hookLine: String = "",
    var viralityScore: Int = 0,
    var reason: String = ""
)

/** One detected face sample at a point in time, in SOURCE pixel coordinates. */
data class FaceSample(
    val frameIndex: Int,
    val timeSec: Double,
    val cx: Float, val cy: Float,
    val w: Float, val h: Float,
    val score: Float,
    val mouthOpenness: Float = Float.NaN   // relative lip gap, NaN = not measured this sample
)

/** A tracked face across consecutive samples (greedy centroid tracker output). */
data class FaceTrack(
    val id: Int,
    val samples: MutableList<FaceSample> = mutableListOf()
)

enum class LayoutMode { SINGLE, SPLIT2, SPLIT3, FIT }

/** One segment of the final vertical render: which layout, which track(s), for which time range. */
data class RenderSegment(
    val startSec: Double,
    val endSec: Double,
    val mode: LayoutMode,
    val trackIds: List<Int> = emptyList()
)

data class ClipResult(
    val index: Int,
    val sourceStart: Double,
    val sourceEnd: Double,
    val title: String,
    val hookLine: String,
    val viralityScore: Int,
    val reason: String,
    val outputPath: String
)

enum class Stage { DOWNLOAD, TRANSCRIBE, DETECT_MOMENTS, FACE_TRACK, RENDER, CAPTION, TITLE, DONE, ERROR }

data class PipelineProgress(
    val stage: Stage,
    val message: String,
    val fraction: Float,        // 0f..1f within the current stage
    val clipIndex: Int = -1,
    val clipTotal: Int = -1
)
