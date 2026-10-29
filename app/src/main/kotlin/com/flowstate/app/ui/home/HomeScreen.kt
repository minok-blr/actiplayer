package com.flowstate.app.ui.home

import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flowstate.app.ui.ActiBottomSheet
import com.flowstate.app.ui.ActiColors
import com.flowstate.app.ui.SectionLabel
import com.flowstate.app.ui.Caption
import com.flowstate.app.ui.Display
import com.flowstate.app.ui.glyph
import com.flowstate.app.ui.history.ModeStrip
import com.flowstate.app.ui.history.currentLocale
import com.flowstate.app.ui.history.format
import com.flowstate.app.ui.label
import com.flowstate.app.ui.mapping
import com.flowstate.app.ui.music.displayName
import com.flowstate.app.ui.music.playlistMembersByKey
import com.flowstate.app.ui.session.SessionUiState
import com.flowstate.app.ui.session.SessionViewModel
import com.flowstate.app.ui.spokenDuration
import com.flowstate.app.ui.usesTimer
import com.flowstate.data.EnergyResolver
import com.flowstate.data.LocalEnergies
import com.flowstate.data.MediaLibrary
import com.flowstate.data.PlaylistSummary
import com.flowstate.data.PoolRepository
import com.flowstate.data.RatingsRepository
import com.flowstate.data.SettingsStore
import com.flowstate.data.Track
import com.flowstate.data.db.EnergyLevel
import com.flowstate.engine.Activity
import com.flowstate.sensors.HeartRateMonitor
import com.flowstate.sensors.HrState
import com.flowstate.session.ModeSegment
import com.flowstate.session.SessionHistory

/** A pool under this is thin enough that the same songs will come round again. */
private const val MIN_HEALTHY_POOL = 8

/**
 * Screen B1 — the idle home.
 *
 * A launch checklist, not a dashboard: one enormous Start button, and above it only the
 * things that would make a session disappointing if they were wrong. A ready-check row
 * that can be fixed says how and goes there; one with nothing to fix is not a button.
 */
