package com.atmos.launcher.ui.screens

import android.app.Activity
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.atmos.launcher.data.media.MediaHub
import com.atmos.launcher.data.notifications.NotifItem
import com.atmos.launcher.data.notifications.NotificationHub
import com.atmos.launcher.data.settings.LauncherSettings
import com.atmos.launcher.ui.LocalContainer
import com.atmos.launcher.ui.Routes
import com.atmos.launcher.ui.components.*
import com.atmos.launcher.ui.go
import com.atmos.launcher.ui.rememberClock
import com.atmos.launcher.ui.theme.AtmosTheme
import com.atmos.launcher.ui.theme.Palette
import com.atmos.launcher.ui.theme.Palettes
import com.atmos.launcher.ui.theme.mono
import com.atmos.launcher.ui.theme.raj

@androidx.compose.runtime.Immutable
private data class Tile(val id: String, val label: String, val sub: String, val on: Boolean)

private fun torchCameraId(cm: CameraManager): String? = try {
    cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
} catch (_: Throwable) { null }

/** Estado de los 6 accesos rápidos, leído al abrir el panel (sin escuchar nada en segundo plano). */
private fun readTiles(ctx: Context, torchOn: Boolean): List<Tile> {
    val wifi = (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.isWifiEnabled == true
    val cm = ctx.getSystemService(ConnectivityManager::class.java)
    val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
    val cell = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
    val bt = try { ctx.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true } catch (_: Throwable) { false }
    val nm = ctx.getSystemService(NotificationManager::class.java)
    val dnd = nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
    val saver = ctx.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
    return listOf(
        Tile("wifi", "WI-FI", if (wifi) "ACTIVO" else "APAGADO", wifi),
        Tile("data", "DATOS", if (cell) "EN USO" else "EN ESPERA", cell),
        Tile("bt", "BLUETOOTH", if (bt) "ACTIVO" else "APAGADO", bt),
        Tile("dnd", "NO MOLESTAR", if (dnd) "ACTIVO" else "APAGADO", dnd),
        Tile("torch", "LINTERNA", if (torchOn) "ENCENDIDA" else "APAGADA", torchOn),
        Tile("saver", "AHORRO", if (saver) "ACTIVO" else "APAGADO", saver)
    )
}

@Composable
fun ShadePanel(settings: LauncherSettings, nav: NavHostController, onClose: () -> Unit) {
    val c = LocalContainer.current
    val ctx = LocalContext.current
    val p = AtmosTheme.p
    val clock by rememberClock()
    val notifs by NotificationHub.items.collectAsStateWithLifecycle()
    val linked by NotificationHub.connected.collectAsStateWithLifecycle()
    val media by MediaHub.state.collectAsStateWithLifecycle()

    val camera = remember { ctx.getSystemService(CameraManager::class.java) }
    val torchId = remember { camera?.let(::torchCameraId) }
    var torchOn by remember { mutableStateOf(false) }
    DisposableEffect(camera) {
        val cb = object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) { if (cameraId == torchId) torchOn = enabled }
        }
        camera?.registerTorchCallback(cb, null)
        onDispose { camera?.unregisterTorchCallback(cb) }
    }
    var refresh by remember { mutableIntStateOf(0) }
    val tiles = remember(torchOn, refresh) { readTiles(ctx, torchOn) }

    fun go(i: Intent) {
        try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Throwable) {}
    }

    fun toggle(t: Tile) {
        when (t.id) {
            "wifi", "data" -> go(
                if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
                else Intent(Settings.ACTION_WIRELESS_SETTINGS)
            )
            "bt" -> go(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            "dnd" -> {
                val nm = ctx.getSystemService(NotificationManager::class.java)
                if (nm.isNotificationPolicyAccessGranted) {
                    nm.setInterruptionFilter(if (t.on) NotificationManager.INTERRUPTION_FILTER_ALL else NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                    refresh++
                } else go(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            }
            "torch" -> torchId?.let { try { camera?.setTorchMode(it, !torchOn) } catch (_: Throwable) {} }
            "saver" -> go(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
        }
    }

    Column(Modifier.fillMaxSize().background(p.bg.copy(alpha = 0.97f)).systemBarsPadding()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 10.dp)) {
            item(key = "head") {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(clock.time, style = raj(40.sp, tracking = 0f, color = p.primary))
                    Spacer(Modifier.weight(1f))
                    Text(clock.date, style = raj(13.sp, FontWeight.SemiBold, 0.2f, p.secondary))
                }
                SimpleGrid(3, 6.dp, Modifier.padding(top = 12.dp)) {
                    tiles.forEach { t -> QuickTile(t) { toggle(t) } }
                }
                Brightness(Modifier.padding(top = 12.dp))

                Spacer(Modifier.height(16.dp))
                BlockLabel("", "TEMA") {
                    Text("AJUSTES ›", style = mono(11.sp, 0.16f, p.primary), modifier = Modifier.clickable { onClose(); nav.go(Routes.SETTINGS) })
                }
                Spacer(Modifier.height(8.dp))
                ThemeGrid(settings.themeId) { c.setTheme(it) }

                Spacer(Modifier.height(18.dp))
                BlockLabel("", "NOTIFICACIONES [${notifs.size}]") {
                    if (notifs.isNotEmpty()) Text("LIMPIAR", style = mono(11.sp, 0.16f, p.secondary), modifier = Modifier.clickable { NotificationHub.cancelAll() }.padding(vertical = 4.dp))
                }
                Spacer(Modifier.height(8.dp))
                media?.let { m ->
                    Column(
                        Modifier.fillMaxWidth().clip(CutShape(br = 10.dp)).background(p.panel)
                            .border(1.dp, p.secondary.copy(alpha = 0.45f), CutShape(br = 10.dp)).padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Text("AUDIO // ${m.app}", style = mono(10.sp, 0.14f, p.secondary))
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                            Column(Modifier.weight(1f).clickable { onClose(); nav.go(Routes.PLAYER) }) {
                                Text(m.title.uppercase(), style = raj(16.sp, tracking = 0.1f, color = p.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(m.artist.uppercase(), style = raj(12.sp, FontWeight.SemiBold, 0.1f, p.muted), maxLines = 1)
                            }
                            Text("◀◀", style = raj(12.sp, color = p.primary), modifier = Modifier.clickable { MediaHub.previous() }.padding(10.dp))
                            SquareBtn(if (m.isPlaying) "❚❚" else "▶", filled = true, size = 40.dp) { MediaHub.playPause() }
                            Text("▶▶", style = raj(12.sp, color = p.primary), modifier = Modifier.clickable { MediaHub.next() }.padding(10.dp))
                        }
                        val pos by rememberPosition(m)
                        ProgressLine(if (m.durationMs > 0) pos.toFloat() / m.durationMs else 0f, p.secondary, Modifier.padding(top = 8.dp), 2.dp)
                    }
                    Spacer(Modifier.height(6.dp))
                }
                if (!linked) {
                    Text("// Sin acceso a notificaciones. Actívalo en AJUSTES › PERMISOS.", style = mono(10.sp, 0.08f, p.muted), modifier = Modifier.padding(vertical = 8.dp))
                }
            }
            items(notifs, key = { it.key }) { n ->
                NotifRow(n, onOpen = { if (NotificationHub.open(ctx, n.key)) onClose() })
            }
        }
        Box(
            Modifier.fillMaxWidth().height(30.dp).clickable(onClick = onClose),
            contentAlignment = Alignment.Center
        ) { Box(Modifier.size(60.dp, 4.dp).background(p.primary)) }
    }
}

@Composable
private fun QuickTile(t: Tile, onClick: () -> Unit) {
    val p = AtmosTheme.p
    val shape = CutShape(br = 10.dp)
    val bg = if (t.on) p.primary else p.panel
    val fg = if (t.on) p.onPrimary else p.text
    Column(
        Modifier.height(62.dp).clip(shape).background(bg).border(1.dp, p.primary.copy(alpha = 0.35f), shape)
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Text(t.label, style = raj(13.sp, tracking = 0.1f, color = fg), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(t.sub, style = mono(10.sp, 0.1f, fg.copy(alpha = 0.8f)), maxLines = 1)
    }
}

/**
 * Brillo: con WRITE_SETTINGS cambia el del sistema; si no, solo el de esta ventana
 * (y ofrece conceder el permiso con una pulsación larga en la etiqueta).
 */
@Composable
private fun Brightness(modifier: Modifier) {
    val ctx = LocalContext.current
    val p = AtmosTheme.p
    val canWrite = remember { Settings.System.canWrite(ctx) }
    var value by remember {
        mutableFloatStateOf(
            try { Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f } catch (_: Throwable) { 0.5f }
        )
    }
    fun setLevel(v: Float) {
        value = v.coerceIn(0.02f, 1f)
        if (canWrite) {
            try {
                Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, (value * 255).toInt())
            } catch (_: Throwable) {}
        } else {
            (ctx as? Activity)?.window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = value } }
        }
    }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "LUM", style = mono(10.sp, 0.14f, p.muted),
            modifier = Modifier.width(36.dp).clickable(enabled = !canWrite) {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        )
        Box(
            Modifier.weight(1f).height(22.dp)
                .pointerInput(Unit) { detectTapGestures { o -> setLevel(o.x / size.width) } }
                .pointerInput(Unit) { detectHorizontalDragGestures { ch, _ -> setLevel(ch.position.x / size.width) } }
                .drawBehind {
                    val h = 6.dp.toPx()
                    val y = (size.height - h) / 2
                    drawRect(p.text.copy(alpha = 0.12f), Offset(0f, y), Size(size.width, h))
                    drawRect(p.primary, Offset(0f, y), Size(size.width * value, h))
                }
        )
        Text("${(value * 100).toInt()}%", style = mono(10.sp, 0f, p.primary), modifier = Modifier.width(40.dp).wrapContentWidth(Alignment.End))
    }
}

