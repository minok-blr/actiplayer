package com.flowstate.app.ui.music

import com.flowstate.app.ui.joinNonEmpty
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flowstate.app.ui.ActiColors
import com.flowstate.app.ui.ArtTile
import com.flowstate.app.ui.energyTint
import com.flowstate.app.ui.rememberAlbumArt
import com.flowstate.data.EnergyResolver
import com.flowstate.data.LocalEnergies
import com.flowstate.data.MediaLibrary
import com.flowstate.data.PlaylistSummary
import com.flowstate.data.PoolRepository
import com.flowstate.data.RatingsRepository
import com.flowstate.data.Track
import com.flowstate.data.db.EnergyLevel

/** The buckets the C4 filter pills switch between. */
enum class BulkFilter(val label: String) {
    HYPE("Hype"),
    MID("Mid"),
    CHILL("Chill"),
    UNSORTED("Unsorted"),
    EXCLUDED("Excluded"),
    ;

    val level: EnergyLevel?
        get() = when (this) {
            HYPE -> EnergyLevel.HYPE
            MID -> EnergyLevel.MID
            CHILL -> EnergyLevel.CHILL
            else -> null
        }
}

/**
 * Screen C4 — select many tracks, give them one energy.
 *
 * Moving a track here writes an explicit rating, which outranks whatever pool its folder
 * belongs to; excluding it keeps it out of every pool for good. That is how a single
 * wrong track inside an otherwise perfect folder gets fixed without breaking the folder.
 */
@Composable
fun BulkEditScreen(
    initial: BulkFilter,
    library: MediaLibrary,
    ratings: RatingsRepository,
    pools: PoolRepository,
    onClose: () -> Unit,
) {
    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var playlists by remember { mutableStateOf<List<PlaylistSummary>>(emptyList()) }
    LaunchedEffect(Unit) {
        tracks = library.tracks()
        playlists = library.playlists()
    }
    val sources by pools.sources.collectAsStateWithLifecycle()
    val ratingMap by ratings.ratings.collectAsStateWithLifecycle()
    val excludedIds by ratings.excluded.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf(initial) }
    val selected = remember { mutableStateListOf<Long>() }

    val energies = LocalEnergies(ratingMap, excludedIds)
    val members = playlistMembersByKey(playlists)
    val resolved = EnergyResolver.resolve(tracks, energies, sources, members)
    val bandById = resolved.associate { it.track.id to it.band }

    val buckets: Map<BulkFilter, List<Track>> = remember(tracks, energies, sources, members) {
        val unsorted = EnergyResolver.unsorted(tracks, energies, sources, members)
        val byLevel = resolved.groupBy { EnergyResolver.levelOf(it.band) }
        mapOf(
            BulkFilter.HYPE to (byLevel[EnergyLevel.HYPE]?.map { it.track } ?: emptyList()),
            BulkFilter.MID to (byLevel[EnergyLevel.MID]?.map { it.track } ?: emptyList()),
            BulkFilter.CHILL to (byLevel[EnergyLevel.CHILL]?.map { it.track } ?: emptyList()),
            BulkFilter.UNSORTED to unsorted,
            BulkFilter.EXCLUDED to tracks.filter { it.id in excludedIds },
        )
    }
    val visible = buckets[filter].orEmpty()

    Column(Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.Close, contentDescription = "Close", tint = ActiColors.snow)
            }
            Text(
                if (selected.isEmpty()) "Sort music" else "${selected.size} selected",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (selected.size == visible.size && visible.isNotEmpty()) "None" else "All",
                color = ActiColors.lift,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clickable {
                        if (selected.size == visible.size) {
                            selected.clear()
                        } else {
                            selected.clear()
                            selected.addAll(visible.map { it.id })
                        }
                    }
                    .padding(12.dp),
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            BulkFilter.entries.forEach { entry ->
                FilterPill(
                    label = "${entry.label} · ${buckets[entry]?.size ?: 0}",
                    selected = filter == entry,
                ) {
                    filter = entry
                    selected.clear()
                }
            }
        }

        if (visible.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    when (filter) {
                        BulkFilter.UNSORTED -> "Everything is sorted."
                        BulkFilter.EXCLUDED -> "Nothing excluded."
                        else -> "No tracks in ${filter.label} yet."
                    },
                    color = ActiColors.dim,
                )
            }
        } else {
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(visible, key = { it.id }) { track ->
                    TrackRow(
                        track = track,
                        band = bandById[track.id],
                        checked = track.id in selected,
                        onToggle = {
                            if (track.id in selected) selected.remove(track.id)
                            else selected.add(track.id)
                        },
                    )
                }
            }
        }

        if (selected.isNotEmpty()) {
            MoveBar(
                count = selected.size,
                current = filter,
                onMove = { level ->
                    ratings.setBands(selected.toList(), level?.band)
                    selected.clear()
                },
                onClear = {
                    ratings.clear(selected.toList())
                    selected.clear()
                },
            )
        }
    }
}

@Composable
private fun FilterPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        color = if (selected) ActiColors.ground else ActiColors.snow,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) ActiColors.snow else ActiColors.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

@Composable
private fun TrackRow(track: Track, band: Int?, checked: Boolean, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Box(
            Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(if (checked) ActiColors.snow else Color.Transparent)
                .border(
                    width = if (checked) 0.dp else 1.5.dp,
                    color = ActiColors.dim,
                    shape = CircleShape,
                ),
        )
        Spacer(Modifier.width(12.dp))
        ArtTile(
            tint = energyTint(band),
            size = 40.dp,
            art = rememberAlbumArt(track.id),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                joinNonEmpty(track.artist, formatTrackLength(track.durationMs)),
                style = MaterialTheme.typography.bodySmall,
                color = ActiColors.dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        if (band != null) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(EnergyResolver.levelOf(band).color, CircleShape),
            )
        }
    }
}

@Composable
private fun MoveBar(
    count: Int,
    current: BulkFilter,
    onMove: (EnergyLevel?) -> Unit,
    onClear: () -> Unit,
) {
    Column(Modifier.background(ActiColors.surface).padding(16.dp)) {
        Text(
            "Move $count to",
            style = MaterialTheme.typography.labelMedium,
            color = ActiColors.dim,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // Offer the levels this selection is not already in, then the two escapes.
            EnergyLevel.entries.reversed()
                .filter { it != current.level }
                .forEach { level ->
                    MoveButton(level.displayName, level.color, Modifier.weight(1f)) {
                        onMove(level)
                    }
                }
            if (current != BulkFilter.EXCLUDED) {
                MoveButton("Exclude", ActiColors.dim, Modifier.weight(1f)) { onMove(null) }
            } else {
                MoveButton("Unsort", ActiColors.dim, Modifier.weight(1f)) { onClear() }
            }
        }
    }
}

@Composable
private fun MoveButton(label: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Text(
        label,
        color = color,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.labelLarge,
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 20.dp),
    )
}
