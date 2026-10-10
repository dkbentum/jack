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

@Composable
fun UserManagementDialog(
    context: Context,
    onDismiss: () -> Unit
) {
    val db = remember { AttendanceDatabase.get(context) }
    var users by remember { mutableStateOf(db.getAllUsers()) }
    // IDs shared by more than one user (only possible for users added before v2.2).
    val duplicateIds = remember(users) { UserIdRules.findDuplicates(users.map { it.attendeeId }) }

    var showAddUserDialog by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text("Workers & Subscribers (${users.size})", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier.adminActivity()
                    .fillMaxWidth()
                    .heightIn(max = 440.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = { showAddUserDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Add New User / Worker")
                }

                if (duplicateIds.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "⚠️ ${duplicateIds.size} Attendee ID(s) are used by more than one person. " +
                                "Those people can't check in until you delete the extra entries (marked below) and re-add them with a new ID.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }

                if (users.isEmpty()) {
                    Text(
                        text = "No users or subscribers registered yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Gray,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(users) { user ->
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
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = user.name,
                                                style = MaterialTheme.typography.bodyLarge,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = when (user.tag.lowercase()) {
                                                    "worker" -> Color(0xFF4CAF50).copy(alpha = 0.15f)
                                                    "subscriber" -> Color(0xFF2196F3).copy(alpha = 0.15f)
                                                    else -> MaterialTheme.colorScheme.primaryContainer
                                                }
                                            ) {
                                                Text(
                                                    text = user.tag,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = when (user.tag.lowercase()) {
                                                        "worker" -> Color(0xFF4CAF50)
                                                        "subscriber" -> Color(0xFF2196F3)
                                                        else -> MaterialTheme.colorScheme.primary
                                                    },
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(2.dp))
                                        val isDuplicate = UserIdRules.normalize(user.attendeeId) in duplicateIds
                                        Text(
                                            text = if (isDuplicate) "Attendee ID: ${user.attendeeId}  ⚠️ DUPLICATE" else "Attendee ID: ${user.attendeeId}",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = if (isDuplicate) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isDuplicate) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            db.deleteUser(user.id)
                                            users = db.getAllUsers()
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Delete",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(18.dp)
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

    val tagsList = listOf("Worker", "Subscriber", "Admin", "Guest", "Staff")
    var tagExpanded by remember { mutableStateOf(false) }

    // Attendee IDs must be unique (ignoring case/spaces) so a check-in always matches exactly one person.
    val existingOwner = remember(attendeeId) {
        if (attendeeId.isBlank()) null else db.getUserByAttendeeId(attendeeId.trim())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PersonAdd,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text("Add User / Worker / Subscriber", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier.adminActivity()
                    .fillMaxWidth()
                    .heightIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Name Box
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { activeField = "name" },
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(2.dp, if (activeField == "name") MaterialTheme.colorScheme.primary else Color.Transparent)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text("Full Name", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Text(
                            text = if (name.isEmpty()) "Tap to type name..." else name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (name.isEmpty()) Color.Gray else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // Attendee ID Box
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { activeField = "id" },
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(2.dp, if (activeField == "id") MaterialTheme.colorScheme.primary else Color.Transparent)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text("Attendee ID", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Text(
                            text = if (attendeeId.isEmpty()) "Tap to type ID..." else attendeeId,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (attendeeId.isEmpty()) Color.Gray else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                if (existingOwner != null) {
                    Text(
                        text = "⚠️ ID \"${attendeeId.trim()}\" is already used by ${existingOwner.name} [${existingOwner.tag}]. Choose another ID.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                // Tag Dropdown
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { tagExpanded = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Tag: $tag", fontWeight = FontWeight.Bold)
                    }
                    DropdownMenu(
                        expanded = tagExpanded,
                        onDismissRequest = { tagExpanded = false }
                    ) {
                        tagsList.forEach { t ->
                            DropdownMenuItem(
                                text = { Text(t) },
                                onClick = {
                                    tag = t
                                    tagExpanded = false
                                }
                            )
                        }
                    }
                }

                // Global Custom Touch Keypad
                CustomKeypad(
                    isNumericOnly = false,
                    onCharClick = { ch ->
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
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank() && attendeeId.isNotBlank() && existingOwner == null) {
                        if (db.addUser(name.trim(), attendeeId.trim(), tag) != -1L) onUserAdded()
                    }
                },
                enabled = name.isNotBlank() && attendeeId.isNotBlank() && existingOwner == null,
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Save User")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
