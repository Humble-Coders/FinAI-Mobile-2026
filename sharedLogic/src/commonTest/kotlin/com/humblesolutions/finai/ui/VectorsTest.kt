package com.humblesolutions.finai.ui

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The icons are authored as numbers, and nobody can review a bezier by reading
 * it. These hold every one to its box: inside the viewport, filling enough of
 * it to be the icon rather than a speck, closed, and free of NaN.
 *
 * This is the only check there is before a device — the shapes cannot be
 * eyeballed from here — so it is deliberately strict.
 *
 * **What it cannot catch:** these measure each icon as a whole. A single
 * component drawn at the wrong size inside a multi-part icon — the piggy
 * bank's body, say, with its ear and legs still holding the bounds — passes
 * every assertion here and looks wrong on a screen. Whether an icon reads as
 * the thing it depicts is a question for eyes, not for this file.
 */
class VectorsTest {

    private val all: Map<String, IconPath> = mapOf(
        "wallet" to Vectors.wallet,
        "card" to Vectors.card,
        "piggyBank" to Vectors.piggyBank,
        "document" to Vectors.document,
        "bell" to Vectors.bell,
        "eye" to Vectors.eye,
        "eyeOff" to Vectors.eyeOff,
        "bolt" to Vectors.bolt,
        "importStatement" to Vectors.importStatement,
        "plus" to Vectors.plus,
        "list" to Vectors.list,
        "chevronRight" to Vectors.chevronRight,
        "chevronLeft" to Vectors.chevronLeft,
        "chevronDown" to Vectors.chevronDown,
        "arrowUp" to Vectors.arrowUp,
        "flag" to Vectors.flag,
        "leaf" to Vectors.leaf,
    )

    private fun pointsOf(path: IconPath): List<Pair<Float, Float>> = path.commands.flatMap { command ->
        when (command) {
            is IconCommand.MoveTo -> listOf(command.x to command.y)

            is IconCommand.LineTo -> listOf(command.x to command.y)

            is IconCommand.CurveTo -> listOf(
                command.x1 to command.y1,
                command.x2 to command.y2,
                command.x to command.y,
            )

            IconCommand.Close -> emptyList()
        }
    }

    @Test
    fun every_icon_stays_inside_its_viewport() {
        // Anything outside is clipped on both platforms, silently.
        all.forEach { (name, path) ->
            pointsOf(path).forEach { (x, y) ->
                assertTrue(x >= 0f && x <= Vectors.VIEWPORT, "$name has x=$x outside 0..24")
                assertTrue(y >= 0f && y <= Vectors.VIEWPORT, "$name has y=$y outside 0..24")
            }
        }
    }

    /**
     * Chevrons are directional glyphs and are narrow across the direction they
     * point — a `>` as wide as it is tall is not a chevron. Named rather than
     * handled by a looser rule for everything, which would stop catching the
     * mistake this test exists for.
     */
    private val directional = setOf("chevronRight", "chevronLeft", "chevronDown")

    @Test
    fun every_icon_actually_fills_its_box() {
        // The likeliest authoring mistake is an icon drawn too small or off in
        // a corner, which renders as a speck and reads as a missing asset.
        all.forEach { (name, path) ->
            val points = pointsOf(path)
            val width = points.maxOf { it.first } - points.minOf { it.first }
            val height = points.maxOf { it.second } - points.minOf { it.second }
            val longest = maxOf(width, height)
            val shortest = minOf(width, height)
            assertTrue(longest >= 12f, "$name spans only ${longest}pt in a 24pt box")
            val floor = if (name in directional) 7f else 9f
            assertTrue(shortest >= floor, "$name is only ${shortest}pt across in a 24pt box")
        }
    }

    @Test
    fun every_icon_is_roughly_centred() {
        // A shape pushed to one side looks misaligned beside its neighbours,
        // and that is hard to see on one icon and obvious on a row of four.
        all.forEach { (name, path) ->
            val points = pointsOf(path)
            val centreX = (points.maxOf { it.first } + points.minOf { it.first }) / 2f
            val centreY = (points.maxOf { it.second } + points.minOf { it.second }) / 2f
            assertTrue(abs(centreX - 12f) <= 2.5f, "$name sits at x=$centreX, not near 12")
            assertTrue(abs(centreY - 12f) <= 2.5f, "$name sits at y=$centreY, not near 12")
        }
    }

    @Test
    fun every_subpath_opens_with_a_move() {
        // A line or curve before any move has no start, and the platforms
        // disagree about what to do with it.
        all.forEach { (name, path) ->
            var started = false
            path.commands.forEach { command ->
                when (command) {
                    is IconCommand.MoveTo -> started = true
                    IconCommand.Close -> started = false
                    else -> assertTrue(started, "$name draws before it moves")
                }
            }
        }
    }

    @Test
    fun every_icon_closes_what_it_opens() {
        // These are filled, not stroked. An unclosed subpath fills to an
        // implied straight edge, which is a different shape.
        all.forEach { (name, path) ->
            val moves = path.commands.count { it is IconCommand.MoveTo }
            val closes = path.commands.count { it == IconCommand.Close }
            assertEquals(moves, closes, "$name opens $moves subpaths and closes $closes")
        }
    }

    @Test
    fun no_icon_carries_a_number_that_is_not_one() {
        all.forEach { (name, path) ->
            pointsOf(path).forEach { (x, y) ->
                assertTrue(x.isFinite(), "$name has a non-finite x")
                assertTrue(y.isFinite(), "$name has a non-finite y")
            }
        }
    }

    // ── The constructors the icons are built from ───────────────────────

