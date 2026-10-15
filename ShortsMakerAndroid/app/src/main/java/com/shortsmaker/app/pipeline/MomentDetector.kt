package com.shortsmaker.app.pipeline

import com.shortsmaker.app.model.Candidate
import com.shortsmaker.app.model.TextUnit
import com.shortsmaker.app.model.Word
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Scores windows of the transcript for "would this make a good short clip" and returns the best
 * non-overlapping set. Audio loudness/laughter cues (used on desktop) are intentionally left out
 * here to keep the on-device version fast and dependency-light; the transcript-cue scoring below
 * (hook / question / emotion / payoff / pacing / completeness) carries most of the signal anyway.
 */
object MomentDetector {

    private const val PAD_BEFORE = 0.25
    private const val PAD_AFTER = 0.40

    fun buildUnits(words: List<Word>): List<TextUnit> {
        if (words.isEmpty()) return emptyList()
        val units = mutableListOf<TextUnit>()
        var start = 0
        for (i in words.indices) {
            val w = words[i]
            val raw = w.text.trim()
            val gap = if (i + 1 < words.size) words[i + 1].startSec - w.endSec else 99.0
            val punctEnd = raw.isNotEmpty() && raw.last() in Lexicon.SENTENCE_END
            if (punctEnd || gap > 0.9 || (i - start + 1) >= 28) {
                units.add(makeUnit(words, start, i, punctEnd || gap > 0.6))
                start = i + 1
            }
        }
        if (start < words.size) units.add(makeUnit(words, start, words.size - 1, true))
        return units
    }

    private fun makeUnit(words: List<Word>, i0: Int, i1: Int, ends: Boolean): TextUnit {
        val slice = words.subList(i0, i1 + 1)
        val text = slice.joinToString(" ") { it.text.trim() }.trim()
        val norm = Lexicon.normalize(text)
        val n = slice.size
        val isQ = text.trimEnd().endsWith("?") || text.trimEnd().endsWith("\u061F") ||
            Lexicon.opensWithQuestion(norm)
        var hook = 0.28 * min(2, Lexicon.count(Lexicon.hook, norm)) + (if (isQ) 0.30 else 0.0)
        val hasDigit = text.any { it.isDigit() }
        hook += if (hasDigit) 0.10 else 0.0
        if (n <= 3) hook *= 0.5
        val excl = text.count { it == '!' }
        val emo = Lexicon.count(Lexicon.emotion, norm) + 0.7 * excl
        return TextUnit(
            startSec = slice.first().startSec, endSec = slice.last().endSec, text = text, normText = norm,
            wordStart = i0, wordEnd = i1, nWords = n, isQuestion = isQ,
            hookScore = min(1.0, hook), emotionScore = emo,
            hasPayoff = Lexicon.count(Lexicon.payoff, norm) > 0, endsSentence = ends
        )
    }

    private fun clip01(x: Double) = max(0.0, min(1.0, x))

