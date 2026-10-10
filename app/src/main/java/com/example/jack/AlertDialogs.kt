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

// User-defined alert rules: list and Add Alert dialog.

@Composable
fun AlertsConfigDialog(
    alertRules: List<AlertRule>,
    onAddClick: () -> Unit,
    onDeleteRule: (AlertRule) -> Unit,
    onDismiss: () -> Unit
) {
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
                Text("Manage Alerts (${alertRules.size})", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier.adminActivity()
                    .fillMaxWidth()
                    .heightIn(max = 380.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (alertRules.isEmpty()) {
                    Text(
                        text = "No custom alerts configured.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Gray,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(alertRules) { rule ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = rule.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "Trigger: \"${rule.triggerText}\"",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    IconButton(
                                        onClick = { onDeleteRule(rule) },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Delete Rule",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Button(
                    onClick = onAddClick,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Add New Alert")
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAlertDialog(
    existingRules: List<AlertRule>,
    onDismiss: () -> Unit,
    onSave: (name: String, trigger: String, toneUri: String) -> Unit
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var triggerText by remember { mutableStateOf("") }
    var activeField by remember { mutableStateOf("name") }
    var expanded by remember { mutableStateOf(false) }

    var tonesList by remember { mutableStateOf<List<ToneOption>>(emptyList()) }
    var isLoadingTones by remember { mutableStateOf(true) }
    var selectedTone by remember { mutableStateOf<ToneOption?>(null) }
    var currentRingtone by remember { mutableStateOf<android.media.Ringtone?>(null) }

    LaunchedEffect(Unit) {
        val loadedTones = withContext(Dispatchers.IO) {
            val list = mutableListOf<ToneOption>()
            try {
                val ringtoneManager = RingtoneManager(context)
                ringtoneManager.setType(RingtoneManager.TYPE_ALL)
                val cursor: Cursor = ringtoneManager.cursor
                while (cursor.moveToNext()) {
                    val title = cursor.getString(RingtoneManager.TITLE_COLUMN_INDEX)
                    val uriStr = ringtoneManager.getRingtoneUri(cursor.position).toString()
                    list.add(ToneOption(title = title, uri = uriStr))
                }
            } catch (_: Exception) {}
            if (list.isEmpty()) {
                list.add(ToneOption("Default Notification", RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION).toString()))
            }
            list
        }
        tonesList = loadedTones
        selectedTone = loadedTones.firstOrNull()
        isLoadingTones = false
    }

    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, flags)

                var displayName = "Custom Audio File"
                val cursor = context.contentResolver.query(uri, null, null, null, null)
                cursor?.use {
                    if (it.moveToFirst()) {
                        val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1) {
                            displayName = it.getString(nameIndex)
                        }
                    }
                }

                val customTone = ToneOption(title = "🎵 $displayName", uri = uri.toString())
                tonesList = listOf(customTone) + tonesList
                selectedTone = customTone

                currentRingtone?.stop()
                val ringtone = RingtoneManager.getRingtone(context, uri)
                currentRingtone = ringtone
                ringtone?.play()
            } catch (_: Exception) {}
        }
    }

    val trimmedTrigger = triggerText.trim()
    val isDuplicate = existingRules.any { it.triggerText.equals(trimmedTrigger, ignoreCase = true) }
    val canSave = name.isNotBlank() && trimmedTrigger.isNotBlank() && !isDuplicate

    DisposableEffect(Unit) {
        onDispose {
            try {
                currentRingtone?.stop()
            } catch (_: Exception) {}
        }
    }

    AlertDialog(
        onDismissRequest = {
            try {
                currentRingtone?.stop()
            } catch (_: Exception) {}
            onDismiss()
        },
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
                Text("Add Custom Alert Rule", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier.adminActivity()
                    .fillMaxWidth()
                    .heightIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Alert Name Box
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { activeField = "name" },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(2.dp, if (activeField == "name") MaterialTheme.colorScheme.primary else Color.Transparent)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text("Alert Name", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Text(
                            text = if (name.isEmpty()) "Tap to type name..." else name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (name.isEmpty()) Color.Gray else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // Trigger Text Box
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { activeField = "triggerText" },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(2.dp, if (activeField == "triggerText") MaterialTheme.colorScheme.primary else Color.Transparent)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text("ESP32 Trigger Text", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Text(
                            text = if (triggerText.isEmpty()) "Tap to type trigger..." else triggerText,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (triggerText.isEmpty()) Color.Gray else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                if (isDuplicate) {
                    Text("An alert with this trigger string already exists!", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                // Custom Touch Keypad
                CustomKeypad(
                    isNumericOnly = false,
                    onCharClick = { ch ->
                        if (activeField == "name") name += ch else triggerText += ch
                    },
                    onBackspace = {
                        if (activeField == "name" && name.isNotEmpty()) name = name.dropLast(1)
                        else if (activeField == "triggerText" && triggerText.isNotEmpty()) triggerText = triggerText.dropLast(1)
                    },
                    onClear = {
                        if (activeField == "name") name = "" else triggerText = ""
                    }
                )

                ExposedDropdownMenuBox(
                    expanded = expanded && !isLoadingTones,
                    onExpandedChange = { if (!isLoadingTones) expanded = !expanded }
                ) {
                    OutlinedTextField(
                        value = if (isLoadingTones) "Loading system tones..." else (selectedTone?.title ?: "Select Tone"),
                        onValueChange = {},
                        readOnly = true,
                        shape = RoundedCornerShape(12.dp),
                        leadingIcon = {
                            if (isLoadingTones) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                        },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = !isLoadingTones)
                            .fillMaxWidth()
                    )

                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("📁 Browse Audio File...", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) },
                            onClick = {
                                expanded = false
                                audioPickerLauncher.launch(arrayOf("audio/*"))
                            }
                        )
                        HorizontalDivider()

                        tonesList.forEach { tone ->
                            DropdownMenuItem(
                                text = { Text(tone.title) },
                                onClick = {
                                    selectedTone = tone
                                    expanded = false
                                    try {
                                        currentRingtone?.stop()
                                        val uri = tone.uri.toUri()
                                        val ringtone = RingtoneManager.getRingtone(context, uri)
                                        currentRingtone = ringtone
                                        ringtone?.play()
                                    } catch (_: Exception) {}
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (canSave) {
                        try {
                            currentRingtone?.stop()
                        } catch (_: Exception) {}
                        onSave(name.trim(), trimmedTrigger, selectedTone?.uri ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION).toString())
                    }
                },
                enabled = canSave,
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Add Alert")
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    try {
                        currentRingtone?.stop()
                    } catch (_: Exception) {}
                    onDismiss()
                }
            ) {
                Text("Cancel")
            }
        }
    )
}
