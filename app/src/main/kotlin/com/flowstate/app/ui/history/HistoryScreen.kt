package com.flowstate.app.ui.history

import androidx.compose.material3.LocalTextStyle
import com.flowstate.app.ui.Tabular
import java.time.format.FormatStyle
import com.flowstate.app.ui.label
import com.flowstate.app.ui.spokenDuration
import com.flowstate.engine.Activity
import com.flowstate.engine.RideState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flowstate.app.ui.ActiColors
import com.flowstate.app.ui.Caption
import com.flowstate.app.ui.Display
import com.flowstate.app.ui.SectionLabel
import com.flowstate.app.ui.glyph
import com.flowstate.app.ui.ScreenHeader
import com.flowstate.app.ui.session.modeColor
import com.flowstate.data.db.SessionEntity
import com.flowstate.session.ModeSegment
import com.flowstate.session.SessionHistory
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Screen D1 — every recorded session, newest first, grouped by month.
 *
 * A row says what the app *did*, not how far you went: the mode strip and the switch
 * count. A watch already records distance and pace, and does it better; this is the only
 * place that can show whether the music followed the effort.
 */
@Composable
fun HistoryScreen(history: SessionHistory, onOpen: (Long) -> Unit) {
    val sessions by history.sessions.collectAsStateWithLifecycle()
    val locale = currentLocale()

    // Segment strips are per-session and cheap to compute once; keeping them keyed by id
    // means scrolling the list never re-reads the database.
    val strips = remember { mutableStateMapOf<Long, List<ModeSegment>>() }
    LaunchedEffect(sessions) {
        sessions.forEach { session ->
            if (session.id !in strips) {
                strips[session.id] = history.recap(session.id)?.second?.segments.orEmpty()
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "History",
            subtitle = if (sessions.isEmpty()) {
                "Every session, mode by mode"
            } else {
                "${sessions.size} ${if (sessions.size == 1) "session" else "sessions"}"
            },
        )

        if (sessions.isEmpty()) {
            EmptyHistory()
            return@Column
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            var lastMonth: String? = null
            sessions.forEach { session ->
                val month = session.startedAtEpochMs.format("MMMM yyyy", locale)
                if (month != lastMonth) {
                    lastMonth = month
                    item(key = "header-$month") {
                        SectionLabel(month, Modifier.padding(top = 12.dp, bottom = 4.dp))
                    }
                }
                item(key = session.id) {
                    SessionRow(session, strips[session.id].orEmpty(), locale) {
                        onOpen(session.id)
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionRow(
    session: SessionEntity,
    segments: List<ModeSegment>,
    locale: Locale,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(ActiColors.surface)
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    // The mode the session mostly spent in — the first segment was an
                    // accident of how it started, and usually just the opening state.
                    .background(segments.dominantMode().modeColor(session.activity)),
            ) {
                Icon(
                    session.activity.glyph,
                    contentDescription = null,
                    tint = ActiColors.ground,
                    modifier = Modifier.size(21.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    session.activity.label(),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp,
                    color = ActiColors.snow,
                )
                // Day and start time: "the morning ride" is how a session is remembered.
                // The switch count lives in the recap; the strip below already shows it.
                Caption(
                    "${session.startedAtEpochMs.format("EEE d MMM", locale)} · " +
                        session.startedAtEpochMs.timeOfDay(locale),
                    maxLines = 1,
                )
            }
            Text(
                session.durationMs.spokenDuration(),
                fontFamily = Display,
                style = LocalTextStyle.current.merge(Tabular),
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = ActiColors.snow,
            )
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = ActiColors.dim,
            )
        }
        if (segments.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            ModeStrip(segments, session.activity, Modifier.fillMaxWidth().height(8.dp))
        }
    }
}

/** The session's shape at a glance: mode segments in proportion, in their own colours. */
@Composable
fun ModeStrip(segments: List<ModeSegment>, activity: Activity, modifier: Modifier = Modifier) {
    val total = segments.sumOf { it.durationMs }.coerceAtLeast(1)
    Row(modifier.clip(RoundedCornerShape(4.dp)), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        segments.forEach { segment ->
            Box(
                Modifier
                    .weight(segment.durationMs.toFloat() / total)
                    .fillMaxSize()
                    .clip(RoundedCornerShape(4.dp))
                    .background(segment.state.modeColor(activity)),
            )
        }
    }
}

@Composable
private fun EmptyHistory() {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Rounded.Timeline,
                contentDescription = null,
                tint = ActiColors.dim,
                modifier = Modifier.size(40.dp),
            )
            Text("No sessions yet", style = MaterialTheme.typography.titleMedium)
            Text(
                "Start a session and it will show up here — how long each mode lasted, " +
                    "and how often you had to override the engine.",
                style = MaterialTheme.typography.bodyMedium,
                color = ActiColors.dim,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * The device locale, read observably so a locale change actually recomposes the dates.
 * `Locale.getDefault()` inside a composable does not.
 */
@Composable
internal fun currentLocale(): Locale = LocalConfiguration.current.locales[0]

internal fun Long.format(pattern: String, locale: Locale): String =
    DateTimeFormatter.ofPattern(pattern, locale)
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(this))

/** "09:40" or "9:40 AM", as the device's locale writes a time. */
internal fun Long.timeOfDay(locale: Locale): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        .withLocale(locale)
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(this))

/** Elapsed time on a clock face, for the recap timeline's axis: "0:24", "1:42:10". */
internal fun Long.clock(): String {
    val seconds = this / 1_000
    return if (seconds >= 3_600) {
        "%d:%02d:%02d".format(seconds / 3_600, (seconds % 3_600) / 60, seconds % 60)
    } else {
        "%d:%02d".format(seconds / 60, seconds % 60)
    }
}

/** The state a session spent the most time in. */
internal fun List<ModeSegment>.dominantMode(): RideState? =
    groupBy { it.state }.maxByOrNull { (_, group) -> group.sumOf { it.durationMs } }?.key
