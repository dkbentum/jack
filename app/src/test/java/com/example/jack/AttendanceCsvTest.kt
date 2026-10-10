package com.example.jack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AttendanceCsvTest {

    private val header = "Visit ID,Attendee ID,Name,Tag,Check In,Check Out,Duration (min),Status\r\n"

    @Test
    fun fileNameUsesDate() {
        assertEquals("attendance_2026-10-10.csv", AttendanceCsv.fileNameFor("2026-10-10"))
    }

    @Test
    fun checkedOutVisitHasDuration() {
        val csv = AttendanceCsv.build(listOf(
            VisitRow(1, "007", "Ada Obi", "Worker", "2026-10-10 09:00:00", "2026-10-10 11:30:59")
        ))
        assertEquals(header + "1,007,Ada Obi,Worker,2026-10-10 09:00:00,2026-10-10 11:30:59,150,Checked out\r\n", csv)
    }

    @Test
    fun stillInBuildingHasNoCheckOutOrDuration() {
        val csv = AttendanceCsv.build(listOf(
            VisitRow(2, "42", "Tunde", "Subscriber", "2026-10-10 10:00:00", null)
        ))
        assertEquals(header + "2,42,Tunde,Subscriber,2026-10-10 10:00:00,,,In building\r\n", csv)
    }

    @Test
    fun legacyVisitShowsUnknownCheckOut() {
        val row = VisitRow(3, "9", "Old", "Guest", "2026-10-01 08:00:00", AttendanceCsv.CHECK_OUT_UNKNOWN)
        assertNull(row.durationMinutes)
        assertEquals("", row.checkOutDisplay)
        assertEquals("Out (time not recorded)", row.status)
    }

    @Test
    fun fieldsWithCommasAndQuotesAreEscaped() {
        assertEquals("\"Obi, Ada\"", AttendanceCsv.escape("Obi, Ada"))
        assertEquals("\"The \"\"Boss\"\"\"", AttendanceCsv.escape("The \"Boss\""))
        assertEquals("plain", AttendanceCsv.escape("plain"))
    }

    @Test
    fun rowsAreSortedByCheckInAndFilteredByDate() {
        val rows = listOf(
            VisitRow(5, "B", "B", "Worker", "2026-10-10 14:00:00", null),
            VisitRow(4, "A", "A", "Worker", "2026-10-10 08:00:00", null),
            VisitRow(3, "Z", "Z", "Worker", "2026-10-09 23:00:00", null)
        )
        val today = AttendanceCsv.rowsForDate(rows, "2026-10-10")
        assertEquals(listOf(5L, 4L), today.map { it.visitId })
        val lines = AttendanceCsv.build(today).trimEnd().split("\r\n")
        assertEquals(3, lines.size)
        assertEquals("4", lines[1].substringBefore(","))
        assertEquals("5", lines[2].substringBefore(","))
    }

    @Test
    fun durationNeverNegative() {
        assertEquals(0L, AttendanceCsv.minutesBetween("2026-10-10 10:00:00", "2026-10-10 09:00:00"))
    }
}
