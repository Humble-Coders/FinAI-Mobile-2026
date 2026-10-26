package com.humblesolutions.finai.util

/**
 * How long the coin loader stays on screen.
 *
 * Signing in or up holds it for at least [MIN_VISIBLE_MS]: the coin travels off
 * the card and needs a full slow turn to read as a coin at all. Everything after
 * that — the hand-over between steps, setup, every signed-in screen — shows it
 * for exactly as long as the work takes. Shared, so Android and iOS agree.
 */
object LoaderTiming {

    const val MIN_VISIBLE_MS: Long = 2_000

    /** The least time a loader shown now stays up: the minimum only while signed out. */
    fun minimumMs(signedIn: Boolean): Long = if (signedIn) 0L else MIN_VISIBLE_MS

    /**
     * How much longer a loader that appeared at [shownAtMs] must stay up at
     * [nowMs], given the [minimumMs] it was shown with. Never negative, and never
     * more than that minimum — a clock that steps backwards cannot hold it up
     * for longer.
     */
    fun remainingMs(shownAtMs: Long, nowMs: Long, minimumMs: Long = MIN_VISIBLE_MS): Long = (minimumMs - (nowMs - shownAtMs)).coerceIn(0L, minimumMs.coerceAtLeast(0L))
}
