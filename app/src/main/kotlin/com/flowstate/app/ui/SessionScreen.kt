package com.flowstate.app.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flowstate.app.ble.HeartRateMonitor
import com.flowstate.app.ble.HrState
import com.flowstate.app.data.MediaLibrary
import com.flowstate.app.data.RatingsStore
import com.flowstate.app.data.SettingsStore
import com.flowstate.app.session.OverrideMode
import com.flowstate.app.session.SessionCoordinator
import com.flowstate.engine.Activity
import com.flowstate.engine.FeatureFrame
import com.flowstate.engine.RideState

/**
 * Two screens in one tab (brief §2.2: in-session interaction count should be zero):
 *  - idle -> a launch checklist: start button + readiness cards + first-run quick guide;
 *  - active -> the Ride Board: full-bleed mode color, band meter, big bottom override
 *    chips in thumb reach. Telemetry is demoted behind a "Signals" toggle.
 */
@Composable
fun SessionScreen(
    coordinator: SessionCoordinator,
    heartRate: HeartRateMonitor,
    settings: SettingsStore,
    library: MediaLibrary,
    ratings: RatingsStore,
) {
    val state by coordinator.state.collectAsStateWithLifecycle()
    if (state.active) {
        RideBoard(coordinator)
    } else {
        IdleScreen(coordinator, heartRate, settings, library, ratings)
    }
}

// ------------------------------------------------------------------ idle / lodge screen

@Composable
private fun IdleScreen(
    coordinator: SessionCoordinator,
    heartRate: HeartRateMonitor,
    settings: SettingsStore,
    library: MediaLibrary,
    ratings: RatingsStore,
) {
    val state by coordinator.state.collectAsStateWithLifecycle()
    val ratingMap by ratings.ratings.collectAsStateWithLifecycle()
    var trackCount by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) { trackCount = library.loadTracks().size }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(
            onClick = { coordinator.startSession() },
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text("Start session", fontSize = 20.sp, fontWeight = FontWeight.Black)
        }
        Text(
            "Start, pocket the phone, ride. The music follows what you're doing.",
            style = MaterialTheme.typography.bodySmall,
            color = FlowColors.dim,
            modifier = Modifier.padding(horizontal = 4.dp),
        )

        ActivityCard(settings)

        val rated = trackCount?.let { total -> ratingMap.keys.size.coerceAtMost(total) }
        ReadinessCard(
            title = "Library",
            ok = (rated ?: 0) > 0,
            value = when {
                trackCount == null -> "Scanning…"
                trackCount == 0 -> "No music on this device"
                else -> "$trackCount tracks · ${rated ?: 0} rated"
            },
            hint = when {
                trackCount == 0 -> "Copy music into the Music/ folder, then reopen the app."
                (rated ?: 0) == 0 ->
                    "Rate a few tracks in the Library tab first — some 1–2 (chill) and " +
                        "some 4–5 (send it) — so the engine has something to choose between."
                else -> "Unrated tracks play as middle energy (3)."
            },
        )

        HeartRateCard(heartRate, settings, state.lastFrame)

        val hasBarometer = state.hasBarometer
        ReadinessCard(
            title = "Sensors",
            ok = true,
            value = if (hasBarometer) "Motion · Barometer · GPS" else "Motion · GPS",
            hint = if (hasBarometer) {
                "All signals available."
            } else {
                "No barometer on this phone — using GPS altitude for lift detection " +
                    "(a bit slower, still works)."
            },
        )
    }
}

/** Implemented activity profiles; the rest are visible but marked "soon". */
private val readyActivities = setOf(Activity.SNOWBOARDING, Activity.CYCLING)

@Composable
private fun ActivityCard(settings: SettingsStore) {
    val selected by settings.activity.collectAsStateWithLifecycle()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeader("Activity")
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                Activity.entries.forEach { activity ->
                    val ready = activity in readyActivities
                    FilterChip(
                        selected = selected == activity,
                        onClick = { settings.setActivity(activity) },
                        enabled = ready,
                        label = {
                            Text(
                                activity.name.lowercase()
                                    .replaceFirstChar { it.uppercase() } +
                                    if (!ready) " · soon" else "",
                            )
                        },
                    )
                }
            }
            Text(
                when (selected) {
                    Activity.CYCLING ->
                        "Cruising under ~15 km/h keeps it chill; pushing the pace (speed " +
                            "and pulse climbing together) brings the hype."
                    else ->
                        "Riding gets the hype, the chairlift chills out, standing around " +
                            "stays mid."
                },
                style = MaterialTheme.typography.bodySmall,
                color = FlowColors.dim,
            )
        }
    }
}

/** Uppercase card-section title with an underline — the visual boundary for each section. */
@Composable
private fun SectionHeader(title: String) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = FlowColors.dim,
            letterSpacing = 1.5.sp,
        )
        HorizontalDivider(Modifier.padding(top = 6.dp), color = FlowColors.outline)
    }
}

