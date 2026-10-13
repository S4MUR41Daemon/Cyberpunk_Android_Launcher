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

@Serializable data class Paging<T>(val items: List<T> = emptyList(), val total: Int = 0, val next: String? = null)
@Serializable data class Cursors(val after: String? = null)
@Serializable data class CursorPaging<T>(val items: List<T> = emptyList(), val total: Int = 0, val cursors: Cursors? = null)
@Serializable data class SavedAlbum(val album: SpAlbum)
/** En la API nueva la pista viene en «item»; en la antigua, en «track». */
@Serializable data class PlaylistItem(
    val track: SpTrack? = null, val item: SpTrack? = null, @SerialName("added_at") val addedAt: String? = null
) {
    val any: SpTrack? get() = item ?: track
}
@Serializable data class FollowedArtists(val artists: CursorPaging<SpArtist> = CursorPaging())
@Serializable data class SavedTrack(val track: SpTrack? = null, @SerialName("added_at") val addedAt: String? = null)

/** Pista dentro de una lista: conserva su posición original (para reproducir en contexto aunque se ordene). */
@Immutable data class PlEntry(val index: Int, val track: SpTrack, val addedAt: String)

/** Resultado que va llegando por bloques: lo cargado hasta ahora y el total que anuncia Spotify. */
@Immutable data class Partial<T>(val items: List<T>, val total: Int, val done: Boolean)
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

/** Error HTTP de Spotify con el mensaje que devuelve el servidor. */
class SpotifyHttpError(val code: Int, val reason: String) : Exception("Spotify $code: $reason")

@Serializable private data class ErrBody(val error: ErrInner? = null)
@Serializable private data class ErrInner(val status: Int = 0, val message: String = "")

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
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val msg = runCatching { json.decodeFromString(ErrBody.serializer(), body).error?.message }.getOrNull()
                    ?.takeIf { it.isNotBlank() } ?: body.take(120)
                android.util.Log.w("NeuroAudio", "GET $path → ${r.code}: $msg")
                throw SpotifyHttpError(r.code, msg)
            }
            val parsed = try { json.decodeFromString(ser, body) } catch (e: Exception) {
                android.util.Log.w("NeuroAudio", "GET $path → JSON no esperado", e)
                throw e
            }
            if (cacheMs > 0) cache[path] = now to body
            parsed
        }
    }

    private inline fun <reified T> s(): KSerializer<T> = serializer()
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Recorre una lista paginada de 50 en 50, emitiendo lo acumulado tras cada bloque. */
    private fun <T, R> paged(base: String, ser: KSerializer<Paging<T>>, max: Int = 2000, map: (List<T>, Int) -> List<R>): kotlinx.coroutines.flow.Flow<Partial<R>> =
        kotlinx.coroutines.flow.flow {
            val acc = ArrayList<T>()
            var offset = 0
            val sep = if ('?' in base) '&' else '?'
            while (true) {
                val pg = get("$base${sep}limit=50&offset=$offset", ser)
                acc += pg.items
                offset += pg.items.size
                val done = pg.items.isEmpty() || offset >= pg.total || acc.size >= max || pg.next == null
                emit(Partial(map(acc, 0), pg.total, done))
                if (done) break
            }
        }

    fun playlists() = paged("/me/playlists", s<Paging<SpPlaylist>>(), max = 500) { l, _ -> l.toList() }
    fun savedAlbums() = paged("/me/albums", s<Paging<SavedAlbum>>(), max = 1000) { l, _ -> l.map { it.album } }

    fun followedArtists(): kotlinx.coroutines.flow.Flow<Partial<SpArtist>> = kotlinx.coroutines.flow.flow {
        val acc = ArrayList<SpArtist>()
        var after: String? = null
        while (true) {
            val page = get("/me/following?type=artist&limit=50" + (after?.let { "&after=$it" } ?: ""), s<FollowedArtists>()).artists
            acc += page.items
            after = page.cursors?.after
            val done = after == null || page.items.isEmpty() || acc.size >= 1000
            emit(Partial(acc.toList(), page.total, done))
            if (done) break
        }
    }
    /** Toda la playlist por bloques. Ruta nueva (/items) y, si no existe, la antigua (/tracks). */
    fun playlistEntries(id: String): kotlinx.coroutines.flow.Flow<Partial<PlEntry>> = kotlinx.coroutines.flow.flow {
        val base = try {
            get("/playlists/$id/items?limit=1&offset=0", s<Paging<PlaylistItem>>()); "/playlists/$id/items"
        } catch (e: SpotifyHttpError) {
            if (e.code != 404 && e.code != 400) throw e
            "/playlists/$id/tracks"
        }
        paged(base, s<Paging<PlaylistItem>>(), max = 5000) { l, _ ->
            l.mapIndexedNotNull { i, pi -> pi.any?.let { t -> PlEntry(i, t, pi.addedAt.orEmpty()) } }
        }.collect { emit(it) }
    }

    fun albumEntries(id: String) = paged("/albums/$id/tracks", s<Paging<SpTrack>>(), max = 500) { l, _ ->
        l.mapIndexed { i, t -> PlEntry(i, t, "") }
    }
    suspend fun album(id: String) = get("/albums/$id", s<SpAlbum>())
    suspend fun artist(id: String) = get("/artists/$id", s<SpArtist>())
    suspend fun artistTop(id: String) = get("/artists/$id/top-tracks?market=from_token", s<TopTracks>()).tracks
    suspend fun artistAlbums(id: String) = get("/artists/$id/albums?include_groups=album,single&limit=10", s<Paging<SpAlbum>>()).items
    suspend fun topArtists(r: TopRange) = get("/me/top/artists?time_range=${r.api}&limit=10", s<Paging<SpArtist>>()).items
    suspend fun topTracks(r: TopRange) = get("/me/top/tracks?time_range=${r.api}&limit=10", s<Paging<SpTrack>>()).items
    suspend fun queue() = get("/me/player/queue", s<QueueResp>(), cacheMs = 0)
    // Las apps en modo desarrollo tienen un máximo de 10 resultados por tipo.
    suspend fun search(q: String, offset: Int = 0) =
        get("/search?type=track,artist&limit=10&offset=$offset&q=${enc(q)}", s<SearchResult>(), cacheMs = 60_000L)
}
