package com.shortsmaker.app.pipeline

import com.shortsmaker.app.model.FaceTrack
import com.shortsmaker.app.model.LayoutMode
import com.shortsmaker.app.model.RenderSegment
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

const val OUT_W = 1080
const val OUT_H = 1920
private const val ASPECT = 9.0 / 16.0   // width / height

/** x0, y0, w, h in SOURCE pixel coordinates - the crop rect to scale up to OUT_W x OUT_H (or a panel). */
data class CropRect(val x: Float, val y: Float, val w: Float, val h: Float)

class ReframePlan(
    val segments: List<RenderSegment>,
    private val srcW: Int, private val srcH: Int,
    private val singleCx: DoubleArray, private val singleCy: DoubleArray, private val singleH: DoubleArray,
    private val trackCx: Map<Int, DoubleArray>, private val trackCy: Map<Int, DoubleArray>,
    private val trackH: Map<Int, Double>,
    val fps: Double
) {
    fun segmentFor(timeSec: Double): RenderSegment =
        segments.firstOrNull { timeSec >= it.startSec && timeSec < it.endSec } ?: segments.last()

    fun singleCropRect(frameIdx: Int): CropRect {
        val ch = singleH[frameIdx.coerceIn(singleH.indices)]
        val cw = ch * ASPECT
        val cx = singleCx[frameIdx.coerceIn(singleCx.indices)]
        val cy = singleCy[frameIdx.coerceIn(singleCy.indices)]
        val x = (cx - cw / 2).coerceIn(0.0, srcW - cw)
        val y = (cy - 0.42 * ch).coerceIn(0.0, srcH - ch)
        return CropRect(x.toFloat(), y.toFloat(), cw.toFloat(), ch.toFloat())
    }

    fun panelCropRect(trackId: Int, frameIdx: Int, panelW: Int, panelH: Int): CropRect {
        val aspect = panelW.toDouble() / panelH
        val baseH = trackH[trackId] ?: (0.3 * srcH)
        var ch = (baseH * 3.4).coerceIn(0.38 * srcH, srcH.toDouble())
        var cw = ch * aspect
        if (cw > srcW) { cw = srcW.toDouble(); ch = srcW / aspect }
        val cxArr = trackCx[trackId]; val cyArr = trackCy[trackId]
        val cx = cxArr?.get(frameIdx.coerceIn(cxArr.indices)) ?: (srcW / 2.0)
        val cy = cyArr?.get(frameIdx.coerceIn(cyArr.indices)) ?: (srcH / 2.0)
        val x = (cx - cw / 2).coerceIn(0.0, srcW - cw)
        val y = (cy - 0.40 * ch).coerceIn(0.0, srcH - ch)
        return CropRect(x.toFloat(), y.toFloat(), cw.toFloat(), ch.toFloat())
    }
}

object ReframePlanner {

    private const val FPS = 30.0

