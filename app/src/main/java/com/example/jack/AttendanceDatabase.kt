package com.example.jack

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.compose.runtime.mutableIntStateOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.*

/**
 * One visit: a check-in, and (once the person signs out) a check-out.
 * [checkOutTime] is null while the person is still in the building.
 */
data class AttendanceRecord(
    val id: Long,
    val attendeeId: String,
    val timestamp: String,          // check-in time, "yyyy-MM-dd HH:mm:ss"
    val selfiePath: String?,
    val synced: Boolean,
    val checkOutTime: String? = null,
    val checkOutSelfiePath: String? = null
) {
    val isCheckedIn: Boolean get() = checkOutTime == null
}

data class UserRecord(
    val id: Long,
    val name: String,
    val attendeeId: String,
    val tag: String,
    val createdAt: String
)

/**
 * Jack's local database. Always use [AttendanceDatabase.get] — one shared connection for the whole app,
 * so the screen and the background sync never fight over the file ("database is locked").
 */
class AttendanceDatabase private constructor(context: Context, fileName: String?) :
    SQLiteOpenHelper(context.applicationContext, fileName, null, DATABASE_VERSION) {

    init {
        // Write-ahead logging: reading (screens) and writing (check-ins, sync) don't block each other.
        setWriteAheadLoggingEnabled(true)
    }

    companion object {
        private const val DATABASE_NAME = "jack_attendance.db"
        private const val DATABASE_VERSION = 5
        private const val TABLE_ATTENDANCE = "attendance_records"
        private const val TABLE_USERS = "users"

        private const val COL_ID = "id"
        private const val COL_ATTENDEE_ID = "attendee_id"
        private const val COL_TIMESTAMP = "timestamp"
        private const val COL_SELFIE_PATH = "selfie_path"
        private const val COL_SYNCED = "synced"
        private const val COL_CHECK_OUT = "check_out_time"   // added in v3; NULL = still in the building
        private const val COL_CHECK_OUT_SELFIE = "check_out_selfie_path"  // added in v4

        private const val COL_USER_ID = "id"
        private const val COL_USER_NAME = "name"
        private const val COL_USER_ATTENDEE_ID = "attendee_id"
        private const val COL_USER_TAG = "tag"
        private const val COL_USER_CREATED_AT = "created_at"

        /** Visits recorded before check-out existed are closed with this value. */
        const val CHECK_OUT_UNKNOWN = "unknown"

        /**
         * Bumped on every attendance change. Composables read it so lists like
         * "Who is in the Building" refresh immediately after a check-in or check-out.
         */
        val revision = mutableIntStateOf(0)

        /** Same signal as [revision], as a Flow for background work (auto-export, Google Sheets sync). */
        private val _changes = MutableStateFlow(0)
        val changes: StateFlow<Int> = _changes

        private fun notifyChanged() {
            revision.intValue++
            _changes.value++
        }

        @Volatile
        private var instance: AttendanceDatabase? = null

        /** The single shared database instance. */
        fun get(context: Context): AttendanceDatabase =
            instance ?: synchronized(this) {
                instance ?: AttendanceDatabase(context.applicationContext, DATABASE_NAME).also { instance = it }
            }

        /**
         * A separate database for automated tests only — never the real attendance file.
         * [fileName] = null gives a throwaway in-memory database.
         */
        @androidx.annotation.VisibleForTesting
        fun openForTest(context: Context, fileName: String? = null): AttendanceDatabase {
            require(fileName != DATABASE_NAME) { "Tests must not open the real attendance database" }
            return AttendanceDatabase(context.applicationContext, fileName)
        }

        /** Indexes so lookups ("is this ID checked in?", "who is in the building?") don't scan every row. */
        private fun createIndexes(db: SQLiteDatabase) {
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_users_attendee ON $TABLE_USERS($COL_USER_ATTENDEE_ID COLLATE NOCASE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_att_attendee_open ON $TABLE_ATTENDANCE($COL_ATTENDEE_ID COLLATE NOCASE, $COL_CHECK_OUT)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_att_open ON $TABLE_ATTENDANCE($COL_CHECK_OUT)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_att_synced ON $TABLE_ATTENDANCE($COL_SYNCED)")
        }

        private fun now(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_ATTENDANCE (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_ATTENDEE_ID TEXT NOT NULL,
                $COL_TIMESTAMP TEXT NOT NULL,
                $COL_SELFIE_PATH TEXT,
                $COL_SYNCED INTEGER DEFAULT 0,
                $COL_CHECK_OUT TEXT,
                $COL_CHECK_OUT_SELFIE TEXT
            )
            """
        )
        db.execSQL(
            """
            CREATE TABLE $TABLE_USERS (
                $COL_USER_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_USER_NAME TEXT NOT NULL,
                $COL_USER_ATTENDEE_ID TEXT NOT NULL,
                $COL_USER_TAG TEXT NOT NULL,
                $COL_USER_CREATED_AT TEXT NOT NULL
            )
            """
        )
        createIndexes(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_USERS (
                    $COL_USER_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                    $COL_USER_NAME TEXT NOT NULL,
                    $COL_USER_ATTENDEE_ID TEXT NOT NULL,
                    $COL_USER_TAG TEXT NOT NULL,
                    $COL_USER_CREATED_AT TEXT NOT NULL
                )
                """
            )
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE $TABLE_ATTENDANCE ADD COLUMN $COL_CHECK_OUT TEXT")
            // Old records had no check-out; close them so they don't all show as "in the building".
            db.execSQL("UPDATE $TABLE_ATTENDANCE SET $COL_CHECK_OUT = '$CHECK_OUT_UNKNOWN'")
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE $TABLE_ATTENDANCE ADD COLUMN $COL_CHECK_OUT_SELFIE TEXT")
        }
        if (oldVersion < 5) {
            createIndexes(db)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Attendance (visits)
    // ---------------------------------------------------------------------------------------------

    /** Records a check-in. Returns the new row id. */
    fun insertRecord(attendeeId: String, selfiePath: String?): Long {
        val values = ContentValues().apply {
            put(COL_ATTENDEE_ID, attendeeId)
            put(COL_TIMESTAMP, now())
            put(COL_SELFIE_PATH, selfiePath)
            put(COL_SYNCED, 0)
        }
        val id = writableDatabase.insert(TABLE_ATTENDANCE, null, values)
        notifyChanged()
        return id
    }

    /** Closes the person's open visit, storing the check-out selfie. Returns true if they were checked in. */
    fun checkOut(attendeeId: String, selfiePath: String?): Boolean {
        val open = getOpenVisit(attendeeId) ?: return false
        val values = ContentValues().apply {
            put(COL_CHECK_OUT, now())
            put(COL_CHECK_OUT_SELFIE, selfiePath)
            put(COL_SYNCED, 0)  // the visit changed, so it needs syncing again
        }
        val rows = writableDatabase.update(TABLE_ATTENDANCE, values, "$COL_ID = ?", arrayOf(open.id.toString()))
        notifyChanged()
        return rows > 0
    }

    /** The person's current visit if they are in the building, else null. */
    fun getOpenVisit(attendeeId: String): AttendanceRecord? =
        readableDatabase.rawQuery(
            "SELECT * FROM $TABLE_ATTENDANCE WHERE $COL_ATTENDEE_ID = ? COLLATE NOCASE AND $COL_CHECK_OUT IS NULL ORDER BY $COL_ID DESC LIMIT 1",
            arrayOf(attendeeId.trim())
        ).use { if (it.moveToFirst()) it.toRecord() else null }

    /** Everyone currently in the building, most recent check-in first. */
    fun getCheckedIn(): List<AttendanceRecord> =
        queryRecords("SELECT * FROM $TABLE_ATTENDANCE WHERE $COL_CHECK_OUT IS NULL ORDER BY $COL_ID DESC")

    /** The most recent visits (newest first) — for on-screen lists, which shouldn't load years of history. */
    fun getRecentRecords(limit: Int): List<AttendanceRecord> =
        queryRecords("SELECT * FROM $TABLE_ATTENDANCE ORDER BY $COL_ID DESC LIMIT $limit")

    /** Number of check-ins on a given day ("yyyy-MM-dd"). */
    fun getVisitCountForDate(date: String): Int {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $TABLE_ATTENDANCE WHERE $COL_TIMESTAMP LIKE ?", arrayOf("$date%")
        ).use { if (it.moveToFirst()) return it.getInt(0) }
        return 0
    }

    fun getRecordCount(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE_ATTENDANCE", null).use {
            if (it.moveToFirst()) return it.getInt(0)
        }
        return 0
    }

    /** Every visit ever recorded, most recent first. */
    fun getAllRecords(): List<AttendanceRecord> =
        queryRecords("SELECT * FROM $TABLE_ATTENDANCE ORDER BY $COL_ID DESC")

    fun clearAttendance() {
        writableDatabase.execSQL("DELETE FROM $TABLE_ATTENDANCE")
        notifyChanged()
    }

    /** Visits not yet sent to Google Sheets (new check-ins, and visits whose check-out changed since). */
    fun getUnsyncedRecords(limit: Int = 200): List<AttendanceRecord> =
        queryRecords("SELECT * FROM $TABLE_ATTENDANCE WHERE $COL_SYNCED = 0 ORDER BY $COL_ID ASC LIMIT $limit")

    /**
     * Marks exactly the visits that were sent as synced — but only if they haven't changed since
     * (e.g. someone checked out while the upload was in flight), so that change still gets synced.
     */
    fun markSynced(sent: List<AttendanceRecord>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues().apply { put(COL_SYNCED, 1) }
            for (r in sent) {
                if (r.checkOutTime == null) {
                    db.update(TABLE_ATTENDANCE, values, "$COL_ID = ? AND $COL_CHECK_OUT IS NULL", arrayOf(r.id.toString()))
                } else {
                    db.update(TABLE_ATTENDANCE, values, "$COL_ID = ? AND $COL_CHECK_OUT = ?", arrayOf(r.id.toString(), r.checkOutTime))
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getUnsyncedCount(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE_ATTENDANCE WHERE $COL_SYNCED = 0", null).use {
            if (it.moveToFirst()) return it.getInt(0)
        }
        return 0
    }

    private fun queryRecords(sql: String): List<AttendanceRecord> {
        val list = mutableListOf<AttendanceRecord>()
        readableDatabase.rawQuery(sql, null).use { while (it.moveToNext()) list.add(it.toRecord()) }
        return list
    }

    private fun Cursor.toRecord(): AttendanceRecord {
        val checkOutIdx = getColumnIndex(COL_CHECK_OUT)
        val checkOutSelfieIdx = getColumnIndex(COL_CHECK_OUT_SELFIE)
        return AttendanceRecord(
            id = getLong(getColumnIndexOrThrow(COL_ID)),
            attendeeId = getString(getColumnIndexOrThrow(COL_ATTENDEE_ID)),
            timestamp = getString(getColumnIndexOrThrow(COL_TIMESTAMP)),
            selfiePath = getString(getColumnIndexOrThrow(COL_SELFIE_PATH)),
            synced = getInt(getColumnIndexOrThrow(COL_SYNCED)) == 1,
            checkOutTime = if (checkOutIdx >= 0 && !isNull(checkOutIdx)) getString(checkOutIdx) else null,
            checkOutSelfiePath = if (checkOutSelfieIdx >= 0 && !isNull(checkOutSelfieIdx)) getString(checkOutSelfieIdx) else null
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Users (unchanged)
    // ---------------------------------------------------------------------------------------------

    /**
     * Registers a user. Returns the new row id, or -1 if the Attendee ID is already used
     * (IDs are unique, ignoring case and surrounding spaces).
     */
    fun addUser(name: String, attendeeId: String, tag: String): Long {
        if (countUsersWithAttendeeId(attendeeId) > 0) return -1L
        val values = ContentValues().apply {
            put(COL_USER_NAME, name)
            put(COL_USER_ATTENDEE_ID, attendeeId)
            put(COL_USER_TAG, tag)
            put(COL_USER_CREATED_AT, now())
        }
        return writableDatabase.insert(TABLE_USERS, null, values)
    }

    fun getAllUsers(): List<UserRecord> {
        val list = mutableListOf<UserRecord>()
        readableDatabase.rawQuery("SELECT * FROM $TABLE_USERS ORDER BY $COL_USER_ID DESC", null).use {
            while (it.moveToNext()) list.add(it.toUser())
        }
        return list
    }

    /** Normalized Attendee IDs shared by more than one user (left over from before v2.2). */
    fun getDuplicateAttendeeIds(): Set<String> =
        UserIdRules.findDuplicates(getAllUsers().map { it.attendeeId })

    /** How many users have this Attendee ID (ignoring case and spaces). */
    fun countUsersWithAttendeeId(attendeeId: String): Int {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $TABLE_USERS WHERE TRIM($COL_USER_ATTENDEE_ID) = ? COLLATE NOCASE",
            arrayOf(attendeeId.trim())
        ).use { if (it.moveToFirst()) return it.getInt(0) }
        return 0
    }

    fun deleteUser(id: Long): Int =
        writableDatabase.delete(TABLE_USERS, "$COL_USER_ID = ?", arrayOf(id.toString()))

    fun getUserByAttendeeId(attendeeId: String): UserRecord? {
        readableDatabase.rawQuery(
            "SELECT * FROM $TABLE_USERS WHERE $COL_USER_ATTENDEE_ID = ? COLLATE NOCASE LIMIT 1",
            arrayOf(attendeeId.trim())
        ).use { if (it.moveToFirst()) return it.toUser() }
        return null
    }

    private fun Cursor.toUser() = UserRecord(
        id = getLong(getColumnIndexOrThrow(COL_USER_ID)),
        name = getString(getColumnIndexOrThrow(COL_USER_NAME)),
        attendeeId = getString(getColumnIndexOrThrow(COL_USER_ATTENDEE_ID)),
        tag = getString(getColumnIndexOrThrow(COL_USER_TAG)),
        createdAt = getString(getColumnIndexOrThrow(COL_USER_CREATED_AT))
    )
}
