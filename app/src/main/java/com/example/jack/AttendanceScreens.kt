package com.example.jack

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.Cursor
import android.util.Log
import com.example.jack.BuildConfig
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.io.FileOutputStream
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.example.jack.ui.theme.JackTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.nio.charset.Charset
import java.util.UUID

// Check-in / check-out dialog, "Who is in the Building" card, attendance log, and selfie helpers.

/** "since 09:12" for today, "since 2026-10-09 09:12" for an earlier day. Input: "yyyy-MM-dd HH:mm:ss". */
fun formatSince(timestamp: String): String {
    val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
    val time = timestamp.drop(11).take(5)
    return if (timestamp.startsWith(today)) "since $time" else "since ${timestamp.take(10)} $time"
}

/** How many visits the on-screen attendance log shows (newest first). */
const val LOG_SCREEN_LIMIT = 300

/** Small round selfie image; draws nothing if there is no photo. */
@Composable
fun SelfieThumbnail(path: String?, description: String) {
    // Decoded in the background so long lists of photos never stall the screen.
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) {
            try { decodeThumbnail(path) } catch (_: Exception) { null }
        }
    }
    bitmap?.let { bmp ->
        androidx.compose.foundation.Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = description,
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape),
            contentScale = androidx.compose.ui.layout.ContentScale.Crop
        )
    }
}

/** Loads a selfie scaled down for small on-screen previews (keeps memory low with many photos). */
fun decodeThumbnail(path: String?, targetPx: Int = 160): android.graphics.Bitmap? {
    if (path.isNullOrEmpty()) return null
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= targetPx && bounds.outHeight / (sample * 2) >= targetPx) sample *= 2
    return android.graphics.BitmapFactory.decodeFile(path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
}

fun saveBitmapToFile(context: Context, bitmap: android.graphics.Bitmap): File? {
    return try {
        val file = File(context.filesDir, "attendance_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { out ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
        }
        file
    } catch (_: Exception) {
        null
    }
}

