package com.flowstate.app.ui.onboarding

import com.flowstate.app.ui.home.btPermissions
import com.flowstate.app.ui.home.HeartRateSetup
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flowstate.app.ui.ActiColors
import com.flowstate.data.SettingsStore
import com.flowstate.sensors.HeartRateMonitor
import com.flowstate.sensors.HrState

// ------------------------------------------------------------------ A4

/**
 * Screen A4 — optional, and it says so. Nothing downstream requires a monitor: motion
 * decides the mode and heart rate only fine-tunes the band within it.
 */
@Composable
internal fun HeartRateStep(
    settings: SettingsStore,
    heartRate: HeartRateMonitor,
    onContinue: () -> Unit,
    onSkip: () -> Unit,
) {
    StepScaffold(
        index = 3,
        title = "Add your pulse",
        subtitle = "Any Bluetooth strap or watch in broadcast mode. When you redline, only " +
            "your hardest tracks play. Motion decides the mode; heart rate fine-tunes it.",
        optional = true,
        primary = "Continue",
        onPrimary = onContinue,
        secondary = "Skip, no monitor",
        onSecondary = onSkip,
    ) {
        // Scanning is this screen's whole purpose, so it asks for Bluetooth permission on
        // entry — in context. It no longer switches the radio on unasked, though: if
        // Bluetooth is off, the card says so and offers the button.
        HeartRateSetup(heartRate, settings, scanOnEntry = true)
    }
}

// ------------------------------------------------------------------ A5

private data class Signal(
    val title: String,
    val reason: String,
    val permissions: List<String>,
)

/**
 * Screen A5 — one row per signal, each asking for only its own permission, with the
 * reason stated in terms of what the app does with it rather than what Android calls it.
 *
 * Also reused after onboarding when a permission a session needs has been revoked, which
 * is why it takes its own title.
 */
@Composable
internal fun PermissionsStep(
    includeBluetooth: Boolean,
    title: String,
    onFinish: () -> Unit,
    /** Null when shown outside the wizard. */
    index: Int? = 4,
) {
    val context = LocalContext.current
    val signals = remember(includeBluetooth) { signals(includeBluetooth) }
    // Recomputed on resume so returning from system Settings shows the new state.
    var granted by remember { mutableStateOf(signals.map { it.isGranted(context) }) }

    fun refresh() {
        granted = signals.map { it.isGranted(context) }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val request = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { refresh() }

    StepScaffold(
        index = index,
        title = title,
        subtitle = "Nothing leaves your phone. Each signal is asked for once, when you " +
            "tap it.",
        primary = if (index == null) "Done" else "Finish",
        onPrimary = onFinish,
    ) {
        signals.forEachIndexed { index, signal ->
            val isGranted = granted.getOrElse(index) { false }
            PermissionRow(
                signal = signal,
                granted = isGranted,
                onRequest = {
                    if (signal.permissions.isEmpty()) return@PermissionRow
                    request.launch(signal.permissions.toTypedArray())
                },
                onOpenSettings = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null),
                        ),
                    )
                },
            )
        }
        if (!includeBluetooth) {
            Text(
                "Bluetooth isn't asked for — you skipped the heart-rate step. Set a " +
                    "monitor up later from Home and it'll ask then.",
                style = MaterialTheme.typography.bodySmall,
                color = ActiColors.dim,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun PermissionRow(
    signal: Signal,
    granted: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    // A permission already granted is not a button; only an outstanding one is tappable.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(ActiColors.surface)
            .border(
                width = if (granted) 0.dp else 1.dp,
                color = if (granted) Color.Transparent else ActiColors.outline,
                shape = RoundedCornerShape(18.dp),
            )
            .clickable(enabled = !granted, onClick = onRequest)
            .padding(16.dp),
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .then(
                    if (granted) {
                        Modifier.background(ActiColors.ok)
                    } else {
                        Modifier.border(2.dp, ActiColors.dim, CircleShape)
                    },
                ),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(signal.title, fontWeight = FontWeight.SemiBold, color = ActiColors.snow)
            Text(
                signal.reason,
                style = MaterialTheme.typography.bodySmall,
                color = ActiColors.dim,
            )
        }
        Spacer(Modifier.width(10.dp))
        if (granted) {
            Text("Allowed", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = ActiColors.ok)
        } else {
            Column(horizontalAlignment = Alignment.End) {
                Text("Allow", fontWeight = FontWeight.Bold, color = ActiColors.riding)
                // A real target, not a 10 sp word: after a permanent denial it is the
                // only way to the switch.
                Text(
                    "Open settings",
                    fontSize = 13.sp,
                    color = ActiColors.dim,
                    modifier = Modifier
                        .clickable(onClick = onOpenSettings)
                        .padding(top = 6.dp, bottom = 6.dp, start = 8.dp),
                )
            }
        }
    }
}

private fun signals(includeBluetooth: Boolean): List<Signal> = buildList {
    add(
        Signal(
            title = "Music library",
            reason = "To play the files on this phone.",
            permissions = if (Build.VERSION.SDK_INT >= 33) {
                listOf(Manifest.permission.READ_MEDIA_AUDIO)
            } else {
                listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            },
        ),
    )
    if (Build.VERSION.SDK_INT >= 33) {
        add(
            Signal(
                title = "Notifications",
                reason = "Lockscreen player and the mode you're in.",
                permissions = listOf(Manifest.permission.POST_NOTIFICATIONS),
            ),
        )
    }
    add(
        Signal(
            title = "Location (GPS)",
            reason = "Speed and altitude tell a run from a chairlift. Sessions run with " +
                "the screen off.",
            permissions = listOf(Manifest.permission.ACCESS_FINE_LOCATION),
        ),
    )
    if (includeBluetooth && btPermissions().isNotEmpty()) {
        add(
            Signal(
                title = "Bluetooth",
                reason = "Only for the heart-rate monitor.",
                permissions = btPermissions(),
            ),
        )
    }
}

private fun Signal.isGranted(context: Context): Boolean = permissions.all {
    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
}