@Composable
fun HomeScreen(
    state: SessionUiState,
    vm: SessionViewModel,
    settings: SettingsStore,
    heartRate: HeartRateMonitor,
    library: MediaLibrary,
    ratings: RatingsRepository,
    pools: PoolRepository,
    history: SessionHistory,
    onOpenMusic: () -> Unit,
    onOpenRecap: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    onStarted: (Activity) -> Unit,
) {
    val context = LocalContext.current
    var sheet by remember { mutableStateOf<HomeSheet?>(null) }

    var tracks by remember { mutableStateOf<List<Track>?>(null) }
    var playlists by remember { mutableStateOf<List<PlaylistSummary>>(emptyList()) }
    LaunchedEffect(Unit) {
        tracks = library.tracks()
        playlists = library.playlists()
    }
    val ratingMap by ratings.ratings.collectAsStateWithLifecycle()
    val excluded by ratings.excluded.collectAsStateWithLifecycle()
    val poolSources by pools.sources.collectAsStateWithLifecycle()
    val activity by settings.activity.collectAsStateWithLifecycle()
    val hrState by heartRate.state.collectAsStateWithLifecycle()
    val hrReading by heartRate.readings.collectAsStateWithLifecycle()
    val lastSession by history.latest.collectAsStateWithLifecycle(initialValue = null)
    val locale = currentLocale()
    val maxHr by settings.maxHr.collectAsStateWithLifecycle()

    // Location services can be switched off from the quick settings at any time, and a
    // session then runs blind on motion alone — so this is re-read whenever Home returns.
    var gpsOn by remember { mutableStateOf(context.gpsEnabled()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { gpsOn = context.gpsEnabled() }

    var lastStrip by remember { mutableStateOf<List<ModeSegment>>(emptyList()) }
    LaunchedEffect(lastSession?.id) {
        lastStrip = lastSession?.let { history.recap(it.id)?.second?.segments }.orEmpty()
    }

    val counts = tracks?.let {
        EnergyResolver.trackCounts(
            it,
            LocalEnergies(ratingMap, excluded),
            poolSources,
            playlistMembersByKey(playlists),
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
        ) {
            // A wordmark, not a monospace label: the name set the way a product sets it.
            Text(
                "ActiPlayer",
                fontFamily = Display,
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.4).sp,
                color = ActiColors.riding,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.weight(1f))
            // Plan §1 puts Settings behind an avatar. There is no account to put a face or
            // initials on, so it is the settings mark — never someone else's initials.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(ActiColors.surfaceHigh)
                    .clickable(onClickLabel = "Settings", role = Role.Button, onClick = onOpenSettings),
            ) {
                Icon(Icons.Rounded.Settings, contentDescription = "Settings", tint = ActiColors.snow)
            }
        }

        ActivityHeadline(activity) { sheet = HomeSheet.ACTIVITY }
        Spacer(Modifier.height(14.dp))
        StartButton {
            // Clock-driven activities pick their plan first, so they must not start here.
            if (!activity.usesTimer()) vm.start()
            onStarted(activity)
        }
        Spacer(Modifier.height(22.dp))

        SectionLabel("Ready check", Modifier.semantics { heading() })
        Spacer(Modifier.height(8.dp))

        val poolTotal = counts?.values?.sum() ?: 0
        val empty = counts?.let { c -> EnergyLevel.entries.filter { (c[it] ?: 0) == 0 } }.orEmpty()
        val thin = counts?.let { c ->
            EnergyLevel.entries.filter { (c[it] ?: 0) in 1 until MIN_HEALTHY_POOL }
        }.orEmpty()
        ReadyRow(
            dot = when {
                counts == null -> ActiColors.dim
                poolTotal == 0 || empty.isNotEmpty() || thin.isNotEmpty() -> ActiColors.hot
                else -> ActiColors.ok
            },
            title = when {
                counts == null -> "Music"
                poolTotal == 0 -> "Music · no pools yet"
                else -> "Music · ${poolSources.size} ${if (poolSources.size == 1) "source" else "sources"}"
            },
            detail = when {
                counts == null -> "Scanning…"
                poolTotal == 0 -> "Everything plays at mid until you sort some"
                else -> EnergyLevel.entries.reversed().joinToString(" · ") {
                    "${it.displayName} ${counts[it] ?: 0}"
                }
            },
            // Plan §4: warn when a pool is too small. Say what it will actually do to the
            // music, not just that a number is low.
            warning = when {
                counts == null || poolTotal == 0 -> null
                empty.isNotEmpty() ->
                    "${empty.joinToString(" and ") { it.displayName }} is empty, so those " +
                        "modes borrow from the nearest pool"
                thin.isNotEmpty() ->
                    "${thin.joinToString(" and ") { it.displayName }} has under " +
                        "$MIN_HEALTHY_POOL tracks, so songs will repeat"
                else -> null
            },
            onClick = onOpenMusic,
        )
        Spacer(Modifier.height(8.dp))
        val remembered = (hrState as? HrState.Disconnected)?.rememberedName
        ReadyRow(
            dot = if (hrState is HrState.Connected) ActiColors.ok else ActiColors.dim,
            title = when (val s = hrState) {
                is HrState.Connected -> s.name ?: "Heart rate"
                is HrState.Disconnected -> s.rememberedName ?: "Heart rate · optional"
                is HrState.Connecting -> s.name ?: "Heart rate"
                is HrState.Reconnecting -> s.name ?: "Heart rate"
                HrState.Scanning -> "Heart rate"
            },
            detail = when {
                // Live bpm here means the strap can be checked at a glance, without
                // opening the sheet and without starting a session to see a number.
                hrState is HrState.Connected && hrReading != null ->
                    "${hrReading!!.bpm} bpm · max $maxHr bpm"
                hrState is HrState.Connected -> "Connected, waiting for data"
                hrState is HrState.Connecting || hrState is HrState.Reconnecting -> "Connecting…"
                hrState is HrState.Disconnected && remembered == null ->
                    "None paired · tap to add a strap or watch"
                else -> "Connects at start · max $maxHr bpm"
            },
            onClick = { sheet = HomeSheet.HEART_RATE },
        )
        Spacer(Modifier.height(8.dp))
        val sensors = buildList {
            add("Motion")
            if (state.hasBarometer) add("barometer")
            if (gpsOn) add("GPS")
        }.joinToString(" · ")
        ReadyRow(
            dot = if (gpsOn) ActiColors.ok else ActiColors.hot,
            title = if (gpsOn) "Sensors" else "Sensors · location is off",
            detail = sensors,
            warning = if (gpsOn) null else "No speed or altitude. Tap to turn location on.",
            // With location on there is nothing to fix here, so the row is not a button.
            onClick = if (gpsOn) {
                null
            } else {
                {
                    context.startActivity(
                        Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            },
        )

        Spacer(Modifier.height(22.dp))
        SectionLabel("Last session", Modifier.semantics { heading() })
        Spacer(Modifier.height(8.dp))
        val last = lastSession
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(ActiColors.surface)
                .then(
                    if (last != null) {
                        Modifier.clickable(onClickLabel = "Open recap") { onOpenRecap(last.id) }
                    } else {
                        Modifier
                    },
                )
                .padding(16.dp),
        ) {
            Column(Modifier.weight(1f)) {
                // As drawn: "Sat · Snowboarding · 3h 12m" on one line, the mode strip below.
                Text(
                    if (last == null) {
                        "Nothing recorded yet"
                    } else {
                        "${last.startedAtEpochMs.format("EEE d MMM", locale)} · " +
                            "${last.activity.label()} · ${last.durationMs.spokenDuration()}"
                    },
                    fontWeight = FontWeight.Bold,
                    color = ActiColors.snow,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (last == null) {
                    Caption("Your first session will show up here")
                } else if (lastStrip.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    ModeStrip(lastStrip, last.activity, Modifier.fillMaxWidth().height(8.dp))
                }
            }
            if (last != null) {
                Spacer(Modifier.width(8.dp))
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = ActiColors.dim,
                )
            }
        }
        Spacer(Modifier.height(32.dp))
    }

    when (sheet) {
        HomeSheet.ACTIVITY -> ActiBottomSheet({ sheet = null }, containerColor = ActiColors.ground) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 32.dp)) {
                ActivityPicker(settings, onPicked = {})
            }
        }
        HomeSheet.HEART_RATE -> ActiBottomSheet({ sheet = null }, containerColor = ActiColors.ground) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 32.dp)) {
                HeartRateSetup(heartRate, settings, scanOnEntry = false)
            }
        }
        null -> Unit
    }
}

