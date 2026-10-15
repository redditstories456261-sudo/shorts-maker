package com.shortsmaker.app.pipeline

import com.shortsmaker.app.model.Word
import java.io.File
import java.util.Locale

/**
 * Builds an .ass subtitle file: short word-groups shown near the bottom, with the word
 * currently being spoken highlighted in yellow (the rest bold white) - "Hormozi-style" karaoke
 * captions, burned in later by [Renderer] via libass (bundled inside FFmpegKit).
 *
 * For Urdu/Hindi, pass a font in [fontName] that actually covers Arabic/Devanagari (e.g. a
 * bundled "NotoNastaliqUrdu" or "NotoSansDevanagari" in app/src/main/assets/fonts/) and make sure
 * [Renderer] points libass at that assets/fonts directory - the default Android system font will
 * render those scripts as boxes otherwise.
 */
object CaptionBuilder {

    private const val MAX_WORDS_PER_GROUP = 4
    private const val MAX_GAP_SEC = 0.55

    fun build(words: List<Word>, clipStartSec: Double, clipEndSec: Double, outFile: File, fontName: String = "Noto Sans"): File {
        val clipWords = words.filter { it.startSec >= clipStartSec - 0.05 && it.endSec <= clipEndSec + 0.05 }
            .map { Word(it.text, it.startSec - clipStartSec, it.endSec - clipStartSec, it.prob) }

        val groups = groupWords(clipWords)
        val sb = StringBuilder()
        sb.append(header(fontName))
        for (group in groups) {
            for ((idx, w) in group.withIndex()) {
                val lineEnd = if (idx + 1 < group.size) group[idx + 1].startSec else w.endSec
                val text = group.mapIndexed { i, gw ->
                    val t = escape(gw.text)
                    if (i == idx) "{\\c&H00FFFF&}$t{\\c&HFFFFFF&}" else t
                }.joinToString("\u2009")
                sb.append(dialogue(w.startSec, lineEnd, text))
            }
        }
        outFile.writeText(sb.toString())
        return outFile
    }

    private fun groupWords(words: List<Word>): List<List<Word>> {
        val groups = mutableListOf<MutableList<Word>>()
        var cur = mutableListOf<Word>()
        for (w in words) {
            val gap = if (cur.isEmpty()) 0.0 else w.startSec - cur.last().endSec
            if (cur.isNotEmpty() && (cur.size >= MAX_WORDS_PER_GROUP || gap > MAX_GAP_SEC)) {
                groups.add(cur); cur = mutableListOf()
            }
            cur.add(w)
        }
        if (cur.isNotEmpty()) groups.add(cur)
        return groups
    }

    private fun escape(s: String) = s.replace("\\", "\\\\").replace("{", "(").replace("}", ")")

    private fun ts(sec: Double): String {
        val cs = (sec * 100).toLong().coerceAtLeast(0)
        val h = cs / 360000; val m = (cs / 6000) % 60; val s = (cs / 100) % 60; val c = cs % 100
        return String.format(Locale.US, "%d:%02d:%02d.%02d", h, m, s, c)
    }

    private fun dialogue(start: Double, end: Double, text: String) =
        "Dialogue: 0,${ts(start)},${ts(end)},Karaoke,,0,0,0,,$text\n"

    private fun header(fontName: String) = """
        [Script Info]
        ScriptType: v4.00+
        PlayResX: $OUT_W
        PlayResY: $OUT_H
        WrapStyle: 2
        ScaledBorderAndShadow: yes

        [V4+ Styles]
        Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
        Style: Karaoke,$fontName,78,&HFFFFFF&,&H00FFFF&,&H000000&,&H96000000&,1,0,0,0,100,100,0,0,1,6,0,2,60,60,260,1

        [Events]
        Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
        """.trimIndent() + "\n"
}
