package com.flowstate.app.ui.home

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flowstate.app.ui.ActiColors
import com.flowstate.app.ui.ArtTile
import com.flowstate.app.ui.ScreenHeader
import com.flowstate.app.ui.energyTint
import com.flowstate.data.MediaLibrary
import com.flowstate.data.MusicSource
import com.flowstate.data.RatingsRepository
import com.flowstate.data.SettingsStore
import com.flowstate.data.SpotifyRatingsRepository
import com.flowstate.engine.Activity
import com.flowstate.engine.FeatureFrame
import com.flowstate.engine.RideState
import com.flowstate.playback.spotify.SpotifyRemote
import com.flowstate.sensors.HeartRateMonitor
import com.flowstate.sensors.HrState
import com.flowstate.session.OverrideMode
import com.flowstate.session.SessionCoordinator

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
    ratings: RatingsRepository,
    spotifyRemote: SpotifyRemote,
    spotifyRatings: SpotifyRatingsRepository,
    onShowGuide: () -> Unit = {},
) {
    val state by coordinator.state.collectAsStateWithLifecycle()
    if (state.active) {
        RideBoard(coordinator)
    } else {
        IdleScreen(
            coordinator, heartRate, settings, library, ratings,
            spotifyRemote, spotifyRatings, onShowGuide,
        )
    }
}

// ------------------------------------------------------------------ idle / lodge screen

@Composable
private fun IdleScreen(
    coordinator: SessionCoordinator,
    heartRate: HeartRateMonitor,
    settings: SettingsStore,
    library: MediaLibrary,
    ratings: RatingsRepository,
    spotifyRemote: SpotifyRemote,
    spotifyRatings: SpotifyRatingsRepository,
    onShowGuide: () -> Unit,
) {
    val state by coordinator.state.collectAsStateWithLifecycle()
    val ratingMap by ratings.ratings.collectAsStateWithLifecycle()
    var trackCount by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) { trackCount = library.loadTracks().size }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenHeader("Ride", "music that follows what you're doing") {
            IconButton(onClick = onShowGuide) {
                Icon(
                    Icons.AutoMirrored.Rounded.HelpOutline,
                    contentDescription = "Quick guide",
                    tint = ActiColors.dim,
                )
            }
        }
        Column(
            Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
        Button(
            onClick = { coordinator.startSession() },
            modifier = Modifier
                .fillMaxWidth()
                .height(76.dp),
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = ActiColors.riding,
                contentColor = ActiColors.ground,
            ),
        ) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = null, Modifier.size(34.dp))
            Spacer(Modifier.width(8.dp))
            Text("Start session", fontSize = 20.sp, fontWeight = FontWeight.Black)
        }
        Text(
            "Start, pocket the phone, ride. The music follows what you're doing.",
            style = MaterialTheme.typography.bodySmall,
            color = ActiColors.dim,
            modifier = Modifier.padding(horizontal = 4.dp),
        )

        ActivityCard(settings)

        MusicSourceCard(settings, spotifyRemote, spotifyRatings)

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
        Spacer(Modifier.height(8.dp))
        }
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
                color = ActiColors.dim,
            )
        }
    }
}

