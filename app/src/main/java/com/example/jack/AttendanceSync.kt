package com.example.jack

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Keeps attendance safe outside the app:
 *  1. Daily CSV file — Documents/Jack/attendance_YYYY-MM-DD.csv, rewritten a few seconds after every
 *     check-in/out (so today's file is always current) and on a 15-minute timer.
 *  2. Google Sheets — pushes new/changed visits to a Google Apps Script web app the admin sets up
 *     (see GOOGLE_SHEETS_SETUP.md). Retries automatically while offline.
 *  3. (Planned) Workshop management app — "who is in the building" feed. Not built yet.
 *
 * Started once by [JackCore.start]; runs as long as the app process (24/7 via the background service).
 */
object AttendanceSync {
    private const val PREF_SHEETS_ENABLED = "pref_sheets_enabled"
    private const val PREF_SHEETS_URL = "pref_sheets_url"
    private const val PREF_SHEETS_SECRET = "pref_sheets_secret"
    private const val PREF_DEVICE_ID = "pref_device_id"

    private const val CHANGE_DEBOUNCE_MS = 3_000L
    private const val PERIODIC_MS = 15 * 60 * 1000L
    private const val EXPORT_FOLDER = "Jack"

    private lateinit var app: Context
    private var started = false
    private val syncMutex = Mutex()
    private val exportMutex = Mutex()

    // ---- Status shown in Settings (Compose state, updated on the main thread) ----
    val lastExportStatus = mutableStateOf("Not exported yet")
    val lastSyncStatus = mutableStateOf("Not synced yet")
    val unsyncedCount = mutableStateOf(0)

    @OptIn(FlowPreview::class)
    fun start(context: Context, scope: CoroutineScope) {
        if (started) return
        started = true
        app = context.applicationContext

        // A few seconds after any check-in/out: refresh the file and push to Google Sheets.
        scope.launch {
            AttendanceDatabase.changes.drop(1).debounce(CHANGE_DEBOUNCE_MS).collect {
                runAll()
            }
        }
        // Safety net: on start and every 15 minutes (catches the day rolling over, and offline retries).
        scope.launch {
            while (true) {
                runAll()
                delay(PERIODIC_MS)
            }
        }
    }

    private suspend fun runAll() {
        exportRecentDays()
        syncToSheets(manual = false)
    }

    // =============================================================================================
    // 1. Daily CSV export
    // =============================================================================================

    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    private fun yesterday(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(System.currentTimeMillis() - 24L * 60 * 60 * 1000))

    /** Re-writes today's and yesterday's files (yesterday's catches late check-outs after midnight). */
    suspend fun exportRecentDays() {
        exportDay(yesterday(), onlyIfVisits = true)
        exportDay(today(), onlyIfVisits = true)
    }

    /** Writes attendance_<date>.csv. Returns a human-readable result. */
    suspend fun exportDay(date: String, onlyIfVisits: Boolean = false): String = exportMutex.withLock {
        val result = withContext(Dispatchers.IO) {
            try {
                val rows = AttendanceCsv.rowsForDate(loadVisitRows(), date)
                if (onlyIfVisits && rows.isEmpty()) return@withContext null
                val fileName = AttendanceCsv.fileNameFor(date)
                val where = writeCsv(fileName, AttendanceCsv.build(rows))
                "✅ ${rows.size} visit(s) → $where"
            } catch (e: Exception) {
                "❌ Export failed: ${e.localizedMessage}"
            }
        } ?: return@withLock "No visits on $date"

        val stamp = SimpleDateFormat("HH:mm", Locale.US).format(Date())
        lastExportStatus.value = "$result (at $stamp)"
        if (result.startsWith("❌")) JackCore.addLog("📁 $result")
        result
    }

    private fun loadVisitRows(): List<VisitRow> {
        val db = AttendanceDatabase.get(app)
        val users = db.getAllUsers().associateBy { it.attendeeId.lowercase() }
        return db.getAllRecords().map { it.toVisitRow(users) }
    }

    private fun AttendanceRecord.toVisitRow(users: Map<String, UserRecord>): VisitRow {
        val user = users[attendeeId.lowercase()]
        return VisitRow(
            visitId = id,
            attendeeId = attendeeId,
            name = user?.name ?: "(unregistered)",
            tag = user?.tag ?: "",
            checkIn = timestamp,
            checkOut = checkOutTime
        )
    }

    /** Saves to the public Documents/Jack folder; falls back to Jack's own folder if that's not allowed. */
    private fun writeCsv(fileName: String, content: String): String {
        val bytes = content.toByteArray(Charsets.UTF_8)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+: MediaStore, no permission needed.
            val resolver = app.contentResolver
            val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val relativePath = "${Environment.DIRECTORY_DOCUMENTS}/$EXPORT_FOLDER/"
            val existing = resolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                arrayOf(relativePath, fileName),
                null
            )?.use { c -> if (c.moveToFirst()) android.content.ContentUris.withAppendedId(collection, c.getLong(0)) else null }

            val uri = existing ?: resolver.insert(collection, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            }) ?: throw IllegalStateException("could not create $fileName")

            resolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                ?: throw IllegalStateException("could not open $fileName")
            return "Documents/$EXPORT_FOLDER/$fileName"
        }

        // Android 9 and older: public Documents needs WRITE_EXTERNAL_STORAGE (auto-granted when device owner).
        if (hasLegacyWritePermission()) {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), EXPORT_FOLDER)
            dir.mkdirs()
            File(dir, fileName).writeBytes(bytes)
            return "Documents/$EXPORT_FOLDER/$fileName"
        }

        // Fallback: Jack's own folder (visible over USB at Android/data/<package>/files/Documents/Jack).
        val dir = File(app.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), EXPORT_FOLDER)
        dir.mkdirs()
        File(dir, fileName).writeBytes(bytes)
        return "Android/data/${app.packageName}/files/Documents/$EXPORT_FOLDER/$fileName (storage permission not granted)"
    }

    fun hasLegacyWritePermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return true
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            return true
        }
        // Kiosk (device owner): grant it to ourselves silently.
        return try {
            val dpm = app.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            if (!dpm.isDeviceOwnerApp(app.packageName)) return false
            dpm.setPermissionGrantState(
                ComponentName(app, JackDeviceAdminReceiver::class.java),
                app.packageName,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
            )
        } catch (_: Exception) {
            false
        }
    }

    // =============================================================================================
    // 2. Google Sheets (via a Google Apps Script web app)
    // =============================================================================================

    data class SheetsConfig(val enabled: Boolean, val url: String, val secret: String)

    fun loadSheetsConfig(context: Context): SheetsConfig {
        val p = context.getSharedPreferences(JackCore.PREF_NAME, Context.MODE_PRIVATE)
        return SheetsConfig(
            enabled = p.getBoolean(PREF_SHEETS_ENABLED, false),
            url = p.getString(PREF_SHEETS_URL, "") ?: "",
            secret = p.getString(PREF_SHEETS_SECRET, "") ?: ""
        )
    }

    fun saveSheetsConfig(context: Context, config: SheetsConfig) {
        context.getSharedPreferences(JackCore.PREF_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(PREF_SHEETS_ENABLED, config.enabled)
            .putString(PREF_SHEETS_URL, config.url.trim())
            .putString(PREF_SHEETS_SECRET, config.secret.trim())
            .apply()
    }

    /** Stable random ID for this tablet, so several Jack tablets can share one sheet. */
    private fun deviceId(): String {
        val p = app.getSharedPreferences(JackCore.PREF_NAME, Context.MODE_PRIVATE)
        return p.getString(PREF_DEVICE_ID, null) ?: UUID.randomUUID().toString().take(8).also {
            p.edit().putString(PREF_DEVICE_ID, it).apply()
        }
    }

    fun isOnline(): Boolean = try {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (_: Exception) {
        true  // can't tell — just try
    }

    /** Checks the URL and secret by sending an empty batch. */
    suspend fun testSheetsConnection(config: SheetsConfig): String {
        if (config.url.isBlank()) return "❌ Enter the Apps Script web app URL first."
        if (!isOnline()) return "❌ No internet connection."
        return withContext(Dispatchers.IO) {
            try {
                val reply = postJson(config.url, buildPayload(config.secret, emptyList()))
                if (reply.optBoolean("ok")) "✅ Connected to your Google Sheet." else "❌ Sheet replied: ${reply.optString("error", "unknown error")}"
            } catch (e: Exception) {
                "❌ ${e.localizedMessage}"
            }
        }
    }

    /** Sends every unsynced visit. Safe to call any time; does nothing if not configured or offline. */
    suspend fun syncToSheets(manual: Boolean): String = syncMutex.withLock {
        val config = loadSheetsConfig(app)
        val db = AttendanceDatabase.get(app)
        unsyncedCount.value = withContext(Dispatchers.IO) { db.getUnsyncedCount() }

        if (!config.enabled || config.url.isBlank()) {
            return@withLock "Google Sheets sync is off.".also { if (manual) lastSyncStatus.value = it }
        }
        if (!isOnline()) {
            return@withLock "📴 Offline — ${unsyncedCount.value} visit(s) waiting, will retry automatically.".also { lastSyncStatus.value = it }
        }

        val result = withContext(Dispatchers.IO) {
            try {
                var total = 0
                while (true) {
                    val batch = db.getUnsyncedRecords(limit = 200)
                    if (batch.isEmpty()) break
                    val users = db.getAllUsers().associateBy { it.attendeeId.lowercase() }
                    val reply = postJson(config.url, buildPayload(config.secret, batch.map { it.toVisitRow(users) }))
                    if (!reply.optBoolean("ok")) {
                        return@withContext "❌ Sheet replied: ${reply.optString("error", "unknown error")}"
                    }
                    db.markSynced(batch)
                    total += batch.size
                    if (batch.size < 200) break
                }
                if (total == 0) "✅ Up to date" else "✅ Synced $total visit(s)"
            } catch (e: Exception) {
                "❌ Sync failed: ${e.localizedMessage} — will retry."
            }
        }

        unsyncedCount.value = withContext(Dispatchers.IO) { db.getUnsyncedCount() }
        val stamp = SimpleDateFormat("HH:mm", Locale.US).format(Date())
        // Don't overwrite a useful status with "Up to date" from the silent background timer.
        if (manual || result != "✅ Up to date" || lastSyncStatus.value.startsWith("❌") || lastSyncStatus.value.startsWith("📴")) {
            lastSyncStatus.value = "$result (at $stamp)"
        }
        if (result.startsWith("✅ Synced")) JackCore.addLog("☁️ Google Sheets: $result")
        if (result.startsWith("❌")) JackCore.addLog("☁️ Google Sheets: $result")
        result
    }

    private fun buildPayload(secret: String, rows: List<VisitRow>): JSONObject {
        val records = JSONArray()
        for (r in rows) {
            records.put(JSONObject().apply {
                put("visitId", r.visitId)
                put("attendeeId", r.attendeeId)
                put("name", r.name)
                put("tag", r.tag)
                put("checkIn", r.checkIn)
                put("checkOut", r.checkOutDisplay)
                put("durationMin", r.durationMinutes ?: JSONObject.NULL)
                put("status", r.status)
            })
        }
        return JSONObject().apply {
            put("secret", secret)
            put("deviceId", deviceId())
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("appVersion", BuildConfig.VERSION_NAME)
            put("records", records)
        }
    }

    /** POSTs JSON and parses the JSON reply. Apps Script answers via a redirect, which is followed. */
    private fun postJson(url: String, body: JSONObject): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            instanceFollowRedirects = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            return try {
                JSONObject(text)
            } catch (_: Exception) {
                // Usually means the web app isn't deployed for "Anyone", so Google returned a sign-in page.
                throw IllegalStateException("unexpected reply — check the web app is deployed with access \"Anyone\"")
            }
        } finally {
            conn.disconnect()
        }
    }
}
