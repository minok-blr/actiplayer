package com.flowstate.app.ui.history

import androidx.compose.material3.LocalTextStyle
import com.flowstate.app.ui.Tabular
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowstate.app.ui.ActiBottomSheet
import com.flowstate.app.ui.ActiColors
import com.flowstate.app.ui.ArtTile
import com.flowstate.app.ui.BackLink
import com.flowstate.app.ui.SectionLabel
import com.flowstate.app.ui.Caption
import com.flowstate.app.ui.Display
import com.flowstate.app.ui.header
import com.flowstate.app.ui.rememberAlbumArt
import com.flowstate.app.ui.reportsSpeed
import com.flowstate.app.ui.session.modeColor
import com.flowstate.app.ui.session.modeLabel
import com.flowstate.app.ui.spokenDuration
import com.flowstate.data.Units
import com.flowstate.data.db.SessionEntity
import com.flowstate.engine.Activity
import com.flowstate.engine.RideState
import com.flowstate.session.Sample
import com.flowstate.session.SessionHistory
import com.flowstate.session.SessionRecap
import com.flowstate.session.modes
import java.util.Locale

/**
 * Screen B5 — one session, after the fact.
 *
 * The point is not fitness data: a watch already records distance and pace better. This
 * answers the question only this app can — did the music follow the effort. Hence the
 * mode timeline as the centrepiece, and the override count sitting beside the stats.
 */
@Composable
fun RecapScreen(sessionId: Long, history: SessionHistory, units: Units, onBack: () -> Unit) {
    var loaded by remember { mutableStateOf<Pair<SessionEntity, SessionRecap>?>(null) }
    var missing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(sessionId) {
        val result = history.recap(sessionId)
        if (result == null) missing = true else loaded = result
    }

    val data = loaded
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        BackLink("History", onBack, Modifier.padding(top = 8.dp))
        when {
            missing -> Text("That session is gone.", color = ActiColors.dim)
            data == null -> Text("Loading…", color = ActiColors.dim)
            else -> {
                RecapBody(data.first, data.second, units, currentLocale())
                Spacer(Modifier.height(20.dp))
                // Test sessions, a Start pressed by accident: a history that can only
                // grow is one nobody wants to scroll.
                Text(
                    "Delete this session",
                    color = ActiColors.hot,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(role = Role.Button) { confirmDelete = true }
                        .padding(vertical = 14.dp, horizontal = 4.dp),
                )
            }
        }
        Spacer(Modifier.height(32.dp))
    }

    if (confirmDelete) {
        ActiBottomSheet({ confirmDelete = false }) {
            Column(
                Modifier.padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Delete this session?", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Its timeline and track list go too. This can't be undone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ActiColors.dim,
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(ActiColors.hot)
                        .clickable(role = Role.Button) {
                            confirmDelete = false
                            history.delete(sessionId)
                            onBack()
                        },
                ) {
                    Text("Delete", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = ActiColors.ground)
                }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(role = Role.Button) { confirmDelete = false },
                ) {
                    Text("Keep it", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = ActiColors.snow)
                }
            }
        }
    }
}

@Composable
private fun RecapBody(session: SessionEntity, recap: SessionRecap, units: Units, locale: Locale) {
    val activity = session.activity
    Text(
        "${activity.header()} · ${session.startedAtEpochMs.format("EEE d MMM", locale)} · " +
            session.startedAtEpochMs.timeOfDay(locale),
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        color = ActiColors.riding,
    )
    Text(
        "${session.durationMs.spokenDuration()} ${activity.phrase()}",
        fontFamily = Display,
        fontSize = 30.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.5).sp,
        fontWeight = FontWeight.Bold,
        color = ActiColors.snow,
        modifier = Modifier.semantics { heading() },
    )
    Spacer(Modifier.height(16.dp))

    // The activity's own modes, hype first as drawn — not whichever happened to be
    // longest, which put REST before WORK on an interval session.
    val tiles = activity.modes().mapNotNull { state ->
        recap.timePerMode[state]?.takeIf { it > 0 }?.let { state to it }
    }
    if (tiles.isNotEmpty()) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            tiles.forEach { (state, ms) -> ModeTile(state, activity, ms, Modifier.weight(1f)) }
        }
        Spacer(Modifier.height(14.dp))
    }

    if (recap.segments.isNotEmpty()) {
        TimelineCard(recap, activity, session.durationMs)
        Spacer(Modifier.height(14.dp))
    }

    // Only numbers that exist: an indoor clock session has no speed, and a session
    // without a strap has no heart rate — a card of dashes says nothing.
    val stats = buildList {
        recap.avgHr?.let { add(Triple("Avg heart rate", "$it", "bpm")) }
        if (activity.reportsSpeed()) {
            recap.topSpeedKmh?.let {
                add(Triple("Top speed", "%.0f".format(units.speedFromKmh(it)), units.speedUnit))
            }
        }
        add(Triple("Switches", "${recap.switches}", ""))
        // Not in the plan's stat list, deliberately: every override is the engine being
        // corrected by hand, which makes this the honest score for the thresholds.
        add(Triple("Overrides", "${recap.overrides}", "by hand"))
    }
    stats.chunked(2).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            row.forEach { (label, value, unit) -> StatCard(label, value, unit, Modifier.weight(1f)) }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
    }

    if (recap.topTracks.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        SectionLabel("Top tracks", Modifier.semantics { heading() })
        Spacer(Modifier.height(8.dp))
        recap.topTracks.take(6).forEach { play ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(ActiColors.surface)
                    .padding(end = 14.dp),
            ) {
                val color = play.mode.modeColor(activity)
                Box(Modifier.width(4.dp).height(64.dp).background(color))
                Spacer(Modifier.width(12.dp))
                ArtTile(tint = color, size = 44.dp, art = rememberAlbumArt(play.mediaStoreId))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                    Text(
                        play.title,
                        fontWeight = FontWeight.Bold,
                        color = ActiColors.snow,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (play.artist.isNotBlank() && play.artist != "<unknown>") {
                        Text(
                            play.artist,
                            style = MaterialTheme.typography.bodySmall,
                            color = ActiColors.dim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Caption("×${play.plays}${play.mode?.let { " · ${it.modeLabel(activity)}" }.orEmpty()}")
            }
        }
    }
}

@Composable
private fun ModeTile(state: RideState, activity: Activity, ms: Long, modifier: Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(ActiColors.surface)
            .padding(14.dp),
    ) {
        Text(
            ms.spokenDuration(),
            fontFamily = Display,
            style = LocalTextStyle.current.merge(Tabular),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            color = state.modeColor(activity),
        )
        Caption(state.modeLabel(activity))
    }
}

