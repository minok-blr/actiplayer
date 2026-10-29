package com.flowstate.app.ui.music

import com.flowstate.app.ui.Tabular
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flowstate.app.ui.ActiColors
import com.flowstate.data.MediaLibrary
import com.flowstate.data.PlaylistSummary
import com.flowstate.data.PoolRepository
import com.flowstate.data.SourceSummary
import com.flowstate.data.Track
import com.flowstate.data.db.EnergyLevel
import com.flowstate.data.db.PoolSourceKind

/**
 * Screen C2 — pick folders, albums or playlists to feed one pool.
 *
 * A source lives in exactly one pool, so a row already in a different level shows where
 * it is and checking it *moves* it. Unchecking a row that is in this pool removes it,
 * which makes this the one screen where a pool's contents are edited.
 */
@Composable
fun AddToPoolScreen(
    level: EnergyLevel,
    library: MediaLibrary,
    pools: PoolRepository,
    onDone: () -> Unit,
) {
    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var playlists by remember { mutableStateOf<List<PlaylistSummary>>(emptyList()) }
    LaunchedEffect(Unit) {
        tracks = library.tracks()
        playlists = library.playlists()
    }
    val existing by pools.sources.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(BrowseTab.FOLDERS) }
    var query by remember { mutableStateOf("") }

    // Selection starts as "what is already in this pool" so the screen shows the truth.
    val selected = remember { mutableStateMapOf<String, SourceSummary>() }
    var seeded by remember { mutableStateOf(false) }
    val alreadyHere = existing.filter { it.level == level }.map { "${it.kind}:${it.key}" }.toSet()
    LaunchedEffect(alreadyHere, tracks, playlists) {
        if (seeded || tracks.isEmpty()) return@LaunchedEffect
        BrowseTab.entries.forEach { t ->
            sourcesFor(tracks, t, playlists).forEach { source ->
                if ("${source.kind}:${source.key}" in alreadyHere) {
                    selected["${source.kind}:${source.key}"] = source
                }
            }
        }
        seeded = true
    }

    val levelByKey = existing.associate { "${it.kind}:${it.key}" to it.level }
    val visible = sourcesFor(tracks, tab, playlists)
        .filter { query.isBlank() || it.label.contains(query, ignoreCase = true) }

    val added = selected.values.filter { levelByKey["${it.kind}:${it.key}"] != level }
    val removed = alreadyHere.filter { it !in selected.keys }

    Column(Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            Text(
                "‹ Music",
                color = ActiColors.dim,
                modifier = Modifier.clickable(onClick = onDone),
            )
            Spacer(Modifier.weight(1f))
            Text(
                "Adding to ${level.displayName}",
                style = MaterialTheme.typography.labelMedium,
                color = level.color,
                fontWeight = FontWeight.Bold,
            )
        }

        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(ActiColors.surface)
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            if (query.isEmpty()) {
                Text("Search folders, albums, playlists", color = ActiColors.dim)
            }
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(color = ActiColors.snow, fontSize = 15.sp),
                cursorBrush = SolidColor(ActiColors.snow),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            BrowseTab.entries.forEach { entry ->
                TabPill(entry.label, tab == entry) { tab = entry }
            }
        }

        if (tab == BrowseTab.PLAYLISTS && playlists.isEmpty()) {
            EmptyPlaylists()
            return@Column
        }

        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(visible, key = { "${it.kind}:${it.key}" }) { source ->
                val id = "${source.kind}:${source.key}"
                SourceRow(
                    source = source,
                    checked = id in selected,
                    currentLevel = levelByKey[id],
                    poolLevel = level,
                    onToggle = {
                        if (id in selected) selected.remove(id) else selected[id] = source
                    },
                )
            }
        }

        val addedTracks = added.sumOf { it.trackCount }
        val label = when {
            added.isNotEmpty() -> "Add $addedTracks tracks to ${level.displayName}"
            removed.isNotEmpty() -> "Update ${level.displayName}"
            // What is already in the pool shows checked, so "nothing selected" read as
            // wrong the moment the screen opened.
            selected.isNotEmpty() -> "No changes"
            else -> "Nothing selected"
        }
        val enabled = added.isNotEmpty() || removed.isNotEmpty()
        Text(
            label,
            fontWeight = FontWeight.Bold,
            fontSize = 17.sp,
            color = if (enabled) ActiColors.ground else ActiColors.dim,
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(if (enabled) level.color else ActiColors.surface)
                .clickable(enabled = enabled) {
                    pools.add(level, added)
                    removed.forEach { id ->
                        val (kind, key) = id.split(":", limit = 2)
                        pools.remove(PoolSourceKind.valueOf(kind), key)
                    }
                    onDone()
                }
                .padding(vertical = 20.dp),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun TabPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        color = if (selected) ActiColors.ground else ActiColors.snow,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) ActiColors.snow else ActiColors.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
    )
}

@Composable
private fun SourceRow(
    source: SourceSummary,
    checked: Boolean,
    currentLevel: EnergyLevel?,
    poolLevel: EnergyLevel,
    onToggle: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(ActiColors.surface)
            .border(
                width = if (checked) 1.dp else 0.dp,
                color = if (checked) ActiColors.snow else ActiColors.surface,
                shape = RoundedCornerShape(16.dp),
            )
            .clickable(onClick = onToggle)
            .padding(14.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (checked) ActiColors.snow else ActiColors.surface)
                // An empty box needs an edge: dark-on-dark, unchecked rows had no visible
                // checkbox at all.
                .border(
                    width = if (checked) 0.dp else 1.5.dp,
                    color = if (checked) ActiColors.snow else ActiColors.dim,
                    shape = RoundedCornerShape(8.dp),
                ),
        ) {
            if (checked) {
                Icon(
                    Icons.Rounded.Check,
                    contentDescription = null,
                    tint = ActiColors.ground,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                source.label,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                // The tail of the path is the part that identifies the folder.
                overflow = TextOverflow.MiddleEllipsis,
            )
            Text(
                "${source.trackCount} ${if (source.trackCount == 1) "track" else "tracks"}" +
                    if (source.durationMs > 0) " · ${formatDuration(source.durationMs)}" else "",
                style = MaterialTheme.typography.bodySmall.merge(Tabular),
                color = ActiColors.dim,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(8.dp))
        // Capped so a long badge cannot squeeze the label out of the row.
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.widthIn(max = 108.dp)) {
            when {
                currentLevel == poolLevel -> Badge("In ${poolLevel.displayName}", ActiColors.dim)
                currentLevel != null -> Badge("In ${currentLevel.displayName}", currentLevel.color)
                else -> Unit
            }
            // Podcasts are dropped by the resolver, so say so rather than letting the
            // user add a folder and wonder why the pool count did not move.
            if (source.podcastCount > 0) {
                Badge("${source.podcastCount} podcasts skipped", ActiColors.hot)
            }
        }
    }
}

@Composable
private fun Badge(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        textAlign = TextAlign.End,
    )
}

@Composable
private fun EmptyPlaylists() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(32.dp),
    ) {
        Text("No device playlists", fontWeight = FontWeight.Bold)
        Text(
            "Android deprecated system playlists in Android 12, and most phones no longer " +
                "have any. Folders and albums work the same way here.",
            style = MaterialTheme.typography.bodySmall,
            color = ActiColors.dim,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
    }
}
