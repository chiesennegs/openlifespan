package dev.openlifespan.logger

import java.util.Calendar
import java.util.Collections
import java.util.Locale
import java.util.Random
import kotlin.math.roundToInt

object MockDataGenerator {
    private const val THREE_YEARS_DAYS = 365 * 3

    fun generate(nowMillis: Long = System.currentTimeMillis()): List<WorkoutSession> {
        val random = Random(1206L)
        val end = Calendar.getInstance().apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val start = end.clone() as Calendar
        start.add(Calendar.DAY_OF_YEAR, -(THREE_YEARS_DAYS - 1))
        start.set(Calendar.HOUR_OF_DAY, 0); start.set(Calendar.MINUTE, 0); start.set(Calendar.SECOND, 0)

        val sessions = mutableListOf<WorkoutSession>()
        var weekStart = start.clone() as Calendar
        while (weekStart.timeInMillis <= end.timeInMillis) {
            // Most weeks have five active days; a few are lighter/heavier, and rare weeks are skipped.
            if (random.nextDouble() >= 0.012) {
                val activeDays = when (random.nextInt(20)) {
                    0 -> 2
                    1 -> 3
                    2 -> 7
                    else -> 4 + random.nextInt(3)
                }
                val weekdays = (0..6).toMutableList()
                Collections.shuffle(weekdays, random)
                weekdays.take(activeDays).forEach { dayOffset ->
                    val day = weekStart.clone() as Calendar
                    day.add(Calendar.DAY_OF_YEAR, dayOffset)
                    if (day.after(end)) return@forEach
                    val bouts = 4 + random.nextInt(3)
                    repeat(bouts) { boutIndex ->
                        val duration = (55 + random.nextInt(16) + random.nextGaussian() * 5).roundToInt().coerceIn(42, 78)
                        val speed = (2.5 + random.nextGaussian() * 0.22).coerceIn(1.8, 3.4)
                        val timestamp = day.clone() as Calendar
                        timestamp.set(Calendar.HOUR_OF_DAY, 8 + boutIndex * 2 + random.nextInt(2))
                        timestamp.set(Calendar.MINUTE, random.nextInt(60))
                        val distance = speed * duration / 60.0
                        sessions += WorkoutSession(
                            capturedAtMillis = timestamp.timeInMillis,
                            durationSeconds = duration * 60,
                            distance = distance,
                            calories = (distance * (48 + random.nextInt(12))).roundToInt(),
                            steps = (distance * (2050 + random.nextInt(250))).roundToInt(),
                            maxSpeed = speed + random.nextDouble() * 0.18,
                            units = 0,
                            averageSpeed = speed,
                            isMock = true
                        )
                    }
                }
            }
            weekStart.add(Calendar.DAY_OF_YEAR, 7)
        }
        return sessions.sortedByDescending { it.capturedAtMillis }
    }

    fun formatBytes(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
