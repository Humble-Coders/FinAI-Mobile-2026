package com.humblesolutions.finai.util

import kotlin.test.Test
import kotlin.test.assertEquals

class LoaderTimingTest {

    @Test
    fun `a fast answer still keeps the loader up for the minimum`() {
        assertEquals(2_000, LoaderTiming.remainingMs(shownAtMs = 10_000, nowMs = 10_000))
        assertEquals(1_700, LoaderTiming.remainingMs(shownAtMs = 10_000, nowMs = 10_300))
    }

    @Test
    fun `a slow answer lets it go straight away`() {
        assertEquals(0, LoaderTiming.remainingMs(shownAtMs = 10_000, nowMs = 12_000))
        assertEquals(0, LoaderTiming.remainingMs(shownAtMs = 10_000, nowMs = 45_000))
    }

    @Test
    fun `a clock that steps backwards holds it no longer than the minimum`() {
        assertEquals(2_000, LoaderTiming.remainingMs(shownAtMs = 10_000, nowMs = 4_000))
    }
}
