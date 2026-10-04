package com.humblesolutions.finai.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.core.view.WindowCompat

/*
 * The deep green field home is drawn on, shared with the screens that open
 * from it — "Your transactions" — so moving between them keeps one ground.
 */

/**
 * The green the top half sits on.
 *
 * Darker than the brand green on purpose: white text on `#22C55E` is about
 * 2.3:1, unreadable for the small print here. These keep every white line at
 * 4.5:1 or better, with a lighter glow behind the chart, where nothing small
 * is written.
 */
internal object Field {
    private val LightTop = Color(0xFF0F5A30)
    private val LightBottom = Color(0xFF18804A)
    private val DarkTop = Color(0xFF0A3A20)
    private val DarkBottom = Color(0xFF07170E)

    fun brush(dark: Boolean): Brush = Brush.verticalGradient(
        if (dark) listOf(DarkTop, DarkBottom) else listOf(LightTop, LightBottom),
    )

    /** White at a strength, for everything written on the field. */
    fun ink(alpha: Float = 1f): Color = Color.White.copy(alpha = alpha)

    /** Frosted glass: the pills and the bell sit on this. */
    val Glass = Color.White.copy(alpha = 0.14f)
    val GlassEdge = Color.White.copy(alpha = 0.22f)
}

/**
 * The soft hills across the field, as in the design. Drawn rather than shipped
 * as an asset so it costs no image; decorative, so hidden from screen readers.
 */
@Composable
internal fun Waves(modifier: Modifier) {
    Canvas(modifier.clearAndSetSemantics {}) {
        val w = size.width
        val h = size.height

        // A glow behind the chart, top right — where the design is brightest.
        drawCircle(
            brush = Brush.radialGradient(
                listOf(Color(0xFF34D27F).copy(alpha = 0.32f), Color.Transparent),
                center = Offset(w * 0.86f, h * 0.2f),
                radius = w * 0.75f,
            ),
            radius = w * 0.75f,
            center = Offset(w * 0.86f, h * 0.2f),
        )

        fun hill(base: Float, lift: Float, phase: Float, alpha: Float) {
            val path = Path().apply {
                moveTo(0f, h)
                lineTo(0f, h * base)
                cubicTo(
                    w * (0.18f + phase),
                    h * (base - lift),
                    w * (0.42f + phase),
                    h * (base + lift * 0.6f),
                    w * 0.62f,
                    h * (base - lift * 0.35f),
                )
                cubicTo(
                    w * 0.78f,
                    h * (base - lift * 0.9f),
                    w * 0.9f,
                    h * (base + lift * 0.2f),
                    w,
                    h * (base - lift * 0.5f),
                )
                lineTo(w, h)
                close()
            }
            drawPath(path, Color.White.copy(alpha = alpha))
        }
        hill(base = 0.30f, lift = 0.06f, phase = 0f, alpha = 0.035f)
        hill(base = 0.58f, lift = 0.07f, phase = 0.06f, alpha = 0.04f)
        hill(base = 0.80f, lift = 0.05f, phase = -0.04f, alpha = 0.05f)
    }
}

/**
 * On a green field the status bar's icons must be white, in either theme. The
 * theme sets them dark for light mode everywhere else, so this flips them
 * while home is showing and puts them back when it goes.
 */
@Composable
internal fun LightStatusBarIcons(dark: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    DisposableEffect(dark) {
        val window = (view.context as? android.app.Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.isAppearanceLightStatusBars = false
        onDispose { controller?.isAppearanceLightStatusBars = !dark }
    }
}
