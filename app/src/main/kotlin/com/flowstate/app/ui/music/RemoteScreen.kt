package com.flowstate.app.ui.music

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flowstate.app.ui.ActiColors
import com.flowstate.app.ui.ArtTile
import com.flowstate.app.ui.RatingBoxes
import com.flowstate.app.ui.ScreenHeader
import com.flowstate.data.SettingsStore
import com.flowstate.data.SpotifyRatingsRepository
import com.flowstate.playback.spotify.SpotifyRemote
import com.flowstate.playback.spotify.SpotifyTrack
import java.net.URLEncoder

/**
 * Streaming, reached from the Music tab: browse streaming services from inside
 * ActiPlayer. Both services keep their audio locked to their own players, so this is
 * browse-and-hand-off (Spotify) and an embedded web player (SoundCloud) — the adaptive
 * engine still runs on the local library.
 */
@Composable
fun RemoteScreen(spotify: SpotifyRemote, settings: SettingsStore, spotifyRatings: SpotifyRatingsRepository) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenHeader("Streaming", "your services, wired into ActiPlayer")
        Column(
            Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            SpotifyCard(spotify, spotifyRatings)
            SoundCloudCard(settings)
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ---- Spotify ---------------------------------------------------------------------------

@Composable
private fun SpotifyCard(spotify: SpotifyRemote, spotifyRatings: SpotifyRatingsRepository) {
    val context = LocalContext.current
    val state by spotify.state.collectAsStateWithLifecycle()
    val error by spotify.error.collectAsStateWithLifecycle()
    val clientId by spotify.clientId.collectAsStateWithLifecycle()
    var editingId by remember { mutableStateOf(false) }

    // Resume a remembered connection as soon as the tab is opened.
    LaunchedEffect(Unit) { spotify.autoLoad() }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        RemoteSectionHeader("Spotify", ActiColors.spotify)

        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = ActiColors.hot) }

        when (val s = state) {
            SpotifyRemote.State.Disconnected, SpotifyRemote.State.Ready -> {
                if (clientId.isEmpty() || editingId) {
                    ClientIdSetup(clientId) { id ->
                        spotify.setClientId(id)
                        editingId = false
                        spotify.beginAuth(context)
                    }
                } else {
                    Text(
                        "Sign in with your Spotify account to list your playlists here " +
                            "and open them in the Spotify app.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ActiColors.dim,
                    )
                    Button(
                        onClick = { spotify.beginAuth(context) },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("Connect Spotify", fontWeight = FontWeight.Bold)
                    }
                    TextButton(onClick = { editingId = true }) {
                        Text("Change Client ID", color = ActiColors.dim)
                    }
                }
            }

            SpotifyRemote.State.Authorizing -> {
                Text(
                    "Waiting for the Spotify sign-in to finish in your browser…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ActiColors.dim,
                )
                TextButton(onClick = { spotify.cancelAuth() }) {
                    Text("Cancel", color = ActiColors.dim)
                }
            }

            SpotifyRemote.State.Loading -> {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        "Loading playlists…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ActiColors.dim,
                    )
                }
            }

            is SpotifyRemote.State.Connected -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Connected as ${s.account}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { spotify.loadPlaylists() }) {
                        Text("Refresh", color = ActiColors.lift)
                    }
                    TextButton(onClick = { spotify.disconnect() }) {
                        Text("Sign out", color = ActiColors.dim)
                    }
                }
                val ratedCount = spotifyRatings.tracks.collectAsStateWithLifecycle().value.size
                Text(
                    "Tap a playlist to rate its tracks 1–5. Rated tracks become the pool " +
                        "when a session's music source is Spotify (Premium). " +
                        if (ratedCount > 0) "$ratedCount rated so far." else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = ActiColors.dim,
                )
                if (s.playlists.isEmpty()) {
                    Text(
                        "No playlists on this account.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ActiColors.dim,
                    )
                }

                var expandedId by remember { mutableStateOf<String?>(null) }
                val trackCache = remember { mutableStateMapOf<String, List<SpotifyTrack>>() }
                var tracksError by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(expandedId) {
                    val id = expandedId ?: return@LaunchedEffect
                    if (trackCache.containsKey(id)) return@LaunchedEffect
                    tracksError = null
                    runCatching { spotify.fetchPlaylistTracks(id) }
                        .onSuccess { trackCache[id] = it }
                        .onFailure { tracksError = "Couldn't load tracks: ${it.message}" }
                }

                s.playlists.forEach { playlist ->
                    val expanded = expandedId == playlist.id
                    PlaylistRow(
                        playlist = playlist,
                        expanded = expanded,
                        onToggle = { expandedId = if (expanded) null else playlist.id },
                        onOpen = { spotify.openPlaylist(context, playlist) },
                    )
                    if (expanded) {
                        val tracks = trackCache[playlist.id]
                        when {
                            tracksError != null -> Text(
                                tracksError!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = ActiColors.hot,
                            )
                            tracks == null -> Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(start = 12.dp),
                            ) {
                                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                Text(
                                    "Loading tracks…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = ActiColors.dim,
                                )
                            }
                            else -> tracks.forEach { track ->
                                SpotifyTrackRow(track, spotifyRatings)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ClientIdSetup(current: String, onConnect: (String) -> Unit) {
    var draft by remember { mutableStateOf(current) }
    Text(
        "One-time setup — Spotify requires an app key of your own (free):",
        style = MaterialTheme.typography.bodyMedium,
        color = ActiColors.dim,
    )
    Text(
        "1. developer.spotify.com → Dashboard → Create app\n" +
            "2. Add this Redirect URI:  ${SpotifyRemote.REDIRECT_URI}\n" +
            "3. Check \"Web API\", save, and paste the app's Client ID below.",
        style = MaterialTheme.typography.bodySmall,
        color = ActiColors.dim,
        lineHeight = 20.sp,
        modifier = Modifier
            .fillMaxWidth()
            .background(ActiColors.surfaceHigh, RoundedCornerShape(10.dp))
            .padding(12.dp),
    )
    OutlinedTextField(
        value = draft,
        onValueChange = { draft = it },
        label = { Text("Client ID") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = { onConnect(draft) },
        enabled = draft.isNotBlank(),
        modifier = Modifier.fillMaxWidth().height(48.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Text("Save & connect", fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun PlaylistRow(
    playlist: SpotifyRemote.Playlist,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(ActiColors.surfaceHigh)
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ArtTile(ActiColors.spotify, size = 40.dp, icon = Icons.AutoMirrored.Rounded.QueueMusic)
        Column(Modifier.weight(1f)) {
            Text(
                playlist.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                buildString {
                    append("${playlist.trackCount} tracks")
                    if (playlist.owner.isNotEmpty()) append(" · ${playlist.owner}")
                },
                style = MaterialTheme.typography.bodySmall,
                color = ActiColors.dim,
            )
        }
        Icon(
            if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
            contentDescription = if (expanded) "Collapse" else "Rate tracks",
            tint = ActiColors.dim,
        )
        Text(
            "Open",
            color = ActiColors.spotify,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onOpen)
                .padding(horizontal = 6.dp, vertical = 4.dp),
        )
    }
}

/** Compact rating row, same idiom as the local Library: five square energy boxes. */
@Composable
private fun SpotifyTrackRow(track: SpotifyTrack, ratings: SpotifyRatingsRepository) {
    val rated by ratings.tracks.collectAsStateWithLifecycle()
    val rating = rated[track.uri]?.rating
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
            )
            Text(
                track.artist,
                style = MaterialTheme.typography.bodySmall,
                color = ActiColors.dim,
                maxLines = 1,
            )
        }
        RatingBoxes(rating, box = 28.dp) { value -> ratings.setRating(track.uri, track.title, track.artist, value) }
    }
}

// ---- SoundCloud ------------------------------------------------------------------------

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SoundCloudCard(settings: SettingsStore) {
    val saved by settings.soundcloudUrl.collectAsStateWithLifecycle()
    var draft by remember(saved) { mutableStateOf(saved) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        RemoteSectionHeader("SoundCloud", ActiColors.soundcloud)
        Text(
            "SoundCloud's API keys now need an Artist Pro plan, so this uses their " +
                "public embedded player instead — paste a playlist or track link " +
                "(soundcloud.com/…).",
            style = MaterialTheme.typography.bodyMedium,
            color = ActiColors.dim,
        )
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text("Playlist or track URL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { settings.setSoundcloudUrl(draft) },
            enabled = draft.contains("soundcloud.com/"),
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text("Load player", fontWeight = FontWeight.Bold)
        }
        if (saved.isNotBlank()) {
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(420.dp)
                    .clip(RoundedCornerShape(12.dp)),
                factory = { ctx ->
                    WebView(ctx).apply {
                        this.settings.javaScriptEnabled = true
                        // The widget app stores state in localStorage and shows a blank
                        // page without it.
                        this.settings.domStorageEnabled = true
                        webViewClient = WebViewClient()
                        // Opaque background: a transparent, hardware-accelerated WebView
                        // renders the widget as a blank page on some devices.
                        setBackgroundColor(ActiColors.surface.toArgb())
                    }
                },
                update = { web ->
                    val target = widgetUrl(saved)
                    if (web.tag != target) {
                        web.tag = target
                        web.loadUrl(target)
                    }
                },
            )
        }
    }
}

/** SoundCloud's keyless widget player, tinted to the RIDING orange. */
private fun widgetUrl(trackOrPlaylistUrl: String): String =
    "https://w.soundcloud.com/player/?url=" +
        URLEncoder.encode(trackOrPlaylistUrl.trim(), "UTF-8") +
        "&color=%23ff5a1f&auto_play=false&show_comments=false&show_teaser=false&visual=true"

// ---- shared ----------------------------------------------------------------------------

@Composable
private fun RemoteSectionHeader(label: String, brand: androidx.compose.ui.graphics.Color) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(10.dp).background(brand, CircleShape))
            Text(
                label.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = ActiColors.snow,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
            )
        }
        HorizontalDivider(Modifier.padding(top = 8.dp), color = ActiColors.outline)
    }
}