    fun plan(tracks: List<FaceTrack>, detFps: Double, durSec: Double, srcW: Int, srcH: Int): ReframePlan {
        val nf = max(1, (durSec * FPS).toInt())
        val usable = tracks.filter { it.samples.size >= max(4, (0.6 * detFps).toInt()) }

        if (usable.isEmpty()) {
            // No reliable faces (slides / b-roll): static centered crop, full source height.
            val cx = DoubleArray(nf) { srcW / 2.0 }
            val cy = DoubleArray(nf) { srcH / 2.0 }
            val ch = DoubleArray(nf) { srcH.toDouble() }
            return ReframePlan(listOf(RenderSegment(0.0, durSec, LayoutMode.FIT)), srcW, srcH, cx, cy, ch, emptyMap(), emptyMap(), emptyMap(), FPS)
        }

        // Per-track smoothed path at output fps, resampled from detection-fps samples.
        val trackCx = mutableMapOf<Int, DoubleArray>()
        val trackCy = mutableMapOf<Int, DoubleArray>()
        val trackMedianH = mutableMapOf<Int, Double>()
        for (tr in usable) {
            val xs = tr.samples.map { it.cx.toDouble() }; val ys = tr.samples.map { it.cy.toDouble() }
            val hs = tr.samples.map { it.h.toDouble() }.sorted()
            trackMedianH[tr.id] = hs[hs.size / 2]
            val times = tr.samples.map { it.frameIndex / detFps }
            trackCx[tr.id] = stabilize(resample(times, xs, nf, FPS), 0.5 * (hs[hs.size / 2]), 0.5, 0.4 * srcW)
            trackCy[tr.id] = stabilize(resample(times, ys, nf, FPS), 0.4 * hs[hs.size / 2], 0.7, 0.5 * srcH)
        }

        // Decide per-output-frame: which track is "speaker" (largest+most active mouth), and
        // whether we're in single or split layout (split when 2-3 faces are simultaneously
        // prominent and spread wider than a single 9:16 crop could comfortably hold).
        val mainTrack = usable.maxByOrNull { t -> t.samples.sumOf { it.h.toDouble() } }!!
        val cropWFull = srcH * ASPECT
        var multiFace = false
        if (usable.size >= 2) {
            val spans = usable.map { it.samples.map { s -> s.cx } }
            val minX = spans.flatten().minOrNull() ?: 0f
            val maxX = spans.flatten().maxOrNull() ?: 0f
            multiFace = (maxX - minX) > 0.75 * cropWFull
        }

        val segments = if (multiFace && usable.size >= 2) {
            val count = min(3, usable.size)
            val chosen = usable.sortedByDescending { t -> t.samples.sumOf { it.h.toDouble() } }.take(count)
            listOf(RenderSegment(0.0, durSec, if (count == 2) LayoutMode.SPLIT2 else LayoutMode.SPLIT3, chosen.map { it.id }))
        } else {
            listOf(RenderSegment(0.0, durSec, LayoutMode.SINGLE, listOf(mainTrack.id)))
        }

        val singleCx = trackCx[mainTrack.id] ?: DoubleArray(nf) { srcW / 2.0 }
        val singleCy = trackCy[mainTrack.id] ?: DoubleArray(nf) { srcH / 2.0 }
        val medH = trackMedianH[mainTrack.id] ?: (0.3 * srcH)
        val singleH = DoubleArray(nf) { (medH / 0.26).coerceIn(srcH / 1.6, srcH.toDouble()) }

        return ReframePlan(segments, srcW, srcH, singleCx, singleCy, singleH, trackCx, trackCy, trackMedianH, FPS)
    }

    /** Linear-resample a sparse (time, value) series onto nf output frames at outFps. */
    private fun resample(times: List<Double>, values: List<Double>, nf: Int, outFps: Double): DoubleArray {
        if (times.isEmpty()) return DoubleArray(nf)
        val out = DoubleArray(nf)
        var j = 0
        for (f in 0 until nf) {
            val t = f / outFps
            while (j < times.size - 2 && times[j + 1] < t) j++
            if (t <= times[0]) { out[f] = values[0]; continue }
            if (t >= times.last()) { out[f] = values.last(); continue }
            val t0 = times[j]; val t1 = times[j + 1]
            val v0 = values[j]; val v1 = values[j + 1]
            val a = if (t1 > t0) (t - t0) / (t1 - t0) else 0.0
            out[f] = v0 + a * (v1 - v0)
        }
        return out
    }

    /** Dead-zone hold + Gaussian ease + hard cut on big jumps (port of reframe.py stabilize()). */
    private fun stabilize(x: DoubleArray, deadZone: Double, sigmaSec: Double, cutPx: Double): DoubleArray {
        val n = x.size
        if (n < 3) return x.copyOf()
        val held = DoubleArray(n)
        var cur = x[0]
        for (i in 0 until n) {
            if (abs(x[i] - cur) > deadZone) cur = x[i]
            held[i] = cur
        }
        val cuts = mutableListOf(0)
        for (i in 1 until n) if (abs(held[i] - held[i - 1]) > cutPx) cuts.add(i)
        cuts.add(n)
        val out = DoubleArray(n)
        for (idx in 0 until cuts.size - 1) {
            val a = cuts[idx]; val b = cuts[idx + 1]
            val seg = gaussianSmooth(held.copyOfRange(a, b), sigmaSec * FPS)
            System.arraycopy(seg, 0, out, a, seg.size)
        }
        return out
    }

    private fun gaussianSmooth(x: DoubleArray, sigma: Double): DoubleArray {
        if (sigma < 0.3 || x.size < 3) return x.copyOf()
        val r = (3 * sigma).toInt() + 1
        val kernel = DoubleArray(2 * r + 1) { i -> exp(-0.5 * ((i - r) / sigma).let { it * it }) }
        val sum = kernel.sum()
        for (i in kernel.indices) kernel[i] /= sum
        val padded = DoubleArray(x.size + 2 * r)
        for (i in padded.indices) {
            val src = (i - r).coerceIn(0, x.size - 1)
            padded[i] = x[src]
        }
        val out = DoubleArray(x.size)
        for (i in x.indices) {
            var acc = 0.0
            for (k in kernel.indices) acc += padded[i + k] * kernel[k]
            out[i] = acc
        }
        return out
    }
}
