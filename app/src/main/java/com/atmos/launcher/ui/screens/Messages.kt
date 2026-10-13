package com.atmos.launcher.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.atmos.launcher.data.db.ConversationRow
import com.atmos.launcher.data.db.MessageEntity
import com.atmos.launcher.data.notifications.NotificationHub
import com.atmos.launcher.data.settings.LauncherSettings
import com.atmos.launcher.ui.LocalContainer
import com.atmos.launcher.ui.Routes
import com.atmos.launcher.ui.components.*
import com.atmos.launcher.ui.go
import com.atmos.launcher.ui.theme.AtmosTheme
import com.atmos.launcher.ui.theme.chroma
import com.atmos.launcher.ui.theme.mono
import com.atmos.launcher.ui.theme.raj
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private enum class MsgTab(val label: String) { TODOS("TODOS"), NUEVOS("NUEVOS"), GRUPOS("GRUPOS") }

private fun appName(pkg: String) = when (pkg) {
    "com.whatsapp" -> "WHATSAPP"
    "com.whatsapp.w4b" -> "WA BUSINESS"
    "org.telegram.messenger" -> "TELEGRAM"
    "org.thoughtcrime.securesms" -> "SIGNAL"
    else -> pkg.substringAfterLast('.').uppercase()
}

private fun whenLabel(t: Long): String {
    val now = Calendar.getInstance()
    val c = Calendar.getInstance().apply { timeInMillis = t }
    val sameDay = now.get(Calendar.YEAR) == c.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == c.get(Calendar.DAY_OF_YEAR)
    return when {
        sameDay -> SimpleDateFormat("HH:mm", Locale("es")).format(Date(t))
        now.get(Calendar.DAY_OF_YEAR) - c.get(Calendar.DAY_OF_YEAR) == 1 -> "AYER"
        else -> SimpleDateFormat("dd/MM", Locale("es")).format(Date(t))
    }
}

@Composable
fun MessagesScreen(settings: LauncherSettings, nav: NavHostController) {
    val c = LocalContainer.current
    val ctx = LocalContext.current
    val p = AtmosTheme.p
    val linked by NotificationHub.connected.collectAsStateWithLifecycle()
    val unread by NotificationHub.unread.collectAsStateWithLifecycle()
    val avatars by NotificationHub.avatars.collectAsStateWithLifecycle()
    val all by remember { c.db.messages().conversations() }.collectAsStateWithLifecycle(emptyList())
    var tab by remember { mutableStateOf(MsgTab.TODOS) }

    val convs = remember(all, settings.messagingPackages) { all.filter { it.pkg in settings.messagingPackages } }
    val shown = remember(convs, tab, unread) {
        when (tab) {
            MsgTab.TODOS -> convs.take(15) // ya vienen ordenadas por el último mensaje
            MsgTab.NUEVOS -> convs.filter { (unread[it.convKey] ?: 0) > 0 }
            MsgTab.GRUPOS -> convs.filter { it.isGroup }
        }
    }
    val totalUnread = convs.sumOf { unread[it.convKey] ?: 0 }
    val mainPkg = convs.firstOrNull()?.pkg ?: settings.messagingPackages.firstOrNull() ?: "com.whatsapp"

    Column(Modifier.fillMaxSize().glitchEnter()) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp)) {
            BackBar("COMMS", p.primary, "FUENTE: ${appName(mainPkg)}", onBack = { nav.popBackStack() })
            Text("MENSAJES", style = raj(44.sp, tracking = 0.04f, color = p.primary).copy(shadow = chroma()), modifier = Modifier.padding(top = 12.dp))
            Text(if (totalUnread > 0) "$totalUnread SIN LEER" else "BANDEJA AL DÍA", style = raj(13.sp, tracking = 0.22f, color = p.secondary))
            Box(Modifier.padding(top = 14.dp, bottom = 12.dp)) {
                Segmented(MsgTab.entries, tab, { it.label }) { tab = it }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(p.primary.copy(alpha = 0.25f)))
        }

        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 16.dp)) {
            if (!linked) item {
                HudPanel("!", "SIN ENLACE", accent = p.warn, modifier = Modifier.padding(top = 12.dp)) {
                    Text("Concede el acceso a notificaciones para leer y responder mensajes desde el launcher.", style = raj(15.sp, androidx.compose.ui.text.font.FontWeight.Medium, 0.02f, p.text))
                    Spacer(Modifier.height(10.dp))
                    CyberButton("CONCEDER ACCESO", Modifier.fillMaxWidth(), filled = true) {
                        ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }
            items(shown, key = { it.convKey }) { cv ->
                ConversationItem(cv, unread[cv.convKey] ?: 0, avatars[cv.convKey]) { nav.go(Routes.chat(cv.convKey)) }
            }
            if (linked && shown.isEmpty()) item {
                Text("// SIN CONVERSACIONES TODAVÍA", style = mono(12.sp, 0.16f, p.muted), modifier = Modifier.padding(vertical = 30.dp))
            }
            item {
                Text(
                    "// Conversaciones reconstruidas desde notificaciones. Historial completo y multimedia en la app original.",
                    style = mono(10.sp, 0.08f, p.muted), modifier = Modifier.padding(top = 16.dp)
                )
                CyberButton("ABRIR ${appName(mainPkg)} ↗", Modifier.fillMaxWidth().padding(top = 12.dp), color = p.text) {
                    c.apps.launchPackage(mainPkg)
                }
            }
        }
    }
}

