package com.humblesolutions.finai.ui

import kotlin.math.cos
import kotlin.math.sin

/**
 * The icon set, as geometry rather than as assets.
 *
 * **Shared because "same icon" has to mean the same shape.** Material on
 * Android and SF Symbols on iOS are each native and each different: the wallet
 * would have a different clasp, the bell a different curve, and the two apps
 * would not look like one product. These are drawn once, here, and each
 * platform only turns [IconPath] into its own path type.
 *
 * Built from constructors — [circle], [roundedRect], [polyline] — rather than
 * typed out as SVG path data. Transcribed bezier numbers cannot be reviewed or
 * tested; computed ones can be, and [VectorsTest] holds every icon to its
 * viewport.
 *
 * Everything is drawn in a [VIEWPORT]-square box, so a platform scales by one
 * factor and nothing has to know the icon's own units.
 */
object Vectors {

    /** Every icon is authored in a 24×24 box, as icon sets conventionally are. */
    const val VIEWPORT: Float = 24f

    // ── The four cards ──────────────────────────────────────────────────

    /** Income: a wallet, with the clasp on the right. */
    val wallet: IconPath = IconPath(
        roundedRect(left = 2.5f, top = 5.5f, right = 21.5f, bottom = 18.5f, radius = 3f) +
            circle(centreX = 17f, centreY = 12f, radius = 1.6f),
    )

    /** Expenses: a payment card, with the magnetic stripe across it. */
    val card: IconPath = IconPath(
        roundedRect(left = 2f, top = 5f, right = 22f, bottom = 19f, radius = 2.5f) +
            rect(left = 2f, top = 8.5f, right = 22f, bottom = 11f),
    )

    /** Investments: a piggy bank — body, ear, snout and two legs. */
    val piggyBank: IconPath = IconPath(
        // Body, then an ear and a snout that sit OUTSIDE it and legs that sit
        // BELOW it. Anything overlapping the body is subtracted from it rather
        // than added, so a snout drawn across the edge becomes a bite.
        circle(centreX = 11f, centreY = 12f, radius = 6f) +
            polyline(12.6f, 5.6f, 15.6f, 7.6f, 15.5f, 4.2f, close = true) +
            circle(centreX = 18.5f, centreY = 12.6f, radius = 1.3f) +
            rect(left = 7.6f, top = 17.9f, right = 9.4f, bottom = 20.8f) +
            rect(left = 12.6f, top = 17.9f, right = 14.4f, bottom = 20.8f) +
            // Inside the body, so these two ARE meant to be holes.
            rect(left = 9.2f, top = 6.6f, right = 12.8f, bottom = 7.8f) +
            circle(centreX = 8.4f, centreY = 10.4f, radius = 0.85f),
    )

    /** Debts: a statement — a page with a folded corner and two ruled lines. */
    val document: IconPath = IconPath(
        polyline(5f, 2.5f, 14f, 2.5f, 19f, 7.5f, 19f, 21.5f, 5f, 21.5f, close = true) +
            polyline(14f, 2.5f, 14f, 7.5f, 19f, 7.5f, close = true) +
            rect(left = 8f, top = 12f, right = 16f, bottom = 13.2f) +
            rect(left = 8f, top = 15.5f, right = 16f, bottom = 16.7f),
    )

    // ── The chrome ──────────────────────────────────────────────────────

    /** A bell, for anything waiting on a person. */
    val bell: IconPath = IconPath(
        listOf(
            IconCommand.MoveTo(5.5f, 17f),
            IconCommand.LineTo(5.5f, 10.5f),
        ) + arcSweep(centreX = 12f, centreY = 10.5f, radius = 6.5f, fromDegrees = 180f, toDegrees = 360f) +
            listOf(
                IconCommand.LineTo(18.5f, 17f),
                IconCommand.LineTo(5.5f, 17f),
                IconCommand.Close,
            ) +
            circle(centreX = 12f, centreY = 19.5f, radius = 1.8f) +
            rect(left = 11.2f, top = 2.5f, right = 12.8f, bottom = 4.2f),
    )

    /** An eye, for showing a figure that is currently hidden. */
    val eye: IconPath = IconPath(
        lens(centreX = 12f, centreY = 12f, halfWidth = 10f, halfHeight = 5.5f) +
            circle(centreX = 12f, centreY = 12f, radius = 2.6f),
    )

    /** The same eye with a stroke through it, for hiding one that is shown. */
    val eyeOff: IconPath = IconPath(
        lens(centreX = 12f, centreY = 12f, halfWidth = 10f, halfHeight = 5.5f) +
            circle(centreX = 12f, centreY = 12f, radius = 2.6f) +
            thickLine(fromX = 4f, fromY = 3.6f, toX = 20f, toY = 20.4f, width = 1.8f),
    )

