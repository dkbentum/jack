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
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Bluetooth
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

/** What a terminal line is, for colouring and filtering. */
private enum class LogKind { DEVICE, ALERT, WARNING, JACK }

private fun logKindOf(line: String): LogKind {
    val msg = line.substringAfter("] ", line)
    return when {
        msg.startsWith("🔔") -> LogKind.ALERT
        msg.startsWith("⚠️") || msg.startsWith("❌") -> LogKind.WARNING
        // Lines from the ESP32 are logged exactly as received (no emoji tag in front).
        msg.isNotEmpty() && msg[0].code < 0x2000 -> LogKind.DEVICE
        else -> LogKind.JACK
    }
}

/**
 * v3.7: full-screen TERMINAL. Dark log pane with coloured lines, filters (All / ESP32 / Alerts /
 * Warnings), follow-latest toggle, quick ESP32 commands and a command box for testing firmware.
 */
@Composable
fun TerminalLogsDialog(
    logLines: List<String>,
    onClearLogs: () -> Unit,
    onDismiss: () -> Unit,
    connected: Boolean = false,
    onSendCommand: (String) -> Unit = {}
) {
    val listState = rememberLazyListState()
    var filter by remember { mutableStateOf<LogKind?>(null) }   // null = all
    var follow by remember { mutableStateOf(true) }
    var command by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }

    val shown = remember(logLines.size, logLines.lastOrNull(), filter) {
        if (filter == null) logLines.toList() else logLines.filter { logKindOf(it) == filter }
    }

    LaunchedEffect(shown.size, follow) {
        if (follow && shown.isNotEmpty()) listState.scrollToItem(shown.size - 1)
    }

    fun send(cmd: String) {
        val c = cmd.trim()
        if (c.isEmpty() || !connected) return
        onSendCommand(c)
        command = ""
        follow = true
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
                                Text("TERMINAL", style = MaterialTheme.typography.headlineSmall, color = JackColors.Ink)
                                Text("ESP32 MESSAGES & JACK EVENTS", style = MaterialTheme.typography.labelSmall, color = JackColors.Graphite)
                            }
                            StatusChip("${logLines.size} lines", JackColors.Ink)
                        }
                        StripeBar()
                    }
                }

                // ---------- Filters ----------
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TerminalFilterChip("All", filter == null, JackColors.Ink) { filter = null }
                    TerminalFilterChip("ESP32", filter == LogKind.DEVICE, JackColors.Green) { filter = LogKind.DEVICE }
                    TerminalFilterChip("Alerts", filter == LogKind.ALERT, JackColors.Red) { filter = LogKind.ALERT }
                    TerminalFilterChip("Warnings", filter == LogKind.WARNING, JackColors.Amber) { filter = LogKind.WARNING }
                    TerminalFilterChip("Jack", filter == LogKind.JACK, JackColors.Blue) { filter = LogKind.JACK }
                }

                // ---------- Log pane ----------
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = Color(0xFF101010),
                    border = BorderStroke(1.dp, JackColors.Steel)
                ) {
                    Box {
                        if (shown.isEmpty()) {
                            Column(
                                modifier = Modifier.align(Alignment.Center),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(Icons.Default.Terminal, contentDescription = null, tint = JackColors.Graphite, modifier = Modifier.size(40.dp))
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    if (filter == null) "WAITING FOR DATA…" else "NOTHING HERE YET",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = JackColors.Gray
                                )
                            }
                        } else {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(12.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                items(shown) { line -> TerminalLine(line) }
                            }
                        }

                        // Follow toggle (bottom-right of the pane)
                        Surface(
                            onClick = { follow = !follow },
                            shape = MaterialTheme.shapes.extraSmall,
                            color = if (follow) JackColors.Blue else JackColors.Steel,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(10.dp)
                        ) {
                            Text(
                                if (follow) "▼ FOLLOWING" else "❚❚ PAUSED",
                                style = MaterialTheme.typography.labelSmall,
                                color = JackColors.Paper,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                // ---------- Send to ESP32 ----------
                Surface(color = JackColors.Paper, border = BorderStroke(1.dp, JackColors.Line), modifier = Modifier.padding(top = 12.dp)) {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("SEND TO ESP32", style = MaterialTheme.typography.labelMedium, color = JackColors.Ink, modifier = Modifier.weight(1f))
                            if (!connected) StatusChip("Not connected", JackColors.Red)
                        }
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf("PING", "CHARGE", "DISCHARGE").forEach { c ->
                                JackOutlineButton(text = c, onClick = { send(c) }, enabled = connected)
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = command,
                                onValueChange = { command = it.replace("\n", "") },
                                placeholder = { Text("Type a command…", fontFamily = FontFamily.Monospace) },
                                singleLine = true,
                                enabled = connected,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                shape = MaterialTheme.shapes.small,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = JackColors.Blue,
                                    unfocusedBorderColor = JackColors.Line,
                                    cursorColor = JackColors.Blue
                                ),
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send),
                                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { send(command) }),
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            JackPrimaryButton(
                                text = "Send",
                                onClick = { send(command) },
                                enabled = connected && command.isNotBlank(),
                                height = 56.dp
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Commands are listed in ESP32_COMMANDS.md.",
                                style = HintStyle,
                                modifier = Modifier.weight(1f)
                            )
                            JackOutlineButton(
                                text = "Clear log",
                                onClick = { confirmClear = true },
                                icon = Icons.Default.Delete,
                                color = JackColors.Red,
                                enabled = logLines.isNotEmpty()
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = JackColors.Paper,
            shape = MaterialTheme.shapes.medium,
            title = { Text("CLEAR THE TERMINAL?", style = MaterialTheme.typography.titleLarge, color = JackColors.Ink) },
            text = { Text("Removes the ${logLines.size} lines shown here. Attendance and settings are not affected.", style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                JackPrimaryButton(text = "Clear", color = JackColors.Red, height = 48.dp, onClick = {
                    confirmClear = false
                    onClearLogs()
                })
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("CANCEL", style = MaterialTheme.typography.labelLarge, color = JackColors.Ink) } }
        )
    }
}

