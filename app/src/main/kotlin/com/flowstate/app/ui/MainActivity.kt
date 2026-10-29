package com.flowstate.app.ui

import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.material3.ScaffoldDefaults
import com.flowstate.app.ui.settings.SettingsScreen
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.foundation.layout.statusBarsPadding
import com.flowstate.app.ui.history.HistoryScreen
import com.flowstate.app.ui.history.RecapScreen
import com.flowstate.app.ui.home.HomeScreen
import com.flowstate.app.ui.session.SessionScreen
import com.flowstate.app.ui.session.SessionViewModel
import com.flowstate.app.ui.session.TimerSetupScreen
import com.flowstate.engine.Activity
import com.flowstate.app.ui.session.Phase
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.flowstate.app.ui.music.AddToPoolScreen
import com.flowstate.app.ui.music.BulkEditScreen
import com.flowstate.app.ui.music.BulkFilter
import com.flowstate.app.ui.music.MusicScreen
import com.flowstate.app.ui.music.RemoteScreen
import com.flowstate.app.ui.onboarding.OnboardingScreen
import com.flowstate.app.ui.onboarding.PermissionsStep
import com.flowstate.data.MediaLibrary
import androidx.compose.runtime.CompositionLocalProvider
import com.flowstate.app.ui.LocalAlbumArt
import com.flowstate.data.AlbumArtLoader
import com.flowstate.data.PoolRepository
import com.flowstate.data.RatingsRepository
import com.flowstate.data.SettingsStore
import com.flowstate.data.SpotifyRatingsRepository
import com.flowstate.data.db.EnergyLevel
import com.flowstate.playback.spotify.SpotifyRemote
import com.flowstate.sensors.HeartRateMonitor
import com.flowstate.session.SessionCoordinator
import com.flowstate.session.SessionHistory
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
    @Inject lateinit var poolRepository: PoolRepository
    @Inject lateinit var sessionHistory: SessionHistory
    @Inject lateinit var albumArt: AlbumArtLoader

    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge to edge on every version, not only where Android 15 forces it: B2's mode
        // colour is meant to reach the top of the screen, and the dark-only palette wants
        // light system-bar icons whatever the phone's own theme is.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // Spotify redirect can arrive on a cold start (browser killed the process).
        intent?.data?.let { spotifyRemote.handleRedirect(it) }
        setContent {
            ActiTheme {
                CompositionLocalProvider(LocalAlbumArt provides albumArt) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Root(
                        sessionCoordinator = sessionCoordinator,
                        heartRateMonitor = heartRateMonitor,
                        settingsStore = settingsStore,
                        mediaLibrary = mediaLibrary,
                        ratings = ratingsRepository,
                        spotifyRemote = spotifyRemote,
                        spotifyRatings = spotifyRatings,
                        pools = poolRepository,
                        history = sessionHistory,
                    )
                }
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
 * What a session cannot start without: the music library to play, and location because the
 * foreground service is declared with the location type — on API 34+ promoting it without
 * the permission throws. Notifications are not in here; a session runs fine without them.
 */
private fun sessionPermissions(): List<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    if (Build.VERSION.SDK_INT >= 33) {
        add(Manifest.permission.READ_MEDIA_AUDIO)
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
    pools: PoolRepository,
    history: SessionHistory,
) {
    val context = LocalContext.current

    // First run goes through the wizard (A1-A5). It asks for each permission in context,
    // so the old blunt "grant everything at launch" gate is gone.
    val onboarded by settingsStore.onboardingComplete.collectAsStateWithLifecycle()
    if (!onboarded) {
        OnboardingScreen(
            settings = settingsStore,
            heartRate = heartRateMonitor,
            library = mediaLibrary,
            onDone = { settingsStore.setOnboardingComplete(true) },
        )
        return
    }

    // A returning user can revoke a permission a session needs. Rather than crashing when
    // the location-typed foreground service starts, the same A5 screen asks again.
    fun sessionReady() = sessionPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    var ready by remember { mutableStateOf(sessionReady()) }
    if (!ready) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 20.dp)) {
            PermissionsStep(
                includeBluetooth = false,
                title = "ActiPlayer needs these back",
                onFinish = { ready = sessionReady() },
                index = null,
            )
        }
        return
    }

    val navController = rememberNavController()
    val sessionState by sessionCoordinator.state.collectAsStateWithLifecycle()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val onSession = currentRoute == Routes.SESSION
    // Screens that are a step in a flow rather than a place to browse carry no tab bar:
    // D4 sits between Start and the session, D3 is a detour from Home — both as drawn.
    val chromeless = onSession || currentRoute == Routes.TIMER_SETUP || currentRoute == Routes.SETTINGS

    Scaffold(
        containerColor = ActiColors.ground,
        // B2/B3 is full-bleed: mode colour has to reach every edge, under the status bar
        // too, so the session route takes no insets from the shell and pads itself.
        contentWindowInsets = if (onSession) WindowInsets(0) else ScaffoldDefaults.contentWindowInsets,
        bottomBar = {
            if (!chromeless) {
                Column {
                    // Session stays one tap away while browsing the other tabs.
                    if (sessionState.active) {
                        MiniPlayerBar(sessionCoordinator) {
                            navController.navigate(Routes.SESSION) { launchSingleTop = true }
                        }
                    }
                    AppBottomBar(navController)
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            // Consumed, so a screen that pads for the status bar itself does not get it twice.
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
        ) {
            composable(Routes.HOME) {
                val vm: SessionViewModel = hiltViewModel()
                val sessionUi by vm.state.collectAsStateWithLifecycle()
                HomeScreen(
                    state = sessionUi,
                    vm = vm,
                    settings = settingsStore,
                    heartRate = heartRateMonitor,
                    library = mediaLibrary,
                    ratings = ratings,
                    pools = pools,
                    history = history,
                    onOpenMusic = { navController.navigateToTopLevel(TopLevelDestination.MUSIC) },
                    onOpenRecap = { navController.navigate(Routes.recap(it)) },
                    onOpenSettings = {
                        navController.navigate(Routes.SETTINGS) { launchSingleTop = true }
                    },
                    onStarted = { activity ->
                        // Single-top: a double tap on Start must not stack two screens.
                        navController.navigate(
                            if (activity.usesTimer()) Routes.TIMER_SETUP else Routes.SESSION,
                        ) { launchSingleTop = true }
                    },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    settings = settingsStore,
                    heartRate = heartRateMonitor,
                    spotifyRemote = spotifyRemote,
                    spotifyRatings = spotifyRatings,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.TIMER_SETUP) {
                val activity by settingsStore.activity.collectAsStateWithLifecycle()
                TimerSetupScreen(
                    activity = activity,
                    settings = settingsStore,
                    onBack = { navController.popBackStack() },
                    onStart = {
                        sessionCoordinator.startSession()
                        navController.navigate(Routes.SESSION) {
                            // The setup screen has done its job; back from the session
                            // should reach the tab shell, not the clock picker again.
                            popUpTo(Routes.TIMER_SETUP) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                )
            }
            composable(Routes.SESSION) {
                val vm: SessionViewModel = hiltViewModel()
                val sessionUi by vm.state.collectAsStateWithLifecycle()
                // Ending a session drops straight back to the tab shell rather than
                // leaving a dead screen up. The seen-active latch matters: the view model
                // publishes an IDLE placeholder before the first real emission, and
                // popping on that would close this route the instant it opened.
                var seenActive by remember { mutableStateOf(false) }
                LaunchedEffect(sessionUi.phase) {
                    if (sessionUi.phase != Phase.IDLE) {
                        seenActive = true
                    } else if (seenActive) {
                        navController.popBackStack()
                    }
                }
                SessionScreen(sessionUi, vm)
            }
            composable(Routes.MUSIC) {
                MusicScreen(
                    library = mediaLibrary,
                    ratings = ratings,
                    pools = pools,
                    onAddTo = { navController.navigate(Routes.musicAdd(it.name)) },
                    onOpenPool = { navController.navigate(Routes.musicEdit(it.name)) },
                    onOpenUnsorted = {
                        navController.navigate(Routes.musicEdit(BulkFilter.UNSORTED.name))
                    },
                    onOpenSpotify = { navController.navigate(Routes.MUSIC_SPOTIFY) },
                )
            }
            composable(Routes.MUSIC_ADD) { entry ->
                val level = entry.arguments?.getString("level")
                    ?.let { runCatching { EnergyLevel.valueOf(it) }.getOrNull() }
                    ?: EnergyLevel.HYPE
                AddToPoolScreen(
                    level = level,
                    library = mediaLibrary,
                    pools = pools,
                    onDone = { navController.popBackStack() },
                )
            }
            composable(Routes.MUSIC_EDIT) { entry ->
                val requested = entry.arguments?.getString("filter")
                val filter = BulkFilter.entries.firstOrNull { it.name == requested }
                    ?: runCatching { EnergyLevel.valueOf(requested.orEmpty()) }.getOrNull()
                        ?.let { level -> BulkFilter.entries.first { it.level == level } }
                    ?: BulkFilter.HYPE
                BulkEditScreen(
                    initial = filter,
                    library = mediaLibrary,
                    ratings = ratings,
                    pools = pools,
                    onClose = { navController.popBackStack() },
                )
            }
            composable(Routes.MUSIC_SPOTIFY) {
                RemoteScreen(spotifyRemote, settingsStore, spotifyRatings) {
                    navController.popBackStack()
                }
            }
            composable(Routes.HISTORY) {
                HistoryScreen(history) { navController.navigate(Routes.recap(it)) }
            }
            composable(Routes.RECAP) { entry ->
                val id = entry.arguments?.getString("sessionId")?.toLongOrNull()
                if (id == null) {
                    LaunchedEffect(Unit) { navController.popBackStack() }
                } else {
                    val units by settingsStore.units.collectAsStateWithLifecycle()
                    RecapScreen(id, history, units) { navController.popBackStack() }
                }
            }
        }
    }
}
