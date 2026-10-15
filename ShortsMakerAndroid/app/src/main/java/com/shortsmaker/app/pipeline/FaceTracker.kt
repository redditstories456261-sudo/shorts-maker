package com.shortsmaker.app.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facedetector.FaceDetector
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.shortsmaker.app.model.FaceSample
import com.shortsmaker.app.model.FaceTrack
import java.io.File
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Samples the clip at [detFps] (default 6 fps - matches the desktop pipeline), runs MediaPipe's
 * on-device FaceDetector on each frame, and links detections across frames into tracks with a
 * simple greedy nearest-centroid tracker. When 2+ faces are visible in a frame we also run
 * FaceLandmarker on each face crop to measure mouth openness, used later to pick the active
 * speaker for split-screen layouts.
 *
 * Models are loaded from plain files (not bundled Android assets) so they can be imported at
 * runtime via MainActivity's "Import model" pickers - handy if you have no PC/adb to push files
 * with (see GITHUB_BUILD.md). Point [faceDetectorFile] / [faceLandmarkerFile] at the .task files
 * downloaded per README.md step 5.
 */
class FaceTracker(
    context: Context,
    faceDetectorFile: File,
    faceLandmarkerFile: File?,
    private val detFps: Double = 6.0,
    private val analysisWidth: Int = 720
) {

    private val detector: FaceDetector = FaceDetector.createFromOptions(
        context,
        FaceDetector.FaceDetectorOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetBuffer(mapFile(faceDetectorFile)).build())
            .setRunningMode(RunningMode.IMAGE)
            .setMinDetectionConfidence(0.6f)
            .build()
    )

    private val landmarker: FaceLandmarker? = try {
        if (faceLandmarkerFile == null || !faceLandmarkerFile.exists()) null else
            FaceLandmarker.createFromOptions(
                context,
                FaceLandmarker.FaceLandmarkerOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetBuffer(mapFile(faceLandmarkerFile)).build())
                    .setRunningMode(RunningMode.IMAGE)
                    .setNumFaces(1)
                    .setMinFaceDetectionConfidence(0.4f)
                    .build()
            )
    } catch (e: Exception) {
        null  // optional: active-speaker selection falls back to "largest face" without it
    }

    companion object {
        private fun mapFile(f: File): MappedByteBuffer =
            FileInputStream(f).channel.use { it.map(FileChannel.MapMode.READ_ONLY, 0, f.length()) }
    }

    data class Result(val fps: Double, val sampleCount: Int, val tracks: List<FaceTrack>, val srcW: Int, val srcH: Int)

    fun close() {
        detector.close()
        landmarker?.close()
    }

    fun analyze(videoPath: String, startSec: Double, durSec: Double, onProgress: (Float) -> Unit): Result {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(videoPath)
        val srcW = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1920
        val srcH = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1080
        val scale = min(1f, analysisWidth / srcW.toFloat())

        val nSamples = max(1, (durSec * detFps).toInt())
        val tracker = Tracker()
        for (k in 0 until nSamples) {
            val tSec = startSec + k / detFps
            val bmpFull = retriever.getFrameAtTime((tSec * 1_000_000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST)
                ?: continue
            val bmp = if (scale < 1f) {
                Bitmap.createScaledBitmap(bmpFull, (srcW * scale).toInt(), (srcH * scale).toInt(), true)
            } else bmpFull

            val mpImage = BitmapImageBuilder(bmp).build()
            val result = detector.detect(mpImage)
            val dets = result.detections().map { d ->
                val box = d.boundingBox()
                val sx = srcW / bmp.width.toFloat(); val sy = srcH / bmp.height.toFloat()
                Det(
                    cx = (box.left + box.width() / 2f) * sx, cy = (box.top + box.height() / 2f) * sy,
                    w = box.width() * sx, h = box.height() * sy,
                    score = d.categories().firstOrNull()?.score() ?: 0.5f
                )
            }.filter { it.w > 0.015f * srcW && it.h > 0.015f * srcH }

            val detsWithMouth = if (dets.size >= 2 && landmarker != null) {
                dets.map { it.copy(mouth = mouthOpenness(bmp, it, srcW.toFloat() / bmp.width, srcH.toFloat() / bmp.height)) }
            } else dets

            tracker.update(k, detsWithMouth)
            onProgress(k.toFloat() / nSamples)
            if (bmp !== bmpFull) bmpFull.recycle()
        }
        retriever.release()
        return Result(detFps, nSamples, tracker.tracks, srcW, srcH)
    }

    private fun mouthOpenness(bmp: Bitmap, d: Det, sx: Float, sy: Float): Float {
        val cx = d.cx / sx; val cy = d.cy / sy; val w = d.w / sx; val h = d.h / sy
        val pad = 0.35f
        val left = max(0f, cx - w / 2 - pad * w).toInt()
        val top = max(0f, cy - h / 2 - pad * h).toInt()
        val right = min(bmp.width.toFloat(), cx + w / 2 + pad * w).toInt()
        val bottom = min(bmp.height.toFloat(), cy + h / 2 + pad * h).toInt()
        if (right - left < 16 || bottom - top < 16) return Float.NaN
        return try {
            val crop = Bitmap.createBitmap(bmp, left, top, right - left, bottom - top)
            val mp = BitmapImageBuilder(crop).build()
            val res = landmarker!!.detect(mp)
            crop.recycle()
            val lm = res.faceLandmarks().firstOrNull() ?: return Float.NaN
            // MediaPipe FaceMesh indices: 13/14 = inner lip center top/bottom, 10/152 = face top/chin
            val faceH = kotlin.math.abs(lm[152].y() - lm[10].y())
            if (faceH < 1e-3f) Float.NaN else kotlin.math.abs(lm[14].y() - lm[13].y()) / faceH
        } catch (e: Exception) {
            Float.NaN
        }
    }

    private data class Det(val cx: Float, val cy: Float, val w: Float, val h: Float, val score: Float, val mouth: Float = Float.NaN)

    /** Greedy nearest-centroid tracker, tolerant of short detection dropouts (port of faces.py Tracker). */
    private class Tracker(private val maxGapSamples: Int = 7) {
        val tracks = mutableListOf<FaceTrack>()
        private val lastSampleIdx = mutableMapOf<Int, Int>()

        fun update(k: Int, dets: List<Det>) {
            val active = tracks.filter { k - (lastSampleIdx[it.id] ?: -999) <= maxGapSamples }
            val usedDet = BooleanArray(dets.size)
            val usedTrack = mutableSetOf<Int>()
            val pairs = mutableListOf<Triple<Double, Int, FaceTrack>>()
            for ((di, d) in dets.withIndex()) {
                for (tr in active) {
                    val last = tr.samples.last()
                    val gap = k - lastSampleIdx[tr.id]!!
                    val gate = 0.8 * max(last.w, d.w) * (1.0 + 1.5 * gap)
                    val dist = hypot((d.cx - last.cx).toDouble(), (d.cy - last.cy).toDouble())
                    if (dist < gate) pairs.add(Triple(dist, di, tr))
                }
            }
            for ((_, di, tr) in pairs.sortedBy { it.first }) {
                if (usedDet[di] || tr.id in usedTrack) continue
                usedDet[di] = true; usedTrack.add(tr.id)
                tr.samples.add(FaceSample(k, k / 1.0, dets[di].cx, dets[di].cy, dets[di].w, dets[di].h, dets[di].score, dets[di].mouth))
                lastSampleIdx[tr.id] = k
            }
            for ((di, d) in dets.withIndex()) {
                if (!usedDet[di]) {
                    val tr = FaceTrack(tracks.size)
                    tr.samples.add(FaceSample(k, k / 1.0, d.cx, d.cy, d.w, d.h, d.score, d.mouth))
                    tracks.add(tr)
                    lastSampleIdx[tr.id] = k
                }
            }
        }
    }
}
