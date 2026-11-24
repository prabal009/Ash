package com.jarvis.assistant.voice

import android.content.Context
import java.util.Locale

/** English unigram pieces and scores extracted from the pinned GigaSpeech keyword model's bpe.model. */
object WakePhrase {
    const val DEFAULT = "Hey Ash"
    const val ALTERNATE = "Hello Ash"
    internal const val HEY_ASH = "HEY_ASH"
    internal const val HELLO_ASH = "HELLO_ASH"
    private val valid = Regex("[A-Za-z]+(?: [A-Za-z]+)*")

    fun normalize(text: String) = text.trim().replace(Regex("\\s+"), " ")
    fun isValid(text: String) = text.length in 1..40 && valid.matches(text)

    internal fun optional(text: String): String = normalize(text).takeIf {
        isValid(it) && !it.equals("Ash", true) && !it.equals(DEFAULT, true) && !it.equals(ALTERNATE, true)
    }.orEmpty()

    internal fun detectedPhrase(hit: String, custom: String): String = when (hit) {
        HEY_ASH -> DEFAULT
        HELLO_ASH -> ALTERNATE
        "CUSTOM" -> custom
        else -> ""
    }

    @Volatile private var piecesByScore: Map<String, Float>? = null

    /** Highest-scoring segmentation, as used by the model's SentencePiece unigram tokenizer. */
    fun keyword(context: Context, phrase: String): String {
        require(isValid(phrase))
        val scores = piecesByScore ?: synchronized(this) {
            piecesByScore ?: context.assets.open("wake-pieces.txt").bufferedReader().useLines { lines ->
                lines.associate { it.substringBefore(' ') to it.substringAfter(' ').toFloat() }
            }.also { piecesByScore = it }
        }
        val pieces = phrase.uppercase(Locale.ROOT).split(' ').flatMap { word ->
            val input = "▁$word"
            val best = FloatArray(input.length + 1) { Float.NEGATIVE_INFINITY }
            val previous = IntArray(input.length + 1)
            best[0] = 0f
            for (end in 1..input.length) {
                for (start in 0 until end) {
                    val score = scores[input.substring(start, end)] ?: continue
                    if (best[start] + score > best[end]) {
                        best[end] = best[start] + score
                        previous[end] = start
                    }
                }
            }
            check(best.last().isFinite())
            val tokens = mutableListOf<String>()
            var end = input.length
            while (end > 0) {
                val start = previous[end]
                tokens += input.substring(start, end)
                end = start
            }
            tokens.asReversed()
        }
        return pieces.joinToString(" ") + " :2.0 #0.1 @CUSTOM"
    }
}
