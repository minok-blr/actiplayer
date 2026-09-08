package com.flowstate.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.flowstate.app.ui.history.HistoryScreen
import com.flowstate.app.ui.home.SessionScreen
import com.flowstate.app.ui.music.LibraryScreen
import com.flowstate.app.ui.music.RemoteScreen
import com.flowstate.data.MediaLibrary
import com.flowstate.data.RatingsRepository
import com.flowstate.data.SettingsStore
import com.flowstate.data.SpotifyRatingsRepository
import com.flowstate.playback.spotify.SpotifyRemote
import com.flowstate.sensors.HeartRateMonitor
import com.flowstate.session.SessionCoordinator
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var sessionCoordinator: SessionCoordinator
    @Inject lateinit var heartRateMonitor: HeartRateMonitor
    @Inject lateinit var settingsStore: SettingsStore
    @Inject lateinit var mediaLibrary: MediaLibrary
    @Inject lateinit var ratingsRepository: RatingsRepository
    @Inject lateinit var spotifyRemote: SpotifyRemote
    @Inject lateinit var spotifyRatings: SpotifyRatingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Spotify redirect can arrive on a cold start (browser killed the process).
        intent?.data?.let { spotifyRemote.handleRedirect(it) }
        setContent {
            ActiTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Root(
                        sessionCoordinator = sessionCoordinator,
                        heartRateMonitor = heartRateMonitor,
                        settingsStore = settingsStore,
                        mediaLibrary = mediaLibrary,
                        ratings = ratingsRepository,
                        spotifyRemote = spotifyRemote,
                        spotifyRatings = spotifyRatings,
                    )
                }
            }
        }
    }

    // singleTask: the Spotify OAuth redirect lands here while we're already running.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let { spotifyRemote.handleRedirect(it) }
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
private fun Root(
    sessionCoordinator: SessionCoordinator,
    heartRateMonitor: HeartRateMonitor,
    settingsStore: SettingsStore,
    mediaLibrary: MediaLibrary,
    ratings: RatingsRepository,
    spotifyRemote: SpotifyRemote,
    spotifyRatings: SpotifyRatingsRepository,
) {
    val context = LocalContext.current

    // First launch: the guide IS the first screen. Afterwards it lives behind "?".
    var showGuide by remember { mutableStateOf(!settingsStore.guideDismissed.value) }
    if (showGuide) {
        GuideScreen(
            onClose = {
                settingsStore.setGuideDismissed(true)
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
                Icon(
                    Icons.Rounded.LocationOn,
                    contentDescription = null,
                    tint = ActiColors.riding,
                    modifier = Modifier.size(44.dp),
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "ActiPlayer needs your music library to play it, and location to tell " +
                        "riding from the chairlift. Nothing leaves the phone.",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                Button(onClick = { launcher.launch(requiredPermissions().toTypedArray()) }) {
                    Text("Grant permissions", fontWeight = FontWeight.Bold)
                }
            }
        }
        return
    }

    val navController = rememberNavController()
    val sessionState by sessionCoordinator.state.collectAsStateWithLifecycle()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        containerColor = ActiColors.ground,
        bottomBar = {
            Column {
                // Session stays one tap away while browsing the other tabs.
                if (sessionState.active && currentRoute != Routes.HOME) {
                    MiniPlayerBar(sessionCoordinator) {
                        navController.navigate(Routes.HOME) { launchSingleTop = true }
                    }
                }
                AppBottomBar(navController)
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.HOME) {
                SessionScreen(
                    sessionCoordinator,
                    heartRateMonitor,
                    settingsStore,
                    mediaLibrary,
                    ratings,
                    spotifyRemote,
                    spotifyRatings,
                    onShowGuide = { showGuide = true },
                )
            }
            composable(Routes.MUSIC) {
                LibraryScreen(
                    mediaLibrary,
                    ratings,
                    onOpenSpotify = { navController.navigate(Routes.MUSIC_SPOTIFY) },
                )
            }
            composable(Routes.MUSIC_SPOTIFY) {
                RemoteScreen(spotifyRemote, settingsStore, spotifyRatings)
            }
            composable(Routes.HISTORY) { HistoryScreen() }
        }
    }
}
