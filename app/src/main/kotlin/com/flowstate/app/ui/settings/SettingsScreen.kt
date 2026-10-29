package com.flowstate.app.ui.settings

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flowstate.app.ui.ActiBottomSheet
import com.flowstate.app.ui.ActiColors
import com.flowstate.app.ui.BackLink
import com.flowstate.app.ui.SectionLabel
import com.flowstate.app.ui.Segmented
import com.flowstate.app.ui.Stepper
import com.flowstate.app.ui.home.HeartRateSetup
import com.flowstate.data.Sensitivity
import com.flowstate.data.SettingsStore
import com.flowstate.data.SourceKind
import com.flowstate.data.SpotifyRatingsRepository
import com.flowstate.data.Units
import com.flowstate.playback.spotify.SpotifyRemote
import com.flowstate.sensors.HeartRateMonitor
import com.flowstate.sensors.HrState

/**
 * Screen D3 — Settings, grouped the way the design draws it: You / Playback / Detection,
 * with setup and the version at the end.
 *
 * Until now these lived in a bottom sheet of mismatched cards off the Home avatar, with
 * max heart rate in a different sheet again; the plan's D3 was never built. The Account
 * group (Pro, JSON export) arrives with phases 8 and 10 — a row that does nothing today
 * would be a stub.
 */