    /** A bolt, for the panel that offers a first step. */
    val bolt: IconPath = IconPath(
        polyline(13.5f, 2f, 5f, 13.5f, 11f, 13.5f, 10.5f, 22f, 19f, 10.5f, 13f, 10.5f, close = true),
    )

    // ── The three actions ───────────────────────────────────────────────

    /** Import: a page with an arrow going into it. */
    val importStatement: IconPath = IconPath(
        polyline(5f, 2.5f, 14f, 2.5f, 19f, 7.5f, 19f, 21.5f, 5f, 21.5f, close = true) +
            polyline(14f, 2.5f, 14f, 7.5f, 19f, 7.5f, close = true) +
            thickLine(fromX = 12f, fromY = 11f, toX = 12f, toY = 17.5f, width = 1.6f) +
            polyline(9.2f, 15f, 12f, 18.4f, 14.8f, 15f, close = true),
    )

    /** Add: a plus, drawn as one cross-shaped outline. */
    val plus: IconPath = IconPath(cross(centre = 12f, arm = 7.5f, half = 1.6f))

    /** Review: a list, as rules with bullets. */
    val list: IconPath = IconPath(
        (0..2).flatMap { row ->
            val y = 6.5f + row * 5.5f
            circle(centreX = 5f, centreY = y, radius = 1.3f) +
                rect(left = 9f, top = y - 0.8f, right = 20f, bottom = y + 0.8f)
        },
    )

    /** The chevron at the end of a row that opens something. */
    val chevronRight: IconPath = IconPath(
        thickPolyline(width = 1.9f, points = floatArrayOf(9.5f, 5.5f, 16f, 12f, 9.5f, 18.5f)),
    )

    val chevronLeft: IconPath = IconPath(
        thickPolyline(width = 1.9f, points = floatArrayOf(14.5f, 5.5f, 8f, 12f, 14.5f, 18.5f)),
    )

    /** A small chevron for the month control, pointing down. */
    val chevronDown: IconPath = IconPath(
        thickPolyline(width = 1.9f, points = floatArrayOf(5.5f, 9.5f, 12f, 16f, 18.5f, 9.5f)),
    )

    /** A plus sign as a single twelve-sided outline. */
    fun cross(centre: Float, arm: Float, half: Float): List<IconCommand> = polyline(
        centre - half, centre - arm,
        centre + half, centre - arm,
        centre + half, centre - half,
        centre + arm, centre - half,
        centre + arm, centre + half,
        centre + half, centre + half,
        centre + half, centre + arm,
        centre - half, centre + arm,
        centre - half, centre + half,
        centre - arm, centre + half,
        centre - arm, centre - half,
        centre - half, centre - half,
        close = true,
    )

    /** The arrow on a change pill. Flipped by the platform for a fall. */
    val arrowUp: IconPath = IconPath(
        // The stem starts exactly at the head's base. Overlapping them punches
        // a hole straight through the arrow under even-odd filling.
        rect(left = 11.1f, top = 11.5f, right = 12.9f, bottom = 19.5f) +
            polyline(5.5f, 11.5f, 12f, 4.5f, 18.5f, 11.5f, close = true),
    )

    // ── The decoration behind the header ────────────────────────────────

    /** A pennant on a pole, for the peak in the header. */
    val flag: IconPath = IconPath(
        rect(left = 5f, top = 2.5f, right = 6.6f, bottom = 21.5f) +
            listOf(
                IconCommand.MoveTo(6.6f, 3.5f),
                IconCommand.CurveTo(11f, 1.6f, 15f, 6.4f, 19.5f, 4.5f),
                IconCommand.LineTo(19.5f, 11.5f),
                IconCommand.CurveTo(15f, 13.4f, 11f, 8.6f, 6.6f, 10.5f),
                IconCommand.Close,
            ),
    )

    /** A leaf, for the two that drift across the hills. */
    val leaf: IconPath = IconPath(
        listOf(
            IconCommand.MoveTo(3.5f, 20.5f),
            IconCommand.CurveTo(3.5f, 9f, 11f, 3.5f, 20.5f, 3.5f),
            IconCommand.CurveTo(20.5f, 15f, 13f, 20.5f, 3.5f, 20.5f),
            IconCommand.Close,
        ),
    )

    // ── Construction ────────────────────────────────────────────────────

    /** How far a bezier control point sits to round a quarter circle. */
    private const val KAPPA = 0.5522848f

