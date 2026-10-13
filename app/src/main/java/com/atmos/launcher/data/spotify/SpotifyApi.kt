package com.atmos.launcher.data.spotify

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

// ───────────────────────── Modelos (solo los campos que usamos) ─────────────────────────

@Immutable @Serializable data class SpImage(val url: String, val width: Int? = null)
@Immutable @Serializable data class SpFollowers(val total: Int = 0)
@Immutable @Serializable data class SpArtistRef(val id: String? = null, val name: String = "", val uri: String = "")

@Immutable @Serializable
data class SpArtist(
    val id: String = "", val name: String = "", val uri: String = "",
    val genres: List<String> = emptyList(), val images: List<SpImage> = emptyList(),
    val followers: SpFollowers? = null, val popularity: Int = 0
)

@Immutable @Serializable
data class SpAlbum(
    val id: String = "", val name: String = "", val uri: String = "",
    val images: List<SpImage> = emptyList(), @SerialName("release_date") val releaseDate: String = "",
    val artists: List<SpArtistRef> = emptyList(), @SerialName("total_tracks") val totalTracks: Int = 0,
    @SerialName("album_type") val albumType: String = ""
)

@Immutable @Serializable
data class SpTrack(
    val id: String? = null, val name: String = "", val uri: String = "",
    @SerialName("duration_ms") val durationMs: Long = 0, val explicit: Boolean = false,
    val artists: List<SpArtistRef> = emptyList(), val album: SpAlbum? = null
)

@Immutable @Serializable data class SpTracksRef(val total: Int = 0)
@Immutable @Serializable data class SpOwner(@SerialName("display_name") val name: String? = null)

@Immutable @Serializable
data class SpPlaylist(
    val id: String = "", val name: String = "", val uri: String = "",
    val images: List<SpImage>? = null, val tracks: SpTracksRef? = null, val owner: SpOwner? = null
)

@Serializable data class Paging<T>(val items: List<T> = emptyList(), val total: Int = 0)
@Serializable data class SavedAlbum(val album: SpAlbum)
@Serializable data class PlaylistItem(val track: SpTrack? = null)
@Serializable data class FollowedArtists(val artists: Paging<SpArtist> = Paging())
@Serializable data class SearchResult(val tracks: Paging<SpTrack>? = null, val artists: Paging<SpArtist>? = null)
@Serializable data class TopTracks(val tracks: List<SpTrack> = emptyList())
@Serializable data class QueueResp(@SerialName("currently_playing") val current: SpTrack? = null, val queue: List<SpTrack> = emptyList())

fun List<SpImage>?.best(minWidth: Int = 300): String? =
    this?.sortedBy { it.width ?: 0 }?.firstOrNull { (it.width ?: 0) >= minWidth }?.url ?: this?.firstOrNull()?.url

fun spotifyId(uri: String?): String? = uri?.substringAfterLast(':')?.takeIf { it.isNotBlank() }

enum class TopRange(val api: String, val label: String) {
    CORTO("short_term", "4 SEMANAS"), MEDIO("medium_term", "6 MESES"), LARGO("long_term", "SIEMPRE")
}

class SpotifyAuthExpired : Exception("Token de Spotify caducado")

/**
 * Web API de Spotify para lo que el App Remote no da: biblioteca, búsqueda, artistas, tops y cola.
 * Caché en memoria de 5 min por URL para no repetir peticiones al cambiar de pestaña.
 */
class SpotifyApi(private val auth: SpotifyAuth) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val cache = ConcurrentHashMap<String, Pair<Long, String>>()

    private suspend fun <T> get(path: String, ser: KSerializer<T>, cacheMs: Long = 5 * 60_000L): T = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        cache[path]?.takeIf { now - it.first < cacheMs }?.let { return@withContext json.decodeFromString(ser, it.second) }
        val token = auth.current() ?: throw SpotifyAuthExpired()
        val req = Request.Builder().url("https://api.spotify.com/v1$path").header("Authorization", "Bearer $token").build()
        http.newCall(req).execute().use { r ->
            if (r.code == 401) { auth.invalidate(); throw SpotifyAuthExpired() }
            if (!r.isSuccessful) throw IllegalStateException("Spotify ${r.code}")
            val body = r.body?.string().orEmpty()
            if (cacheMs > 0) cache[path] = now to body
            json.decodeFromString(ser, body)
        }
    }

    private inline fun <reified T> s(): KSerializer<T> = serializer()
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    suspend fun playlists() = get("/me/playlists?limit=50", s<Paging<SpPlaylist>>()).items
    suspend fun savedAlbums() = get("/me/albums?limit=50", s<Paging<SavedAlbum>>()).items.map { it.album }
    suspend fun followedArtists() = get("/me/following?type=artist&limit=50", s<FollowedArtists>()).artists.items
    suspend fun playlistTracks(id: String) = get("/playlists/$id/tracks?limit=100", s<Paging<PlaylistItem>>()).items.mapNotNull { it.track }
    suspend fun album(id: String) = get("/albums/$id", s<SpAlbum>())
    suspend fun albumTracks(id: String) = get("/albums/$id/tracks?limit=50", s<Paging<SpTrack>>()).items
    suspend fun artist(id: String) = get("/artists/$id", s<SpArtist>())
    suspend fun artistTop(id: String) = get("/artists/$id/top-tracks?market=from_token", s<TopTracks>()).tracks
    suspend fun artistAlbums(id: String) = get("/artists/$id/albums?include_groups=album,single&limit=20", s<Paging<SpAlbum>>()).items
    suspend fun topArtists(r: TopRange) = get("/me/top/artists?time_range=${r.api}&limit=20", s<Paging<SpArtist>>()).items
    suspend fun topTracks(r: TopRange) = get("/me/top/tracks?time_range=${r.api}&limit=10", s<Paging<SpTrack>>()).items
    suspend fun queue() = get("/me/player/queue", s<QueueResp>(), cacheMs = 0)
    suspend fun search(q: String) = get("/search?type=track,artist&limit=20&q=${enc(q)}", s<SearchResult>(), cacheMs = 60_000L)
}
