package dev.openlifespan.logger

import java.util.Calendar
import java.util.Locale

enum class TrendPeriod(val label: String, val days: Int) { DAY("Day", 1), WEEK("Week", 7), MONTH("Month", 30), YEAR("Year", 365) }
data class TrendPoint(val label: String, val distance: Double, val calories: Int, val sessions: Int, val activeSeconds: Int = 0, val activeDays: Int = 0, val steps: Int = 0)

object TrendCalculator {
    fun points(sessions: List<WorkoutSession>, period: TrendPeriod, now: Long = System.currentTimeMillis()): List<TrendPoint> {
        val count = when (period) { TrendPeriod.DAY -> 7; TrendPeriod.WEEK -> 8; TrendPeriod.MONTH -> 6; TrendPeriod.YEAR -> 5 }
        return (count - 1 downTo 0).map { offset ->
            val (start, end) = calendarBucket(period, now, offset)
            val bucket = sessions.filter { it.capturedAtMillis in start.timeInMillis..end.timeInMillis }
            val label = when (period) {
                TrendPeriod.DAY -> String.format(Locale.US, "%02d/%02d", end.get(Calendar.MONTH) + 1, end.get(Calendar.DAY_OF_MONTH))
                TrendPeriod.WEEK -> "W${end.get(Calendar.WEEK_OF_YEAR)}"
                TrendPeriod.MONTH -> String.format(Locale.US, "%02d/%02d", end.get(Calendar.MONTH) + 1, end.get(Calendar.YEAR) % 100)
                TrendPeriod.YEAR -> end.get(Calendar.YEAR).toString()
            }
            TrendPoint(label, bucket.sumOf { it.distance }, bucket.sumOf { it.calories }, bucket.size, bucket.sumOf { it.durationSeconds }, bucket.map { java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date(it.capturedAtMillis)) }.distinct().size, bucket.sumOf { it.steps })
        }
    }

    /** Calendar-aligned buckets prevent all bars from changing when navigation moves within one period. */
    private fun calendarBucket(period: TrendPeriod, now: Long, offset: Int): Pair<Calendar, Calendar> {
        val start = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        when (period) {
            TrendPeriod.DAY -> start.add(Calendar.DAY_OF_YEAR, -offset)
            TrendPeriod.WEEK -> {
                val daysFromWeekStart = Math.floorMod(start.get(Calendar.DAY_OF_WEEK) - start.firstDayOfWeek, 7)
                start.add(Calendar.DAY_OF_YEAR, -daysFromWeekStart)
                start.add(Calendar.WEEK_OF_YEAR, -offset)
            }
            TrendPeriod.MONTH -> { start.set(Calendar.DAY_OF_MONTH, 1); start.add(Calendar.MONTH, -offset) }
            TrendPeriod.YEAR -> { start.set(Calendar.DAY_OF_YEAR, 1); start.add(Calendar.YEAR, -offset) }
        }
        val end = start.clone() as Calendar
        when (period) {
            TrendPeriod.DAY -> Unit
            TrendPeriod.WEEK -> end.add(Calendar.DAY_OF_YEAR, 6)
            TrendPeriod.MONTH -> end.set(Calendar.DAY_OF_MONTH, end.getActualMaximum(Calendar.DAY_OF_MONTH))
            TrendPeriod.YEAR -> end.set(Calendar.DAY_OF_YEAR, end.getActualMaximum(Calendar.DAY_OF_YEAR))
        }
        end.set(Calendar.HOUR_OF_DAY, 23); end.set(Calendar.MINUTE, 59); end.set(Calendar.SECOND, 59); end.set(Calendar.MILLISECOND, 999)
        return start to end
    }
}
