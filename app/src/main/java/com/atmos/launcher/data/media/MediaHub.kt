package com.atmos.launcher.data.media

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.atmos.launcher.LauncherApp
import com.atmos.launcher.data.db.TrackEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@Immutable
data class QueueEntry(val id: Long, val title: String, val artist: String)

@Immutable
data class MediaState(
    val pkg: String,
    val app: String,
    val title: String,
    val artist: String,
    val album: String,
    val art: ImageBitmap?,
    val durationMs: Long,
    val positionMs: Long,
    val updatedAt: Long,
    val speed: Float,
    val isPlaying: Boolean,
    val queue: List<QueueEntry>,
    val likeAction: String?,
    val likeLabel: String?,
    val shuffleAction: String?,
    val repeatAction: String?
) {
    /** La posición se calcula, no se consulta. */
    fun positionAt(now: Long = SystemClock.elapsedRealtime()): Long {
        val p = if (isPlaying) positionMs + ((now - updatedAt) * speed).toLong() else positionMs
        return if (durationMs > 0) p.coerceIn(0, durationMs) else p.coerceAtLeast(0)
    }
}

/**
 * Sesión multimedia activa a través de MediaSessionManager (requiere el acceso a notificaciones).
 * Sirve para Spotify, YouTube Music, etc. Todo por callbacks: ningún sondeo.
 */
object MediaHub {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var msm: MediaSessionManager? = null
    private var ctx: Context? = null
    private var controller: MediaController? = null
    private var lastTrackKey: String? = null
    private var artKey: String? = null
    private var artCache: ImageBitmap? = null

    private val _state = MutableStateFlow<MediaState?>(null)
    val state: StateFlow<MediaState?> = _state

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> pick(list.orEmpty()) }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = emit()
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            // Si otra sesión empieza a sonar, cambiamos a ella.
            if (state?.state != PlaybackState.STATE_PLAYING) reevaluate()
            emit()
        }
        override fun onQueueChanged(queue: MutableList<android.media.session.MediaSession.QueueItem>?) = emit()
        override fun onSessionDestroyed() = reevaluate()
    }

    fun attach(context: Context, listener: ComponentName) {
        ctx = context.applicationContext
        val m = context.getSystemService(MediaSessionManager::class.java) ?: return
        msm = m
        try {
            m.addOnActiveSessionsChangedListener(sessionsListener, listener, main)
            pick(m.getActiveSessions(listener))
        } catch (_: SecurityException) {}
    }

    fun detach() {
        try { msm?.removeOnActiveSessionsChangedListener(sessionsListener) } catch (_: Throwable) {}
        controller?.unregisterCallback(callback)
        controller = null
        msm = null
    }

    private fun reevaluate() {
        val m = msm ?: return
        val c = ctx ?: return
        try { pick(m.getActiveSessions(ComponentName(c, "com.atmos.launcher.data.notifications.AtmosNotificationListener"))) } catch (_: Throwable) {}
    }

    private fun pick(list: List<MediaController>) {
        val chosen = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: list.firstOrNull { it.sessionToken == controller?.sessionToken }
            ?: list.firstOrNull()
        if (chosen?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = chosen
            chosen?.registerCallback(callback, main)
        }
        emit()
    }

    private fun emit() {
        val c = controller
        if (c == null) { _state.value = null; return }
        val md = c.metadata
        val ps = c.playbackState
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        if (md == null || title.isEmpty()) { _state.value = null; return }
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty()
        val album = md.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty()
        val app = ctx?.let { (it as? LauncherApp)?.container?.apps?.labelOf(c.packageName) } ?: c.packageName

        // Carátula: se reduce UNA vez a 512 px y se cachea por pista.
        val key = md.getString(MediaMetadata.METADATA_KEY_MEDIA_ID) ?: "$title|$artist"
        if (key != artKey) {
            artKey = key
            val raw: Bitmap? = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            artCache = raw?.let { scale(it, 512).asImageBitmap() }
        }

        val custom = ps?.customActions.orEmpty()
        fun find(vararg words: String) = custom.firstOrNull { a ->
            val n = (a.name?.toString() ?: "") + " " + a.action
            words.any { n.contains(it, ignoreCase = true) }
        }
        val like = find("like", "heart", "favorite", "favourite", "save", "guardar", "me gusta", "add to")

        _state.value = MediaState(
            pkg = c.packageName, app = app.uppercase(), title = title, artist = artist, album = album, art = artCache,
            durationMs = md.getLong(MediaMetadata.METADATA_KEY_DURATION),
            positionMs = ps?.position ?: 0, updatedAt = ps?.lastPositionUpdateTime ?: SystemClock.elapsedRealtime(),
            speed = ps?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
            isPlaying = ps?.state == PlaybackState.STATE_PLAYING,
            queue = c.queue.orEmpty().take(8).map {
                QueueEntry(it.queueId, it.description.title?.toString().orEmpty(), it.description.subtitle?.toString().orEmpty())
            },
            likeAction = like?.action, likeLabel = like?.name?.toString(),
            shuffleAction = find("shuffle", "aleatori")?.action,
            repeatAction = find("repeat", "repetir")?.action
        )

        // Registro de escucha: una entrada por pista nueva.
        val trackKey = "$title|$artist"
        if (trackKey != lastTrackKey) {
            lastTrackKey = trackKey
            val db = (ctx as? LauncherApp)?.container?.db ?: return
            scope.launch {
                db.tracks().upsert(TrackEntity(title = title, artist = artist, album = album, pkg = c.packageName, playedAt = System.currentTimeMillis()))
                db.tracks().trim()
            }
        }
    }

    private fun scale(b: Bitmap, max: Int): Bitmap {
        val m = maxOf(b.width, b.height)
        if (m <= max) return b
        val f = max.toFloat() / m
        return Bitmap.createScaledBitmap(b, (b.width * f).toInt(), (b.height * f).toInt(), true)
    }

    private val tc get() = controller?.transportControls

    fun playPause() {
        val playing = controller?.playbackState?.state == PlaybackState.STATE_PLAYING
        if (playing) tc?.pause() else tc?.play()
    }
    fun next() = tc?.skipToNext()
    fun previous() = tc?.skipToPrevious()
    fun seekTo(ms: Long) = tc?.seekTo(ms)
    fun skipToQueueItem(id: Long) = tc?.skipToQueueItem(id)
    fun custom(action: String) = tc?.sendCustomAction(action, null)
}