@Composable
private fun StatCard(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(ActiColors.surface)
            .padding(14.dp),
    ) {
        SectionLabel(label)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value,
                fontFamily = Display,
                style = LocalTextStyle.current.merge(Tabular),
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = ActiColors.snow,
            )
            if (unit.isNotEmpty()) {
                Spacer(Modifier.width(4.dp))
                Text(
                    unit,
                    style = MaterialTheme.typography.bodySmall,
                    color = ActiColors.dim,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
        }
    }
}

/**
 * Mode segments as a background, with heart rate solid and speed dashed over them —
 * the one picture that shows whether the engine's mode changes lined up with the effort.
 */
@Composable
private fun TimelineCard(recap: SessionRecap, activity: Activity, durationMs: Long) {
    val hasHr = recap.samples.count { it.hrBpm != null } >= 2
    val hasSpeed = activity.reportsSpeed() && recap.samples.count { it.speedKmh != null } >= 2
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(ActiColors.surface)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Mode timeline")
            Spacer(Modifier.weight(1f))
            // The legend only names lines that are actually drawn.
            val legend = listOfNotNull(
                if (hasHr) "— Heart rate" else null,
                if (hasSpeed) "··· Speed" else null,
            )
            if (legend.isNotEmpty()) Caption(legend.joinToString("   "))
        }
        Spacer(Modifier.height(10.dp))

        val total = durationMs.coerceAtLeast(1)
        Canvas(Modifier.fillMaxWidth().height(if (hasHr || hasSpeed) 140.dp else 72.dp)) {
            recap.segments.forEach { segment ->
                val x = size.width * (segment.startMs.toFloat() / total)
                val w = size.width * (segment.durationMs.toFloat() / total)
                drawRect(
                    color = segment.state.modeColor(activity).copy(alpha = 0.75f),
                    topLeft = Offset(x, 0f),
                    size = androidx.compose.ui.geometry.Size(w.coerceAtLeast(1f), size.height),
                )
            }
            if (hasHr) {
                line(recap.samples, total) { it.hrBpm?.toDouble() }?.let {
                    drawPath(it, ActiColors.snow, style = Stroke(width = 3f))
                }
            }
            if (hasSpeed) {
                line(recap.samples, total) { it.speedKmh }?.let {
                    drawPath(
                        it,
                        ActiColors.snow.copy(alpha = 0.6f),
                        style = Stroke(
                            width = 3f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
                        ),
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(0L, durationMs / 2, durationMs).forEach {
                Text(
                    it.clock(),
                    fontSize = 12.sp,
                    style = LocalTextStyle.current.merge(Tabular),
                    color = ActiColors.dim,
                )
            }
        }
        if (!hasHr && !hasSpeed) {
            Spacer(Modifier.height(8.dp))
            Text(
                if (activity.reportsSpeed()) {
                    "No heart rate or speed was recorded, so only the modes are drawn."
                } else {
                    "No heart rate was recorded, so only the clock's blocks are drawn."
                },
                style = MaterialTheme.typography.bodySmall,
                color = ActiColors.dim,
            )
        }
    }
}

/**
 * Builds a polyline for one series, scaled to its own range so a flat heart rate does not
 * become an invisible line along the bottom. Null when there is nothing to draw.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.line(
    samples: List<Sample>,
    totalMs: Long,
    value: (Sample) -> Double?,
): Path? {
    val points = samples.mapNotNull { sample -> value(sample)?.let { sample.tMs to it } }
    if (points.size < 2) return null
    val min = points.minOf { it.second }
    val max = points.maxOf { it.second }
    val span = (max - min).takeIf { it > 0.0 } ?: 1.0
    val path = Path()
    points.forEachIndexed { index, (t, v) ->
        val x = size.width * (t.toFloat() / totalMs.coerceAtLeast(1))
        // 10% inset top and bottom so the extremes are not drawn on the card edge.
        val y = size.height * (0.9f - 0.8f * ((v - min) / span).toFloat())
        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    return path
}

/** What the headline calls the session, so it reads like a sentence per activity. */
private fun Activity.phrase(): String = when (this) {
    Activity.SNOWBOARDING -> "on the hill"
    Activity.CYCLING -> "on the bike"
    Activity.WALKING -> "on foot"
    Activity.RUNNING -> "running"
    Activity.STRENGTH -> "lifting"
    Activity.HIIT -> "of intervals"
    Activity.GENERIC -> "moving"
}