    /** Four cubics, which is how a circle is drawn when there are no arcs. */
    fun circle(centreX: Float, centreY: Float, radius: Float): List<IconCommand> {
        val offset = radius * KAPPA
        return listOf(
            IconCommand.MoveTo(centreX, centreY - radius),
            IconCommand.CurveTo(
                centreX + offset,
                centreY - radius,
                centreX + radius,
                centreY - offset,
                centreX + radius,
                centreY,
            ),
            IconCommand.CurveTo(
                centreX + radius,
                centreY + offset,
                centreX + offset,
                centreY + radius,
                centreX,
                centreY + radius,
            ),
            IconCommand.CurveTo(
                centreX - offset,
                centreY + radius,
                centreX - radius,
                centreY + offset,
                centreX - radius,
                centreY,
            ),
            IconCommand.CurveTo(
                centreX - radius,
                centreY - offset,
                centreX - offset,
                centreY - radius,
                centreX,
                centreY - radius,
            ),
            IconCommand.Close,
        )
    }

    fun rect(left: Float, top: Float, right: Float, bottom: Float): List<IconCommand> = listOf(
        IconCommand.MoveTo(left, top),
        IconCommand.LineTo(right, top),
        IconCommand.LineTo(right, bottom),
        IconCommand.LineTo(left, bottom),
        IconCommand.Close,
    )

    fun roundedRect(left: Float, top: Float, right: Float, bottom: Float, radius: Float): List<IconCommand> {
        val offset = radius * KAPPA
        return listOf(
            IconCommand.MoveTo(left + radius, top),
            IconCommand.LineTo(right - radius, top),
            IconCommand.CurveTo(right - radius + offset, top, right, top + radius - offset, right, top + radius),
            IconCommand.LineTo(right, bottom - radius),
            IconCommand.CurveTo(right, bottom - radius + offset, right - radius + offset, bottom, right - radius, bottom),
            IconCommand.LineTo(left + radius, bottom),
            IconCommand.CurveTo(left + radius - offset, bottom, left, bottom - radius + offset, left, bottom - radius),
            IconCommand.LineTo(left, top + radius),
            IconCommand.CurveTo(left, top + radius - offset, left + radius - offset, top, left + radius, top),
            IconCommand.Close,
        )
    }

    /** A closed run of straight edges, as x/y pairs. */
    fun polyline(vararg points: Float, close: Boolean = false): List<IconCommand> {
        require(points.size >= 4 && points.size % 2 == 0) { "a polyline needs pairs, and at least two" }
        val commands = mutableListOf<IconCommand>(IconCommand.MoveTo(points[0], points[1]))
        var index = 2
        while (index < points.size) {
            commands += IconCommand.LineTo(points[index], points[index + 1])
            index += 2
        }
        if (close) commands += IconCommand.Close
        return commands
    }

    /** A straight stroke given as a filled quad, so platforms need no stroke style. */
    fun thickLine(fromX: Float, fromY: Float, toX: Float, toY: Float, width: Float): List<IconCommand> {
        val deltaX = toX - fromX
        val deltaY = toY - fromY
        val length = kotlin.math.sqrt(deltaX * deltaX + deltaY * deltaY)
        if (length == 0f) return emptyList()
        // The normal, scaled to half the stroke, offsets each end both ways.
        val normalX = -deltaY / length * (width / 2f)
        val normalY = deltaX / length * (width / 2f)
        return polyline(
            fromX + normalX, fromY + normalY,
            toX + normalX, toY + normalY,
            toX - normalX, toY - normalY,
            fromX - normalX, fromY - normalY,
            close = true,
        )
    }

    /**
     * A stroked polyline as **one** closed outline, with mitred corners.
     *
     * The obvious construction — a quad per segment and a disc at each joint —
     * is wrong here: the parts overlap, and overlapping subpaths cancel under
     * even-odd filling, so every corner came out with a notch bitten through
     * it. One outline has nothing to cancel against.
     */
    fun thickPolyline(width: Float, points: FloatArray): List<IconCommand> {
        require(points.size >= 4 && points.size % 2 == 0) { "a polyline needs pairs, and at least two" }
        val half = width / 2f
        val vertices = (points.indices step 2).map { points[it] to points[it + 1] }
        val left = offsetSide(vertices, half)
        val right = offsetSide(vertices, -half)
        return polyline(
            *(left + right.reversed()).flatMap { listOf(it.first, it.second) }.toFloatArray(),
            close = true,
        )
    }