/** One log line: dim time stamp + coloured message. */
@Composable
private fun TerminalLine(line: String) {
    val kind = logKindOf(line)
    val color = when (kind) {
        LogKind.ALERT -> Color(0xFFFF6B6B)
        LogKind.WARNING -> Color(0xFFFFC94D)
        LogKind.DEVICE -> Color(0xFF5BE38A)
        LogKind.JACK -> Color(0xFFB8C7E0)
    }
    val time = if (line.startsWith("[")) line.substringBefore("] ", "").removePrefix("[") else ""
    val msg = if (time.isNotEmpty()) line.substringAfter("] ") else line
    Row {
        if (time.isNotEmpty()) {
            Text(
                time,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = Color(0xFF6E6E6E),
                modifier = Modifier.padding(end = 8.dp, top = 1.dp)
            )
        }
        Text(
            msg,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = if (kind == LogKind.ALERT) FontWeight.Bold else FontWeight.Normal,
            color = color
        )
    }
}

@Composable
private fun TerminalFilterChip(label: String, selected: Boolean, color: Color, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.extraSmall,
        color = if (selected) color else JackColors.Paper,
        border = BorderStroke(1.dp, if (selected) color else JackColors.Line)
    ) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!selected) {
                Box(Modifier.size(8.dp).background(color, CircleShape))
                Spacer(Modifier.width(6.dp))
            }
            Text(label.uppercase(), style = MaterialTheme.typography.labelMedium, color = if (selected) JackColors.Paper else JackColors.Ink)
        }
    }
}

