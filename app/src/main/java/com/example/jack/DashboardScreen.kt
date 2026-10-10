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

// Main dashboard screen, connection settings dialog and serial terminal log.

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ESP32SerialScreen(
    modifier: Modifier = Modifier,
    connectionStatus: ConnectionState,
    alertRules: List<AlertRule>,
    onConnectClick: () -> Unit,
    onOpenConfigDialog: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAlerts: () -> Unit,
    onOpenTerminal: () -> Unit,
    onMarkAttendance: () -> Unit,
    deviceName: String = "",
    transportLabel: String = ""
) {
    val snapshot = rememberAttendanceSnapshot()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(JackColors.Concrete)
    ) {
        // ---------------- Top bar: brand + admin icons ----------------
        Surface(color = JackColors.Paper) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BrandLogo(size = 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "TRIPLE DIMENSION",
                            style = MaterialTheme.typography.headlineSmall.copy(letterSpacing = 1.sp),
                            color = JackColors.Ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "JACK  ·  WORKSHOP KIOSK",
                            style = MaterialTheme.typography.labelMedium,
                            color = JackColors.Graphite,
                            maxLines = 1
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        JackIconButton(Icons.Default.Terminal, "Serial terminal log", onOpenTerminal)
                        JackIconButton(Icons.Default.Notifications, "Manage alerts", onOpenAlerts, badge = alertRules.isNotEmpty())
                        JackIconButton(Icons.Default.Settings, "Settings", onOpenSettings)
                    }
                }
                ConnectionStrip(
                    status = connectionStatus,
                    deviceName = deviceName,
                    transportLabel = transportLabel,
                    modifier = Modifier.combinedClickable(
                        onClick = onConnectClick,
                        onLongClick = onOpenConfigDialog
                    )
                )
                StripeBar()
            }
        }

        // ---------------- Body: ONE scrolling page ----------------
        // Clock, check-in panel and counters scroll away; the photo grid flows below them at full size.
        val people = snapshot?.inBuilding.orEmpty()
        val fullWidth: androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope.() -> androidx.compose.foundation.lazy.grid.GridItemSpan =
            { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }

        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
            columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(minSize = 150.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            item(span = fullWidth) { ClockHeader() }

            // Main action: check in / out
            item(span = fullWidth) {
                Surface(
                    color = JackColors.Ink,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = JackColors.Blue, modifier = Modifier.size(28.dp))
                            Spacer(Modifier.width(10.dp))
                            Text("WORKSHOP ATTENDANCE", style = MaterialTheme.typography.headlineSmall, color = JackColors.Paper)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Enter your ID, then take a selfie to check in or out.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = JackColors.Line
                        )
                        Spacer(Modifier.height(16.dp))
                        JackPrimaryButton(
                            text = "Check in / out",
                            onClick = onMarkAttendance,
                            icon = Icons.Default.CameraAlt,
                            height = 64.dp,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Shortcut: press VOLUME UP",
                            style = MaterialTheme.typography.labelSmall,
                            color = JackColors.Gray,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                    }
                }
            }

            // Live numbers
            item(span = fullWidth) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile(
                        label = "In the building",
                        value = snapshot?.inBuilding?.size?.toString() ?: "–",
                        valueColor = JackColors.Green,
                        modifier = Modifier.weight(1f)
                    )
                    StatTile(
                        label = "Visits today",
                        value = snapshot?.visitsToday?.toString() ?: "–",
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Who is in the building
            item(span = fullWidth) {
                Box(modifier = Modifier.padding(top = 8.dp)) { RosterHeader(count = people.size) }
            }
            if (snapshot != null && people.isEmpty()) {
                item(span = fullWidth) { RosterEmptyState() }
            } else {
                items(people.size, key = { people[it].first.id }) { index ->
                    val (record, user) = people[index]
                    PersonTile(record, user)
                }
            }
        }
    }
}

