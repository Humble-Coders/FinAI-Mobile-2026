package com.humblesolutions.finai.usecase

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SplashIntroTest {

    private val frames = SplashIntro.frames
    private fun shown(frame: WordmarkFrame) = frame.plain + frame.accent

    @Test
    fun `it types Finance one letter at a time first`() {
        assertEquals(
            listOf("F", "Fi", "Fin", "Fina", "Finan", "Financ", "Finance"),
            frames.take(7).map(::shown),
        )
        assertTrue(frames.take(7).all { it.accent.isEmpty() })
    }

    @Test
    fun `it deletes back to Fin before any green appears`() {
        assertEquals(listOf("Financ", "Finan", "Fina", "Fin"), frames.drop(7).take(4).map(::shown))
        val firstAccent = frames.indexOfFirst { it.accent.isNotEmpty() }
        assertEquals("Fin", frames[firstAccent - 1].plain)
    }

    @Test
    fun `it ends on FinAI with only AI in the accent`() {
        assertEquals(WordmarkFrame(plain = "Fin", accent = "AI", holdMs = 0), SplashIntro.finalFrame)
    }

    @Test
    fun `every frame changes exactly one letter`() {
        frames.zipWithNext().forEach { (before, after) ->
            val difference = shown(after).length - shown(before).length
            assertEquals(1, kotlin.math.abs(difference), "${shown(before)} -> ${shown(after)}")
        }
    }

    @Test
    fun `the whole intro lasts about two seconds`() {
        // Long enough to read, short enough not to delay a signed-in user.
        assertTrue(SplashIntro.totalMs in 1_500L..2_500L, "was ${SplashIntro.totalMs}ms")
    }
}