@Composable
fun AttendanceDialog(
    onDismiss: () -> Unit,
    onCheckedIn: (attendeeId: String) -> Unit,
    onCheckedOut: (attendeeId: String) -> Unit
) {
    val context = LocalContext.current
    val db = remember { AttendanceDatabase.get(context) }
    var attendeeId by remember { mutableStateOf("") }
    val dbRevision = AttendanceDatabase.revision.intValue

    val matchedUser = remember(attendeeId) {
        if (attendeeId.isBlank()) null else db.getUserByAttendeeId(attendeeId.trim())
    }
    // The person's current visit, if they are already in the building → offer Check Out instead.
    val openVisit = remember(matchedUser, dbRevision) {
        matchedUser?.let { db.getOpenVisit(it.attendeeId) }
    }
    val isCheckedIn = openVisit != null
    // Two users sharing this ID (left over from before v2.2): we can't tell who it is, so block it.
    val isAmbiguousId = remember(attendeeId) {
        attendeeId.isNotBlank() && db.countUsersWithAttendeeId(attendeeId) > 1
    }
    val canProceed = matchedUser != null && !isAmbiguousId

    var showCamera by remember { mutableStateOf(false) }
    var cameraMessage by remember { mutableStateOf<String?>(null) }

    // A selfie is required both ways, so nobody can check in or out on someone else's behalf.
    fun handleSelfie(bitmap: android.graphics.Bitmap) {
        val user = matchedUser ?: return
        val file = saveBitmapToFile(context, bitmap)
        if (db.getOpenVisit(user.attendeeId) != null) {
            if (db.checkOut(user.attendeeId, file?.absolutePath)) onCheckedOut(user.attendeeId)
        } else {
            db.insertRecord(user.attendeeId, file?.absolutePath)
            onCheckedIn(user.attendeeId)
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            showCamera = true
        } else {
            cameraMessage = "Camera permission is needed to take the selfie."
        }
    }

    val actionColor = if (isCheckedIn) JackColors.Amber else JackColors.Blue

    fun openCamera() {
        if (!canProceed) return
        cameraMessage = null
        // Built-in camera (works in kiosk mode). Device owner grants itself the permission.
        if (ensureCameraPermission(context)) {
            showCamera = true
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // v3.3: full-screen kiosk page — big ID display, who-it-is card, keypad, one big action button.
    JackFullScreenDialog(onDismissRequest = onDismiss) {
        Surface(modifier = Modifier.fillMaxSize(), color = JackColors.Concrete) {
            Column(modifier = Modifier.fillMaxSize()) {
                // ---------- Top bar ----------
                Surface(color = JackColors.Paper) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            JackIconButton(Icons.Default.Close, "Cancel", onDismiss)
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("CHECK IN / OUT", style = MaterialTheme.typography.headlineSmall, color = JackColors.Ink)
                                Text("ENTER YOUR ATTENDEE ID", style = MaterialTheme.typography.labelSmall, color = JackColors.Graphite)
                            }
                            BrandLogo(size = 36.dp)
                        }
                        StripeBar()
                    }
                }

                // ---------- Body ----------
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    AttendeeIdDisplay(attendeeId = attendeeId, matched = canProceed, error = attendeeId.isNotBlank() && !canProceed)

                    when {
                        attendeeId.isBlank() -> IdHintCard()
                        isAmbiguousId -> IdProblemCard(
                            title = "This ID belongs to more than one person",
                            detail = "Please ask an admin to fix it in Settings → Users."
                        )
                        matchedUser == null -> IdProblemCard(
                            title = "ID not registered",
                            detail = "Check the number, or ask an admin to add you."
                        )
                        else -> MatchedPersonCard(user = matchedUser, openVisit = openVisit)
                    }

                    CustomKeypad(
                        onCharClick = { digit -> attendeeId += digit },
                        onBackspace = { if (attendeeId.isNotEmpty()) attendeeId = attendeeId.dropLast(1) },
                        onClear = { attendeeId = "" },
                        startInNumberMode = true  // digits first; "ABC" for IDs that contain letters
                    )

                    cameraMessage?.let {
                        Text(it, color = JackColors.Red, style = MaterialTheme.typography.labelMedium)
                    }
                }

                // ---------- Action ----------
                Surface(color = JackColors.Paper, border = BorderStroke(1.dp, JackColors.Line)) {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        JackPrimaryButton(
                            text = when {
                                !canProceed -> "Enter your ID"
                                isCheckedIn -> "Take selfie · Check out"
                                else -> "Take selfie · Check in"
                            },
                            onClick = { openCamera() },
                            enabled = canProceed,
                            icon = Icons.Default.CameraAlt,
                            color = actionColor,
                            height = 64.dp,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "A selfie is required both ways so nobody can check in or out for someone else.",
                            style = HintStyle,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
        }
    }

    if (showCamera && matchedUser != null) {
        SelfieCaptureDialog(
            personName = matchedUser.name,
            isCheckOut = isCheckedIn,
            onCaptured = { bitmap ->
                showCamera = false
                handleSelfie(bitmap)
            },
            onDismiss = { showCamera = false }
        )
    }
}



/** Big black ID readout (looks like a machine display). */
@Composable
private fun AttendeeIdDisplay(attendeeId: String, matched: Boolean, error: Boolean) {
    val edge = when {
        matched -> JackColors.Green
        error -> JackColors.Red
        else -> JackColors.Steel
    }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = JackColors.Ink,
        border = BorderStroke(2.dp, edge),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            Text("ATTENDEE ID", style = MaterialTheme.typography.labelSmall, color = JackColors.Gray)
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (attendeeId.isEmpty()) "_ _ _ _" else attendeeId,
                style = MaterialTheme.typography.displaySmall.copy(fontFamily = FontFamily.Monospace, letterSpacing = 4.sp),
                color = if (attendeeId.isEmpty()) JackColors.Graphite else JackColors.Paper,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun IdHintCard() {
    JackCard(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Person, contentDescription = null, tint = JackColors.Gray, modifier = Modifier.size(36.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Type your ID on the keypad", style = MaterialTheme.typography.titleSmall, color = JackColors.Ink)
                Text("Your name appears here when the ID is found.", style = HintStyle)
            }
        }
    }
}

