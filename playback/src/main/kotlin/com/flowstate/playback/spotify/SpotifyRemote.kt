package com.flowstate.playback.spotify

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Minimal Spotify Web API client for the Remote tab: Authorization Code + PKCE (the flow
 * Spotify mandates for apps that can't hold a secret — a custom-scheme redirect is still
 * allowed as of the 2025 security update), then read-only playlist listing. No SDK
 * dependency: one browser round-trip plus plain HTTPS calls.
 *
 * Spotify audio is DRM-locked to Spotify's own players, so this remote is browse-and-open:
 * tapping a playlist hands off to the Spotify app. Adaptive playback stays local-only.
 *
 * The Client ID is the user's own (developer.spotify.com → Create app, redirect URI
 * [REDIRECT_URI]) and is pasted in the UI — nothing is baked into the APK.
 */
/** One playable Spotify track; the URI is the stable identity ratings are keyed by. */
data class SpotifyTrack(val uri: String, val title: String, val artist: String)

@Singleton
class SpotifyRemote @Inject constructor(
    @ApplicationContext context: Context,
) {

    data class Playlist(val id: String, val name: String, val trackCount: Int, val owner: String)

    sealed interface State {
        /** No tokens stored — needs the browser sign-in. */
        data object Disconnected : State

        /** Connected in a past session; playlists not fetched yet this launch. */
        data object Ready : State

        /** Browser round-trip in flight. */
        data object Authorizing : State

        /** Talking to the Web API. */
        data object Loading : State

        data class Connected(val account: String, val playlists: List<Playlist>) : State
    }

    private val prefs = context.getSharedPreferences("remote", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _clientId = MutableStateFlow(prefs.getString(KEY_CLIENT_ID, "") ?: "")
    val clientId: StateFlow<String> = _clientId

    private val _state = MutableStateFlow<State>(
        if (prefs.contains(KEY_REFRESH_TOKEN)) State.Ready else State.Disconnected,
    )
    val state: StateFlow<State> = _state

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    fun setClientId(id: String) {
        val trimmed = id.trim()
        prefs.edit().putString(KEY_CLIENT_ID, trimmed).apply()
        _clientId.value = trimmed
    }

    /** Opens the Spotify consent page in the browser; the redirect lands in MainActivity. */
    fun beginAuth(from: Context) {
        val id = _clientId.value
        if (id.isEmpty()) {
            _error.value = "Paste your Spotify Client ID first."
            return
        }
        // The verifier must survive the browser round-trip even if the process dies.
        val verifier = randomVerifier()
        prefs.edit().putString(KEY_VERIFIER, verifier).apply()
        val uri = Uri.parse("https://accounts.spotify.com/authorize").buildUpon()
            .appendQueryParameter("client_id", id)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", base64Url(sha256(verifier.toByteArray())))
            .appendQueryParameter("scope", SCOPES)
            .build()
        _error.value = null
        _state.value = State.Authorizing
        from.startActivity(Intent(Intent.ACTION_VIEW, uri))
    }

    /** Escape hatch for a sign-in abandoned in the browser. */
    fun cancelAuth() {
        if (_state.value == State.Authorizing) {
            _state.value = if (prefs.contains(KEY_REFRESH_TOKEN)) State.Ready else State.Disconnected
        }
    }

    /** Returns true if the uri was our OAuth redirect (flowstate://spotify-auth). */
    fun handleRedirect(uri: Uri): Boolean {
        if (uri.scheme != "flowstate" || uri.host != "spotify-auth") return false
        val code = uri.getQueryParameter("code")
        val verifier = prefs.getString(KEY_VERIFIER, null)
        if (code == null || verifier == null) {
            _error.value = "Spotify sign-in failed: " +
                (uri.getQueryParameter("error") ?: "no code returned")
            cancelAuthToStored()
            return true
        }
        _state.value = State.Loading
        scope.launch {
            try {
                storeTokens(withContext(Dispatchers.IO) { exchangeCode(code, verifier) })
                loadNow()
            } catch (e: Exception) {
                _error.value = "Spotify sign-in failed: ${e.message}"
                cancelAuthToStored()
            }
        }
        return true
    }

    /** Called when the Remote tab opens: silently resumes a past connection. */
    fun autoLoad() {
        if (_state.value == State.Ready) loadPlaylists()
    }

    fun loadPlaylists() {
        if (_state.value == State.Loading || _state.value == State.Authorizing) return
        _state.value = State.Loading
        scope.launch {
            try {
                loadNow()
            } catch (e: Exception) {
                _error.value = "Couldn't load playlists: ${e.message}"
                cancelAuthToStored()
            }
        }
    }

    fun disconnect() {
        prefs.edit()
            .remove(KEY_ACCESS_TOKEN).remove(KEY_REFRESH_TOKEN)
            .remove(KEY_EXPIRY).remove(KEY_VERIFIER)
            .apply()
        _error.value = null
        _state.value = State.Disconnected
    }

    fun openPlaylist(from: Context, playlist: Playlist) {
        // The https link opens the Spotify app when installed, the web player otherwise.
        from.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com/playlist/${playlist.id}")),
        )
    }

    // ---- catalog (for rating) ----------------------------------------------------------

    /** All playable tracks of a playlist, for the rating UI. */
    suspend fun fetchPlaylistTracks(playlistId: String): List<SpotifyTrack> =
        withContext(Dispatchers.IO) {
            val token = validAccessToken()
            val tracks = mutableListOf<SpotifyTrack>()
            var url: String? = "$API/playlists/$playlistId/tracks?limit=100" +
                "&fields=next,items(track(uri,name,is_local,artists(name)))"
            while (url != null && tracks.size < 300) {
                val page = getJson(url, token)
                val items = page.optJSONArray("items") ?: break
                for (i in 0 until items.length()) {
                    val t = items.optJSONObject(i)?.optJSONObject("track") ?: continue
                    val uri = t.optString("uri")
                    // Local files and episodes can't be commanded by URI — skip them.
                    if (t.optBoolean("is_local") || !uri.startsWith("spotify:track:")) continue
                    val artists = t.optJSONArray("artists")
                    tracks += SpotifyTrack(
                        uri = uri,
                        title = t.optString("name").ifEmpty { "Untitled" },
                        artist = artists?.optJSONObject(0)?.optString("name").orEmpty(),
                    )
                }
                url = page.optString("next").takeIf { it.isNotEmpty() && it != "null" }
            }
            tracks
        }

    // ---- player control (Spotify Connect; needs Premium) --------------------------------

    /**
     * Replaces what the Spotify app is playing with this list of tracks (it then advances
     * through them on its own — that's what makes one call per band change enough).
     * Retries once against a concrete device when Spotify reports none active.
     */
    suspend fun playUris(uris: List<String>) = withContext(Dispatchers.IO) {
        val token = validAccessToken()
        val body = JSONObject().put("uris", JSONArray(uris)).toString()
        val first = send("PUT", "$API/me/player/play", token, body)
        if (first.code == 404) {
            val deviceId = firstDeviceId(token)
                ?: throw IOException(
                    "No Spotify device found — open the Spotify app once, then retry.",
                )
            requirePlayerOk(send("PUT", "$API/me/player/play?device_id=$deviceId", token, body))
        } else {
            requirePlayerOk(first)
        }
    }

    /** Best effort; session teardown must not fail because Spotify was already stopped. */
    suspend fun pausePlayback(): Unit = withContext(Dispatchers.IO) {
        runCatching { send("PUT", "$API/me/player/pause", validAccessToken(), "") }
        Unit
    }

    /** "Title — Artist" of whatever the Spotify app plays right now, or null. */
    suspend fun currentlyPlayingLabel(): String? = withContext(Dispatchers.IO) {
        val result = send("GET", "$API/me/player/currently-playing", validAccessToken(), null)
        val item = result.json?.optJSONObject("item") ?: return@withContext null
        val title = item.optString("name").ifEmpty { return@withContext null }
        val artist = item.optJSONArray("artists")?.optJSONObject(0)?.optString("name").orEmpty()
        if (artist.isEmpty()) title else "$title — $artist"
    }

    private fun firstDeviceId(token: String): String? {
        val devices = send("GET", "$API/me/player/devices", token, null)
            .json?.optJSONArray("devices") ?: return null
        // Prefer this phone; Connect can otherwise resurrect a desktop at home.
        for (i in 0 until devices.length()) {
            val d = devices.optJSONObject(i) ?: continue
            if (d.optString("type").equals("Smartphone", ignoreCase = true)) {
                return d.optString("id").takeIf { it.isNotEmpty() }
            }
        }
        return devices.optJSONObject(0)?.optString("id")?.takeIf { it.isNotEmpty() }
    }

    private fun requirePlayerOk(result: HttpResult) {
        when {
            result.code in 200..299 -> Unit
            result.code == 403 -> throw IOException(
                "Spotify refused playback control — this needs a Premium account.",
            )
            result.code == 404 -> throw IOException(
                "No active Spotify device — open Spotify, play anything once, then retry.",
            )
            else -> throw IOException(errorDetail(result))
        }
    }

    // ---- internals -------------------------------------------------------------------

    private fun cancelAuthToStored() {
        _state.value = if (prefs.contains(KEY_REFRESH_TOKEN)) State.Ready else State.Disconnected
    }

    private suspend fun loadNow() {
        val result = withContext(Dispatchers.IO) {
            val token = validAccessToken()
            fetchAccountAndPlaylists(token)
        }
        _error.value = null
        _state.value = State.Connected(result.first, result.second)
    }

    private fun exchangeCode(code: String, verifier: String): JSONObject = postForm(
        TOKEN_URL,
        mapOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to REDIRECT_URI,
            "client_id" to _clientId.value,
            "code_verifier" to verifier,
        ),
    )

    /** Returns a live access token, refreshing through the stored refresh token if stale. */
    private fun validAccessToken(): String {
        val access = prefs.getString(KEY_ACCESS_TOKEN, null)
        if (access != null && System.currentTimeMillis() < prefs.getLong(KEY_EXPIRY, 0) - 60_000) {
            return access
        }
        val refresh = prefs.getString(KEY_REFRESH_TOKEN, null)
            ?: throw IOException("not connected")
        val json = postForm(
            TOKEN_URL,
            mapOf(
                "grant_type" to "refresh_token",
                "refresh_token" to refresh,
                "client_id" to _clientId.value,
            ),
        )
        storeTokens(json)
        return json.getString("access_token")
    }

    private fun storeTokens(json: JSONObject) {
        val editor = prefs.edit()
            .putString(KEY_ACCESS_TOKEN, json.getString("access_token"))
            .putLong(
                KEY_EXPIRY,
                System.currentTimeMillis() + json.optLong("expires_in", 3600) * 1000,
            )
        // Spotify rotates refresh tokens on some refreshes; keep the old one if absent.
        json.optString("refresh_token").takeIf { it.isNotEmpty() }?.let {
            editor.putString(KEY_REFRESH_TOKEN, it)
        }
        editor.remove(KEY_VERIFIER).apply()
    }

    private fun fetchAccountAndPlaylists(token: String): Pair<String, List<Playlist>> {
        val me = getJson("$API/me", token)
        val account = me.optString("display_name").ifEmpty { me.optString("id", "Spotify") }
        val lists = mutableListOf<Playlist>()
        var url: String? = "$API/me/playlists?limit=50"
        while (url != null && lists.size < 200) {
            val page = getJson(url, token)
            val items = page.optJSONArray("items") ?: break
            for (i in 0 until items.length()) {
                val o = items.optJSONObject(i) ?: continue
                lists += Playlist(
                    id = o.optString("id"),
                    name = o.optString("name").ifEmpty { "Untitled" },
                    trackCount = o.optJSONObject("tracks")?.optInt("total") ?: 0,
                    owner = o.optJSONObject("owner")?.optString("display_name").orEmpty(),
                )
            }
            url = page.optString("next").takeIf { it.isNotEmpty() && it != "null" }
        }
        return account to lists
    }

    private fun postForm(url: String, form: Map<String, String>): JSONObject {
        val body = form.entries.joinToString("&") { (k, v) ->
            "$k=${URLEncoder.encode(v, "UTF-8")}"
        }
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.outputStream.use { it.write(body.toByteArray()) }
            readJson(conn)
        } finally {
            conn.disconnect()
        }
    }

    private fun getJson(url: String, token: String): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.setRequestProperty("Authorization", "Bearer $token")
            readJson(conn)
        } finally {
            conn.disconnect()
        }
    }

    private fun readJson(conn: HttpURLConnection): JSONObject {
        val status = conn.responseCode
        val text = (if (status in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (status !in 200..299) {
            throw IOException(errorDetail(HttpResult(status, runCatching { JSONObject(text) }.getOrNull())))
        }
        return JSONObject(text)
    }

    private data class HttpResult(val code: Int, val json: JSONObject?)

    /** Like [getJson]/[postForm] but returns the status instead of throwing — the player
     *  endpoints use 404 ("no active device") as a normal answer we react to. */
    private fun send(method: String, url: String, token: String, body: String?): HttpResult {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = method
            conn.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            HttpResult(code, runCatching { JSONObject(text) }.getOrNull())
        } finally {
            conn.disconnect()
        }
    }

    private fun errorDetail(result: HttpResult): String =
        result.json?.optString("error_description")?.ifEmpty { null }
            ?: result.json?.optJSONObject("error")?.optString("message")?.ifEmpty { null }
            ?: "HTTP ${result.code}"

    private fun randomVerifier(): String {
        val bytes = ByteArray(64)
        SecureRandom().nextBytes(bytes)
        return base64Url(bytes)
    }

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    private fun base64Url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    companion object {
        /** Must match a Redirect URI added in the user's Spotify app settings. */
        const val REDIRECT_URI = "flowstate://spotify-auth"

        private const val TOKEN_URL = "https://accounts.spotify.com/api/token"
        private const val API = "https://api.spotify.com/v1"
        // Read scopes list playlists/tracks for rating; the playback scopes drive the
        // Spotify app during sessions (control itself additionally requires Premium).
        private const val SCOPES = "playlist-read-private playlist-read-collaborative " +
            "user-read-playback-state user-modify-playback-state"

        private const val KEY_CLIENT_ID = "spotify_client_id"
        private const val KEY_ACCESS_TOKEN = "spotify_access_token"
        private const val KEY_REFRESH_TOKEN = "spotify_refresh_token"
        private const val KEY_EXPIRY = "spotify_token_expiry"
        private const val KEY_VERIFIER = "spotify_pkce_verifier"
    }
}