    /** One side of a stroked polyline, offset by [distance] and mitred at the corners. */
    private fun offsetSide(vertices: List<Pair<Float, Float>>, distance: Float): List<Pair<Float, Float>> {
        val out = mutableListOf<Pair<Float, Float>>()
        for (index in vertices.indices) {
            when (index) {
                0 -> out += shift(vertices[0], normalBetween(vertices[0], vertices[1]), distance)

                vertices.lastIndex ->
                    out += shift(vertices[index], normalBetween(vertices[index - 1], vertices[index]), distance)

                else -> {
                    val before = normalBetween(vertices[index - 1], vertices[index])
                    val after = normalBetween(vertices[index], vertices[index + 1])
                    // The mitre: where the two offset edges would meet. Falls
                    // back to the plain offset when they are near-parallel and
                    // the intersection runs away to infinity.
                    val mitreX = before.first + after.first
                    val mitreY = before.second + after.second
                    val length = kotlin.math.sqrt(mitreX * mitreX + mitreY * mitreY)
                    if (length < 0.0001f) {
                        out += shift(vertices[index], before, distance)
                    } else {
                        val cosHalf = (before.first * mitreX + before.second * mitreY) / length
                        val reach = if (kotlin.math.abs(cosHalf) < 0.2f) distance else distance / cosHalf
                        out += vertices[index].first + mitreX / length * reach to
                            vertices[index].second + mitreY / length * reach
                    }
                }
            }
        }
        return out
    }

    private fun normalBetween(from: Pair<Float, Float>, to: Pair<Float, Float>): Pair<Float, Float> {
        val deltaX = to.first - from.first
        val deltaY = to.second - from.second
        val length = kotlin.math.sqrt(deltaX * deltaX + deltaY * deltaY)
        if (length == 0f) return 0f to 0f
        return -deltaY / length to deltaX / length
    }

    private fun shift(point: Pair<Float, Float>, normal: Pair<Float, Float>, distance: Float): Pair<Float, Float> = point.first + normal.first * distance to point.second + normal.second * distance

    /** An open sweep approximated in cubics — for a dome rather than a full circle. */
    fun arcSweep(
        centreX: Float,
        centreY: Float,
        radius: Float,
        fromDegrees: Float,
        toDegrees: Float,
        segments: Int = 8,
    ): List<IconCommand> {
        val commands = mutableListOf<IconCommand>()
        val total = (toDegrees - fromDegrees) / segments
        var angle = fromDegrees
        repeat(segments) {
            val next = angle + total
            commands += cubicFor(centreX, centreY, radius, angle, next)
            angle = next
        }
        return commands
    }

    private fun cubicFor(
        centreX: Float,
        centreY: Float,
        radius: Float,
        fromDegrees: Float,
        toDegrees: Float,
    ): IconCommand.CurveTo {
        val from = fromDegrees * PI_OVER_180
        val to = toDegrees * PI_OVER_180
        val handle = 4f / 3f * kotlin.math.tan((to - from) / 4f)
        val startX = centreX + radius * cos(from)
        val startY = centreY + radius * sin(from)
        val endX = centreX + radius * cos(to)
        val endY = centreY + radius * sin(to)
        return IconCommand.CurveTo(
            startX - handle * radius * sin(from),
            startY + handle * radius * cos(from),
            endX + handle * radius * sin(to),
            endY - handle * radius * cos(to),
            endX,
            endY,
        )
    }

    /** A lens: two arcs meeting at the corners, as an eye is drawn. */
    fun lens(centreX: Float, centreY: Float, halfWidth: Float, halfHeight: Float): List<IconCommand> = listOf(
        IconCommand.MoveTo(centreX - halfWidth, centreY),
        IconCommand.CurveTo(
            centreX - halfWidth / 2f,
            centreY - halfHeight * 1.6f,
            centreX + halfWidth / 2f,
            centreY - halfHeight * 1.6f,
            centreX + halfWidth,
            centreY,
        ),
        IconCommand.CurveTo(
            centreX + halfWidth / 2f,
            centreY + halfHeight * 1.6f,
            centreX - halfWidth / 2f,
            centreY + halfHeight * 1.6f,
            centreX - halfWidth,
            centreY,
        ),
        IconCommand.Close,
    )

    private const val PI_OVER_180 = 0.017453293f
}

/** One icon: a list of commands in a [Vectors.VIEWPORT]-square box. */
data class IconPath(val commands: List<IconCommand>) {
    val viewport: Float get() = Vectors.VIEWPORT
}

/**
 * A drawing command. Deliberately only four: a move, a line, a cubic and a
 * close. Arcs are approximated into cubics by [Vectors.arcSweep], so neither
 * platform needs an arc renderer and the two cannot round them differently.
 */
sealed interface IconCommand {
    data class MoveTo(val x: Float, val y: Float) : IconCommand

    data class LineTo(val x: Float, val y: Float) : IconCommand

    data class CurveTo(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val x: Float,
        val y: Float,
    ) : IconCommand

    data object Close : IconCommand
}