@Composable
fun ThemeGrid(selected: String, onPick: (String) -> Unit) {
    SimpleGrid(2, 6.dp) {
        Palettes.all.forEach { pal -> ThemeSwatch(pal, pal.id == selected) { onPick(pal.id) } }
    }
}

@Composable
private fun ThemeSwatch(pal: Palette, on: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.height(50.dp).background(pal.bg).border(1.dp, if (on) pal.primary else pal.secondary.copy(alpha = 0.4f))
            .clickable(onClick = onClick).padding(6.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Box(Modifier.weight(2f).height(10.dp).background(pal.primary))
            Box(Modifier.weight(1f).height(10.dp).background(pal.secondary))
            Box(Modifier.weight(1f).height(10.dp).background(pal.tertiary))
        }
        Text((if (on) "▶ " else "") + pal.name, style = raj(11.sp, tracking = 0.14f, color = Color(0xFFECE8E1)), maxLines = 1)
    }
}

private fun categoryColor(n: NotifItem, p: Palette): Color = when (n.category) {
    android.app.Notification.CATEGORY_MESSAGE, android.app.Notification.CATEGORY_EMAIL -> p.primary
    android.app.Notification.CATEGORY_CALL, android.app.Notification.CATEGORY_ALARM, android.app.Notification.CATEGORY_ERROR -> p.warn
    android.app.Notification.CATEGORY_SOCIAL -> p.secondary
    else -> p.tertiary
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun NotifRow(n: NotifItem, onOpen: () -> Unit) {
    val p = AtmosTheme.p
    val time = remember(n.time) { java.text.SimpleDateFormat("HH:mm", java.util.Locale("es")).format(java.util.Date(n.time)) }
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
        if (v != SwipeToDismissBoxValue.Settled && n.clearable) { NotificationHub.cancel(n.key); true } else false
    })
    SwipeToDismissBox(state, backgroundContent = {}, modifier = Modifier.padding(bottom = 6.dp)) {
        val accent = categoryColor(n, p)
        Column(
            Modifier.fillMaxWidth().background(p.panel)
                .drawBehind { drawRect(accent, Offset.Zero, Size(2.dp.toPx(), size.height)) }
                .clickable(onClick = onOpen).padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Row {
                Text(n.app.uppercase(), style = mono(10.sp, 0.14f, p.muted), maxLines = 1, modifier = Modifier.weight(1f))
                Text(time, style = mono(10.sp, 0.14f, p.muted))
            }
            if (n.title.isNotEmpty()) Text(n.title.uppercase(), style = raj(15.sp, tracking = 0.1f, color = p.text), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            if (n.body.isNotEmpty()) Text(n.body, style = raj(14.sp, FontWeight.Medium, 0f, p.muted), maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}
