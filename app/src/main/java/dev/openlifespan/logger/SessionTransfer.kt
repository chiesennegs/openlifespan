package dev.openlifespan.logger

import android.util.JsonReader
import android.util.JsonToken
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

object SessionTransfer {
    const val MAX_IMPORT_BYTES = 64L * 1024L * 1024L
    const val MAX_IMPORT_SESSIONS = 100_000
    private val timestamp = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z$")
    private val iso = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
    data class ImportResult(val sessions: List<WorkoutSession>, val added: Int, val replaced: Int, val skipped: Int)
    /** Exported session objects are one-per-line; record 1 begins on line 5. */
    class ValidationException(val record: Int, detail: String) : IOException("record $record, line ${if (record > 0) record + 4 else 1} — $detail")

    fun toJson(sessions: List<WorkoutSession>): String = buildString {
        append("{\n  \"format\": \"openlifespan-backup\",\n  \"version\": 1,\n  \"sessions\": [\n")
        sessions.forEachIndexed { i, s ->
            if (i > 0) append(",\n")
            append("    {\"id\":\"").append(s.id).append("\",\"startedAt\":\"").append(iso.format(Instant.ofEpochMilli(s.startedAtMillis))).append("\",\"endedAt\":\"").append(iso.format(Instant.ofEpochMilli(s.endedAtMillis))).append("\",\"distance\":").append("%.4f".format(Locale.US, s.distance)).append(",\"calories\":").append(s.calories).append(",\"steps\":").append(s.steps).append(",\"maxSpeed\":").append(s.maxSpeed ?: "null").append(",\"units\":").append(s.units ?: "null").append('}')
        }
        append("\n  ]\n}\n")
    }

    fun toCsv(sessions: List<WorkoutSession>): String {
        val header = "id,startedAt,endedAt,distance,calories,steps,maxSpeed,units,averageSpeed"
        return (listOf(header) + sessions.map { s -> listOf(s.id, iso.format(Instant.ofEpochMilli(s.startedAtMillis)), iso.format(Instant.ofEpochMilli(s.endedAtMillis)), "%.4f".format(Locale.US, s.distance), s.calories, s.steps, s.maxSpeed ?: "", s.units ?: "", "%.4f".format(Locale.US, s.averageSpeed)).joinToString(",") }).joinToString("\n") + "\n"
    }

    /** Reads the exact CSV layout written by [toCsv], then applies the same interval validation as backups. */
    fun readCsv(input: InputStream): List<WorkoutSession> {
        val expectedHeader = "id,startedAt,endedAt,distance,calories,steps,maxSpeed,units,averageSpeed"
        return LimitedInputStream(input, MAX_IMPORT_BYTES).bufferedReader(Charsets.UTF_8).use { reader ->
            if (reader.readLine() != expectedHeader) throw IOException("CSV header must match an OpenLifeSpan activity export")
            buildList {
                var row = 1
                while (true) {
                    val line = reader.readLine() ?: break
                    row++
                    if (line.isEmpty()) continue
                    if (size >= MAX_IMPORT_SESSIONS) throw IOException("CSV has more than $MAX_IMPORT_SESSIONS activity rows")
                    val fields = line.split(',', ignoreCase = false, limit = 9)
                    if (fields.size != 9) throw IOException("CSV row $row must contain 9 columns")
                    try {
                        val max = fields[6].takeIf { it.isNotEmpty() }?.toDoubleOrNull() ?: if (fields[6].isEmpty()) null else throw IllegalArgumentException("maxSpeed is invalid")
                        val units = fields[7].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: if (fields[7].isEmpty()) null else throw IllegalArgumentException("units is invalid")
                        add(validatedSession(size + 1, fields[0], fields[1], fields[2], fields[3].toDouble(), fields[4].toInt(), fields[5].toInt(), max, units))
                    } catch (e: ValidationException) { throw e } catch (e: Exception) { throw IOException("CSV row $row — ${e.message ?: "invalid activity"}") }
                }
            }
        }
    }

    fun read(input: InputStream): List<WorkoutSession> {
        JsonReader(InputStreamReader(LimitedInputStream(input, MAX_IMPORT_BYTES), Charsets.UTF_8)).use { reader ->
            reader.isLenient = false; var format: String? = null; var version: Int? = null; var sessions: List<WorkoutSession>? = null
            reader.beginObject(); while (reader.hasNext()) when (reader.nextName()) {
                "format" -> format = reader.nextString(); "version" -> version = reader.nextInt(); "sessions" -> sessions = readSessions(reader)
                else -> throw ValidationException(0, "unknown top-level field")
            }; reader.endObject()
            if (format != "openlifespan-backup" || version != 1 || sessions == null) throw ValidationException(0, "expected OpenLifeSpan backup version 1")
            return sessions!!
        }
    }