@Composable
private fun ConversationItem(cv: ConversationRow, unread: Int, photo: androidx.compose.ui.graphics.ImageBitmap?, onClick: () -> Unit) {
    val p = AtmosTheme.p
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .drawBehind { drawLine(p.text.copy(alpha = 0.07f), Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) }
            .padding(top = 12.dp, bottom = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(initials(cv.title), photo)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(cv.title.uppercase(), style = raj(17.sp, tracking = 0.12f, color = p.text), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text(whenLabel(cv.lastTime), style = mono(10.sp, 0f, if (unread > 0) p.primary else p.muted))
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                val prefix = when {
                    cv.fromMe -> "TÚ: "
                    cv.isGroup && cv.lastSender.isNotEmpty() -> "${cv.lastSender}: "
                    else -> ""
                }
                Text(prefix + cv.lastText, style = raj(14.sp, androidx.compose.ui.text.font.FontWeight.Medium, 0f, p.muted), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (unread > 0) {
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.defaultMinSize(20.dp, 18.dp).background(p.primary).padding(horizontal = 5.dp), contentAlignment = Alignment.Center) {
                        Text("$unread", style = mono(11.sp, 0f, p.onPrimary))
                    }
                }
            }
        }
    }
}

/** Oculta el eco de mis respuestas: el que guardamos al enviar y el que luego reenvía la notificación. */
private fun dedupeMine(list: List<MessageEntity>): List<MessageEntity> {
    val out = ArrayList<MessageEntity>(list.size)
    for (m in list) {
        if (m.fromMe && out.any { it.fromMe && it.text == m.text && kotlin.math.abs(it.time - m.time) < 120_000 }) continue
        out += m
    }
    return out
}