@Composable
private fun IdProblemCard(title: String, detail: String) {
    JackCard(modifier = Modifier.fillMaxWidth(), color = JackColors.RedTint, borderColor = JackColors.Red) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Warning, contentDescription = null, tint = JackColors.Red, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall, color = JackColors.Red)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = JackColors.Graphite)
            }
        }
    }
}

/**
 * Who the ID belongs to. When they are checking out, their check-in photo is shown so the
 * person at the kiosk (and anyone watching) can see it is really them.
 */
@Composable
private fun MatchedPersonCard(user: UserRecord, openVisit: AttendanceRecord?) {
    val photo by produceState<android.graphics.Bitmap?>(initialValue = null, openVisit?.selfiePath) {
        value = withContext(Dispatchers.IO) {
            try { decodeThumbnail(openVisit?.selfiePath, targetPx = 240) } catch (_: Exception) { null }
        }
    }
    JackCard(modifier = Modifier.fillMaxWidth(), borderColor = JackColors.Green) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(JackColors.Ink),
                contentAlignment = Alignment.Center
            ) {
                val bmp = photo
                if (bmp != null) {
                    androidx.compose.foundation.Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Check-in photo of ${user.name}",
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(
                        user.name.trim().take(1).uppercase().ifEmpty { "?" },
                        style = MaterialTheme.typography.headlineMedium,
                        color = JackColors.Paper
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    user.name,
                    style = MaterialTheme.typography.titleLarge,
                    color = JackColors.Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusChip(user.tag, tagColor(user.tag))
                    Spacer(Modifier.width(6.dp))
                    if (openVisit != null) {
                        StatusChip("In ${formatSince(openVisit.timestamp)}", JackColors.Green, filled = true)
                    } else {
                        StatusChip("Not in", JackColors.Gray)
                    }
                }
            }
        }
    }
}

/** What the attendance log page loads in the background. */
private class LogData(val records: List<AttendanceRecord>, val total: Int, val names: Map<String, UserRecord>)

/**
 * v3.12: full-screen ATTENDANCE LOG. Visits grouped by day with names, both selfies, in → out times,
 * time spent and sync state; filters (All / In now / Not synced), search, and a tap-to-enlarge photo
 * check. Shows the newest LOG_SCREEN_LIMIT visits; older ones are in the daily files / Google Sheet.
 */
