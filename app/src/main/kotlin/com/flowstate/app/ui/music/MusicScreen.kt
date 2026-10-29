package com.flowstate.app.ui.music

import androidx.compose.material3.LocalTextStyle
import com.flowstate.app.ui.Tabular
import com.flowstate.app.ui.Caption
import com.flowstate.app.ui.Display
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import com.flowstate.app.ui.ActiBottomSheet
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flowstate.app.ui.ActiColors
import com.flowstate.app.ui.ScreenHeader
import com.flowstate.data.EnergyResolver
import com.flowstate.data.LocalEnergies
import com.flowstate.data.MediaLibrary
import com.flowstate.data.PlaylistSummary
import com.flowstate.data.PoolRepository
import com.flowstate.data.RatingsRepository
import com.flowstate.data.Track
import com.flowstate.data.db.EnergyLevel
import com.flowstate.data.db.PoolSourceEntity
import com.flowstate.data.db.PoolSourceKind

/**
 * Screen C1 — the three energy pools and what feeds them.
 *
 * This is the contract the whole app rests on: the engine only ever picks from these
 * pools, so what a user puts here is exactly what they will hear.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicScreen(
    library: MediaLibrary,
    ratings: RatingsRepository,
    pools: PoolRepository,
    onAddTo: (EnergyLevel) -> Unit,
    onOpenPool: (EnergyLevel) -> Unit,
    onOpenUnsorted: () -> Unit,
    onOpenSpotify: () -> Unit,
) {
    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var playlists by remember { mutableStateOf<List<PlaylistSummary>>(emptyList()) }
    LaunchedEffect(Unit) {
        tracks = library.tracks()
        playlists = library.playlists()
    }
    val sources by pools.sources.collectAsStateWithLifecycle()
    val ratingMap by ratings.ratings.collectAsStateWithLifecycle()
    val excluded by ratings.excluded.collectAsStateWithLifecycle()
    var pickLevelFor by remember { mutableStateOf(false) }

    val energies = LocalEnergies(ratingMap, excluded)
    val members = playlistMembersByKey(playlists)
    val counts = EnergyResolver.trackCounts(tracks, energies, sources, members)
    val unsorted = EnergyResolver.unsorted(tracks, energies, sources, members).size
    // Which level each track actually resolved to — a folder's own track count says
    // nothing about what it contributes once ratings and exclusions have had their say.
    val resolvedLevel = EnergyResolver.resolve(tracks, energies, sources, members)
        .associate { it.track.id to EnergyResolver.levelOf(it.band) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader("Music") {
            IconButton(onClick = onOpenSpotify) {
                Icon(
                    Icons.Rounded.CloudQueue,
                    contentDescription = "Streaming services",
                    tint = ActiColors.dim,
                )
            }
            AddPill(onClick = { pickLevelFor = true })
            Spacer(Modifier.width(8.dp))
        }
        Text(
            "Three energy levels. Fill each from local folders, albums or playlists. " +
                "The engine only ever picks from these.",
            style = MaterialTheme.typography.bodyMedium,
            color = ActiColors.dim,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        Spacer(Modifier.height(12.dp))

        EnergyLevel.entries.reversed().forEach { level ->
            PoolCard(
                level = level,
                trackCount = counts[level] ?: 0,
                sources = sources.filter { it.level == level },
                tracks = tracks,
                playlists = playlists,
                resolvedLevel = resolvedLevel,
                onOpen = { onOpenPool(level) },
                onAdd = { onAddTo(level) },
            )
        }

        if (unsorted > 0) {
            UnsortedBanner(unsorted, onOpenUnsorted)
        }
        Spacer(Modifier.height(24.dp))
    }

    if (pickLevelFor) {
        ActiBottomSheet(onDismiss = { pickLevelFor = false }) {
            Text(
                "Add music to",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 20.dp, bottom = 8.dp),
            )
            EnergyLevel.entries.reversed().forEach { level ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            pickLevelFor = false
                            onAddTo(level)
                        }
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                ) {
                    Box(Modifier.size(10.dp).background(level.color, CircleShape))
                    Spacer(Modifier.width(12.dp))
                    Text(level.displayName, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Spacer(Modifier.weight(1f))
                    Text(
                        level.modes,
                        style = MaterialTheme.typography.labelSmall,
                        color = ActiColors.dim,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AddPill(onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(ActiColors.surfaceHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Icon(Icons.Rounded.Add, contentDescription = null, Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text("Add", fontWeight = FontWeight.Bold)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PoolCard(
    level: EnergyLevel,
    trackCount: Int,
    sources: List<PoolSourceEntity>,
    tracks: List<Track>,
    playlists: List<PlaylistSummary>,
    resolvedLevel: Map<Long, EnergyLevel>,
    onOpen: () -> Unit,
    onAdd: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(ActiColors.surface)
            // The colour bar is the pool's identity; it matches the mode colour it feeds.
            // Drawn rather than laid out, so it spans the card however many lines the
            // chips wrap to — a fixed 112 dp bar fell short, and intrinsic measuring
            // clipped the second line of chips off entirely.
            .drawBehind { drawRect(level.color, size = Size(4.dp.toPx(), size.height)) }
            .clickable(onClick = onOpen),
    ) {
        Spacer(Modifier.width(4.dp))
        Column(Modifier.padding(16.dp).fillMaxWidth()) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(level.displayName, fontFamily = Display, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Caption(level.modes)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "$trackCount",
                        fontFamily = Display,
                        style = LocalTextStyle.current.merge(Tabular),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Caption(if (trackCount == 1) "track" else "tracks")
                }
            }
            Spacer(Modifier.height(12.dp))
            // Wrapped, not squeezed two to a row: halving the width middle-ellipsised
            // "Music/Samsung" into "Music/S…sung" with the card half empty.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                sources.take(MAX_CHIPS).forEach { source ->
                    SourceChip(
                        source,
                        contributing = source.contributing(tracks, playlists, resolvedLevel),
                        total = source.trackCount(tracks, playlists),
                    )
                }
                if (sources.size > MAX_CHIPS) {
                    SourceChipLabel("+${sources.size - MAX_CHIPS} more", ActiColors.dim)
                }
                if (sources.isEmpty()) {
                    SourceChipLabel("Nothing here yet — tap Add", ActiColors.dim)
                }
            }
        }
    }
}

private const val MAX_CHIPS = 4

/**
 * A pool source and what it actually gives this pool. A folder can hold 161 tracks and
 * supply three — the rest excluded, or rated into another level — and a chip reading
 * "161" beside a pool of 3 looked like the counting was broken.
 */
@Composable
private fun SourceChip(source: PoolSourceEntity, contributing: Int, total: Int, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(ActiColors.surfaceHigh)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .background(
                    // Spotify sources get the service's own mark; local ones stay neutral.
                    if (source.kind == PoolSourceKind.SPOTIFY_PLAYLIST) {
                        ActiColors.spotify
                    } else {
                        ActiColors.dim
                    },
                    CircleShape,
                ),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "${source.label} · ${if (contributing == total) "$total" else "$contributing of $total"}",
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
        )
    }
}

@Composable
private fun SourceChipLabel(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

@Composable
private fun UnsortedBanner(count: Int, onOpen: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, ActiColors.outline, RoundedCornerShape(20.dp))
            .clickable(onClick = onOpen)
            .padding(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("$count unsorted tracks", fontWeight = FontWeight.Bold)
            Text(
                "Not in any pool, so they never play. Sort them into one.",
                style = MaterialTheme.typography.bodySmall,
                color = ActiColors.dim,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            "Sort",
            fontWeight = FontWeight.Bold,
            color = ActiColors.ground,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(ActiColors.snow)
                .padding(horizontal = 20.dp, vertical = 12.dp),
        )
    }
}