/**
 * v3.6: full-screen ESP32 CONNECTION page. Live status panel, Bluetooth / USB link tiles,
 * a short tip for the chosen link, and one big Connect / Cancel / Disconnect button.
 * The page now stays open so you can watch it connect (close it with ✕ or Back).
 */
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
    val isBle = transportMode == TransportMode.BLE
    val busy = connectionStatus == ConnectionState.SCANNING || connectionStatus == ConnectionState.CONNECTING

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
                            JackIconButton(Icons.Default.Close, "Close", onDismiss)
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("ESP32 CONNECTION", style = MaterialTheme.typography.headlineSmall, color = JackColors.Ink)
                                Text("LINK BETWEEN JACK AND THE WORKSHOP CONTROLLER", style = MaterialTheme.typography.labelSmall, color = JackColors.Graphite, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    ConnectionStatusPanel(connectionStatus, deviceName, if (isBle) "Bluetooth" else "USB")

                    SectionLabel("Link type")
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        LinkTile(
                            title = "Bluetooth",
                            detail = "Wireless · looks for ESP32_Device",
                            icon = Icons.Default.Bluetooth,
                            selected = isBle,
                            enabled = true,
                            onClick = { onTransportChange(TransportMode.BLE) },
                            modifier = Modifier.weight(1f)
                        )
                        LinkTile(
                            title = "USB cable",
                            detail = if (hasUsbHost) "Wired · via OTG adapter" else "Not supported on this tablet",
                            icon = Icons.Default.Usb,
                            selected = !isBle,
                            enabled = hasUsbHost,
                            onClick = { onTransportChange(TransportMode.USB) },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // Tip for the chosen link
                    JackCard(modifier = Modifier.fillMaxWidth(), color = JackColors.BlueTint, borderColor = JackColors.Blue.copy(alpha = 0.4f)) {
                        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                            Icon(Icons.Default.Info, contentDescription = null, tint = JackColors.Blue, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(
                                if (isBle) {
                                    "Bluetooth and Location must both be on (Android 9 needs Location to find Bluetooth devices). Keep the ESP32 powered and within a few metres."
                                } else {
                                    "Plug the ESP32 into the tablet's USB port with an OTG adapter, then allow Jack when Android asks for USB permission."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = JackColors.Steel
                            )
                        }
                    }

                    Text(
                        "Tip: on the dashboard, tap the ESP32 strip to connect and hold it to open this page.",
                        style = HintStyle
                    )
                }

                // ---------- Action ----------
                Surface(color = JackColors.Paper, border = BorderStroke(1.dp, JackColors.Line)) {
                    Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        when {
                            connectionStatus == ConnectionState.CONNECTED -> JackPrimaryButton(
                                text = "Disconnect",
                                onClick = onDisconnectClick,
                                icon = Icons.Default.Power,
                                color = JackColors.Red,
                                height = 64.dp,
                                modifier = Modifier.fillMaxWidth()
                            )
                            busy -> JackOutlineButton(
                                text = "Cancel connecting",
                                onClick = onDisconnectClick,
                                modifier = Modifier.fillMaxWidth().height(64.dp)
                            )
                            else -> JackPrimaryButton(
                                text = if (isBle) "Scan & connect" else "Connect USB",
                                onClick = onConnectClick,
                                icon = if (isBle) Icons.Default.Bluetooth else Icons.Default.Usb,
                                height = 64.dp,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Black status panel with a status light that pulses while searching/connecting. */
@Composable
private fun ConnectionStatusPanel(status: ConnectionState, deviceName: String, transportLabel: String) {
    val (color, word) = when (status) {
        ConnectionState.CONNECTED -> JackColors.Green to "CONNECTED"
        ConnectionState.CONNECTING -> JackColors.Amber to "CONNECTING…"
        ConnectionState.SCANNING -> JackColors.Amber to "SEARCHING…"
        ConnectionState.DISCONNECTED -> JackColors.Red to "OFFLINE"
    }
    val pulsing = status == ConnectionState.SCANNING || status == ConnectionState.CONNECTING
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "pulse")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(700),
            androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = JackColors.Ink,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(JackColors.Steel, MaterialTheme.shapes.small),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .background(color.copy(alpha = if (pulsing) pulse else 1f), CircleShape)
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("ESP32 STATUS", style = MaterialTheme.typography.labelSmall, color = JackColors.Gray)
                Text(word, style = MaterialTheme.typography.headlineSmall, color = color)
                Text(
                    "$deviceName  ·  $transportLabel",
                    style = MaterialTheme.typography.labelMedium,
                    color = JackColors.Line,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** Big selectable tile for a link type. */
@Composable
private fun LinkTile(
    title: String,
    detail: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val edge = when {
        !enabled -> JackColors.Line
        selected -> JackColors.Blue
        else -> JackColors.Line
    }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (selected && enabled) JackColors.BlueTint else JackColors.Paper,
        border = BorderStroke(if (selected) 2.dp else 1.dp, edge),
        modifier = modifier
            .heightIn(min = 132.dp)
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (!enabled) JackColors.Gray else if (selected) JackColors.Blue else JackColors.Ink,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(Modifier.weight(1f))
                if (selected && enabled) {
                    Icon(Icons.Default.CheckCircle, contentDescription = "Selected", tint = JackColors.Blue, modifier = Modifier.size(22.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) JackColors.Ink else JackColors.Gray
            )
            Text(detail, style = MaterialTheme.typography.bodySmall, color = JackColors.Graphite)
        }
    }
}
