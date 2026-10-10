package com.example.jack

import java.text.SimpleDateFormat
import java.util.Locale

/** One visit, flattened for export (CSV file) and sync (Google Sheets). */
data class VisitRow(
    val visitId: Long,
    val attendeeId: String,
    val name: String,
    val tag: String,
    val checkIn: String,        // "yyyy-MM-dd HH:mm:ss"
    val checkOut: String?       // null = still in the building; "unknown" = recorded before check-out existed
) {
    val status: String
        get() = when (checkOut) {
            null -> "In building"
            AttendanceCsv.CHECK_OUT_UNKNOWN -> "Out (time not recorded)"
            else -> "Checked out"
        }

    val checkOutDisplay: String
        get() = when (checkOut) {
            null, AttendanceCsv.CHECK_OUT_UNKNOWN -> ""
            else -> checkOut
        }

    /** Whole minutes between check-in and check-out, or null while still in / unknown. */
    val durationMinutes: Long?
        get() = AttendanceCsv.minutesBetween(checkIn, checkOut)
}

/** Pure Kotlin (no Android types) so it can be unit-tested on the JVM. */
object AttendanceCsv {
    const val CHECK_OUT_UNKNOWN = "unknown"

    val HEADER = listOf("Visit ID", "Attendee ID", "Name", "Tag", "Check In", "Check Out", "Duration (min)", "Status")

    /** File name for a day's export, e.g. attendance_2026-10-10.csv */
    fun fileNameFor(date: String) = "attendance_$date.csv"

    /** Visits whose check-in happened on [date] ("yyyy-MM-dd"). */
    fun rowsForDate(rows: List<VisitRow>, date: String) = rows.filter { it.checkIn.startsWith(date) }

    fun build(rows: List<VisitRow>): String {
        val sb = StringBuilder()
        sb.append(HEADER.joinToString(",") { escape(it) }).append("\r\n")
        for (r in rows.sortedBy { it.checkIn }) {
            val fields = listOf(
                r.visitId.toString(),
                r.attendeeId,
                r.name,
                r.tag,
                r.checkIn,
                r.checkOutDisplay,
                r.durationMinutes?.toString() ?: "",
                r.status
            )
            sb.append(fields.joinToString(",") { escape(it) }).append("\r\n")
        }
        return sb.toString()
    }

    /** RFC 4180 quoting: wrap in quotes if the field contains a comma, quote or line break. */
    fun escape(field: String): String =
        if (field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + field.replace("\"", "\"\"") + "\""
        } else {
            field
        }

    fun minutesBetween(checkIn: String, checkOut: String?): Long? {
        if (checkOut == null || checkOut == CHECK_OUT_UNKNOWN) return null
        return try {
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            val start = fmt.parse(checkIn) ?: return null
            val end = fmt.parse(checkOut) ?: return null
            ((end.time - start.time) / 60_000L).coerceAtLeast(0)
        } catch (_: Exception) {
            null
        }
    }
}
