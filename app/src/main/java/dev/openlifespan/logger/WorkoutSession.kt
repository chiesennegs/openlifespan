package dev.openlifespan.logger

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class WorkoutSession(
    val id: String = UUID.randomUUID().toString(),
    val capturedAtMillis: Long = System.currentTimeMillis(),
    val durationSeconds: Int,
    val distance: Double,
    val calories: Int,
    val steps: Int,
    val maxSpeed: Double?,
    val units: Int?,
    val averageSpeed: Double
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("capturedAtMillis", capturedAtMillis)
            .put("durationSeconds", durationSeconds)
            .put("distance", distance)
            .put("calories", calories)
            .put("steps", steps)
            .put("maxSpeed", maxSpeed)
            .put("units", units)
            .put("averageSpeed", averageSpeed)
    }

    fun displayTitle(): String {
        val formatter = SimpleDateFormat("MMM d, h:mm a", Locale.US)
        return formatter.format(Date(capturedAtMillis))
    }

    companion object {
        fun fromJson(json: JSONObject): WorkoutSession {
            return WorkoutSession(
                id = json.getString("id"),
                capturedAtMillis = json.getLong("capturedAtMillis"),
                durationSeconds = json.getInt("durationSeconds"),
                distance = json.getDouble("distance"),
                calories = json.getInt("calories"),
                steps = json.getInt("steps"),
                maxSpeed = if (json.isNull("maxSpeed")) null else json.getDouble("maxSpeed"),
                units = if (json.isNull("units")) null else json.getInt("units"),
                averageSpeed = json.getDouble("averageSpeed")
            )
        }

        fun averageSpeed(distance: Double, durationSeconds: Int): Double {
            if (durationSeconds <= 0) return 0.0
            return distance / (durationSeconds / 3600.0)
        }
    }
}

data class SyncSnapshot(
    var units: Int? = null,
    var speed: Double? = null,
    var distance: Double? = null,
    var calories: Int? = null,
    var steps: Int? = null,
    var durationSeconds: Int? = null,
    var maxSpeed: Double? = null,
    var deviceState: Int? = null,
    var workoutStatus: Int? = null
) {
    fun toSessionOrNull(): WorkoutSession? {
        val duration = durationSeconds ?: return null
        val finalDistance = distance ?: return null
        val finalCalories = calories ?: return null
        val finalSteps = steps ?: return null

        return WorkoutSession(
            durationSeconds = duration,
            distance = finalDistance,
            calories = finalCalories,
            steps = finalSteps,
            maxSpeed = maxSpeed,
            units = units,
            averageSpeed = WorkoutSession.averageSpeed(finalDistance, duration)
        )
    }
}
