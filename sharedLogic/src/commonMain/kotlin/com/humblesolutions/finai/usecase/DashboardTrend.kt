package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.model.DayPoint
import com.humblesolutions.finai.model.MonthPoint
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/**
 * Where the trend's points sit on the dashboard's chart (PRD F3).
 *
 * Geometry, not money: positions in 0..1 for a platform to scale to its own
 * canvas. Shared because the honest choices about a chart are decisions — what
 * a month with nothing recorded looks like, and whether a line is allowed to
 * cross it — and two apps drawing the same months differently is the kind of
 * difference nobody finds until they compare two phones.
 */
object DashboardTrend {

    /**
     * One month on the chart.
     *
     * @property x 0 at the left edge, 1 at the right.
     * @property y 0 at the bottom, 1 at the top; null for a month with no rows,
     *   which is a gap and never a zero — a zero is a figure nobody recorded.
     */
    data class Point(
        val month: String,
        val x: Double,
        val y: Double?,
    )

    /**
     * @property segments runs of consecutive recorded months, as indices into
     *   [points]. A line is drawn within a run and never across a gap, because
     *   a line through an empty month invents a value for it.
     * @property markerIndex the month in view, when it holds something. The
     *   server's window ends at that month, so this is the last point or none.
     * @property zero where nothing gained and nothing lost sits, when the
     *   months cross it: a line that dips below that mark is a month that went
     *   into the red, and without the mark that is invisible. Null when every
     *   month is on one side of it.
     */
    data class Chart(
        val points: List<Point>,
        val segments: List<List<Int>>,
        val markerIndex: Int?,
        val zero: Double? = null,
    )

    /**
     * One day of the month's running balance on the chart.
     *
     * @property day the ISO date, as the server sent it.
     * @property x where the day falls in the whole month: 0 on the 1st, 1 on
     *   the last day — so a month still running stops part-way across.
     * @property y 0 at the bottom, 1 at the top.
     */
    data class Day(
        val day: String,
        val x: Double,
        val y: Double,
    )

    /** A day named along the bottom, at [x]. */
    data class Tick(val day: Int, val x: Double)

    /**
     * @property zero where nothing gained and nothing lost sits. Always on the
     *   chart: a running balance is read against it — above is ahead for the
     *   month, below is behind — so the scale is stretched to include it.
     * @property markerIndex the latest day, which is the figure the hero shows.
     * @property ticks the days named along the bottom: the 1st, each week
     *   after, and the month's last day.
     */
    data class DailyChart(
        val days: List<Day>,
        val zero: Double,
        val markerIndex: Int,
        val ticks: List<Tick>,
    )

    /**
     * The month drawn day by day, or null when there is nothing to draw — a
     * month with no rows, or a server too old to send the series.
     *
     * The x axis is the whole calendar month whatever the series holds, so a
     * month in progress reads as one: the line stops at today with the rest of
     * the month still ahead of it.
     */
    fun daily(daily: List<DayPoint>, month: String): DailyChart? {
        val first = runCatching { LocalDate.parse(month) }.getOrNull()?.let { LocalDate(it.year, it.month, 1) }
            ?: return null
        val length = first.plus(DatePeriod(months = 1)).minus(DatePeriod(days = 1)).day
        fun xOf(dayOfMonth: Int) = (dayOfMonth - 1).toDouble() / (length - 1)

        val known = daily.mapNotNull { point ->
            val date = runCatching { LocalDate.parse(point.day) }.getOrNull() ?: return@mapNotNull null
            val value = point.net.toDoubleOrNull() ?: return@mapNotNull null
            if (date.year != first.year || date.month != first.month) return@mapNotNull null
            Triple(point.day, date.day, value)
        }.sortedBy { it.second }
        if (known.isEmpty()) return null

        val low = minOf(known.minOf { it.third }, 0.0)
        val high = maxOf(known.maxOf { it.third }, 0.0)
        val days = known.map { (iso, dayOfMonth, value) -> Day(iso, xOf(dayOfMonth), scale(value, low, high)) }
        val ticks = (listOf(1, 8, 15, 22) + length).map { Tick(it, xOf(it)) }

        return DailyChart(
            days = days,
            zero = scale(0.0, low, high),
            markerIndex = days.lastIndex,
            ticks = ticks,
        )
    }

    /** Headroom above and below, so the line never runs along an edge. */
    private const val PADDING = 0.14

    /**
     * The chart for [trend], oldest month first, or null when no month in it
     * holds anything.
     *
     * Months before the first recorded one are left off rather than drawn as
     * a long empty run: somebody who started last month would otherwise see a
     * single dot pinned to the right of an empty chart. Gaps *after* that are
     * kept, because a month that went unrecorded in the middle is information.
     */
    fun chart(trend: List<MonthPoint>): Chart? {
        val first = trend.indexOfFirst { it.hasData }
        if (first < 0) return null
        val shown = trend.drop(first)

        val values = shown.map { it.net?.toDoubleOrNull() }
        val recorded = values.filterNotNull()
        if (recorded.isEmpty()) return null
        val low = recorded.min()
        val high = recorded.max()

        val points = shown.mapIndexed { index, point ->
            Point(
                month = point.month,
                // One month alone sits at the right, beside the figure it is.
                x = if (shown.size == 1) 1.0 else index.toDouble() / (shown.size - 1),
                y = values[index]?.let { scale(it, low, high) },
            )
        }

        return Chart(
            points = points,
            segments = runs(points),
            markerIndex = points.lastIndex.takeIf { points.last().y != null },
            zero = if (low < 0 && high > 0) scale(0.0, low, high) else null,
        )
    }

    /**
     * A signed value placed between [low] and [high]: a loss sits below a gain,
     * so a bad month reads as down rather than as a quiet one. Every month
     * equal is a flat line through the middle — no rise or fall to show.
     */
    private fun scale(value: Double, low: Double, high: Double): Double {
        if (high == low) return 0.5
        val fraction = (value - low) / (high - low)
        return PADDING + fraction * (1 - 2 * PADDING)
    }

    private fun runs(points: List<Point>): List<List<Int>> {
        val out = mutableListOf<List<Int>>()
        var current = mutableListOf<Int>()
        points.forEachIndexed { index, point ->
            if (point.y != null) {
                current += index
            } else if (current.isNotEmpty()) {
                out += current
                current = mutableListOf()
            }
        }
        if (current.isNotEmpty()) out += current
        return out
    }
}