@Composable
private fun MusicSourceCard(
    settings: SettingsStore,
    spotifyRemote: SpotifyRemote,
    spotifyRatings: SpotifyRatingsRepository,
) {
    val selected by settings.musicSource.collectAsStateWithLifecycle()
    val spotifyState by spotifyRemote.state.collectAsStateWithLifecycle()
    val ratedSpotify by spotifyRatings.tracks.collectAsStateWithLifecycle()

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeader("Music source")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = selected == MusicSource.LOCAL,
                    onClick = { settings.setMusicSource(MusicSource.LOCAL) },
                    label = { Text("This phone") },
                )
                FilterChip(
                    selected = selected == MusicSource.SPOTIFY,
                    onClick = { settings.setMusicSource(MusicSource.SPOTIFY) },
                    label = { Text("Spotify") },
                )
            }
            Text(
                when (selected) {
                    MusicSource.LOCAL ->
                        "Plays your on-device library with full adaptive fades."
                    MusicSource.SPOTIFY -> {
                        val connected = spotifyState !is SpotifyRemote.State.Disconnected
                        buildString {
                            append("Drives the Spotify app by energy band — ")
                            append("track switches are hard cuts, and it needs Premium, ")
                            append("the Spotify app, and network. ")
                            append(
                                when {
                                    !connected -> "Connect Spotify in the Remote tab first."
                                    ratedSpotify.isEmpty() ->
                                        "Rate some playlist tracks in the Remote tab first."
                                    else -> "${ratedSpotify.size} tracks rated and ready."
                                },
                            )
                        }
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = ActiColors.dim,
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
            color = ActiColors.dim,
            letterSpacing = 1.5.sp,
        )
        HorizontalDivider(Modifier.padding(top = 6.dp), color = ActiColors.outline)
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
                Text("●", color = if (ok) ActiColors.ok else ActiColors.dim, fontSize = 10.sp)
                Text(value, style = MaterialTheme.typography.titleSmall)
            }
            Text(hint, style = MaterialTheme.typography.bodySmall, color = ActiColors.dim)
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
        RideState.RIDING, RideState.EFFORT -> ActiColors.riding
        RideState.LIFT, RideState.CRUISE -> ActiColors.lift
        RideState.PAUSED, null -> ActiColors.paused
    }

    Column(
        Modifier
            .fillMaxSize()
            // Spotify album-page trick: the mode color washes down into the ground.
            .background(
                Brush.verticalGradient(
                    0f to modeColor.copy(alpha = 0.30f),
                    0.45f to ActiColors.ground,
                ),
            )
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "SESSION · ${state.activity.name}",
                style = MaterialTheme.typography.labelMedium,
                color = ActiColors.dim,
                letterSpacing = 1.5.sp,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { coordinator.stopSession() }) { Text("End") }
        }

        // Mode block: the color IS the message — readable through goggles.
        Column(
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(listOf(modeColor, modeColor.copy(alpha = 0.75f))),
                    RoundedCornerShape(22.dp),
                )
                .padding(horizontal = 20.dp, vertical = 22.dp),
        ) {
            Text(
                mode?.name ?: "STARTING",
                fontSize = 40.sp,
                fontWeight = FontWeight.Black,
                color = ActiColors.ground,
                lineHeight = 42.sp,
            )
            Text(
                state.decision?.reason ?: "listening to sensors…",
                style = MaterialTheme.typography.bodySmall,
                color = ActiColors.ground.copy(alpha = 0.8f),
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        BandMeter(state.effectiveBand, state.override, Modifier.padding(top = 16.dp))

        Spacer(Modifier.weight(1f))

        SectionHeader("Now playing")
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.padding(top = 10.dp),
        ) {
            if (state.source == MusicSource.SPOTIFY) {
                val spotifyNow by coordinator.spotifyQueue.nowPlaying.collectAsStateWithLifecycle()
                val spotifyError by coordinator.spotifyQueue.error.collectAsStateWithLifecycle()
                ArtTile(ActiColors.spotify, size = 58.dp)
                Column {
                    Text(
                        spotifyNow ?: "sending tracks to Spotify…",
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 25.sp,
                    )
                    Text(
                        spotifyError ?: "via Spotify",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (spotifyError != null) ActiColors.hot else ActiColors.dim,
                    )
                }
            } else {
                ArtTile(energyTint(nowPlaying?.rating), size = 58.dp)
                Column {
                    Text(
                        nowPlaying?.track?.title ?: "picking a track…",
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 25.sp,
                    )
                    Text(
                        nowPlaying?.let { "${it.track.artist} · rated ${it.rating}" } ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ActiColors.dim,
                    )
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(top = 12.dp),
        ) {
            HrChip(state.lastFrame)
            TextButton(onClick = { showSignals = !showSignals }) {
                Text(if (showSignals) "Hide signals" else "Signals", color = ActiColors.dim)
            }
            Spacer(Modifier.weight(1f))
            FilledIconButton(
                onClick = { coordinator.skip() },
                modifier = Modifier.size(52.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = ActiColors.snow,
                    contentColor = ActiColors.ground,
                ),
            ) {
                Icon(Icons.Rounded.SkipNext, contentDescription = "Skip", Modifier.size(30.dp))
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
                            containerColor = ActiColors.snow,
                            contentColor = ActiColors.ground,
                        )
                    } else {
                        ButtonDefaults.buttonColors(
                            containerColor = ActiColors.surface,
                            contentColor = ActiColors.dim,
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
                            if (level in band) ActiColors.snow else ActiColors.outline,
                            RoundedCornerShape(4.dp),
                        ),
                )
            }
        }
        Text(
            "ENERGY ${band.first}–${band.last}" +
                if (override != OverrideMode.AUTO) " · PINNED BY ${override.name}" else "",
            style = MaterialTheme.typography.labelSmall,
            color = ActiColors.dim,
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
            .background(ActiColors.surfaceHigh, RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text("●", color = if (hot) ActiColors.riding else ActiColors.lift, fontSize = 9.sp)
        Text(
            "$bpm bpm" + if (pct != null) " · ${(pct * 100).toInt()}%" else "",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = if (hot) ActiColors.hot else ActiColors.snow,
        )
    }
}

@Composable
private fun SignalsReadout(f: FeatureFrame?, hasBarometer: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .background(ActiColors.surface, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (!hasBarometer) {
            Text(
                "no barometer — GPS altitude fallback",
                style = MaterialTheme.typography.labelSmall,
                color = ActiColors.dim,
            )
        }
        Text("motionRms ${f?.motionRms.fmt()} m/s²", style = MaterialTheme.typography.bodySmall, color = ActiColors.dim)
        Text("vertRate ${f?.vertRate.fmt()} m/s", style = MaterialTheme.typography.bodySmall, color = ActiColors.dim)
        Text("speed ${f?.speed.fmt()} m/s", style = MaterialTheme.typography.bodySmall, color = ActiColors.dim)
        Text(
            "hr ${f?.hrBpm?.toString() ?: "—"} bpm · trend ${f?.hrTrend.fmt()} bpm/min",
            style = MaterialTheme.typography.bodySmall,
            color = ActiColors.dim,
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
                        Text("●", color = ActiColors.ok, fontSize = 10.sp)
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
                        color = ActiColors.dim,
                    )
                    TextButton(onClick = { monitor.disconnect() }) { Text("Disconnect") }
                }

                is HrState.Scanning -> {
                    Text(
                        "Scanning… on a Garmin, make sure Broadcast Heart Rate is ON " +
                            "(Settings → Sensors & Accessories → Wrist Heart Rate).",
                        style = MaterialTheme.typography.bodySmall,
                        color = ActiColors.dim,
                    )
                    if (scanResults.isEmpty()) {
                        Text(
                            "Nothing yet — watches can take ~20 s to appear.",
                            style = MaterialTheme.typography.bodySmall,
                            color = ActiColors.dim,
                        )
                    }
                    scanResults.forEach { device ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(ActiColors.surfaceHigh, RoundedCornerShape(10.dp))
                                .clickable { monitor.connectTo(device) }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                        ) {
                            Text("●", color = ActiColors.lift, fontSize = 9.sp)
                            Column(Modifier.padding(start = 10.dp)) {
                                Text(
                                    device.name ?: "Unknown device",
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    device.address,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ActiColors.dim,
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            Text(
                                "Connect",
                                color = ActiColors.riding,
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
                            Text("●", color = ActiColors.dim, fontSize = 10.sp)
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
                            color = ActiColors.dim,
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
                    color = ActiColors.dim,
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
