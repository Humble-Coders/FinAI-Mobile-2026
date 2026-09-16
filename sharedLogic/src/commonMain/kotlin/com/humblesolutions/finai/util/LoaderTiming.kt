package com.humblesolutions.finai.util

/**
 * How long the coin loader stays on screen: at least [MIN_VISIBLE_MS], or as
 * long as the work really takes when that is longer.
 *
 * A loader that flashes up for a fast answer and vanishes reads as a glitch,
 * and the coin needs a full slow turn to read as a coin at all. Shared, so
 * Android and iOS hold it for exactly the same time.
 */
object LoaderTiming {

    const val MIN_VISIBLE_MS: Long = 2_000

    /**
     * How much longer a loader that appeared at [shownAtMs] must stay up at
     * [nowMs]. Never negative, and never more than the minimum — a clock that
     * steps backwards cannot hold it up for longer than that.
     */
    fun remainingMs(shownAtMs: Long, nowMs: Long): Long =
        (MIN_VISIBLE_MS - (nowMs - shownAtMs)).coerceIn(0L, MIN_VISIBLE_MS)
}
