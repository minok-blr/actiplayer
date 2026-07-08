package com.flowstate.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.flowstate.app.FlowStateApp
import com.flowstate.app.di.AppContainer

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as FlowStateApp).container
        setContent {
            FlowTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Root(container)
                }
            }
        }
    }
}

/**
 * A session needs the music library (to play it) and location (to tell riding from the
 * chairlift). Location must be granted BEFORE starting the service: on API 34+ a
 * foreground service declared with the location type throws if the permission is missing.
 */
private fun requiredPermissions(): List<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    if (Build.VERSION.SDK_INT >= 33) {
        add(Manifest.permission.READ_MEDIA_AUDIO)
        add(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        add(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}

@Composable
private fun Root(container: AppContainer) {
    val context = LocalContext.current

    // First launch: the guide IS the first screen. Afterwards it lives behind "?".
    var showGuide by remember {
        mutableStateOf(!container.settingsStore.guideDismissed.value)
    }
    if (showGuide) {
        GuideScreen(
            onClose = {
                container.settingsStore.setGuideDismissed(true)
                showGuide = false
            },
        )
        return
    }

    fun allGranted() = requiredPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    var granted by remember { mutableStateOf(allGranted()) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        granted = allGranted()
    }

    if (!granted) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "FlowState needs your music library to play it, and location to tell " +
                        "riding from the chairlift. Nothing leaves the phone.",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                Button(onClick = { launcher.launch(requiredPermissions().toTypedArray()) }) {
                    Text("Grant permissions")
                }
            }
        }
        return
    }

    var tab by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TabRow(selectedTabIndex = tab, modifier = Modifier.weight(1f)) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Session") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Library") })
            }
            IconButton(onClick = { showGuide = true }) {
                Text(
                    "?",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black,
                    color = FlowColors.dim,
                )
            }
        }
        when (tab) {
            0 -> SessionScreen(
                container.sessionCoordinator,
                container.heartRateMonitor,
                container.settingsStore,
                container.mediaLibrary,
                container.ratingsStore,
            )
            1 -> LibraryScreen(container.mediaLibrary, container.ratingsStore)
        }
    }
}
