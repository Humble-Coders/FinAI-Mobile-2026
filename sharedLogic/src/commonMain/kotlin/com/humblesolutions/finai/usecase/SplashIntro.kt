package com.humblesolutions.finai.usecase

/**
 * One moment of the splash wordmark: [plain] in the text colour, then [accent]
 * in the brand green, held for [holdMs] before the next frame.
 */
data class WordmarkFrame(
    val plain: String = "",
    val accent: String = "",
    val holdMs: Long = 0,
)

/**
 * The launch splash's wordmark animation — shared, so Android and iOS play the
 * same timeline to the millisecond.
 *
 * "Finance" is typed, its last four letters are deleted back to "Fin", then
 * "AI" is typed in green. The tagline fades in once the last frame shows.
 *
 * These are the product's name, not copy, so they are not translated and live
 * here rather than in i18n. Every other wordmark in the app renders
 * [finalFrame], so the name is spelled in exactly one place.
 */
object SplashIntro {

    const val TYPE_MS = 90L
    const val DELETE_MS = 60L
    const val PAUSE_AFTER_WORD_MS = 450L
    const val PAUSE_BEFORE_AI_MS = 150L
    const val TAGLINE_FADE_MS = 450L

    private const val WORD = "Finance"
    private const val STEM = "Fin"
    private const val ACCENT = "AI"

    val frames: List<WordmarkFrame> = buildList {
        for (length in 1..WORD.length) {
            val last = length == WORD.length
            add(WordmarkFrame(plain = WORD.take(length), holdMs = if (last) PAUSE_AFTER_WORD_MS else TYPE_MS))
        }
        for (length in WORD.length - 1 downTo STEM.length) {
            val last = length == STEM.length
            add(WordmarkFrame(plain = WORD.take(length), holdMs = if (last) PAUSE_BEFORE_AI_MS else DELETE_MS))
        }
        for (length in 1..ACCENT.length) {
            val last = length == ACCENT.length
            add(WordmarkFrame(plain = STEM, accent = ACCENT.take(length), holdMs = if (last) 0 else TYPE_MS))
        }
    }

    /**
     * The finished wordmark: what shows with no animation — reduced motion, any
     * splash after the first, and everywhere else the name appears.
     */
    val finalFrame: WordmarkFrame get() = frames.last()

    /** From the first letter to the tagline fully shown. */
    val totalMs: Long get() = frames.sumOf { it.holdMs } + TAGLINE_FADE_MS
}