private enum class HomeSheet { ACTIVITY, HEART_RATE }

private fun Context.gpsEnabled(): Boolean {
    val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
    return runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)
}

/**
 * The whole card is the target for changing activity: "Change" alone was a 28 dp word in
 * the corner, a fraction of the 48 dp plan §5 asks for off-session.
 */
@Composable
private fun ActivityHeadline(activity: Activity, onChange: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(ActiColors.surface)
            .clickable(onClickLabel = "Change activity", onClick = onChange)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Activity")
            Spacer(Modifier.weight(1f))
            Text("Change", color = ActiColors.lift, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // A ring in the activity's own colour — the same channel the session uses.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(44.dp).clip(CircleShape).background(ActiColors.riding),
            ) {
                Icon(
                    activity.glyph,
                    contentDescription = null,
                    tint = ActiColors.ground,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    activity.label(),
                    fontFamily = Display,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = ActiColors.snow,
                )
                Caption(activity.mapping())
            }
        }
    }
}

/** 96 dp of unmissable, because it is pressed with gloves on before pocketing the phone. */
@Composable
private fun StartButton(onStart: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(ActiColors.riding)
            .clickable(onClickLabel = "Start session", role = Role.Button, onClick = onStart)
            .padding(horizontal = 22.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "Start session",
                fontFamily = Display,
                fontSize = 26.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.3).sp,
                color = ActiColors.ground,
            )
            Text(
                "Pocket it. We'll do the rest.",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                // One line or the button grows past its 96 dp and the play icon shifts.
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = ActiColors.ground.copy(alpha = 0.72f),
            )
        }
        Icon(
            Icons.Rounded.PlayArrow,
            contentDescription = null,
            tint = ActiColors.ground,
            modifier = Modifier.size(40.dp),
        )
    }
}

@Composable
private fun ReadyRow(
    dot: Color,
    title: String,
    detail: String,
    onClick: (() -> Unit)?,
    warning: String? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(ActiColors.surface)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = ActiColors.snow)
            Caption(detail)
            if (warning != null) {
                Caption(warning, color = ActiColors.hot, modifier = Modifier.padding(top = 2.dp))
            }
        }
        if (onClick != null) {
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = ActiColors.dim,
            )
        }
    }
}
