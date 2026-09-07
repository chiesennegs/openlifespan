package dev.openlifespan.logger

import org.json.JSONArray
import java.util.Locale

object SessionTransfer {
    fun toJson(sessions: List<WorkoutSession>): String = JSONArray().apply { sessions.forEach { put(it.toJson()) } }.toString(2)
    fun fromJson(text: String): List<WorkoutSession> {
        val array = JSONArray(text)
        return buildList { for (index in 0 until array.length()) add(WorkoutSession.fromJson(array.getJSONObject(index))) }
    }
    fun toCsv(sessions: List<WorkoutSession>): String {
        val header = "id,capturedAtMillis,durationSeconds,distance,calories,steps,maxSpeed,units,averageSpeed"
        return (listOf(header) + sessions.map { s -> listOf(s.id, s.capturedAtMillis, s.durationSeconds, "%.4f".format(Locale.US, s.distance), s.calories, s.steps, s.maxSpeed ?: "", s.units ?: "", "%.4f".format(Locale.US, s.averageSpeed)).joinToString(",") }).joinToString("\n") + "\n"
    }
}
