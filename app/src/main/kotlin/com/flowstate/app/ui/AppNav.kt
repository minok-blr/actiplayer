package com.flowstate.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CropSquare
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.flowstate.data.MusicSource
import com.flowstate.engine.RideState
import com.flowstate.session.SessionCoordinator

/**
 * The three top-level destinations (plan §1: Home · Music · History) plus the routes that
 * hang off them. Settings lives behind the avatar on Home, so it is not a tab.
 */
object Routes {
    const val HOME = "home"
    const val MUSIC = "music"
    const val MUSIC_SPOTIFY = "music/spotify"
    const val HISTORY = "history"
}

enum class TopLevelDestination(val route: String, val label: String, val icon: ImageVector) {
    HOME(Routes.HOME, "Home", Icons.Rounded.CropSquare),
    MUSIC(Routes.MUSIC, "Music", Icons.Rounded.RadioButtonUnchecked),
    HISTORY(Routes.HISTORY, "History", Icons.Rounded.Menu),
}

/**
 * Bottom bar per the redesign: geometric outline icons, quiet labels, and a short
 * underline under the selected one — no Material pill indicator, which would introduce a
 * second saturated shape on screens where mode color is meant to be the only one.
 */
@Composable
fun AppBottomBar(navController: NavHostController) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val current = backStackEntry?.destination

    Surface(color = ActiColors.surface) {
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(top = 10.dp, bottom = 6.dp),
        ) {
            TopLevelDestination.entries.forEach { destination ->
                val selected = current?.hierarchy?.any { it.route == destination.route } == true
                val tint by animateColorAsState(
                    if (selected) ActiColors.snow else ActiColors.dim,
                    label = "navTint",
                )
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { navController.navigateToTopLevel(destination) }
                        .heightIn(min = 48.dp),
                ) {
                    Icon(destination.icon, contentDescription = destination.label, tint = tint)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        destination.label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = tint,
                    )
                    Spacer(Modifier.height(4.dp))
                    Box(
                        Modifier
                            .width(18.dp)
                            .height(2.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(if (selected) ActiColors.snow else Color.Transparent),
                    )
                }
            }
        }
    }
}

/**
 * Single-top navigation between tabs, popping back to Home so the system back button
 * always leaves by the same door instead of unwinding a pile of tab visits.
 */
private fun NavHostController.navigateToTopLevel(destination: TopLevelDestination) {
    navigate(destination.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** Docked above the bottom bar while a session runs and another tab is open. */
@Composable
fun MiniPlayerBar(coordinator: SessionCoordinator, onOpen: () -> Unit) {
    val state by coordinator.state.collectAsStateWithLifecycle()
    val localNow by coordinator.queue.nowPlaying.collectAsStateWithLifecycle()
    val spotifyNow by coordinator.spotifyQueue.nowPlaying.collectAsStateWithLifecycle()

    val mode = state.decision?.state
    val modeColor = when (mode) {
        RideState.RIDING, RideState.EFFORT -> ActiColors.riding
        RideState.LIFT, RideState.CRUISE -> ActiColors.lift
        RideState.PAUSED, null -> ActiColors.paused
    }
    val title = when (state.source) {
        MusicSource.SPOTIFY -> spotifyNow ?: "sending tracks to Spotify…"
        MusicSource.LOCAL -> localNow?.let { "${it.track.title} — ${it.track.artist}" }
            ?: "picking a track…"
    }

    Surface(color = ActiColors.surfaceHigh) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Box(Modifier.size(10.dp).background(modeColor, CircleShape))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${mode?.name ?: "STARTING"} · band ${state.effectiveBand.first}–${state.effectiveBand.last}",
                    style = MaterialTheme.typography.bodySmall,
                    color = ActiColors.dim,
                )
            }
            IconButton(onClick = { coordinator.skip() }) {
                Icon(Icons.Rounded.SkipNext, contentDescription = "Skip", tint = ActiColors.snow)
            }
        }
    }
}