/** Full-width ESP32 connection status. Tap = connect, long-press = connection settings. */
@Composable
private fun ConnectionStrip(
    status: ConnectionState,
    deviceName: String,
    transportLabel: String,
    modifier: Modifier = Modifier
) {
    val (color, label) = when (status) {
        ConnectionState.CONNECTED -> JackColors.Green to "CONNECTED"
        ConnectionState.CONNECTING -> JackColors.Amber to "CONNECTING"
        ConnectionState.SCANNING -> JackColors.Amber to "SEARCHING"
        ConnectionState.DISCONNECTED -> JackColors.Red to "DISCONNECTED"
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.08f))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color, CircleShape)
        )
        Spacer(Modifier.width(10.dp))
        Text("ESP32  ", style = MaterialTheme.typography.labelLarge, color = JackColors.Graphite)
        Text(label, style = MaterialTheme.typography.labelLarge, color = color)
        val detail = listOf(deviceName, transportLabel).filter { it.isNotBlank() }.joinToString("  ·  ")
        if (detail.isNotBlank()) {
            Text(
                text = "  ·  $detail",
                style = MaterialTheme.typography.labelMedium,
                color = JackColors.Graphite,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        Text(
            text = if (status == ConnectionState.CONNECTED) "HOLD FOR OPTIONS" else "TAP TO CONNECT",
            style = MaterialTheme.typography.labelSmall,
            color = JackColors.Gray
        )
    }
}

/** Big kiosk clock and date. */
@Composable
private fun ClockHeader() {
    val now by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            kotlinx.coroutines.delay(1_000L)
        }
    }
    val time = remember(now / 60_000) {
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(now))
    }
    val date = remember(now / 60_000) {
        java.text.SimpleDateFormat("EEEE, d MMMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(now))
    }
    Row(verticalAlignment = Alignment.Bottom) {
        Text(time, style = MaterialTheme.typography.displayLarge, color = JackColors.Ink)
        Spacer(Modifier.width(14.dp))
        Text(
            date.uppercase(),
            style = MaterialTheme.typography.titleSmall.copy(letterSpacing = 1.sp),
            color = JackColors.Graphite,
            modifier = Modifier.padding(bottom = 12.dp)
        )
    }
}

@Composable
fun TerminalLogsDialog(
    logLines: List<String>,
    onClearLogs: () -> Unit,
    onDismiss: () -> Unit
) {
    val listState = rememberLazyListState()

    LaunchedEffect(logLines.size) {
        if (logLines.isNotEmpty()) {
            listState.animateScrollToItem(logLines.size - 1)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Terminal,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text("Serial Terminal Logs", fontWeight = FontWeight.Bold)
                }
                IconButton(onClick = onClearLogs, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Clear Logs",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        },
        text = {
            Surface(
                modifier = Modifier.adminActivity()
                    .fillMaxWidth()
                    .height(350.dp),
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF1E1E1E),
                tonalElevation = 4.dp
            ) {
                if (logLines.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Waiting for data stream...",
                            color = Color.Gray,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(logLines) { line ->
                            // Red: a message that set off one of the user's alerts. Amber: Jack warnings/errors.
                            val lineColor = when {
                                line.contains("🔔") -> Color(0xFFFF5252)
                                line.contains("⚠️") || line.contains("❌") -> Color(0xFFFFC107)
                                else -> Color(0xFF00E676)
                            }
                            Text(
                                text = line,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                fontWeight = if (line.contains("🔔")) FontWeight.Bold else FontWeight.Normal,
                                color = lineColor
                            )
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConnectionConfigDialog(
    connectionStatus: ConnectionState,
    deviceName: String,
    transportMode: TransportMode,
    hasUsbHost: Boolean,
    onTransportChange: (TransportMode) -> Unit,
    onConnectClick: () -> Unit,
    onDisconnectClick: () -> Unit,
    onDismiss: () -> Unit
) {
    val isConnected = connectionStatus == ConnectionState.CONNECTED

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Power,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text("Connection Settings", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column {
                    Text("Status: ${connectionStatus.name}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text("Device: $deviceName", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                HorizontalDivider()

                Text("Select Transport Mode", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = transportMode == TransportMode.USB,
                        onClick = { onTransportChange(TransportMode.USB) },
                        enabled = hasUsbHost,
                        label = { Text("USB Serial") },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = transportMode == TransportMode.BLE,
                        onClick = { onTransportChange(TransportMode.BLE) },
                        label = { Text("Bluetooth BLE") },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isConnected) {
                    Button(
                        onClick = {
                            onDisconnectClick()
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Disconnect")
                    }
                } else {
                    Button(
                        onClick = {
                            onConnectClick()
                            onDismiss()
                        },
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(if (transportMode == TransportMode.BLE) "Scan & Connect" else "Connect USB")
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}
