package com.flowstate.app.ui.home

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flowstate.app.ui.ActiColors
import com.flowstate.app.ui.SectionLabel
import com.flowstate.app.ui.Caption
import com.flowstate.app.ui.Stepper
import com.flowstate.data.SettingsStore
import com.flowstate.sensors.HeartRateMonitor
import com.flowstate.sensors.HrState

/**
 * Pairing a heart-rate monitor and setting max HR — the same two cards on screen A4 and
 * in the heart-rate sheet off Home, so the setup looks and behaves one way wherever it is
 * reached from. (The sheet was a leftover from the pre-redesign app: Material cards and
 * text buttons, a "Forget" as loud as "Connect now", and copy claiming heart rate never
 * changes the mode, which stopped being true when cycling and walking arrived.)
 *
 * Bluetooth is never switched on behind the user's back. If it is off the card says so
 * and offers the button; the scan starts once the radio is actually on.
 *
 * @param scanOnEntry A4 scans as soon as it opens — scanning is that screen's purpose.
 *   The sheet waits to be asked.
 */
@Composable
internal fun HeartRateSetup(
    monitor: HeartRateMonitor,
    settings: SettingsStore,
    scanOnEntry: Boolean,
) {
    val context = LocalContext.current
    val hrState by monitor.state.collectAsStateWithLifecycle()
    val reading by monitor.readings.collectAsStateWithLifecycle()
    val devices by monitor.scanResults.collectAsStateWithLifecycle()
    val bluetoothOn by monitor.bluetoothOn.collectAsStateWithLifecycle()
    val maxHr by settings.maxHr.collectAsStateWithLifecycle()

    val enableBluetooth = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { /* the radio's own broadcast starts the pending scan when it is really on */ }

    fun hasPermissions() = btPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    val requestPermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted -> if (granted.values.all { it }) monitor.requestScan() }

    fun scan() {
        if (!hasPermissions()) {
            requestPermissions.launch(btPermissions().toTypedArray())
        } else {
            monitor.requestScan()
        }
    }

    fun turnOnBluetooth() {
        monitor.requestScan() // queued until the radio reports it is on
        enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
    }

    DisposableEffect(Unit) {
        if (scanOnEntry && hrState !is HrState.Connected) scan()
        onDispose { monitor.stopScan() }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card {
            SectionLabel(if (hrState is HrState.Connected) "Your monitor" else "Nearby")
            Spacer(Modifier.height(10.dp))

            when (val s = hrState) {
                is HrState.Connected -> {
                    val bpm = reading?.bpm
                    DeviceRow(
                        name = s.name ?: "Monitor",
                        detail = bpm?.let { "$it bpm · ${it * 100 / maxHr.coerceAtLeast(1)}% of max" }
                            ?: "Connected, waiting for data",
                        dot = ActiColors.ok,
                        action = null,
                    )
                    Spacer(Modifier.height(10.dp))
                    PillButton("Disconnect", filled = false) { monitor.disconnect() }
                }

                is HrState.Connecting, is HrState.Reconnecting -> {
                    val name = (s as? HrState.Connecting)?.name ?: (s as? HrState.Reconnecting)?.name
                    DeviceRow(
                        name = name ?: "Monitor",
                        detail = if (s is HrState.Reconnecting) "Signal lost, reconnecting…" else "Connecting…",
                        dot = ActiColors.lift,
                        action = null,
                    )
                }

                is HrState.Scanning, is HrState.Disconnected -> {
                    val remembered = (s as? HrState.Disconnected)?.rememberedName
                    if (remembered != null && s !is HrState.Scanning) {
                        DeviceRow(
                            name = remembered,
                            detail = "Reconnects when a session starts",
                            dot = ActiColors.dim,
                            // Connecting needs the radio: with Bluetooth off the only
                            // useful button is the one that turns it on.
                            action = if (bluetoothOn) "Connect" to { monitor.connect(force = true) } else null,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    devices.filter { it.name != remembered || s is HrState.Scanning }.forEach { device ->
                        DeviceRow(
                            name = device.name ?: "Heart-rate monitor",
                            detail = device.address,
                            dot = ActiColors.lift,
                            action = "Connect" to { monitor.connectTo(device) },
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    when {
                        !bluetoothOn -> {
                            Text(
                                "Bluetooth is off.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = ActiColors.snow,
                            )
                            Spacer(Modifier.height(8.dp))
                            PillButton("Turn on Bluetooth", filled = true, onClick = ::turnOnBluetooth)
                        }
                        s is HrState.Scanning -> {
                            if (devices.isEmpty()) {
                                Text(
                                    "Scanning… watches can take ~20 s to appear.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = ActiColors.dim,
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                            PillButton("Stop scanning", filled = false) { monitor.stopScan() }
                        }
                        else -> PillButton(
                            if (remembered == null) "Find a monitor" else "Pair a different one",
                            filled = remembered == null,
                            onClick = ::scan,
                        )
                    }
                    if (remembered != null && s !is HrState.Scanning) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Forget $remembered",
                            color = ActiColors.dim,
                            fontSize = 14.sp,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable(role = Role.Button) { monitor.forget() }
                                .padding(horizontal = 4.dp, vertical = 14.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                "Garmin: Settings → Sensors & Accessories → Wrist Heart Rate → Broadcast.",
                style = MaterialTheme.typography.bodySmall,
                color = ActiColors.dim,
            )
        }

        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    SectionLabel("Max heart rate", color = ActiColors.snow)
                    Text(
                        "Roughly 220 minus your age, or your tested max.",
                        style = MaterialTheme.typography.bodySmall,
                        color = ActiColors.dim,
                    )
                }
                Stepper(
                    value = "$maxHr",
                    // Read the live value, not the composed one: holding the button
                    // repeats faster than a DataStore write recomposes this screen.
                    onDown = { settings.setMaxHr(settings.maxHr.value - 1) },
                    onUp = { settings.setMaxHr(settings.maxHr.value + 1) },
                    valueWidth = 56.dp,
                    label = "max heart rate",
                )
            }
        }

        Text(
            "Heart rate fine-tunes the energy within a mode. On a bike, a walk or a run " +
                "it also helps confirm you are really pushing. Everything works without it.",
            style = MaterialTheme.typography.bodySmall,
            color = ActiColors.dim,
        )
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(ActiColors.surface)
            .padding(16.dp),
    ) { content() }
}

@Composable
private fun DeviceRow(
    name: String,
    detail: String,
    dot: Color,
    action: Pair<String, () -> Unit>?,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(ActiColors.surfaceHigh)
            .then(
                if (action != null) {
                    Modifier.clickable(onClickLabel = "${action.first} $name", onClick = action.second)
                } else {
                    Modifier
                },
            )
            .padding(14.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, fontWeight = FontWeight.SemiBold, color = ActiColors.snow)
            Caption(detail)
        }
        if (action != null) {
            Text(action.first, fontWeight = FontWeight.Bold, color = ActiColors.riding)
        }
    }
}

@Composable
private fun PillButton(label: String, filled: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (filled) ActiColors.snow else ActiColors.surfaceHigh)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Text(
            label,
            fontWeight = FontWeight.Bold,
            color = if (filled) ActiColors.ground else ActiColors.snow,
        )
    }
}

internal fun btPermissions(): List<String> =
    if (Build.VERSION.SDK_INT >= 31) {
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        emptyList()
    }
