package com.atmos.launcher.data.notifications

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.toBitmap
import com.atmos.launcher.LauncherApp
import com.atmos.launcher.data.db.MessageEntity
import com.atmos.launcher.data.media.MediaHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

@Immutable
data class NotifItem(
    val key: String,
    val pkg: String,
    val app: String,
    val title: String,
    val body: String,
    val time: Long,
    val category: String?,
    val clearable: Boolean
)

/** Lo necesario para responder / marcar como leído una conversación desde el launcher. */
class ReplyTarget(
    val pkg: String,
    val reply: Notification.Action?,
    val markRead: Notification.Action?
)

/**
 * Estado compartido entre el listener (mismo proceso) y la UI. El listener es pasivo: solo reacciona a
 * onNotificationPosted/Removed, sin trabajo periódico.
 */
object NotificationHub {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var service: WeakReference<AtmosNotificationListener>? = null

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected
    private val _items = MutableStateFlow<List<NotifItem>>(emptyList())
    val items: StateFlow<List<NotifItem>> = _items
    /** Notificaciones activas por paquete (insignias de la cuadrícula). */
    private val _badges = MutableStateFlow<Map<String, Int>>(emptyMap())
    val badges: StateFlow<Map<String, Int>> = _badges
    /** Mensajes sin leer por conversación (los que siguen en la notificación activa). */
    private val _unread = MutableStateFlow<Map<String, Int>>(emptyMap())
    val unread: StateFlow<Map<String, Int>> = _unread
    private val _avatars = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())
    val avatars: StateFlow<Map<String, ImageBitmap>> = _avatars

    private val intents = ConcurrentHashMap<String, PendingIntent>()
    private val targets = ConcurrentHashMap<String, ReplyTarget>()

    @Volatile var messagingPackages: Set<String> = setOf("com.whatsapp", "com.whatsapp.w4b")

    fun isAccessGranted(ctx: Context): Boolean {
        val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
        val me = ComponentName(ctx, AtmosNotificationListener::class.java)
        return flat.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    internal fun attach(s: AtmosNotificationListener) {
        service = WeakReference(s)
        _connected.value = true
        rebuild()
        // Ingesta inicial de conversaciones que ya estaban en la barra.
        s.safeActive().forEach { ingestMessages(s, it) }
    }

    internal fun detach() {
        service = null
        _connected.value = false
    }

    internal fun onPosted(s: AtmosNotificationListener, sbn: StatusBarNotification) {
        rebuild()
        ingestMessages(s, sbn)
    }

    internal fun onRemoved() = rebuild()

    private fun rebuild() {
        val s = service?.get() ?: return
        val active = s.safeActive()
        val pm = s.packageManager
        val labels = HashMap<String, String>()
        fun label(pkg: String) = labels.getOrPut(pkg) {
            try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (_: Throwable) { pkg }
        }
        val list = ArrayList<NotifItem>()
        val badges = HashMap<String, Int>()
        val unread = HashMap<String, Int>()
        intents.clear()
        for (sbn in active) {
            val n = sbn.notification
            if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) continue
            val ex = n.extras
            val isMedia = ex.containsKey(Notification.EXTRA_MEDIA_SESSION)
            if (!isMedia) badges[sbn.packageName] = (badges[sbn.packageName] ?: 0) + 1
            if (sbn.packageName in messagingPackages) {
                val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
                if (style != null) unread[convKey(n, style)] = style.messages.size
            }
            if (isMedia) {
                // La notificación multimedia siempre lleva la carátula como icono grande: fallback para MediaHub.
                val t = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
                val icon = n.getLargeIcon()
                if (icon != null && t.isNotEmpty() && !MediaHub.hasNotificationArt(sbn.packageName, t)) {
                    val pkg = sbn.packageName
                    scope.launch {
                        try { icon.loadDrawable(s)?.toBitmap()?.let { MediaHub.offerNotificationArt(pkg, t, it) } } catch (_: Throwable) {}
                    }
                }
                continue // la tarjeta multimedia del panel ya la muestra
            }
            val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val body = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
            if (title.isEmpty() && body.isEmpty()) continue
            n.contentIntent?.let { intents[sbn.key] = it }
            list += NotifItem(sbn.key, sbn.packageName, label(sbn.packageName), title, body, sbn.postTime, n.category, sbn.isClearable)
        }
        list.sortByDescending { it.time }
        _items.value = list
        _badges.value = badges
        _unread.value = unread
    }

    private fun convKey(n: Notification, style: NotificationCompat.MessagingStyle?): String =
        n.shortcutId
            ?: style?.conversationTitle?.toString()
            ?: n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: "?"

    private fun ingestMessages(s: AtmosNotificationListener, sbn: StatusBarNotification) {
        if (sbn.packageName !in messagingPackages) return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n) ?: return
        val key = convKey(n, style)
        val title = style.conversationTitle?.toString()
            ?: n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: key
        val me = style.user.name?.toString()
        val isGroup = style.isGroupConversation
        val rows = style.messages.mapNotNull { m ->
            val text = m.text?.toString() ?: return@mapNotNull null
            val sender = m.person?.name?.toString() ?: me ?: ""
            MessageEntity(
                convKey = key, title = title, sender = sender, text = text, time = m.timestamp,
                fromMe = m.person == null || (me != null && sender == me), isGroup = isGroup, pkg = sbn.packageName
            )
        }
        val reply = n.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }
        val markRead = n.actions?.firstOrNull { a ->
            (android.os.Build.VERSION.SDK_INT >= 28 && a.semanticAction == Notification.Action.SEMANTIC_ACTION_MARK_AS_READ) ||
                a.title?.toString()?.contains("leído", ignoreCase = true) == true
        }
        targets[key] = ReplyTarget(sbn.packageName, reply, markRead)

        // Foto de la persona si viene (icono grande), reducida una vez a 96 px.
        val large: Icon? = n.getLargeIcon()
        if (large != null && key !in _avatars.value) {
            scope.launch {
                try {
                    val bmp = large.loadDrawable(s)?.toBitmap(96, 96)?.asImageBitmap() ?: return@launch
                    _avatars.value = _avatars.value + (key to bmp)
                } catch (_: Throwable) {}
            }
        }
        val db = (s.application as LauncherApp).container.db
        scope.launch { db.messages().ingest(key, rows) }
    }

    fun hasReply(convKey: String) = targets[convKey]?.reply != null

    /** Responde con la RemoteInput de la acción «Responder». Devuelve false si ya no hay notificación. */
    fun reply(ctx: Context, convKey: String, text: String): Boolean {
        val t = targets[convKey] ?: return false
        val action = t.reply ?: return false
        val inputs = action.remoteInputs ?: return false
        val intent = Intent()
        val results = Bundle()
        inputs.forEach { results.putCharSequence(it.resultKey, text) }
        RemoteInput.addResultsToIntent(inputs, intent, results)
        return try {
            action.actionIntent.send(ctx, 0, intent)
            true
        } catch (_: Throwable) { false }
    }

    fun markRead(ctx: Context, convKey: String) {
        val a = targets[convKey]?.markRead ?: return
        try { a.actionIntent.send(ctx, 0, Intent()) } catch (_: Throwable) {}
    }

    fun open(ctx: Context, key: String): Boolean {
        val pi = intents[key] ?: return false
        return try { pi.send(); true } catch (_: Throwable) { false }
    }

    fun cancel(key: String) { service?.get()?.cancelNotification(key) }
    fun cancelAll() { service?.get()?.cancelAllNotifications() }
}

class AtmosNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        NotificationHub.attach(this)
        MediaHub.attach(this, ComponentName(this, AtmosNotificationListener::class.java))
    }

    override fun onListenerDisconnected() {
        NotificationHub.detach()
        // MediaHub sigue enganchado desde la app: no depende del enlace del servicio.
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = NotificationHub.onPosted(this, sbn)
    override fun onNotificationRemoved(sbn: StatusBarNotification) = NotificationHub.onRemoved()

    internal fun safeActive(): Array<StatusBarNotification> =
        try { activeNotifications ?: emptyArray() } catch (_: Throwable) { emptyArray() }
}
