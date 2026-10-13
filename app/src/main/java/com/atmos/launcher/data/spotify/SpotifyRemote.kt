package com.atmos.launcher.data.spotify

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.protocol.client.Subscription
import com.spotify.protocol.types.Image
import com.spotify.protocol.types.PlayerState
import com.spotify.sdk.android.auth.AuthorizationClient
import com.spotify.sdk.android.auth.AuthorizationRequest
import com.spotify.sdk.android.auth.AuthorizationResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object SpotifyConfig {
    const val CLIENT_ID = "3d885cea96cc40029158e30bc1fbfac1"
    const val REDIRECT_URI = "atmos-launcher://spotify-callback"
    const val PACKAGE = "com.spotify.music"
    val SCOPES = arrayOf(
        "app-remote-control", "streaming",
        "user-read-playback-state", "user-read-currently-playing",
        "user-library-read", "user-library-modify",
        "playlist-read-private", "playlist-read-collaborative",
        "user-follow-read", "user-top-read", "user-read-recently-played"
    )
}

/**
 * Token de la Web API. Login implícito con la app oficial (sin «client secret»): dura 1 h y, al caducar,
 * volver a pedirlo es instantáneo y sin pantallas si ya diste permiso una vez.
 */
class SpotifyAuth(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("spotify", Context.MODE_PRIVATE)
    private val _token = MutableStateFlow(validToken())
    val token: StateFlow<String?> = _token

    private fun validToken(): String? {
        val t = prefs.getString("token", null) ?: return null
        return if (System.currentTimeMillis() < prefs.getLong("expires", 0) - 60_000) t else null
    }

    fun current(): String? = validToken().also { if (it == null && _token.value != null) _token.value = null }

    fun loginIntent(activity: Activity): Intent {
        val req = AuthorizationRequest.Builder(SpotifyConfig.CLIENT_ID, AuthorizationResponse.Type.TOKEN, SpotifyConfig.REDIRECT_URI)
            .setScopes(SpotifyConfig.SCOPES)
            .build()
        return AuthorizationClient.createLoginActivityIntent(activity, req)
    }

    /** Devuelve null si todo fue bien, o el texto del error. */
    fun handleResult(resultCode: Int, data: Intent?): String? {
        val r = AuthorizationClient.getResponse(resultCode, data)
        return when (r.type) {
            AuthorizationResponse.Type.TOKEN -> {
                prefs.edit().putString("token", r.accessToken)
                    .putLong("expires", System.currentTimeMillis() + r.expiresIn * 1000L).apply()
                _token.value = r.accessToken
                null
            }
            AuthorizationResponse.Type.ERROR -> r.error ?: "ERROR DE LOGIN"
            else -> "LOGIN CANCELADO"
        }
    }

    fun invalidate() {
        prefs.edit().remove("token").remove("expires").apply()
        _token.value = null
    }
}

@Immutable
data class SpPlayer(
    val uri: String,
    val title: String,
    val artist: String,
    val artistUri: String?,
    val album: String,
    val albumUri: String?,
    val durationMs: Long,
    val positionMs: Long,
    val updatedAt: Long,
    val paused: Boolean,
    val shuffle: Boolean,
    val repeat: Int,
    val canSkipPrev: Boolean,
    val canSkipNext: Boolean
) {
    fun positionAt(now: Long = SystemClock.elapsedRealtime()): Long {
        val p = if (paused) positionMs else positionMs + (now - updatedAt)
        return p.coerceIn(0, durationMs.coerceAtLeast(1))
    }
}

enum class RemoteLink { DESCONECTADO, CONECTANDO, CONECTADO, SIN_SPOTIFY, ERROR }

/**
 * Control de la app oficial de Spotify con el App Remote SDK. La conexión solo vive mientras NEURO//AUDIO
 * está en pantalla (se abre en ON_START y se cierra en ON_STOP): sin conexiones en segundo plano.
 */
object SpotifyRemote {
    private var remote: SpotifyAppRemote? = null
    private var sub: Subscription<PlayerState>? = null
    private var lastImage: String? = null
    private var lastLibUri: String? = null

    private val _link = MutableStateFlow(RemoteLink.DESCONECTADO)
    val link: StateFlow<RemoteLink> = _link
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    private val _player = MutableStateFlow<SpPlayer?>(null)
    val player: StateFlow<SpPlayer?> = _player
    private val _art = MutableStateFlow<ImageBitmap?>(null)
    val art: StateFlow<ImageBitmap?> = _art
    private val _liked = MutableStateFlow(false)
    val liked: StateFlow<Boolean> = _liked