@Composable
fun AttendanceLogsDialog(
    context: Context,
    onDismiss: () -> Unit
) {
    val db = remember { AttendanceDatabase.get(context) }
    val dbRevision = AttendanceDatabase.revision.intValue

    val logData by produceState<LogData?>(initialValue = null, dbRevision) {
        value = withContext(Dispatchers.IO) {
            LogData(
                records = db.getRecentRecords(LOG_SCREEN_LIMIT),
                total = db.getRecordCount(),
                names = db.getAllUsers().associateBy { UserIdRules.normalize(it.attendeeId) }
            )
        }
    }
    val records = logData?.records.orEmpty()
    val totalRecords = logData?.total ?: 0
    val users = logData?.names.orEmpty()

    var filter by remember { mutableStateOf("all") }        // all · in · unsynced
    var query by remember { mutableStateOf("") }
    var viewing by remember { mutableStateOf<AttendanceRecord?>(null) }

    val today = remember { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date()) }
    val inNowCount = records.count { it.checkOutTime == null }
    val unsyncedCount = records.count { !it.synced }
    val todayCount = records.count { it.timestamp.startsWith(today) }

    val shown = remember(records, filter, query, users) {
        val q = query.trim()
        records.filter { r ->
            when (filter) {
                "in" -> r.checkOutTime == null
                "unsynced" -> !r.synced
                else -> true
            } && (q.isEmpty() || r.attendeeId.contains(q, ignoreCase = true) ||
                users[UserIdRules.normalize(r.attendeeId)]?.name?.contains(q, ignoreCase = true) == true)
        }
    }
    val byDay = remember(shown) { shown.groupBy { it.timestamp.take(10) } }

    JackFullScreenDialog(onDismissRequest = onDismiss) {
        Surface(modifier = Modifier.fillMaxSize().adminActivity(), color = JackColors.Concrete) {
            Column(modifier = Modifier.fillMaxSize()) {
                // ---------- Top bar ----------
                Surface(color = JackColors.Paper) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            JackIconButton(Icons.Default.Close, "Close", onDismiss)
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("ATTENDANCE LOG", style = MaterialTheme.typography.headlineSmall, color = JackColors.Ink)
                                Text(
                                    if (totalRecords > records.size) "LATEST ${records.size} OF $totalRecords VISITS" else "$totalRecords VISITS STORED ON THIS TABLET",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = JackColors.Graphite
                                )
                            }
                        }
                        StripeBar()
                    }
                }

                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Summary tiles
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatTile("Today", "$todayCount", modifier = Modifier.weight(1f))
                            StatTile("In now", "$inNowCount", modifier = Modifier.weight(1f), valueColor = JackColors.Green)
                            StatTile("Not synced", "$unsyncedCount", modifier = Modifier.weight(1f), valueColor = if (unsyncedCount > 0) JackColors.Amber else JackColors.Ink)
                        }
                    }
                    // Search + filters
                    item {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text("Search name or ID") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = JackColors.Graphite) },
                            trailingIcon = {
                                if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, contentDescription = "Clear search") }
                            },
                            singleLine = true,
                            shape = MaterialTheme.shapes.small,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = JackColors.Blue,
                                unfocusedBorderColor = JackColors.Line,
                                focusedContainerColor = JackColors.Paper,
                                unfocusedContainerColor = JackColors.Paper,
                                cursorColor = JackColors.Blue
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LogFilterChip("All", filter == "all") { filter = "all" }
                            LogFilterChip("In now", filter == "in") { filter = "in" }
                            LogFilterChip("Not synced", filter == "unsynced") { filter = "unsynced" }
                        }
                    }

                    if (logData == null) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = JackColors.Ink)
                            }
                        }
                    } else if (shown.isEmpty()) {
                        item {
                            JackCard(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 36.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = JackColors.Line, modifier = Modifier.size(56.dp))
                                    Spacer(Modifier.height(10.dp))
                                    Text(
                                        if (records.isEmpty()) "No visits recorded yet." else "No visits match.",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = JackColors.Graphite
                                    )
                                }
                            }
                        }
                    } else {
                        byDay.forEach { (day, visits) ->
                            item(key = "day-$day") {
                                SectionLabel(
                                    text = dayLabel(day, today),
                                    trailing = { StatusChip("${visits.size} visits", JackColors.Graphite) }
                                )
                            }
                            items(visits, key = { it.id }) { record ->
                                VisitRow(
                                    record = record,
                                    user = users[UserIdRules.normalize(record.attendeeId)],
                                    onClick = { viewing = record }
                                )
                            }
                        }
                        if (totalRecords > records.size) {
                            item {
                                Text(
                                    "Older visits are in the daily files (Documents/Jack) and the Google Sheet.",
                                    style = HintStyle,
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    viewing?.let { record ->
        VisitPhotosDialog(record, users[UserIdRules.normalize(record.attendeeId)]) { viewing = null }
    }
}

/** "TODAY", "YESTERDAY" or "SAT 10 OCT 2026". */
private fun dayLabel(day: String, today: String): String {
    if (day == today) return "Today"
    return try {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val d = fmt.parse(day) ?: return day
        val y = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, -1) }
        if (fmt.format(y.time) == day) "Yesterday"
        else java.text.SimpleDateFormat("EEE d MMM yyyy", java.util.Locale.getDefault()).format(d)
    } catch (_: Exception) { day }
}

/** "2h 28m" between two "yyyy-MM-dd HH:mm:ss" times, or null. */
private fun visitDuration(from: String, to: String?): String? {
    if (to == null || to == AttendanceDatabase.CHECK_OUT_UNKNOWN) return null
    return try {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        val mins = ((fmt.parse(to)!!.time - fmt.parse(from)!!.time) / 60000).coerceAtLeast(0)
        if (mins >= 60) "${mins / 60}h ${mins % 60}m" else "${mins}m"
    } catch (_: Exception) { null }
}

/** Square selfie for the log; grey box with a label when there's no photo. */
@Composable
private fun LogPhoto(path: String?, label: String, size: androidx.compose.ui.unit.Dp) {
    val bmp by produceState<android.graphics.Bitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) { try { decodeThumbnail(path, targetPx = 200) } catch (_: Exception) { null } }
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(MaterialTheme.shapes.extraSmall)
            .background(JackColors.Panel),
        contentAlignment = Alignment.Center
    ) {
        val b = bmp
        if (b != null) {
            androidx.compose.foundation.Image(
                bitmap = b.asImageBitmap(),
                contentDescription = label,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(Icons.Default.Person, contentDescription = null, tint = JackColors.Gray, modifier = Modifier.size(size / 2))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = JackColors.Paper,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .background(JackColors.Ink.copy(alpha = 0.75f))
                .padding(horizontal = 4.dp)
        )
    }
}

