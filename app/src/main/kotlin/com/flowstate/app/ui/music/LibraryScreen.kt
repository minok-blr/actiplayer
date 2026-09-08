package com.flowstate.app.ui.music

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material3.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flowstate.app.ui.ActiColors
import com.flowstate.app.ui.ArtTile
import com.flowstate.app.ui.RatingBoxes
import com.flowstate.app.ui.ScreenHeader
import com.flowstate.app.ui.energyTint
import com.flowstate.data.MediaLibrary
import com.flowstate.data.RatingsRepository
import com.flowstate.data.Track

/**
 * Browse the device library and rate each track's energy 1 (couch) .. 5 (send it).
 * Ratings are what the queue engine selects from during a session. Filters keep this
 * usable on real devices where podcasts and voice notes drown the actual music.
 */
private enum class LibFilter(val label: String) {
    ALL("All"), UNRATED("Unrated"), LOW("Chill 1–2"), MID("Mid 3"), HIGH("Hype 4–5");

    fun matches(rating: Int?): Boolean = when (this) {
        ALL -> true
        UNRATED -> rating == null
        LOW -> rating == 1 || rating == 2
        MID -> rating == 3
        HIGH -> rating == 4 || rating == 5
    }
}

@Composable
fun LibraryScreen(
    library: MediaLibrary,
    ratings: RatingsRepository,
    onOpenSpotify: () -> Unit,
) {
    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        tracks = library.loadTracks()
        loaded = true
    }
    val ratingMap by ratings.ratings.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf(LibFilter.ALL) }

    Column(Modifier.fillMaxSize()) {
        val ratedCount = tracks.count { ratingMap.containsKey(it.id) }
        ScreenHeader(
            "Music",
            "${tracks.size} tracks · $ratedCount rated · unrated plays as 3",
        ) {
            IconButton(onClick = onOpenSpotify) {
                Icon(
                    Icons.Rounded.CloudQueue,
                    contentDescription = "Spotify and SoundCloud",
                    tint = ActiColors.dim,
                )
            }
        }
        if (loaded && tracks.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No music found on this device.\nCopy some files into Music/ and reopen.",
                    color = ActiColors.dim,
                    textAlign = TextAlign.Center,
                )
            }
            return@Column
        }
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
                    shape = RoundedCornerShape(50),
                    label = { Text("${f.label} · $count") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = ActiColors.snow,
                        selectedLabelColor = ActiColors.ground,
                    ),
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
            .padding(horizontal = 16.dp, vertical = 7.dp),
    ) {
        ArtTile(energyTint(rating))
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
                color = ActiColors.dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        RatingBoxes(rating, box = 28.dp, onRate = onRate)
    }
}

private fun Long.fmtDuration(): String {
    val totalSec = this / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
}
