package dev.openlifespan.logger

import android.content.Context
import org.json.JSONArray
import java.io.IOException

class SessionStore(private val context: Context) {
    private val fileName = "openlifespan-sessions.json"

    fun load(): List<WorkoutSession> {
        return try {
            val text = context.openFileInput(fileName).bufferedReader().use { it.readText() }
            val array = JSONArray(text)
            buildList {
                for (index in 0 until array.length()) {
                    add(WorkoutSession.fromJson(array.getJSONObject(index)))
                }
            }.sortedByDescending { it.capturedAtMillis }
        } catch (_: IOException) {
            emptyList()
        }
    }

    fun add(session: WorkoutSession): List<WorkoutSession> {
        val existing = load()
        val sessions = if (existing.any { it.contentKey() == session.contentKey() }) {
            existing
        } else {
            listOf(session) + existing
        }
        save(sessions)
        return sessions
    }

    fun containsEquivalent(session: WorkoutSession): Boolean =
        load().any { it.contentKey() == session.contentKey() }

    fun replaceAll(sessions: List<WorkoutSession>) {
        save(sessions.sortedByDescending { it.capturedAtMillis })
    }

    private fun save(sessions: List<WorkoutSession>) {
        val array = JSONArray()
        sessions.sortedByDescending { it.capturedAtMillis }.forEach { session ->
            array.put(session.toJson())
        }
        context.openFileOutput(fileName, Context.MODE_PRIVATE).use { output ->
            output.write(array.toString(2).toByteArray(Charsets.UTF_8))
        }
    }
}