@Composable
private fun ReadinessCard(title: String, ok: Boolean, value: String, hint: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionHeader(title)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("●", color = if (ok) FlowColors.ok else FlowColors.dim, fontSize = 10.sp)
                Text(value, style = MaterialTheme.typography.titleSmall)
            }
            Text(hint, style = MaterialTheme.typography.bodySmall, color = FlowColors.dim)
        }
    }
}

// ------------------------------------------------------------------ ride board

@Composable
private fun RideBoard(coordinator: SessionCoordinator) {
    val state by coordinator.state.collectAsStateWithLifecycle()
    val nowPlaying by coordinator.queue.nowPlaying.collectAsStateWithLifecycle()
    var showSignals by remember { mutableStateOf(false) }

    val mode = state.decision?.state
    val modeColor = when (mode) {
        RideState.RIDING, RideState.EFFORT -> FlowColors.riding
        RideState.LIFT, RideState.CRUISE -> FlowColors.lift
        RideState.PAUSED, null -> FlowColors.paused
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "SESSION · ${state.activity.name}",
                style = MaterialTheme.typography.labelMedium,
                color = FlowColors.dim,
                letterSpacing = 1.5.sp,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { coordinator.stopSession() }) { Text("End") }
        }

        // Mode block: the color IS the message — readable through goggles.
        Column(
            Modifier
                .fillMaxWidth()
                .background(modeColor, RoundedCornerShape(18.dp))
                .padding(horizontal = 20.dp, vertical = 22.dp),
        ) {
            Text(
                mode?.name ?: "STARTING",
                fontSize = 40.sp,
                fontWeight = FontWeight.Black,
                color = FlowColors.ground,
                lineHeight = 42.sp,
            )
            Text(
                state.decision?.reason ?: "listening to sensors…",
                style = MaterialTheme.typography.bodySmall,
                color = FlowColors.ground.copy(alpha = 0.8f),
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        BandMeter(state.effectiveBand, state.override, Modifier.padding(top = 16.dp))

        Spacer(Modifier.weight(1f))

        SectionHeader("Now playing")
        Text(
            nowPlaying?.track?.title ?: "picking a track…",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 26.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            nowPlaying?.let { "${it.track.artist} · rated ${it.rating}" } ?: "",
            style = MaterialTheme.typography.bodyMedium,
            color = FlowColors.dim,
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(top = 12.dp),
        ) {
            HrChip(state.lastFrame)
            TextButton(onClick = { showSignals = !showSignals }) {
                Text(if (showSignals) "Hide signals" else "Signals", color = FlowColors.dim)
            }
            Spacer(Modifier.weight(1f))
            FilledTonalButton(
                onClick = { coordinator.queue.skip() },
                shape = RoundedCornerShape(50),
            ) {
                Text("Skip ›", fontWeight = FontWeight.Bold)
            }
        }

        if (showSignals) {
            SignalsReadout(state.lastFrame, state.hasBarometer, Modifier.padding(top = 8.dp))
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
        ) {
            OverrideMode.entries.forEach { m ->
                val selected = state.override == m
                Button(
                    onClick = { coordinator.setOverride(m) },
                    modifier = Modifier
                        .weight(1f)
                        .height(64.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = if (selected) {
                        ButtonDefaults.buttonColors(
                            containerColor = FlowColors.snow,
                            contentColor = FlowColors.ground,
                        )
                    } else {
                        ButtonDefaults.buttonColors(
                            containerColor = FlowColors.surface,
                            contentColor = FlowColors.dim,
                        )
                    },
                ) {
                    Text(m.name, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                }
            }
        }
    }
}

@Composable
private fun BandMeter(band: IntRange, override: OverrideMode, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            (1..5).forEach { level ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(8.dp)
                        .background(
                            if (level in band) FlowColors.snow else FlowColors.outline,
                            RoundedCornerShape(4.dp),
                        ),
                )
            }
        }
        Text(
            "ENERGY ${band.first}–${band.last}" +
                if (override != OverrideMode.AUTO) " · PINNED BY ${override.name}" else "",
            style = MaterialTheme.typography.labelSmall,
            color = FlowColors.dim,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun HrChip(frame: FeatureFrame?) {
    val bpm = frame?.hrBpm ?: return
    val pct = frame.hrPctMax
    val hot = pct != null && pct >= 0.85
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .background(FlowColors.surfaceHigh, RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text("●", color = if (hot) FlowColors.riding else FlowColors.lift, fontSize = 9.sp)
        Text(
            "$bpm bpm" + if (pct != null) " · ${(pct * 100).toInt()}%" else "",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = if (hot) FlowColors.hot else FlowColors.snow,
        )
    }
}

@Composable
private fun SignalsReadout(f: FeatureFrame?, hasBarometer: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .background(FlowColors.surface, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (!hasBarometer) {
            Text(
                "no barometer — GPS altitude fallback",
                style = MaterialTheme.typography.labelSmall,
                color = FlowColors.dim,
            )
        }
        Text("motionRms ${f?.motionRms.fmt()} m/s²", style = MaterialTheme.typography.bodySmall, color = FlowColors.dim)
        Text("vertRate ${f?.vertRate.fmt()} m/s", style = MaterialTheme.typography.bodySmall, color = FlowColors.dim)
        Text("speed ${f?.speed.fmt()} m/s", style = MaterialTheme.typography.bodySmall, color = FlowColors.dim)
        Text(
            "hr ${f?.hrBpm?.toString() ?: "—"} bpm · trend ${f?.hrTrend.fmt()} bpm/min",
            style = MaterialTheme.typography.bodySmall,
            color = FlowColors.dim,
        )
    }
}

// ------------------------------------------------------------------ heart rate card

@Composable
private fun HeartRateCard(
    monitor: HeartRateMonitor,
    settings: SettingsStore,
    lastFrame: FeatureFrame?,
) {
    val hrState by monitor.state.collectAsStateWithLifecycle()
    val scanResults by monitor.scanResults.collectAsStateWithLifecycle()
    val maxHr by settings.maxHr.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Bluetooth off is the top setup trap: instead of silently doing nothing, ask the
    // system to turn it on and start scanning as soon as the user agrees.
    val btEnableLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) monitor.startScan()
    }

    fun startScanOrEnableBluetooth() {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager)
            .adapter ?: return
        if (adapter.isEnabled) {
            monitor.startScan()
        } else {
            btEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }
    }

    val btLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.values.all { it }) startScanOrEnableBluetooth()
    }

    fun scanWithPermissions() {
        val missing = btPermissions().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startScanOrEnableBluetooth()
        } else {
            btLauncher.launch(missing.toTypedArray())
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeader("Heart rate")

            when (val s = hrState) {
                is HrState.Connected -> {
                    val bpm = lastFrame?.hrBpm
                    val pct = lastFrame?.hrPctMax
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("●", color = FlowColors.ok, fontSize = 10.sp)
                        Text(
                            when {
                                bpm != null && pct != null ->
                                    "${s.name ?: "Monitor"} — $bpm bpm · ${(pct * 100).toInt()}% of max"
                                bpm != null -> "${s.name ?: "Monitor"} — $bpm bpm"
                                else -> "${s.name ?: "Monitor"} — waiting for data"
                            },
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                    Text(
                        "Heart rate nudges song energy within a mode — it never changes the mode.",
                        style = MaterialTheme.typography.bodySmall,
                        color = FlowColors.dim,
                    )
                    TextButton(onClick = { monitor.disconnect() }) { Text("Disconnect") }
                }

                is HrState.Scanning -> {
                    Text(
                        "Scanning… on a Garmin, make sure Broadcast Heart Rate is ON " +
                            "(Settings → Sensors & Accessories → Wrist Heart Rate).",
                        style = MaterialTheme.typography.bodySmall,
                        color = FlowColors.dim,
                    )
                    if (scanResults.isEmpty()) {
                        Text(
                            "Nothing yet — watches can take ~20 s to appear.",
                            style = MaterialTheme.typography.bodySmall,
                            color = FlowColors.dim,
                        )
                    }
                    scanResults.forEach { device ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(FlowColors.surfaceHigh, RoundedCornerShape(10.dp))
                                .clickable { monitor.connectTo(device) }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                        ) {
                            Text("●", color = FlowColors.lift, fontSize = 9.sp)
                            Column(Modifier.padding(start = 10.dp)) {
                                Text(
                                    device.name ?: "Unknown device",
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    device.address,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = FlowColors.dim,
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            Text(
                                "Connect",
                                color = FlowColors.riding,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                    TextButton(onClick = { monitor.stopScan() }) { Text("Stop scan") }
                }

                is HrState.Connecting ->
                    Text("Connecting to ${s.name ?: "monitor"}…")

                is HrState.Reconnecting ->
                    Text("Signal lost — reconnecting to ${s.name ?: "monitor"}…")

                is HrState.Disconnected -> {
                    if (s.rememberedName != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("●", color = FlowColors.dim, fontSize = 10.sp)
                            Text(
                                "${s.rememberedName} — reconnects at session start",
                                style = MaterialTheme.typography.titleSmall,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { monitor.connect() }) { Text("Connect now") }
                            TextButton(onClick = { monitor.forget() }) { Text("Forget") }
                        }
                    } else {
                        Text(
                            "Optional: pair a BLE heart-rate monitor and redlining mid-run " +
                                "narrows the music to your highest-rated tracks.",
                            style = MaterialTheme.typography.bodySmall,
                            color = FlowColors.dim,
                        )
                        TextButton(onClick = { scanWithPermissions() }) { Text("Set up monitor") }
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Max HR: $maxHr", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { settings.setMaxHr(maxHr - 5) }) { Text("−5") }
                TextButton(onClick = { settings.setMaxHr(maxHr + 5) }) { Text("+5") }
                Text(
                    "≈ 220 − age",
                    style = MaterialTheme.typography.labelSmall,
                    color = FlowColors.dim,
                )
            }
        }
    }
}

private fun btPermissions(): List<String> =
    if (Build.VERSION.SDK_INT >= 31) {
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        emptyList()
    }

private fun Double?.fmt(): String = this?.let { "%.2f".format(it) } ?: "—"
