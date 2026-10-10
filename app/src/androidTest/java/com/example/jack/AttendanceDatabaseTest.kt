package com.example.jack

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Runs on the tablet/emulator (Android Studio: right-click → Run 'AttendanceDatabaseTest').
 * Every test uses its own throwaway database file — the real attendance data is never touched.
 */
@RunWith(AndroidJUnit4::class)
class AttendanceDatabaseTest {

    private lateinit var context: Context
    private lateinit var fileName: String
    private lateinit var db: AttendanceDatabase

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        fileName = "jack_test_${UUID.randomUUID()}.db"
        db = AttendanceDatabase.openForTest(context, fileName)
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(fileName)
    }

    @Test
    fun checkInThenCheckOut() {
        db.insertRecord("007", "/selfies/in.jpg")
        val open = db.getOpenVisit("007")
        assertNotNull(open)
        assertEquals(1, db.getCheckedIn().size)

        assertTrue(db.checkOut("007", "/selfies/out.jpg"))
        assertNull(db.getOpenVisit("007"))
        assertEquals(0, db.getCheckedIn().size)

        val visit = db.getAllRecords().single()
        assertNotNull(visit.checkOutTime)
        assertEquals("/selfies/in.jpg", visit.selfiePath)
        assertEquals("/selfies/out.jpg", visit.checkOutSelfiePath)
    }

    @Test
    fun checkOutWithoutCheckInDoesNothing() {
        assertFalse(db.checkOut("nobody", null))
        assertEquals(0, db.getAllRecords().size)
    }

    @Test
    fun visitLookupIgnoresCase() {
        db.insertRecord("AB12", null)
        assertNotNull(db.getOpenVisit("ab12"))
    }

    @Test
    fun duplicateAttendeeIdsAreRejected() {
        assertTrue(db.addUser("Ada Obi", "007", "Worker") > 0)
        assertEquals(-1L, db.addUser("Someone Else", " 007 ", "Guest"))

        assertTrue(db.addUser("Tunde", "AB12", "Subscriber") > 0)
        assertEquals(-1L, db.addUser("Copy", "ab12", "Guest"))

        assertEquals(2, db.getAllUsers().size)
        assertEquals(1, db.countUsersWithAttendeeId("ab12"))
        assertEquals("Tunde", db.getUserByAttendeeId("ab12")?.name)
    }

    @Test
    fun checkOutDuringUploadStaysUnsynced() {
        db.insertRecord("007", null)
        val sent = db.getUnsyncedRecords()          // upload starts with the visit still open…
        db.checkOut("007", null)                    // …and the person checks out mid-upload
        db.markSynced(sent)

        assertEquals("the check-out must still be synced", 1, db.getUnsyncedCount())
        db.markSynced(db.getUnsyncedRecords())
        assertEquals(0, db.getUnsyncedCount())
    }

    @Test
    fun recentRecordsAreNewestFirstAndLimited() {
        repeat(5) { db.insertRecord("id$it", null) }
        val recent = db.getRecentRecords(3)
        assertEquals(listOf("id4", "id3", "id2"), recent.map { it.attendeeId })
        assertEquals(5, db.getRecordCount())
    }

    @Test
    fun upgradeFromVersion2KeepsDataAndClosesOldVisits() {
        // Build a database exactly as the original app (schema v2) left it.
        db.close()
        context.deleteDatabase(fileName)
        val path = context.getDatabasePath(fileName).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            old.execSQL("CREATE TABLE attendance_records (id INTEGER PRIMARY KEY AUTOINCREMENT, attendee_id TEXT NOT NULL, timestamp TEXT NOT NULL, selfie_path TEXT, synced INTEGER DEFAULT 0)")
            old.execSQL("CREATE TABLE users (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, attendee_id TEXT NOT NULL, tag TEXT NOT NULL, created_at TEXT NOT NULL)")
            old.execSQL("INSERT INTO users (name, attendee_id, tag, created_at) VALUES ('Ada Obi', '007', 'Worker', '2026-09-01 10:00:00')")
            old.execSQL("INSERT INTO attendance_records (attendee_id, timestamp, selfie_path, synced) VALUES ('007', '2026-09-02 09:00:00', '/old.jpg', 1)")
            old.version = 2
        }

        // Opening it with today's app runs the upgrades (v3 check-out, v4 check-out selfie, v5 indexes).
        db = AttendanceDatabase.openForTest(context, fileName)

        assertEquals("Ada Obi", db.getUserByAttendeeId("007")?.name)
        val visit = db.getAllRecords().single()
        assertEquals("/old.jpg", visit.selfiePath)
        assertEquals(AttendanceDatabase.CHECK_OUT_UNKNOWN, visit.checkOutTime)
        assertNull(visit.checkOutSelfiePath)
        assertEquals("old visits must not show as 'in the building'", 0, db.getCheckedIn().size)

        // And the upgraded database works normally.
        db.insertRecord("007", null)
        assertEquals(1, db.getCheckedIn().size)
    }
}
