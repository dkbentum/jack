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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Search
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

// Registered users: list and Add User dialog.

/**
 * v3.10: full-screen USERS page. Search, tag filter, who is in right now, duplicate-ID warnings,
 * delete with confirmation, and a big ADD USER button.
 */
@Composable
fun UserManagementDialog(
    context: Context,
    onDismiss: () -> Unit
) {
    val db = remember { AttendanceDatabase.get(context) }
    var users by remember { mutableStateOf(db.getAllUsers()) }
    // IDs shared by more than one user (only possible for users added before v2.2).
    val duplicateIds = remember(users) { UserIdRules.findDuplicates(users.map { it.attendeeId }) }
    // Who is in the building right now (normalised IDs).
    val dbRevision = AttendanceDatabase.revision.intValue
    val inNow = remember(dbRevision) { db.getCheckedIn().map { UserIdRules.normalize(it.attendeeId) }.toSet() }

    var query by remember { mutableStateOf("") }
    var tagFilter by remember { mutableStateOf<String?>(null) }
    var showAddUserDialog by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<UserRecord?>(null) }

    val tagCounts = remember(users) { users.groupingBy { it.tag }.eachCount().toSortedMap() }
    val shown = remember(users, query, tagFilter) {
        val q = query.trim()
        users.filter { u ->
            (tagFilter == null || u.tag == tagFilter) &&
                (q.isEmpty() || u.name.contains(q, ignoreCase = true) || u.attendeeId.contains(q, ignoreCase = true))
        }.sortedBy { it.name.lowercase() }
    }

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
                                Text("USERS", style = MaterialTheme.typography.headlineSmall, color = JackColors.Ink)
                                Text("WORKERS & SUBSCRIBERS", style = MaterialTheme.typography.labelSmall, color = JackColors.Graphite)
                            }
                            StatusChip("${users.size} people", JackColors.Ink)
                        }
                        StripeBar()
                    }
                }

                // ---------- Search + filters ----------
                Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Search name or ID") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = JackColors.Graphite) },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, contentDescription = "Clear search") }
                            }
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
                    if (tagCounts.size > 1) {
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            UserTagChip("All · ${users.size}", tagFilter == null) { tagFilter = null }
                            tagCounts.forEach { (t, n) ->
                                UserTagChip("$t · $n", tagFilter == t) { tagFilter = if (tagFilter == t) null else t }
                            }
                        }
                    }
                }

                // ---------- List ----------
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (duplicateIds.isNotEmpty()) {
                        item {
                            JackCard(modifier = Modifier.fillMaxWidth(), color = JackColors.RedTint, borderColor = JackColors.Red) {
                                Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                                    Icon(Icons.Default.Warning, contentDescription = null, tint = JackColors.Red, modifier = Modifier.size(22.dp))
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        "${duplicateIds.size} Attendee ID(s) are shared by more than one person. They can't check in until you delete the extra entries (marked DUPLICATE) and re-add them with a new ID.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = JackColors.Steel
                                    )
                                }
                            }
                        }
                    }

                    when {
                        users.isEmpty() -> item {
                            UsersEmptyState("No users yet.", "Tap ADD USER to register your first worker or subscriber.")
                        }
                        shown.isEmpty() -> item {
                            UsersEmptyState("No matches.", "Try another name, ID or tag.")
                        }
                        else -> items(shown, key = { it.id }) { user ->
                            UserRow(
                                user = user,
                                isIn = UserIdRules.normalize(user.attendeeId) in inNow,
                                isDuplicate = UserIdRules.normalize(user.attendeeId) in duplicateIds,
                                onDelete = { toDelete = user }
                            )
                        }
                    }
                }

                // ---------- Action ----------
                Surface(color = JackColors.Paper, border = BorderStroke(1.dp, JackColors.Line)) {
                    Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        JackPrimaryButton(
                            text = "Add user",
                            onClick = { showAddUserDialog = true },
                            icon = Icons.Default.PersonAdd,
                            height = 64.dp,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }

    toDelete?.let { user ->
        val isIn = UserIdRules.normalize(user.attendeeId) in inNow
        AlertDialog(
            onDismissRequest = { toDelete = null },
            containerColor = JackColors.Paper,
            shape = MaterialTheme.shapes.medium,
            title = { Text("DELETE ${user.name.uppercase()}?", style = MaterialTheme.typography.titleLarge, color = JackColors.Ink) },
            text = {
                Text(
                    buildString {
                        append("ID ${user.attendeeId} will no longer be able to check in. Their past visits stay in the attendance log.")
                        if (isIn) append("\n\nThey are checked in right now.")
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                JackPrimaryButton(text = "Delete", color = JackColors.Red, height = 48.dp, onClick = {
                    db.deleteUser(user.id)
                    users = db.getAllUsers()
                    toDelete = null
                })
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("CANCEL", style = MaterialTheme.typography.labelLarge, color = JackColors.Ink) } }
        )
    }

    if (showAddUserDialog) {
        AddUserDialog(
            context = context,
            onDismiss = { showAddUserDialog = false },
            onUserAdded = {
                users = db.getAllUsers()
                showAddUserDialog = false
            }
        )
    }
}

/** One user: initial tile, name, tag, ID, IN NOW / DUPLICATE markers, delete. */
@Composable
private fun UserRow(user: UserRecord, isIn: Boolean, isDuplicate: Boolean, onDelete: () -> Unit) {
    JackCard(
        modifier = Modifier.fillMaxWidth(),
        borderColor = if (isDuplicate) JackColors.Red else JackColors.Line
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(tagColor(user.tag), MaterialTheme.shapes.small),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    user.name.trim().take(1).uppercase().ifEmpty { "?" },
                    style = MaterialTheme.typography.titleLarge,
                    color = JackColors.Paper
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(user.name, style = MaterialTheme.typography.titleSmall, color = JackColors.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusChip(user.tag, tagColor(user.tag))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "ID ${user.attendeeId}",
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        color = if (isDuplicate) JackColors.Red else JackColors.Graphite,
                        maxLines = 1
                    )
                }
                if (isIn || isDuplicate) {
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (isIn) StatusChip("In now", JackColors.Green, filled = true)
                        if (isDuplicate) StatusChip("Duplicate ID", JackColors.Red, filled = true)
                    }
                }
            }
            OutlinedIconButton(
                onClick = onDelete,
                shape = MaterialTheme.shapes.small,
                border = BorderStroke(1.dp, JackColors.Red.copy(alpha = 0.5f)),
                modifier = Modifier.size(48.dp)
            ) { Icon(Icons.Default.Delete, contentDescription = "Delete ${user.name}", tint = JackColors.Red) }
        }
    }
}