@Composable
fun ChatScreen(convKey: String, nav: NavHostController) {
    val c = LocalContainer.current
    val ctx = LocalContext.current
    val p = AtmosTheme.p
    val scope = rememberCoroutineScope()
    val raw by remember(convKey) { c.db.messages().conversation(convKey) }.collectAsStateWithLifecycle(emptyList())
    val msgs = remember(raw) { dedupeMine(raw) }
    val avatars by NotificationHub.avatars.collectAsStateWithLifecycle()
    val unread by NotificationHub.unread.collectAsStateWithLifecycle()
    val canReply = remember(unread, convKey) { NotificationHub.hasReply(convKey) }
    val title = msgs.lastOrNull()?.title ?: convKey
    val pkg = msgs.lastOrNull()?.pkg ?: "com.whatsapp"
    var draft by remember { mutableStateOf("") }
    val list = rememberLazyListState()

    LaunchedEffect(convKey) { NotificationHub.markRead(ctx, convKey) }
    LaunchedEffect(msgs.size) { if (msgs.isNotEmpty()) list.scrollToItem(msgs.lastIndex + 1) }

    fun send() {
        val text = draft.trim()
        if (text.isEmpty()) return
        if (NotificationHub.reply(ctx, convKey, text)) {
            draft = ""
            scope.launch {
                c.db.messages().insertAll(listOf(MessageEntity(
                    convKey = convKey, title = title, sender = "", text = text, time = System.currentTimeMillis(),
                    fromMe = true, isGroup = msgs.any { it.isGroup }, pkg = pkg
                )))
            }
        }
    }

    Column(Modifier.fillMaxSize().glitchEnter().imePadding()) {
        Row(
            Modifier.fillMaxWidth().drawBehind {
                drawLine(p.primary.copy(alpha = 0.3f), Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
            }.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("‹", style = raj(22.sp, color = p.primary), modifier = Modifier.clickable { nav.popBackStack() }.padding(end = 10.dp))
            Avatar(initials(title), avatars[convKey], 40.dp, filled = true)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title.uppercase(), style = raj(19.sp, tracking = 0.14f, color = p.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("CANAL CIFRADO // ${appName(pkg)}", style = mono(10.sp, 0.14f, p.tertiary), modifier = Modifier.padding(top = 3.dp))
            }
            Text("↗", style = mono(11.sp, 0f, p.muted), modifier = Modifier.border(1.dp, p.text.copy(alpha = 0.2f)).clickable { c.apps.launchPackage(pkg) }.padding(horizontal = 8.dp, vertical = 6.dp))
        }

        LazyColumn(
            state = list, modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { Text("— HISTORIAL —", style = mono(10.sp, 0.18f, p.muted), modifier = Modifier.fillMaxWidth().wrapContentWidth()) }
            items(msgs, key = { it.id }) { m -> Bubble(m) }
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(p.text.copy(alpha = 0.08f)))
        if (canReply) {
            Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.weight(1f).height(46.dp).background(p.panel).border(1.dp, p.primary.copy(alpha = 0.35f)).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(">", style = mono(14.sp, 0f, p.primary))
                    Spacer(Modifier.width(8.dp))
                    BasicTextField(
                        draft, { draft = it }, singleLine = true,
                        textStyle = raj(15.sp, androidx.compose.ui.text.font.FontWeight.Medium, 0.04f, p.text),
                        cursorBrush = SolidColor(p.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { send() }),
                        modifier = Modifier.weight(1f),
                        decorationBox = { inner ->
                            Box {
                                if (draft.isEmpty()) Text("transmitir mensaje_", style = raj(15.sp, androidx.compose.ui.text.font.FontWeight.Medium, 0.04f, p.muted))
                                inner()
                            }
                        }
                    )
                }
                Pressable(Modifier.size(60.dp, 46.dp), shape = CutShape(br = 10.dp), idle = p.primary, onClick = { send() }) {
                    Text("ENV", style = raj(13.sp, tracking = 0.14f, color = p.onPrimary), modifier = Modifier.align(Alignment.Center))
                }
            }
        } else {
            Column(Modifier.padding(16.dp)) {
                Text("// Sin notificación activa: no se puede responder desde aquí.", style = mono(10.sp, 0.08f, p.muted))
                CyberButton("ABRIR EN ${appName(pkg)} ↗", Modifier.fillMaxWidth().padding(top = 8.dp), filled = true) { c.apps.launchPackage(pkg) }
            }
        }
    }
}

@Composable
private fun Bubble(m: MessageEntity) {
    val p = AtmosTheme.p
    val time = remember(m.time) { SimpleDateFormat("HH:mm", Locale("es")).format(Date(m.time)) }
    if (!m.fromMe) {
        Column(Modifier.fillMaxWidth(0.8f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            if (m.isGroup && m.sender.isNotEmpty()) Text(m.sender.uppercase(), style = mono(9.sp, 0.1f, p.secondary))
            Text(
                m.text, style = raj(15.sp, androidx.compose.ui.text.font.FontWeight.Medium, 0.02f, p.text),
                modifier = Modifier.clip(CutShape(br = 8.dp)).background(p.panel)
                    .drawBehind { drawRect(p.secondary, Offset.Zero, Size(2.dp.toPx(), size.height)) }
                    .padding(horizontal = 12.dp, vertical = 9.dp)
            )
            Text(time, style = mono(9.sp, 0.1f, p.muted))
        }
    } else {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            Column(Modifier.fillMaxWidth(0.8f), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    m.text, style = raj(15.sp, androidx.compose.ui.text.font.FontWeight.Medium, 0.02f, p.text),
                    modifier = Modifier.clip(CutShape(tl = 8.dp)).background(p.primary.copy(alpha = 0.16f))
                        .drawBehind { drawRect(p.primary, Offset(size.width - 2.dp.toPx(), 0f), Size(2.dp.toPx(), size.height)) }
                        .padding(horizontal = 12.dp, vertical = 9.dp)
                )
                Text("$time · ENVIADO", style = mono(9.sp, 0.1f, p.primary))
            }
        }
    }
}