    @Test
    fun a_circle_is_bounded_by_its_own_radius() {
        val points = pointsOf(IconPath(Vectors.circle(centreX = 12f, centreY = 12f, radius = 5f)))
        // Control points of a circle sit outside the curve but inside the
        // square around it, which is what a platform's bounds will report.
        assertTrue(points.minOf { it.first } >= 7f - 0.01f)
        assertTrue(points.maxOf { it.first } <= 17f + 0.01f)
        assertEquals(7f, points.minOf { it.second }, 0.01f)
        assertEquals(17f, points.maxOf { it.second }, 0.01f)
    }

    @Test
    fun a_rounded_rect_reaches_its_corners_without_passing_them() {
        val points = pointsOf(
            IconPath(Vectors.roundedRect(left = 2f, top = 4f, right = 22f, bottom = 20f, radius = 3f)),
        )
        assertEquals(2f, points.minOf { it.first }, 0.01f)
        assertEquals(22f, points.maxOf { it.first }, 0.01f)
        assertEquals(4f, points.minOf { it.second }, 0.01f)
        assertEquals(20f, points.maxOf { it.second }, 0.01f)
    }

    @Test
    fun a_thick_line_is_as_wide_as_it_was_asked_to_be() {
        // Horizontal, so the width is simply the height of the quad.
        val points = pointsOf(IconPath(Vectors.thickLine(4f, 12f, 20f, 12f, width = 2f)))
        assertEquals(2f, points.maxOf { it.second } - points.minOf { it.second }, 0.01f)
        assertEquals(16f, points.maxOf { it.first } - points.minOf { it.first }, 0.01f)
    }

    @Test
    fun a_thick_line_of_no_length_draws_nothing_rather_than_dividing_by_zero() {
        assertTrue(Vectors.thickLine(5f, 5f, 5f, 5f, width = 2f).isEmpty())
    }

    @Test
    fun an_arc_sweep_starts_and_ends_where_it_was_told() {
        // A half turn from due left to due right, over the top of the circle.
        val commands = Vectors.arcSweep(12f, 12f, 6f, fromDegrees = 180f, toDegrees = 360f)
        val last = commands.last() as IconCommand.CurveTo
        assertEquals(18f, last.x, 0.05f)
        assertEquals(12f, last.y, 0.05f)
    }

    @Test
    fun a_polyline_needs_pairs() {
        val thrown = runCatching { Vectors.polyline(1f, 2f, 3f) }.exceptionOrNull()
        assertTrue(thrown is IllegalArgumentException)
    }

    /**
     * Icons that must be one solid shape, because they are a single stroke or
     * a single outline rather than a drawing made of parts.
     */
    private val solid = mapOf(
        "plus" to Vectors.plus,
        "chevronRight" to Vectors.chevronRight,
        "chevronLeft" to Vectors.chevronLeft,
        "chevronDown" to Vectors.chevronDown,
    )

    @Test
    fun a_shape_that_should_be_solid_is_drawn_as_one_outline() {
        // Found by rendering, not by any assertion here: these were built from
        // overlapping parts — a bar per arm, a disc per joint — and overlapping
        // subpaths CANCEL under even-odd filling. The plus had a hole punched
        // clean through its middle and every chevron had a notch bitten out of
        // its corner, and both passed every other test in this file.
        solid.forEach { (name, path) ->
            val subpaths = path.commands.count { it is IconCommand.MoveTo }
            assertEquals(1, subpaths, "$name is $subpaths subpaths; overlapping parts cancel out")
        }
    }

    /** The icon split into its subpaths, each as the points it is drawn from. */
    private fun subpathsOf(path: IconPath): List<List<Pair<Float, Float>>> {
        val out = mutableListOf<List<Pair<Float, Float>>>()
        var current = mutableListOf<Pair<Float, Float>>()
        path.commands.forEach { command ->
            when (command) {
                is IconCommand.MoveTo -> {
                    if (current.isNotEmpty()) out += current
                    current = mutableListOf(command.x to command.y)
                }

                is IconCommand.LineTo -> current += command.x to command.y

                is IconCommand.CurveTo -> {
                    current += command.x1 to command.y1
                    current += command.x2 to command.y2
                    current += command.x to command.y
                }

                IconCommand.Close -> Unit
            }
        }
        if (current.isNotEmpty()) out += current
        return out
    }

    @Test
    fun the_parts_of_the_piggy_bank_do_not_eat_each_other() {
        // Same failure as above, one step subtler: the ear and the snout were
        // drawn ACROSS the body's edge, so each took a bite out of it instead
        // of adding to it. A part must be wholly outside the body (an ear, a
        // leg) or wholly inside it (the coin slot, the eye, which are meant to
        // be holes). Straddling the edge is the one thing that cannot work.
        //
        // Read from the icon rather than from numbers copied out of it — the
        // first version of this test hardcoded both, so moving the snout in
        // the source changed nothing it checked.
        val parts = subpathsOf(Vectors.piggyBank)
        val body = parts.first()
        val centreX = (body.maxOf { it.first } + body.minOf { it.first }) / 2f
        val centreY = (body.maxOf { it.second } + body.minOf { it.second }) / 2f
        val radius = (body.maxOf { it.first } - body.minOf { it.first }) / 2f

        fun distance(point: Pair<Float, Float>) = kotlin.math.sqrt(
            (point.first - centreX) * (point.first - centreX) +
                (point.second - centreY) * (point.second - centreY),
        )

        parts.drop(1).forEachIndexed { index, part ->
            val inside = part.count { distance(it) < radius }
            assertTrue(
                inside == 0 || inside == part.size,
                "piggy part ${index + 1} straddles the body edge: $inside of ${part.size} points are inside",
            )
        }
    }
}