/** One visit: in/out selfies, name + ID, in → out times, time spent, sync state. */
@Composable
private fun VisitRow(record: AttendanceRecord, user: UserRecord?, onClick: () -> Unit) {
    val stillIn = record.checkOutTime == null
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = JackColors.Paper,
        border = BorderStroke(1.dp, if (stillIn) JackColors.Green.copy(alpha = 0.6f) else JackColors.Line),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            LogPhoto(record.selfiePath, "IN", 56.dp)
            Spacer(Modifier.width(4.dp))
            LogPhoto(record.checkOutSelfiePath, "OUT", 56.dp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    user?.name ?: "Unknown user",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (user == null) JackColors.Graphite else JackColors.Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "ID ${record.attendeeId}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = JackColors.Graphite
                )
                Spacer(Modifier.height(4.dp))
                val inTime = record.timestamp.drop(11).take(5)
                val outText = when (record.checkOutTime) {
                    null -> "still in"
                    AttendanceDatabase.CHECK_OUT_UNKNOWN -> "not recorded"
                    else -> record.checkOutTime.drop(11).take(5)
                }
                Text(
                    "$inTime → $outText" + (visitDuration(record.timestamp, record.checkOutTime)?.let { "  ·  $it" } ?: ""),
                    style = MaterialTheme.typography.labelLarge,
                    color = JackColors.Ink
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (stillIn) StatusChip("In now", JackColors.Green, filled = true)
                if (record.synced) StatusChip("Synced", JackColors.Gray) else StatusChip("Not synced", JackColors.Amber)
            }
        }
    }
}

/** Big side-by-side check-in / check-out photos so an admin can confirm it was the same person. */
@Composable
private fun VisitPhotosDialog(record: AttendanceRecord, user: UserRecord?, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = JackColors.Paper,
        shape = MaterialTheme.shapes.medium,
        title = {
            Column {
                Text((user?.name ?: "ID ${record.attendeeId}").uppercase(), style = MaterialTheme.typography.titleLarge, color = JackColors.Ink)
                Text("${record.timestamp.take(16)}  ·  ID ${record.attendeeId}", style = MaterialTheme.typography.labelMedium, color = JackColors.Graphite)
            }
        },
        text = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(modifier = Modifier.weight(1f).aspectRatio(0.8f)) { BigPhoto(record.selfiePath, "CHECK IN") }
                Box(modifier = Modifier.weight(1f).aspectRatio(0.8f)) { BigPhoto(record.checkOutSelfiePath, "CHECK OUT") }
            }
        },
        confirmButton = { JackPrimaryButton(text = "Close", height = 48.dp, color = JackColors.Ink, onClick = onDismiss) }
    )
}