@Composable
private fun UserTagChip(label: String, selected: Boolean, onClick: () -> Unit) {
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

@Composable
private fun UsersEmptyState(title: String, hint: String) {
    JackCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Default.People, contentDescription = null, tint = JackColors.Line, modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(10.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, color = JackColors.Graphite)
            Text(hint, style = HintStyle)
        }
    }
}

/**
 * v3.11: full-screen ADD USER page. Name and Attendee ID typed on Jack's keypad (the ID opens on
 * digits), tag picked from big chips, a "next free ID" helper, live duplicate check and a preview.
 */
@Composable
fun AddUserDialog(
    context: Context,
    onDismiss: () -> Unit,
    onUserAdded: () -> Unit
) {
    val db = remember { AttendanceDatabase.get(context) }
    var name by remember { mutableStateOf("") }
    var attendeeId by remember { mutableStateOf("") }
    var tag by remember { mutableStateOf("Worker") }
    var activeField by remember { mutableStateOf("name") }
    var saveError by remember { mutableStateOf<String?>(null) }

    val tagsList = listOf("Worker", "Subscriber", "Staff", "Guest", "Admin")

    // Attendee IDs must be unique (ignoring case/spaces) so a check-in always matches exactly one person.
    val existingOwner = remember(attendeeId) {
        if (attendeeId.isBlank()) null else db.getUserByAttendeeId(attendeeId.trim())
    }
    // Next free number: one more than the highest all-digit ID in use.
    val nextFreeId = remember {
        val maxId = db.getAllUsers().mapNotNull { it.attendeeId.trim().toLongOrNull() }.maxOrNull() ?: 0L
        (maxId + 1).toString()
    }
    val canSave = name.isNotBlank() && attendeeId.isNotBlank() && existingOwner == null

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
                            JackIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back to users", onDismiss)
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("ADD USER", style = MaterialTheme.typography.headlineSmall, color = JackColors.Ink)
                                Text("REGISTER A WORKER OR SUBSCRIBER", style = MaterialTheme.typography.labelSmall, color = JackColors.Graphite)
                            }
                            Icon(Icons.Default.PersonAdd, contentDescription = null, tint = JackColors.Ink, modifier = Modifier.size(28.dp))
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
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Preview of how they will appear
                    JackCard(modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .background(tagColor(tag), MaterialTheme.shapes.small),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    name.trim().take(1).uppercase().ifEmpty { "?" },
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = JackColors.Paper
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    name.ifBlank { "New user" },
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (name.isBlank()) JackColors.Gray else JackColors.Ink,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    StatusChip(tag, tagColor(tag))
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "ID ${attendeeId.ifBlank { "—" }}",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontFamily = FontFamily.Monospace,
                                        color = JackColors.Graphite
                                    )
                                }
                            }
                            Text("PREVIEW", style = MaterialTheme.typography.labelSmall, color = JackColors.Gray)
                        }
                    }

                    UserTextField(
                        label = "1 · Full name",
                        value = name,
                        placeholder = "e.g. Ama Mensah",
                        active = activeField == "name",
                        monospace = false,
                        onClick = { activeField = "name" }
                    )
                    UserTextField(
                        label = "2 · Attendee ID",
                        value = attendeeId,
                        placeholder = "e.g. $nextFreeId",
                        active = activeField == "id",
                        monospace = true,
                        error = existingOwner?.let { "Already used by ${it.name} [${it.tag}]. Choose another ID." },
                        onClick = { activeField = "id" },
                        trailing = {
                            if (attendeeId.isBlank() || existingOwner != null) {
                                Surface(
                                    onClick = { attendeeId = nextFreeId; activeField = "id" },
                                    shape = MaterialTheme.shapes.extraSmall,
                                    color = JackColors.BlueTint,
                                    border = BorderStroke(1.dp, JackColors.Blue.copy(alpha = 0.5f))
                                ) {
                                    Text(
                                        "USE $nextFreeId",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = JackColors.Blue,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                                    )
                                }
                            }
                        }
                    )

                    // Keypad: letters for the name, digits first for the ID (key() resets its mode on switch).
                    key(activeField) {
                        CustomKeypad(
                            isNumericOnly = false,
                            startInNumberMode = activeField == "id",
                            onCharClick = { ch ->
                                saveError = null
                                if (activeField == "name") name += ch else attendeeId += ch
                            },
                            onBackspace = {
                                if (activeField == "name" && name.isNotEmpty()) name = name.dropLast(1)
                                else if (activeField == "id" && attendeeId.isNotEmpty()) attendeeId = attendeeId.dropLast(1)
                            },
                            onClear = {
                                if (activeField == "name") name = "" else attendeeId = ""
                            }
                        )
                    }

                    Text("3 · TAG", style = MaterialTheme.typography.labelMedium, color = JackColors.Ink)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        tagsList.forEach { t ->
                            val selected = t == tag
                            Surface(
                                onClick = { tag = t },
                                shape = MaterialTheme.shapes.small,
                                color = if (selected) tagColor(t) else JackColors.Paper,
                                border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) tagColor(t) else JackColors.Line)
                            ) {
                                Text(
                                    t.uppercase(),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (selected) JackColors.Paper else JackColors.Ink,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                                )
                            }
                        }
                    }

                    saveError?.let {
                        Text(it, style = MaterialTheme.typography.labelMedium, color = JackColors.Red)
                    }
                }

                // ---------- Action ----------
                Surface(color = JackColors.Paper, border = BorderStroke(1.dp, JackColors.Line)) {
                    Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        JackPrimaryButton(
                            text = when {
                                name.isBlank() -> "Enter a name"
                                attendeeId.isBlank() -> "Enter an Attendee ID"
                                existingOwner != null -> "ID already used"
                                else -> "Save user"
                            },
                            onClick = {
                                if (canSave) {
                                    if (db.addUser(name.trim(), attendeeId.trim(), tag) != -1L) onUserAdded()
                                    else saveError = "Could not save. Please try again."
                                }
                            },
                            enabled = canSave,
                            icon = Icons.Default.PersonAdd,
                            height = 64.dp,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

/** A tap-to-focus text box filled by Jack's keypad (blue border when active, red on error). */
@Composable
private fun UserTextField(
    label: String,
    value: String,
    placeholder: String,
    active: Boolean,
    monospace: Boolean,
    error: String? = null,
    onClick: () -> Unit,
    trailing: @Composable (() -> Unit)? = null
) {
    val edge = when {
        error != null -> JackColors.Red
        active -> JackColors.Blue
        else -> JackColors.Line
    }
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = JackColors.Paper,
        border = BorderStroke(if (active || error != null) 2.dp else 1.dp, edge),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = if (active) JackColors.Blue else JackColors.Graphite)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = value.ifEmpty { placeholder },
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = if (monospace) FontFamily.Monospace else null,
                        color = if (value.isEmpty()) JackColors.Gray else JackColors.Ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (active) {
                        Box(Modifier.padding(start = 2.dp).size(width = 2.dp, height = 20.dp).background(JackColors.Blue))
                    }
                }
                if (error != null) {
                    Text(error, style = MaterialTheme.typography.labelSmall, color = JackColors.Red)
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                trailing()
            }
        }
    }
}