@Composable
fun SettingsScreen(
    settings: SettingsStore,
    heartRate: HeartRateMonitor,
    spotifyRemote: SpotifyRemote,
    spotifyRatings: SpotifyRatingsRepository,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val maxHr by settings.maxHr.collectAsStateWithLifecycle()
    val units by settings.units.collectAsStateWithLifecycle()
    val source by settings.musicSource.collectAsStateWithLifecycle()
    val crossfadeMs by settings.crossfadeMs.collectAsStateWithLifecycle()
    val pauseOnDisconnect by settings.pauseOnDisconnect.collectAsStateWithLifecycle()
    val sensitivity by settings.sensitivity.collectAsStateWithLifecycle()
    val showSignals by settings.showSignals.collectAsStateWithLifecycle()
    val hrState by heartRate.state.collectAsStateWithLifecycle()
    val spotifyState by spotifyRemote.state.collectAsStateWithLifecycle()
    val ratedSpotify by spotifyRatings.tracks.collectAsStateWithLifecycle()
    var hrSheet by remember { mutableStateOf(false) }

    val version = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "—"
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        BackLink("Home", onBack, Modifier.padding(top = 8.dp))
        Text(
            "Settings",
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.padding(top = 4.dp, bottom = 18.dp).semantics { heading() },
        )

        Group("You") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Max heart rate", fontWeight = FontWeight.SemiBold, color = ActiColors.snow)
                    Hint("Roughly 220 minus your age")
                }
                Stepper(
                    value = "$maxHr",
                    onDown = { settings.setMaxHr(settings.maxHr.value - 1) },
                    onUp = { settings.setMaxHr(settings.maxHr.value + 1) },
                    valueWidth = 52.dp,
                    valueSize = 18.sp,
                    buttonSize = 44.dp,
                    label = "max heart rate",
                )
            }
            Divider()
            NavRow(
                title = "Heart-rate monitor",
                value = when (val s = hrState) {
                    is HrState.Connected -> s.name ?: "Connected"
                    is HrState.Disconnected -> s.rememberedName ?: "None"
                    is HrState.Connecting -> s.name ?: "Connecting…"
                    is HrState.Reconnecting -> s.name ?: "Reconnecting…"
                    HrState.Scanning -> "Scanning…"
                },
            ) { hrSheet = true }
            Divider()
            Column(Modifier.padding(16.dp)) {
                Text("Units", fontWeight = FontWeight.SemiBold, color = ActiColors.snow)
                Spacer(Modifier.height(10.dp))
                Segmented(
                    options = Units.entries.toList(),
                    selected = units,
                    label = { if (it == Units.METRIC) "Metric · km/h" else "Imperial · mph" },
                    onSelect = settings::setUnits,
                )
            }
        }

        Group("Playback") {
            Column(Modifier.padding(16.dp)) {
                Text("Music source", fontWeight = FontWeight.SemiBold, color = ActiColors.snow)
                Spacer(Modifier.height(10.dp))
                Segmented(
                    options = SourceKind.entries.toList(),
                    selected = source,
                    label = { if (it == SourceKind.LOCAL) "This phone" else "Spotify" },
                    onSelect = settings::setMusicSource,
                )
                Spacer(Modifier.height(8.dp))
                Hint(
                    when (source) {
                        SourceKind.LOCAL -> "Your on-device library, with full crossfades."
                        SourceKind.SPOTIFY -> buildString {
                            append("Drives the Spotify app by energy — hard cuts, and it needs ")
                            append("Premium and a signal. ")
                            append(
                                when {
                                    spotifyState is SpotifyRemote.State.Disconnected ->
                                        "Connect Spotify under Music → Streaming first."
                                    ratedSpotify.isEmpty() ->
                                        "Rate some playlist tracks under Music → Streaming first."
                                    else -> "${ratedSpotify.size} tracks rated and ready."
                                },
                            )
                        }
                    },
                )
            }
            Divider()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Crossfade", fontWeight = FontWeight.SemiBold, color = ActiColors.snow)
                    Hint("Instant cut going up, gentle when easing off")
                }
                Stepper(
                    value = "%.1f s".format(crossfadeMs / 1000f),
                    onDown = { settings.setCrossfadeMs(settings.crossfadeMs.value - 500) },
                    onUp = { settings.setCrossfadeMs(settings.crossfadeMs.value + 500) },
                    valueWidth = 64.dp,
                    valueSize = 16.sp,
                    buttonSize = 44.dp,
                    label = "crossfade",
                )
            }
            Divider()
            ToggleRow(
                title = "Pause when headphones unplug",
                hint = "Or when a Bluetooth headset drops — nothing out of the speaker",
                checked = pauseOnDisconnect,
                onChange = settings::setPauseOnDisconnect,
            )
        }

        Group("Detection") {
            Column(Modifier.padding(16.dp)) {
                Text("Switch sensitivity", fontWeight = FontWeight.SemiBold, color = ActiColors.snow)
                Hint("How sure before easing down. Getting hyped is always instant.")
                Spacer(Modifier.height(10.dp))
                Segmented(
                    options = Sensitivity.entries.toList(),
                    selected = sensitivity,
                    label = { it.label },
                    onSelect = settings::setSensitivity,
                )
                Spacer(Modifier.height(8.dp))
                Hint(sensitivity.blurb)
            }
            Divider()
            ToggleRow(
                title = "Show live signals in session",
                hint = "Adds the raw telemetry under the sentence",
                checked = showSignals,
                onChange = settings::setShowSignals,
            )
        }

        Group("App") {
            NavRow(title = "Run setup again", value = null) {
                settings.setOnboardingComplete(false)
            }
            Divider()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp),
            ) {
                Text("Version", fontWeight = FontWeight.SemiBold, color = ActiColors.snow, modifier = Modifier.weight(1f))
                Text(version, color = ActiColors.dim)
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (hrSheet) {
        ActiBottomSheet({ hrSheet = false }, containerColor = ActiColors.ground) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 32.dp)) {
                HeartRateSetup(heartRate, settings, scanOnEntry = false)
            }
        }
    }
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    SectionLabel(title, Modifier.padding(start = 4.dp, bottom = 8.dp).semantics { heading() })
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(ActiColors.surface),
    ) { content() }
    Spacer(Modifier.height(22.dp))
}

@Composable
private fun Divider() = HorizontalDivider(color = ActiColors.outline)

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = ActiColors.dim)
}

@Composable
private fun NavRow(title: String, value: String?, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
    ) {
        Text(title, fontWeight = FontWeight.SemiBold, color = ActiColors.snow, modifier = Modifier.weight(1f))
        if (value != null) {
            Text(
                value,
                color = ActiColors.dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
        Spacer(Modifier.width(4.dp))
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = ActiColors.dim)
    }
}

@Composable
private fun ToggleRow(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = ActiColors.snow)
            Hint(hint)
        }
        Spacer(Modifier.width(12.dp))
        Box {
            Switch(
                checked = checked,
                onCheckedChange = null,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = ActiColors.ground,
                    checkedTrackColor = ActiColors.ok,
                    uncheckedThumbColor = ActiColors.dim,
                    uncheckedTrackColor = ActiColors.surfaceHigh,
                    uncheckedBorderColor = ActiColors.outline,
                ),
            )
        }
    }
}
