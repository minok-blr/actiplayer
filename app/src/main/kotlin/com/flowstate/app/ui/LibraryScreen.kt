package com.flowstate.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flowstate.app.data.MediaLibrary
import com.flowstate.app.data.RatingsStore
import com.flowstate.app.data.Track

/**
 * Browse the device library and rate each track's energy 1 (couch) .. 5 (send it).
 * Ratings are what the queue engine selects from during a session. Filters keep this
 * usable on real devices where podcasts and voice notes drown the actual music.
 */
private enum class LibFilter(val label: String) {
    ALL("All"), UNRATED("Unrated"), LOW("1–2"), MID("3"), HIGH("4–5");

    fun matches(rating: Int?): Boolean = when (this) {
        ALL -> true
        UNRATED -> rating == null
        LOW -> rating == 1 || rating == 2
        MID -> rating == 3
        HIGH -> rating == 4 || rating == 5
    }
}

@Composable
fun LibraryScreen(library: MediaLibrary, ratings: RatingsStore) {
    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        tracks = library.loadTracks()
        loaded = true
    }
    val ratingMap by ratings.ratings.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf(LibFilter.ALL) }

    if (loaded && tracks.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "No music found on this device.\nCopy some files into Music/ and reopen.",
                color = FlowColors.dim,
            )
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        Text(
            "Rate tracks by energy — 1 couch, 5 send it. Unrated plays as 3.",
            style = MaterialTheme.typography.bodySmall,
            color = FlowColors.dim,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            LibFilter.entries.forEach { f ->
                val count = when (f) {
                    LibFilter.ALL -> tracks.size
                    else -> tracks.count { f.matches(ratingMap[it.id]) }
                }
                FilterChip(
                    selected = filter == f,
                    onClick = { filter = f },
                    label = { Text("${f.label} $count") },
                )
            }
        }

        val visible = tracks.filter { filter.matches(ratingMap[it.id]) }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
            items(visible, key = { it.id }) { track ->
                TrackRow(
                    track = track,
                    rating = ratingMap[track.id],
                    onRate = { r -> ratings.setRating(track.id, r) },
                )
            }
        }
    }
}

@Composable
private fun TrackRow(track: Track, rating: Int?, onRate: (Int) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${track.artist} · ${track.durationMs.fmtDuration()}",
                style = MaterialTheme.typography.bodySmall,
                color = FlowColors.dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            (1..5).forEach { r ->
                val selected = rating == r
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(32.dp)
                        .background(
                            if (selected) FlowColors.riding else FlowColors.surface,
                            RoundedCornerShape(8.dp),
                        )
                        .clickable { onRate(r) },
                ) {
                    Text(
                        "$r",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black,
                        color = if (selected) FlowColors.ground else FlowColors.dim,
                    )
                }
            }
        }
    }
}

private fun Long.fmtDuration(): String {
    val totalSec = this / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
}
