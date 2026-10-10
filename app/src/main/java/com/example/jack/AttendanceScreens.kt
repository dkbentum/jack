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

@Composable
fun AttendanceLogsDialog(
    context: Context,
    onDismiss: () -> Unit
) {
    val db = remember { AttendanceDatabase.get(context) }
    val dbRevision = AttendanceDatabase.revision.intValue
    // Load the newest visits in the background; full history lives in the daily files / Google Sheet.
    val logData by produceState<Pair<List<AttendanceRecord>, Int>?>(initialValue = null, dbRevision) {
        value = withContext(Dispatchers.IO) { db.getRecentRecords(LOG_SCREEN_LIMIT) to db.getRecordCount() }
    }
    val records = logData?.first.orEmpty()
    val totalRecords = logData?.second ?: 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Notifications,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text("Attendance Logs ($totalRecords)", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier.adminActivity()
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (totalRecords > records.size) {
                    Text(
                        text = "Showing the latest ${records.size} of $totalRecords visits. Older visits are in the daily files (Documents/Jack) and Google Sheet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (logData != null && records.isEmpty()) {
                    Text(
                        text = "No attendance records stored locally yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Gray,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(records) { record ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        // Check-in selfie, and check-out selfie once they've left — so faces can be compared.
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            SelfieThumbnail(record.selfiePath, "Check-in selfie")
                                            SelfieThumbnail(record.checkOutSelfiePath, "Check-out selfie")
                                        }

                                        Column {
                                            Text(
                                                text = "ID: ${record.attendeeId}",
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = "In:  ${record.timestamp}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Text(
                                                text = when (record.checkOutTime) {
                                                    null -> "Out: 🟢 still in the building"
                                                    AttendanceDatabase.CHECK_OUT_UNKNOWN -> "Out: not recorded (before v1.6)"
                                                    else -> "Out: ${record.checkOutTime}"
                                                },
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = if (record.synced) Color(0xFF4CAF50).copy(alpha = 0.15f) else Color(0xFFFFC107).copy(alpha = 0.15f)
                                    ) {
                                        Text(
                                            text = if (record.synced) "Synced" else "Offline",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = if (record.synced) Color(0xFF4CAF50) else Color(0xFFFFC107),
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss, shape = RoundedCornerShape(10.dp)) {
                Text("Close")
            }
        }
    )
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