    fun connect(ctx: Context) {
        if (!SpotifyAppRemote.isSpotifyInstalled(ctx)) { _link.value = RemoteLink.SIN_SPOTIFY; return }
        if (remote?.isConnected == true || _link.value == RemoteLink.CONECTANDO) return
        _link.value = RemoteLink.CONECTANDO
        val params = ConnectionParams.Builder(SpotifyConfig.CLIENT_ID)
            .setRedirectUri(SpotifyConfig.REDIRECT_URI)
            .showAuthView(true)
            .build()
        SpotifyAppRemote.connect(ctx.applicationContext, params, object : Connector.ConnectionListener {
            override fun onConnected(r: SpotifyAppRemote) {
                remote = r
                _link.value = RemoteLink.CONECTADO
                _error.value = null
                sub = r.playerApi.subscribeToPlayerState().setEventCallback { s -> onState(r, s) }
            }

            override fun onFailure(t: Throwable) {
                remote = null
                _link.value = RemoteLink.ERROR
                _error.value = t.javaClass.simpleName.replace("Exception", "").uppercase()
            }
        })
    }

    fun disconnect() {
        try { sub?.cancel() } catch (_: Throwable) {}
        sub = null
        remote?.let { SpotifyAppRemote.disconnect(it) }
        remote = null
        if (_link.value == RemoteLink.CONECTADO || _link.value == RemoteLink.CONECTANDO) _link.value = RemoteLink.DESCONECTADO
    }

    private fun onState(r: SpotifyAppRemote, s: PlayerState) {
        val t = s.track ?: return
        _player.value = SpPlayer(
            uri = t.uri, title = t.name.orEmpty(), artist = t.artist?.name.orEmpty(), artistUri = t.artist?.uri,
            album = t.album?.name.orEmpty(), albumUri = t.album?.uri,
            durationMs = t.duration, positionMs = s.playbackPosition, updatedAt = SystemClock.elapsedRealtime(),
            paused = s.isPaused, shuffle = s.playbackOptions?.isShuffling == true, repeat = s.playbackOptions?.repeatMode ?: 0,
            canSkipPrev = s.playbackRestrictions?.canSkipPrev != false, canSkipNext = s.playbackRestrictions?.canSkipNext != false
        )
        // Carátula: una petición por pista, a la app de Spotify (ya la tiene en caché).
        val img = t.imageUri?.raw
        if (img != null && img != lastImage) {
            lastImage = img
            r.imagesApi.getImage(t.imageUri, Image.Dimension.LARGE).setResultCallback { bmp ->
                if (lastImage == img) _art.value = bmp.asImageBitmap()
            }
        }
        if (t.uri != lastLibUri) {
            lastLibUri = t.uri
            r.userApi.getLibraryState(t.uri).setResultCallback { st -> if (lastLibUri == st.uri) _liked.value = st.isAdded }
        }
    }

    private inline fun withRemote(block: (SpotifyAppRemote) -> Unit) { remote?.takeIf { it.isConnected }?.let(block) }

    fun playPause() = withRemote { r -> if (_player.value?.paused != false) r.playerApi.resume() else r.playerApi.pause() }
    fun next() = withRemote { it.playerApi.skipNext() }
    fun previous() = withRemote { it.playerApi.skipPrevious() }
    fun seekTo(ms: Long) = withRemote { it.playerApi.seekTo(ms) }
    fun toggleShuffle() = withRemote { it.playerApi.toggleShuffle() }
    fun toggleRepeat() = withRemote { it.playerApi.toggleRepeat() }
    fun play(uri: String) = withRemote { it.playerApi.play(uri) }
    fun playAt(contextUri: String, index: Int) = withRemote { it.playerApi.skipToIndex(contextUri, index) }
    fun shufflePlay(uri: String) = withRemote { r -> r.playerApi.setShuffle(true); r.playerApi.play(uri) }
    fun enqueue(uri: String) = withRemote { it.playerApi.queue(uri) }
    fun save(uri: String) = withRemote { it.userApi.addToLibrary(uri) }

    fun toggleLike() = withRemote { r ->
        val uri = _player.value?.uri ?: return@withRemote
        val now = !_liked.value
        _liked.value = now
        if (now) r.userApi.addToLibrary(uri) else r.userApi.removeFromLibrary(uri)
    }

    val isConnected: Boolean get() = remote?.isConnected == true
}
