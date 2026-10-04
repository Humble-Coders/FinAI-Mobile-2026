package com.humblesolutions.finai.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
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

    /** The field's top colour, solid: the bar home's header shrinks into sits on it. */
    fun top(dark: Boolean): Color = if (dark) DarkTop else LightTop

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

/**
 * The faint pictures on the field, as in the design: a tilted card in the top
 * corner, a rising bar chart with its arrow down the right side, and soft
 * rounds lower down. White at a few percent, so they read as texture and never
 * compete with a figure. Fixed to the screen rather than the scroll, and
 * hidden from screen readers.
 */
@Composable
internal fun FieldVectors(modifier: Modifier = Modifier) {
    Canvas(modifier.clearAndSetSemantics {}) {
        val w = size.width
        val h = size.height
        val dp = density
        fun ink(alpha: Float) = Color.White.copy(alpha = alpha)

        // A card, tilted, half off the top right.
        rotate(degrees = -16f, pivot = Offset(w * 0.78f, 110 * dp)) {
            val cardSize = Size(210 * dp, 134 * dp)
            val topLeft = Offset(w * 0.78f - cardSize.width / 2, 110 * dp - cardSize.height / 2)
            val corner = CornerRadius(20 * dp)
            drawRoundRect(ink(0.08f), topLeft, cardSize, corner)
            drawRoundRect(ink(0.13f), topLeft, cardSize, corner, style = Stroke(1.5f * dp))
            // The stripe and the chip.
            drawRect(ink(0.07f), Offset(topLeft.x, topLeft.y + 30 * dp), Size(cardSize.width, 22 * dp))
            drawRoundRect(
                ink(0.10f),
                Offset(topLeft.x + 22 * dp, topLeft.y + 68 * dp),
                Size(34 * dp, 26 * dp),
                CornerRadius(6 * dp),
            )
        }

        // A bar chart down the right side, rising, with its arrow.
        val base = h * 0.46f
        val barWidth = 18 * dp
        val heights = listOf(34f, 56f, 82f, 112f)
        heights.forEachIndexed { index, height ->
            val left = w - 118 * dp + index * (barWidth + 8 * dp)
            drawRoundRect(
                ink(0.06f + index * 0.01f),
                Offset(left, base - height * dp),
                Size(barWidth, height * dp),
                CornerRadius(5 * dp),
            )
        }
        val arrow = Path().apply {
            moveTo(w - 130 * dp, base - 40 * dp)
            lineTo(w - 92 * dp, base - 76 * dp)
            lineTo(w - 70 * dp, base - 64 * dp)
            lineTo(w - 30 * dp, base - 128 * dp)
        }
        drawPath(arrow, ink(0.12f), style = Stroke(3 * dp, cap = StrokeCap.Round, join = StrokeJoin.Round))
        val head = Path().apply {
            moveTo(w - 30 * dp, base - 128 * dp)
            lineTo(w - 46 * dp, base - 122 * dp)
            moveTo(w - 30 * dp, base - 128 * dp)
            lineTo(w - 33 * dp, base - 111 * dp)
        }
        drawPath(head, ink(0.12f), style = Stroke(3 * dp, cap = StrokeCap.Round))

        // Soft rounds lower down.
        drawCircle(ink(0.04f), radius = 150 * dp, center = Offset(-20 * dp, h * 0.78f))
        drawCircle(ink(0.035f), radius = 110 * dp, center = Offset(w * 0.92f, h * 0.9f))
        drawCircle(ink(0.05f), radius = 7 * dp, center = Offset(w * 0.18f, h * 0.36f))
        drawCircle(ink(0.05f), radius = 4 * dp, center = Offset(w * 0.62f, h * 0.62f))
    }
}
