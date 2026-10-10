package com.example.jack

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.SimpleDateFormat
import java.util.*

data class AttendanceRecord(
    val id: Long,
    val attendeeId: String,
    val timestamp: String,
    val selfiePath: String?,
    val synced: Boolean
)

data class UserRecord(
    val id: Long,
    val name: String,
    val attendeeId: String,
    val tag: String,
    val createdAt: String
)

class AttendanceDatabase(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "jack_attendance.db"
        private const val DATABASE_VERSION = 2
        private const val TABLE_ATTENDANCE = "attendance_records"
        private const val TABLE_USERS = "users"

        private const val COL_ID = "id"
        private const val COL_ATTENDEE_ID = "attendee_id"
        private const val COL_TIMESTAMP = "timestamp"
        private const val COL_SELFIE_PATH = "selfie_path"
        private const val COL_SYNCED = "synced"

        private const val COL_USER_ID = "id"
        private const val COL_USER_NAME = "name"
        private const val COL_USER_ATTENDEE_ID = "attendee_id"
        private const val COL_USER_TAG = "tag"
        private const val COL_USER_CREATED_AT = "created_at"
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createAttendanceTable = """
            CREATE TABLE $TABLE_ATTENDANCE (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_ATTENDEE_ID TEXT NOT NULL,
                $COL_TIMESTAMP TEXT NOT NULL,
                $COL_SELFIE_PATH TEXT,
                $COL_SYNCED INTEGER DEFAULT 0
            )
        """
        db.execSQL(createAttendanceTable)

        val createUserTable = """
            CREATE TABLE $TABLE_USERS (
                $COL_USER_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_USER_NAME TEXT NOT NULL,
                $COL_USER_ATTENDEE_ID TEXT NOT NULL,
                $COL_USER_TAG TEXT NOT NULL,
                $COL_USER_CREATED_AT TEXT NOT NULL
            )
        """
        db.execSQL(createUserTable)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            val createUserTable = """
                CREATE TABLE IF NOT EXISTS $TABLE_USERS (
                    $COL_USER_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                    $COL_USER_NAME TEXT NOT NULL,
                    $COL_USER_ATTENDEE_ID TEXT NOT NULL,
                    $COL_USER_TAG TEXT NOT NULL,
                    $COL_USER_CREATED_AT TEXT NOT NULL
                )
            """
            db.execSQL(createUserTable)
        }
    }

    fun insertRecord(attendeeId: String, selfiePath: String?): Long {
        val db = writableDatabase
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val values = ContentValues().apply {
            put(COL_ATTENDEE_ID, attendeeId)
            put(COL_TIMESTAMP, timestamp)
            put(COL_SELFIE_PATH, selfiePath)
            put(COL_SYNCED, 0)
        }
        return db.insert(TABLE_ATTENDANCE, null, values)
    }

    fun getAllRecords(): List<AttendanceRecord> {
        val list = mutableListOf<AttendanceRecord>()
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT * FROM $TABLE_ATTENDANCE ORDER BY $COL_ID DESC", null)
        cursor.use {
            while (it.moveToNext()) {
                val id = it.getLong(it.getColumnIndexOrThrow(COL_ID))
                val attendeeId = it.getString(it.getColumnIndexOrThrow(COL_ATTENDEE_ID))
                val timestamp = it.getString(it.getColumnIndexOrThrow(COL_TIMESTAMP))
                val selfiePath = it.getString(it.getColumnIndexOrThrow(COL_SELFIE_PATH))
                val synced = it.getInt(it.getColumnIndexOrThrow(COL_SYNCED)) == 1
                list.add(AttendanceRecord(id, attendeeId, timestamp, selfiePath, synced))
            }
        }
        return list
    }

    fun markAllSynced(): Int {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_SYNCED, 1)
        }
        return db.update(TABLE_ATTENDANCE, values, "$COL_SYNCED = 0", null)
    }

    fun getUnsyncedCount(): Int {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_ATTENDANCE WHERE $COL_SYNCED = 0", null)
        cursor.use {
            if (it.moveToFirst()) {
                return it.getInt(0)
            }
        }
        return 0
    }

    fun addUser(name: String, attendeeId: String, tag: String): Long {
        val db = writableDatabase
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val values = ContentValues().apply {
            put(COL_USER_NAME, name)
            put(COL_USER_ATTENDEE_ID, attendeeId)
            put(COL_USER_TAG, tag)
            put(COL_USER_CREATED_AT, timestamp)
        }
        return db.insert(TABLE_USERS, null, values)
    }

    fun getAllUsers(): List<UserRecord> {
        val list = mutableListOf<UserRecord>()
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT * FROM $TABLE_USERS ORDER BY $COL_USER_ID DESC", null)
        cursor.use {
            while (it.moveToNext()) {
                val id = it.getLong(it.getColumnIndexOrThrow(COL_USER_ID))
                val name = it.getString(it.getColumnIndexOrThrow(COL_USER_NAME))
                val attendeeId = it.getString(it.getColumnIndexOrThrow(COL_USER_ATTENDEE_ID))
                val tag = it.getString(it.getColumnIndexOrThrow(COL_USER_TAG))
                val createdAt = it.getString(it.getColumnIndexOrThrow(COL_USER_CREATED_AT))
                list.add(UserRecord(id, name, attendeeId, tag, createdAt))
            }
        }
        return list
    }

    fun deleteUser(id: Long): Int {
        val db = writableDatabase
        return db.delete(TABLE_USERS, "$COL_USER_ID = ?", arrayOf(id.toString()))
    }

    fun getUserByAttendeeId(attendeeId: String): UserRecord? {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT * FROM $TABLE_USERS WHERE $COL_USER_ATTENDEE_ID = ? COLLATE NOCASE LIMIT 1", arrayOf(attendeeId.trim()))
        cursor.use {
            if (it.moveToFirst()) {
                val id = it.getLong(it.getColumnIndexOrThrow(COL_USER_ID))
                val name = it.getString(it.getColumnIndexOrThrow(COL_USER_NAME))
                val attId = it.getString(it.getColumnIndexOrThrow(COL_USER_ATTENDEE_ID))
                val tag = it.getString(it.getColumnIndexOrThrow(COL_USER_TAG))
                val createdAt = it.getString(it.getColumnIndexOrThrow(COL_USER_CREATED_AT))
                return UserRecord(id, name, attId, tag, createdAt)
            }
        }
        return null
    }
}
