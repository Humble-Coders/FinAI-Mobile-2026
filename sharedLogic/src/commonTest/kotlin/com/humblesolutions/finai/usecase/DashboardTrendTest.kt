package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.model.DayPoint
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

    @Test
    fun the_zero_line_is_marked_when_the_months_cross_it() {
        val chart = assertNotNull(DashboardTrend.chart(listOf(month(7, "-500"), month(8, "1500"))))
        val zero = assertNotNull(chart.zero)
        val (loss, gain) = chart.points.map { assertNotNull(it.y) }
        assertTrue(loss < zero && zero < gain)
    }

    @Test
    fun there_is_no_zero_line_when_every_month_is_on_one_side() {
        assertNull(assertNotNull(DashboardTrend.chart(listOf(month(7, "100"), month(8, "300")))).zero)
        assertNull(assertNotNull(DashboardTrend.chart(listOf(month(7, "-100"), month(8, "-300")))).zero)
    }

    // ── The month, day by day ───────────────────────────────────────────

    private fun day(d: Int, net: String, m: Int = 8) = DayPoint("2026-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}", net)

    @Test
    fun no_days_is_no_daily_chart() {
        assertNull(DashboardTrend.daily(emptyList(), "2026-08-01"), "an empty month, or an older server")
        assertNull(DashboardTrend.daily(listOf(day(3, "10")), "not a month"))
    }

    @Test
    fun the_axis_is_the_whole_month_so_a_running_month_stops_part_way() {
        val chart = assertNotNull(DashboardTrend.daily((1..16).map { day(it, "100") }, "2026-08-01"))

        assertEquals(0.0, chart.days.first().x)
        assertEquals(0.5, chart.days.last().x, "the 16th of 31 days is halfway")
        assertEquals(15, chart.markerIndex, "the marker is the latest day")
    }

    @Test
    fun the_ticks_are_the_first_each_week_and_the_last_day() {
        val august = assertNotNull(DashboardTrend.daily(listOf(day(1, "1")), "2026-08-01"))
        val february = assertNotNull(DashboardTrend.daily(listOf(day(1, "1", m = 2)), "2026-02-01"))

        assertEquals(listOf(1, 8, 15, 22, 31), august.ticks.map { it.day })
        assertEquals(listOf(1, 8, 15, 22, 28), february.ticks.map { it.day })
        assertEquals(1.0, february.ticks.last().x)
    }

    @Test
    fun zero_is_always_on_the_scale() {
        // All ahead: zero is the floor the line is read against.
        val ahead = assertNotNull(DashboardTrend.daily(listOf(day(1, "500"), day(2, "900")), "2026-08-01"))
        assertTrue(ahead.zero in 0.0..1.0, "zero is drawn, not off the bottom: ${ahead.zero}")
        assertTrue(ahead.zero < ahead.days.minOf { it.y })

        // Dipping behind: the line crosses it, a day in the red sits below.
        val dips = assertNotNull(DashboardTrend.daily(listOf(day(1, "500"), day(2, "-300")), "2026-08-01"))
        assertTrue(dips.days[1].y < dips.zero && dips.zero < dips.days[0].y)
    }

    @Test
    fun days_outside_the_month_or_unreadable_are_left_off_and_order_is_by_day() {
        val chart = assertNotNull(
            DashboardTrend.daily(listOf(day(3, "30"), day(1, "10"), day(31, "5", m = 7), day(2, "x")), "2026-08-01"),
        )

        assertEquals(listOf("2026-08-01", "2026-08-03"), chart.days.map { it.day })
    }

    @Test
    fun a_month_of_zeroes_is_a_flat_line_on_the_zero_mark() {
        val chart = assertNotNull(DashboardTrend.daily(listOf(day(1, "0"), day(2, "0")), "2026-08-01"))
        assertEquals(chart.zero, chart.days.first().y)
    }
}
