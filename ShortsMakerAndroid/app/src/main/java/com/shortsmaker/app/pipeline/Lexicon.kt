package com.shortsmaker.app.pipeline

import java.text.Normalizer

/**
 * Multilingual cue phrases used by [MomentDetector] (English / Urdu / Hindi), and the text
 * normalization they're matched against. This is a direct port of the desktop pipeline's
 * lexicon.py - edit the word lists freely, they're just data.
 */
object Lexicon {

    private val arabicLetterMap = mapOf(
        'ي' to 'ی', 'ى' to 'ی', 'ك' to 'ک', 'ة' to 'ہ', 'ۃ' to 'ہ', '\u2019' to '\'', '\u2018' to '\''
    )
    private val arabicDiacritics = Regex("[\u064B-\u065F\u0670\u06D6-\u06ED]")

    /** Casefold, unify Urdu letter variants, strip diacritics/punctuation -> single-spaced words. */
    fun normalize(raw: String): String {
        val nfc = Normalizer.normalize(raw, Normalizer.Form.NFC).lowercase()
        val mapped = buildString(nfc.length) {
            for (ch in nfc) append(arabicLetterMap[ch] ?: ch)
        }
        val noDiacritics = arabicDiacritics.replace(mapped, "")
        val sb = StringBuilder(noDiacritics.length)
        for (ch in noDiacritics) {
            when {
                ch == '\'' -> sb.append(ch)
                Character.isLetterOrDigit(ch) -> sb.append(ch)
                else -> sb.append(' ')
            }
        }
        return sb.toString().trim().replace(Regex("\\s+"), " ")
    }

    val SENTENCE_END = setOf('.', '?', '!', '\u061F', '\u06D4', '\u0964', '\u0965', '\u2026')

    val hook: Set<String> = setOf(
        // English
        "secret", "secrets", "truth", "nobody", "no one", "never", "always", "mistake", "mistakes",
        "worst", "best", "biggest", "stop", "warning", "shocking", "crazy", "insane", "unbelievable",
        "million", "billion", "free", "hack", "trick", "lesson", "regret", "most people",
        "here's the thing", "the problem is", "let me tell you", "listen", "imagine", "what if",
        "the reason", "you won't believe", "the truth is", "this is why", "here's why",
        "the real reason", "dangerous", "exposed", "game changer", "changed my life", "you need to",
        "you have to", "you should", "only way", "biggest mistake", "nobody tells you",
        "blew my mind", "rule", "rich", "broke",
        // Urdu
        "راز", "سچ", "سچائی", "حقیقت", "کبھی نہیں", "ہمیشہ", "غلطی", "غلطیاں", "سب سے بڑا",
        "سب سے بڑی", "خطرناک", "حیران", "حیرت", "ناقابل یقین", "لاکھ", "کروڑ", "ارب", "مفت",
        "سبق", "پچھتاوا", "رکو", "رک جاؤ", "سنو", "دیکھو", "تصور کریں", "مسئلہ", "وجہ",
        "زیادہ تر لوگ", "کوئی نہیں بتاتا", "اصل وجہ", "اصل بات", "بات یہ ہے", "یاد رکھیں",
        "دھوکہ", "کامیابی", "ناکامی",
        // Hindi
        "राज", "राज़", "सच", "सच्चाई", "हकीकत", "कभी नहीं", "हमेशा", "गलती", "गलतियां",
        "सबसे बड़ा", "सबसे बड़ी", "खतरनाक", "हैरान", "हैरानी", "अविश्वसनीय", "लाख", "करोड़",
        "अरब", "मुफ्त", "सीख", "पछतावा", "रुको", "सुनो", "कल्पना कीजिए", "समस्या", "वजह", "कारण",
        "ज्यादातर लोग", "कोई नहीं बताता", "असली वजह", "असली बात", "बात यह है", "याद रखिए",
        "धोखा", "सफलता", "असफलता"
    )

    val questionOpeners: Set<String> = setOf(
        "what", "why", "how", "when", "where", "who", "which", "do you", "did you", "have you",
        "are you", "is it", "can you", "would you", "should i",
        "کیا", "کیوں", "کیسے", "کب", "کہاں", "کون", "کس", "کتنا", "کتنے", "کتنی",
        "क्या", "क्यों", "कैसे", "कब", "कहाँ", "कहां", "कौन", "किस", "कितना", "कितने", "कितनी"
    )

    val emotion: Set<String> = setOf(
        "love", "loved", "hate", "hated", "angry", "afraid", "scared", "fear", "cry", "cried",
        "crying", "amazing", "incredible", "terrible", "heartbroken", "proud", "ashamed", "shame",
        "die", "died", "death", "dream", "fight", "pain", "struggle", "blessed", "happy", "sad",
        "wow", "oh my god", "omg", "furious", "devastated", "grateful", "hurt", "lonely", "failed",
        "failure", "won", "lost",
        "محبت", "پیار", "نفرت", "غصہ", "ڈر", "رونا", "خوشی", "غم", "درد", "موت", "خواب", "جنگ",
        "فخر", "شرم", "دل", "تکلیف", "آنسو", "مایوس", "ناکام", "کامیاب", "واہ",
        "प्यार", "मोहब्बत", "नफरत", "गुस्सा", "डर", "रोना", "खुशी", "गम", "दर्द", "मौत", "सपना",
        "जंग", "गर्व", "शर्म", "दिल", "तकलीफ", "आंसू", "निराश", "नाकाम", "कामयाब", "वाह"
    )

    val payoff: Set<String> = setOf(
        "that's why", "thats why", "the key is", "the point is", "in the end", "the lesson",
        "remember", "so if you", "bottom line", "that's how", "this is why", "the answer is",
        "اسی لیے", "اسی وجہ سے", "بات یہ ہے", "آخر میں", "یاد رکھیں", "سبق یہ", "نتیجہ یہ",
        "इसलिए", "इसी वजह से", "बात यह है", "आखिर में", "याद रखिए", "सीख यह", "नतीजा यह"
    )

    /** Count whole-word/phrase hits of [phrases] in already-normalized [text]. */
    fun count(phrases: Set<String>, text: String): Int {
        if (text.isEmpty()) return 0
        val padded = " $text "
        var n = 0
        for (p in phrases) {
            var from = 0
            while (true) {
                val i = padded.indexOf(" $p ", from)
                if (i < 0) break
                n++
                from = i + 1
            }
        }
        return n
    }

    fun opensWithQuestion(normText: String): Boolean {
        val toks = normText.split(' ').filter { it.isNotEmpty() }
        if (toks.isEmpty()) return false
        if (toks[0] in questionOpeners) return true
        return toks.size >= 2 && "${toks[0]} ${toks[1]}" in questionOpeners
    }
}
