package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.model.MonthPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DashboardTrendTest {

    private fun month(m: Int, net: String?) = MonthPoint("2026-${m.toString().padStart(2, '0')}-01", net)

    @Test
    fun nothing_recorded_is_no_chart() {
        assertNull(DashboardTrend.chart(emptyList()))
        assertNull(DashboardTrend.chart(listOf(month(7, null), month(8, null))))
    }

    @Test
    fun the_months_before_the_first_recorded_one_are_left_off() {
        // Otherwise somebody who started last month sees one dot pinned to the
        // right of an empty chart.
        val chart = assertNotNull(
            DashboardTrend.chart(listOf(month(5, null), month(6, null), month(7, "100"), month(8, "300"))),
        )

        assertEquals(listOf("2026-07-01", "2026-08-01"), chart.points.map { it.month })
        assertEquals(0.0, chart.points.first().x)
        assertEquals(1.0, chart.points.last().x)
    }

    @Test
    fun a_gap_in_the_middle_is_kept_and_never_drawn_through() {
        val chart = assertNotNull(
            DashboardTrend.chart(listOf(month(6, "100"), month(7, null), month(8, "300"))),
        )

        assertNull(chart.points[1].y, "July is a gap, not a zero")
        assertEquals(listOf(listOf(0), listOf(2)), chart.segments, "no line crosses July")
    }

    @Test
    fun a_better_month_sits_higher_and_a_loss_sits_lowest() {
        val chart = assertNotNull(
            DashboardTrend.chart(listOf(month(6, "-500"), month(7, "100"), month(8, "900"))),
        )
        val (loss, small, large) = chart.points.map { assertNotNull(it.y) }

        assertTrue(loss < small && small < large)
    }

    @Test
    fun the_line_keeps_clear_of_the_edges() {
        val chart = assertNotNull(DashboardTrend.chart(listOf(month(7, "0"), month(8, "1000"))))
        val ys = chart.points.map { assertNotNull(it.y) }

        assertTrue(ys.min() > 0.0 && ys.max() < 1.0)
    }

    @Test
    fun months_that_are_all_equal_are_a_flat_line_through_the_middle() {
        val chart = assertNotNull(DashboardTrend.chart(listOf(month(7, "250"), month(8, "250"))))
        assertEquals(listOf(0.5, 0.5), chart.points.map { it.y })
    }

    @Test
    fun a_single_month_sits_at_the_right_beside_its_figure() {
        val chart = assertNotNull(DashboardTrend.chart(listOf(month(7, null), month(8, "250"))))

        assertEquals(1, chart.points.size)
        assertEquals(1.0, chart.points.single().x)
        assertEquals(0, chart.markerIndex)
    }

    @Test
    fun the_marker_is_the_month_in_view_when_it_holds_something() {
        val chart = assertNotNull(DashboardTrend.chart(listOf(month(7, "100"), month(8, "300"))))
        assertEquals(1, chart.markerIndex)
    }

    @Test
    fun a_month_in_view_with_nothing_yet_has_no_marker() {
        // Early in a month, before anything has been recorded.
        val chart = assertNotNull(DashboardTrend.chart(listOf(month(7, "100"), month(8, null))))
        assertNull(chart.markerIndex)
    }
}