@Composable
private fun BigPhoto(path: String?, label: String) {
    val bmp by produceState<android.graphics.Bitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) { try { decodeThumbnail(path, targetPx = 600) } catch (_: Exception) { null } }
    }
    Box(
        modifier = Modifier.fillMaxSize().clip(MaterialTheme.shapes.small).background(JackColors.Panel),
        contentAlignment = Alignment.Center
    ) {
        val b = bmp
        if (b != null) {
            androidx.compose.foundation.Image(
                bitmap = b.asImageBitmap(),
                contentDescription = label,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text("NO PHOTO", style = MaterialTheme.typography.labelMedium, color = JackColors.Gray)
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = JackColors.Paper,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(JackColors.Ink.copy(alpha = 0.75f))
                .padding(6.dp)
        )
    }
}

@Composable
private fun LogFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.extraSmall,
        color = if (selected) JackColors.Ink else JackColors.Paper,
        border = BorderStroke(1.dp, if (selected) JackColors.Ink else JackColors.Line)
    ) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) JackColors.Paper else JackColors.Ink,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

/** Live attendance numbers shared by the dashboard tiles and the roster grid. */
data class AttendanceSnapshot(
    val inBuilding: List<Pair<AttendanceRecord, UserRecord?>>,
    val visitsToday: Int
)

/** Loads who is in the building (with their user records) in the background; refreshes on every check-in/out. */
@Composable
fun rememberAttendanceSnapshot(): AttendanceSnapshot? {
    val context = LocalContext.current
    val db = remember { AttendanceDatabase.get(context) }
    val dbRevision = AttendanceDatabase.revision.intValue
    val snapshot by produceState<AttendanceSnapshot?>(initialValue = null, dbRevision) {
        value = withContext(Dispatchers.IO) {
            val users = db.getAllUsers().associateBy { UserIdRules.normalize(it.attendeeId) }
            val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
            AttendanceSnapshot(
                inBuilding = db.getCheckedIn().map { it to users[UserIdRules.normalize(it.attendeeId)] },
                visitsToday = db.getVisitCountForDate(today)
            )
        }
    }
    return snapshot
}

/** "WHO IS IN THE BUILDING" heading with the live count. Shown inside the scrolling dashboard. */
@Composable
fun RosterHeader(count: Int) {
    SectionLabel(
        text = "Who is in the building",
        trailing = { StatusChip("$count in", JackColors.Green, filled = count > 0) }
    )
}

/** Shown when nobody is checked in. */
@Composable
fun RosterEmptyState() {
    JackCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            BrandLogo(size = 64.dp, tint = JackColors.Line)
            Spacer(Modifier.height(12.dp))
            Text("Nobody is checked in right now.", style = MaterialTheme.typography.titleSmall, color = JackColors.Graphite)
            Text("Tap CHECK IN / OUT above to start.", style = HintStyle)
        }
    }
}

/** One person in the building: photo, name, tag and since-when. */
@Composable
fun PersonTile(record: AttendanceRecord, user: UserRecord?) {
    val photo by produceState<android.graphics.Bitmap?>(initialValue = null, record.selfiePath) {
        value = withContext(Dispatchers.IO) {
            try { decodeThumbnail(record.selfiePath, targetPx = 320) } catch (_: Exception) { null }
        }
    }

    Surface(
        shape = MaterialTheme.shapes.small,
        color = JackColors.Paper,
        border = BorderStroke(1.dp, JackColors.Line)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .background(JackColors.Panel),
                contentAlignment = Alignment.Center
            ) {
                val bmp = photo
                if (bmp != null) {
                    androidx.compose.foundation.Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Photo of ${user?.name ?: record.attendeeId}",
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(Icons.Default.Person, contentDescription = null, tint = JackColors.Gray, modifier = Modifier.size(48.dp))
                }
                // Green "IN" marker in the corner of the photo
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                ) { StatusChip("In", JackColors.Green, filled = true) }
            }
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(
                    text = user?.name ?: "ID ${record.attendeeId}",
                    style = MaterialTheme.typography.titleSmall,
                    color = JackColors.Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (user != null) {
                        StatusChip(user.tag, tagColor(user.tag))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        text = formatSince(record.timestamp).replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.labelSmall,
                        color = JackColors.Graphite,
                        maxLines = 1
                    )
                }
            }
        }
    }
}