    fun findCandidates(units: List<TextUnit>, minLen: Double, maxLen: Double): List<Candidate> {
        if (units.isEmpty()) return emptyList()
        val n = units.size
        val cumWords = DoubleArray(n + 1); val cumQ = DoubleArray(n + 1)
        val cumEmo = DoubleArray(n + 1); val cumSpeech = DoubleArray(n + 1)
        for (i in 0 until n) {
            val u = units[i]
            cumWords[i + 1] = cumWords[i] + u.nWords
            cumQ[i + 1] = cumQ[i] + if (u.isQuestion) 1 else 0
            cumEmo[i + 1] = cumEmo[i] + u.emotionScore
            cumSpeech[i + 1] = cumSpeech[i] + (u.endSec - u.startSec)
        }
        val bodyMax = maxLen - PAD_BEFORE - PAD_AFTER
        val out = mutableListOf<Candidate>()
        for (i in 0 until n) {
            val startOk = i == 0 || units[i - 1].endsSentence
            for (j in i until n) {
                val dur = units[j].endSec - units[i].startSec
                if (dur > bodyMax) break
                if (dur < minLen) continue
                val nw = cumWords[j + 1] - cumWords[i]
                if (nw < 20) continue
                val nq = cumQ[j + 1] - cumQ[i]
                val emo = cumEmo[j + 1] - cumEmo[i]
                val speech = cumSpeech[j + 1] - cumSpeech[i]

                val hook = max(units[i].hookScore, 0.75 * (if (i + 1 <= j) units[i + 1].hookScore else 0.0))
                val payoff = if (units[j].hasPayoff || (j - 1 >= i && units[j - 1].hasPayoff)) 1.0 else 0.0
                val qScore = clip01(nq / 2.0)
                val emoScore = clip01((emo / max(nw / 50.0, 1.0)) / 2.5)
                val pace = nw / dur
                val paceScore = clip01(1 - abs(pace - 2.8) / 2.0)
                val deadAir = 1 - min(1.0, speech / dur)
                val lengthBonus = clip01(1 - abs(dur - 45) / 30)

                var raw = 0.30 * hook + 0.16 * qScore + 0.20 * emoScore + 0.10 * payoff +
                    0.14 * paceScore + 0.10 * lengthBonus
                val endOk = units[j].endsSentence
                val complete = 0.85 + (if (startOk) 0.075 else 0.0) + (if (endOk) 0.075 else 0.0)
                raw *= complete * (1 - 0.6 * max(0.0, deadAir - 0.2))
                val score = 100.0 * (1 - exp(-2.2 * raw))

                out.add(
                    Candidate(
                        unitStart = i, unitEnd = j, startSec = units[i].startSec, endSec = units[j].endSec,
                        score = score,
                        features = mapOf(
                            "hook" to hook, "questions" to nq, "emotion" to emoScore, "payoff" to payoff,
                            "pace_wps" to pace, "dead_air" to deadAir, "complete" to complete
                        )
                    )
                )
            }
        }
        if (out.isEmpty()) {
            var j = 0
            for (k in units.indices) {
                if (units[k].endSec - units[0].startSec <= bodyMax) j = k else break
            }
            out.add(Candidate(0, j, units[0].startSec, units[j].endSec, 30.0, mapOf("note" to 1.0)))
        }
        return out
    }

    fun pickTop(candidates: List<Candidate>, count: Int, minGapSec: Double = 1.0): List<Candidate> {
        val chosen = mutableListOf<Candidate>()
        for (c in candidates.sortedByDescending { it.score }) {
            val ok = chosen.all { s -> c.endSec <= s.startSec - minGapSec || c.startSec >= s.endSec + minGapSec }
            if (ok) {
                chosen.add(c)
                if (chosen.size >= count) break
            }
        }
        return chosen
    }

    /** Pad to word boundaries once a candidate is finalized. */
    fun finalizeBounds(c: Candidate, units: List<TextUnit>, words: List<Word>, totalSec: Double, maxLen: Double) {
        val u0 = units[c.unitStart]; val u1 = units[c.unitEnd]
        c.startSec = max(0.0, words[u0.wordStart].startSec - PAD_BEFORE)
        val end = words[u1.wordEnd].endSec + PAD_AFTER
        c.endSec = min(totalSec, min(end, c.startSec + maxLen))
        c.text = (c.unitStart..c.unitEnd).joinToString(" ") { units[it].text }
    }

    /** No-LLM fallback title/hook: the punchiest early sentence of the clip, trimmed. */
    fun heuristicMeta(c: Candidate, units: List<TextUnit>) {
        val seg = units.subList(c.unitStart, c.unitEnd + 1)
        val earlyCount = max(1, (seg.size * 0.6).toInt())
        val early = seg.subList(0, earlyCount)
        val best = early.filter { it.nWords >= 4 }
            .maxByOrNull { it.hookScore + (if (it.isQuestion) 0.2 else 0.0) + 0.02 * min(it.nWords, 12) }
            ?: seg.first()
        c.hookLine = trimWords(best.text.trim(), 14)
        var title = trimWords(best.text.trim().trimEnd('.', ',', ';', ':', '!', '?', '\u060C', '\u06D4'), 9)
        if (title.isNotEmpty() && title[0].isLetter() && title[0].code < 128) {
            title = title[0].uppercase() + title.substring(1)
        }
        c.title = title.ifBlank { "Highlight" }
        c.reason = "Heuristic score (hook=${"%.2f".format(c.features["hook"] ?: 0.0)})"
    }

    private fun trimWords(text: String, maxWords: Int): String {
        val parts = text.split(' ').filter { it.isNotEmpty() }
        if (parts.size <= maxWords) return text.trim()
        return parts.take(maxWords).joinToString(" ").trimEnd(',', ';', ':', '\u060C') + "\u2026"
    }
}