    fun mergeImport(local: List<WorkoutSession>, imported: List<WorkoutSession>): ImportResult {
        val incoming = imported.sortedBy { it.startedAtMillis }
        incoming.zipWithNext().forEachIndexed { i, pair -> if (overlaps(pair.first, pair.second)) throw ValidationException(i + 2, "import contains overlapping activity intervals") }
        val result = local.toMutableList(); var added = 0; var replaced = 0; var skipped = 0
        incoming.forEachIndexed { i, session ->
            val sameId = result.firstOrNull { it.id == session.id }
            if (sameId == session) { skipped++; return@forEachIndexed }
            if (sameId != null) throw ValidationException(i + 1, "session ID conflicts with different local activity")
            val conflicts = result.filter { overlaps(it, session) }; result.removeAll(conflicts); replaced += conflicts.size; result += session; added++
        }
        return ImportResult(result.sortedByDescending { it.endedAtMillis }, added, replaced, skipped)
    }
    fun overlaps(a: WorkoutSession, b: WorkoutSession) = a.startedAtMillis < b.endedAtMillis && b.startedAtMillis < a.endedAtMillis

    private fun readSessions(reader: JsonReader): List<WorkoutSession> = buildList {
        reader.beginArray(); var record = 0; while (reader.hasNext()) { record++; if (record > MAX_IMPORT_SESSIONS) throw ValidationException(record, "more than $MAX_IMPORT_SESSIONS sessions")
            try { add(readSession(reader, record)) } catch (e: ValidationException) { throw e } catch (e: Exception) { throw ValidationException(record, e.message ?: "invalid session") }
        }; reader.endArray()
    }
    private fun readSession(r: JsonReader, record: Int): WorkoutSession {
        var id: String? = null; var start: String? = null; var end: String? = null; var distance: Double? = null; var calories: Int? = null; var steps: Int? = null; var max: Double? = null; var units: Int? = null
        val seen = mutableSetOf<String>(); r.beginObject(); while (r.hasNext()) { val field = r.nextName(); if (!seen.add(field)) throw ValidationException(record, "duplicate field '$field'")
            when (field) { "id" -> id = r.nextString().takeIf { it.length <= 64 }; "startedAt" -> start = r.nextString(); "endedAt" -> end = r.nextString(); "distance" -> distance = r.nextDouble(); "calories" -> calories = r.nextInt(); "steps" -> steps = r.nextInt(); "maxSpeed" -> max = nullableDouble(r); "units" -> units = nullableInt(r); else -> throw ValidationException(record, "unknown field '$field'") }
        }; r.endObject()
        return validatedSession(record, id, start, end, distance ?: throw ValidationException(record, "distance is required"), calories ?: throw ValidationException(record, "calories is required"), steps ?: throw ValidationException(record, "steps is required"), max, units)
    }
    private fun validatedSession(record: Int, id: String?, start: String?, end: String?, d: Double, c: Int, st: Int, max: Double?, units: Int?): WorkoutSession {
        val validId = try { UUID.fromString(id ?: "").toString() } catch (_: Exception) { throw ValidationException(record, "id must be a UUID") }
        val started = parseTime(start, record, "startedAt"); val ended = parseTime(end, record, "endedAt")
        if (!d.isFinite() || d !in 0.0..1_000.0) throw ValidationException(record, "distance is outside allowed range")
        if (c !in 0..1_000_000 || st !in 0..10_000_000) throw ValidationException(record, "calories or steps is outside allowed range")
        if (ended <= started || ended - started > 86_400_000L) throw ValidationException(record, "invalid activity interval")
        if (max != null && (!max!!.isFinite() || max!! !in 0.0..12.0)) throw ValidationException(record, "maxSpeed is outside allowed range")
        if (units != null && units !in 0..1) throw ValidationException(record, "units is invalid")
        val duration = ((ended - started) / 1_000).toInt()
        return WorkoutSession(validId, ended, duration, d, c, st, max, units, WorkoutSession.averageSpeed(d, duration), false, started, ended)
    }
    private fun nullableDouble(r: JsonReader) = if (r.peek() == JsonToken.NULL) { r.nextNull(); null } else r.nextDouble()
    private fun nullableInt(r: JsonReader) = if (r.peek() == JsonToken.NULL) { r.nextNull(); null } else r.nextInt()
    private fun parseTime(value: String?, record: Int, field: String): Long { if (value == null || !timestamp.matches(value)) throw ValidationException(record, "$field must be UTC ISO-8601 with milliseconds"); return try { Instant.parse(value).toEpochMilli() } catch (_: Exception) { throw ValidationException(record, "$field is invalid") } }
    private class LimitedInputStream(input: InputStream, private val limit: Long) : FilterInputStream(input) { var count = 0L; private fun add(n: Int) { if (n > 0) { count += n; if (count > limit) throw IOException("import exceeds ${limit / 1024 / 1024} MiB limit") } }; override fun read() = super.read().also { if (it >= 0) add(1) }; override fun read(b: ByteArray, off: Int, len: Int) = super.read(b, off, len).also { add(it) } }
}
