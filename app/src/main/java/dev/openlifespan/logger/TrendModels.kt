package dev.openlifespan.logger

import java.util.Calendar
import java.util.Locale

enum class TrendPeriod(val label: String, val days: Int) { DAY("Day", 1), WEEK("Week", 7), MONTH("Month", 30), YEAR("Year", 365) }
data class TrendPoint(val label: String, val distance: Double, val calories: Int, val sessions: Int, val activeSeconds: Int = 0, val activeDays: Int = 0, val steps: Int = 0)

object TrendCalculator {
    fun points(sessions: List<WorkoutSession>, period: TrendPeriod, now: Long = System.currentTimeMillis()): List<TrendPoint> {
        val count = when (period) { TrendPeriod.DAY -> 7; TrendPeriod.WEEK -> 8; TrendPeriod.MONTH -> 6; TrendPeriod.YEAR -> 5 }
        return (count - 1 downTo 0).map { offset ->
            val end = Calendar.getInstance().apply { timeInMillis = now; add(Calendar.DAY_OF_YEAR, -offset * period.days) }
            val start = Calendar.getInstance().apply { timeInMillis = end.timeInMillis; add(Calendar.DAY_OF_YEAR, -period.days + 1) }
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
}
