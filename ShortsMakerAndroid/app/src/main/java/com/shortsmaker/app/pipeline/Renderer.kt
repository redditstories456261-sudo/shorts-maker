package com.shortsmaker.app.pipeline

import android.content.Context
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.shortsmaker.app.model.LayoutMode
import java.io.File
import java.util.Locale

/**
 * Renders one clip: dynamic crop pan for the chosen layout (single follow-cam, or split-screen
 * panels), burns in the karaoke captions, scales to 1080x1920, and encodes H.264/AAC with the
 * device's hardware encoder (h264_mediacodec - no GPL libx264 needed, see app/build.gradle.kts).
 *
 * The per-frame crop path is driven with FFmpeg's `sendcmd` filter (a command file with one
 * "<time> crop@name x <val>, crop@name y <val>" line per output frame) - this is the standard
 * FFmpeg technique for frame-accurate dynamic cropping and is what makes the jitter-free,
 * smoothed camera path from [ReframePlanner] actually show up in the exported video.
 *
 * NOTE: split-screen panels use one crop rect per segment (the panel's median position over that
 * segment) rather than a continuously panning crop per panel - a deliberate simplification to
 * keep the generated filter graph reliable. Panels still re-center if the active speaker changes
 * segment; they just don't pan smoothly *within* a segment the way the single-speaker view does.
 */
class Renderer(private val context: Context) {

    fun render(
        videoPath: String,
        clipStartSec: Double,
        clipEndSec: Double,
        plan: ReframePlan,
        assFile: File,
        fontsDir: File,
        outFile: File,
        onLog: (String) -> Unit
    ) {
        val dur = clipEndSec - clipStartSec
        val fps = plan.fps
        val nf = (dur * fps).toInt().coerceAtLeast(1)

        val seg = plan.segmentFor(0.0)
        val filter: String = when (seg.mode) {
            LayoutMode.SINGLE, LayoutMode.FIT -> buildSinglePan(plan, nf, fps)
            LayoutMode.SPLIT2 -> buildSplit(plan, seg.trackIds, 2)
            LayoutMode.SPLIT3 -> buildSplit(plan, seg.trackIds, 3)
        }
        val cmdsFile = filter.first
        val filterGraph = filter.second

        val assEscaped = assFile.absolutePath.replace("'", "\\'").replace(":", "\\:")
        val fontsEscaped = fontsDir.absolutePath.replace("'", "\\'").replace(":", "\\:")
        val fullFilter = "$filterGraph,ass='$assEscaped':fontsdir='$fontsEscaped'"

        val cmd = buildString {
            append("-y -ss ${"%.3f".format(Locale.US, clipStartSec)} -t ${"%.3f".format(Locale.US, dur)} ")
            append("-i \"$videoPath\" ")
            append("-filter_complex \"$fullFilter\" ")
            append("-r ${fps.toInt()} -c:v h264_mediacodec -b:v 8M -c:a aac -b:a 128k ")
            append("-pix_fmt yuv420p \"${outFile.absolutePath}\"")
        }
        onLog("ffmpeg $cmd")
        val session = FFmpegKit.execute(cmd)
        cmdsFile?.delete()
        if (!ReturnCode.isSuccess(session.returnCode)) {
            // Hardware encoder availability varies by device; fall back to the software encoder.
            onLog("Hardware encoder failed, retrying with software libopenh264…")
            val fallback = cmd.replace("h264_mediacodec", "libopenh264")
            val retry = FFmpegKit.execute(fallback)
            if (!ReturnCode.isSuccess(retry.returnCode)) {
                throw IllegalStateException("Render failed: ${retry.failStackTrace ?: retry.state}")
            }
        }
    }

    /** Pair<temp sendcmd file (nullable), filter graph string ending in a labeled [vout]-less chain>. */
    private fun buildSinglePan(plan: ReframePlan, nf: Int, fps: Double): Pair<File?, String> {
        val cmds = File(context.cacheDir, "cmds_${System.currentTimeMillis()}.txt")
        cmds.bufferedWriter().use { w ->
            for (f in 0 until nf) {
                val r = plan.singleCropRect(f)
                val t = f / fps
                w.write(
                    String.format(
                        Locale.US, "%.4f crop@c w %d; %.4f crop@c h %d; %.4f crop@c x %d; %.4f crop@c y %d;\n",
                        t, r.w.toInt(), t, r.h.toInt(), t, r.x.toInt(), t, r.y.toInt()
                    )
                )
            }
        }
        val first = plan.singleCropRect(0)
        val cmdsEscaped = cmds.absolutePath.replace("'", "\\'").replace(":", "\\:")
        val graph = "[0:v]sendcmd=f='$cmdsEscaped',crop@c=w=${first.w.toInt()}:h=${first.h.toInt()}:x=${first.x.toInt()}:y=${first.y.toInt()},scale=$OUT_W:$OUT_H"
        return cmds to graph
    }

    /** Static-per-segment split layout: crop each chosen track to its panel, stack vertically. */
    private fun buildSplit(plan: ReframePlan, trackIds: List<Int>, count: Int): Pair<File?, String> {
        val panelH = OUT_H / count
        val parts = StringBuilder()
        val labels = mutableListOf<String>()
        for ((i, trackId) in trackIds.take(count).withIndex()) {
            val r = plan.panelCropRect(trackId, 0, OUT_W, panelH)
            val label = "p$i"
            parts.append("[0:v]crop=${r.w.toInt()}:${r.h.toInt()}:${r.x.toInt()}:${r.y.toInt()},scale=$OUT_W:$panelH[$label];")
            labels.add("[$label]")
        }
        val stacked = "${labels.joinToString("")}vstack=inputs=${labels.size}"
        return null to "$parts$stacked"
    }
}
