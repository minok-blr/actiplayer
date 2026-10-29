package com.flowstate.app.ui

import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.ui.unit.sp
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
import com.flowstate.data.SourceKind
import com.flowstate.app.ui.session.modeColor
import com.flowstate.app.ui.session.modeLabel
import com.flowstate.session.SessionCoordinator

/**
 * The three top-level destinations (plan §1: Home · Music · History) plus the routes that
 * hang off them. Settings lives behind the avatar on Home, so it is not a tab.
 */
object Routes {
    const val HOME = "home"
    const val MUSIC = "music"
    const val MUSIC_SPOTIFY = "music/spotify"
    const val MUSIC_ADD = "music/add/{level}"
    const val MUSIC_EDIT = "music/edit/{filter}"

    fun musicAdd(level: String) = "music/add/$level"
    fun musicEdit(filter: String) = "music/edit/$filter"
    const val HISTORY = "history"
    const val SESSION = "session"
    const val TIMER_SETUP = "timer"
    const val SETTINGS = "settings"
    const val RECAP = "recap/{sessionId}"

    fun recap(sessionId: Long) = "recap/$sessionId"
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
                        fontFamily = Sans,
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
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
 *
 * Deliberately **no** `saveState`/`restoreState`. Those exist for tabs whose routes live
 * in nested graphs; these routes are flat siblings, so saving on `popUpTo(start)` stores
 * whatever sub-screen happened to be on the stack under the *start* destination, and
 * restoring it later drops you there. Concretely: open Music from Home's ready-check row,
 * switch to History, tap Home — and you land on Music. A tab now always opens its own
 * root, which is what a tab bar promises.
 */
internal fun NavHostController.navigateToTopLevel(destination: TopLevelDestination) {
    navigate(destination.route) {
        popUpTo(graph.findStartDestination().id)
        launchSingleTop = true
    }
}

/** Docked above the bottom bar while a session runs and another tab is open. */
@Composable
fun MiniPlayerBar(coordinator: SessionCoordinator, onOpen: () -> Unit) {
    val state by coordinator.state.collectAsStateWithLifecycle()
    val nowPlaying by coordinator.nowPlaying.collectAsStateWithLifecycle()
    val paused by coordinator.paused.collectAsStateWithLifecycle()

    val mode = state.decision?.state
    val modeColor = mode.modeColor(state.activity)
    val title = nowPlaying?.let { joinNonEmpty(it.title, it.artist, separator = " — ") }
        ?: when (state.source) {
            SourceKind.SPOTIFY -> "Sending tracks to Spotify…"
            SourceKind.LOCAL -> "Picking a track…"
        }

    Surface(color = ActiColors.surfaceHigh) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = "Open session", onClick = onOpen)
                .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        ) {
            Box(Modifier.size(10.dp).background(modeColor, CircleShape))
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Caption(
                    if (paused) "Paused · ${mode.modeLabel(state.activity)}" else mode.modeLabel(state.activity),
                    color = if (paused) ActiColors.hot else ActiColors.dim,
                    maxLines = 1,
                )
            }
            IconButton(onClick = { coordinator.togglePause() }) {
                Icon(
                    if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                    contentDescription = if (paused) "Resume" else "Pause",
                    tint = ActiColors.snow,
                )
            }
            IconButton(onClick = { coordinator.skip() }) {
                Icon(Icons.Rounded.SkipNext, contentDescription = "Skip track", tint = ActiColors.snow)
            }
        }
    }
}
