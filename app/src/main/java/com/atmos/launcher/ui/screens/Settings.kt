package com.atmos.launcher.ui.screens

import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.atmos.launcher.data.notifications.NotificationHub
import com.atmos.launcher.data.settings.HomeVariant
import com.atmos.launcher.data.settings.LauncherSettings
import com.atmos.launcher.data.weather.WeatherLink
import com.atmos.launcher.ui.LocalContainer
import com.atmos.launcher.ui.components.*
import com.atmos.launcher.ui.theme.AtmosTheme
import com.atmos.launcher.ui.theme.Effects
import com.atmos.launcher.ui.theme.chroma
import com.atmos.launcher.ui.theme.mono
import com.atmos.launcher.ui.theme.raj
import kotlinx.coroutines.launch

// ───────────────────────── Permisos ─────────────────────────

object Perms {
    fun isDefaultHome(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 29) {
            ctx.getSystemService(RoleManager::class.java)?.let { return it.isRoleHeld(RoleManager.ROLE_HOME) }
        }
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return ctx.packageManager.resolveActivity(i, 0)?.activityInfo?.packageName == ctx.packageName
    }
    fun notifications(ctx: Context) = NotificationHub.isAccessGranted(ctx)
    fun dnd(ctx: Context) = ctx.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted
    fun brightness(ctx: Context) = Settings.System.canWrite(ctx)

    fun open(ctx: Context, action: String, withPackage: Boolean = false) {
        val i = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (withPackage) i.data = Uri.parse("package:${ctx.packageName}")
        try { ctx.startActivity(i) } catch (_: Throwable) {}
    }
}

/** Se incrementa en cada ON_RESUME: sirve para releer permisos al volver de los ajustes del sistema. */
@Composable
fun rememberResumeTick(): Int {
    val owner = LocalLifecycleOwner.current
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    return tick
}

@Composable
fun rememberHomeRoleRequest(): () -> Unit {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    return {
        val rm = if (Build.VERSION.SDK_INT >= 29) ctx.getSystemService(RoleManager::class.java) else null
        if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
            launcher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_HOME))
        } else {
            Perms.open(ctx, Settings.ACTION_HOME_SETTINGS)
        }
    }
}

@Composable
private fun PermRow(label: String, desc: String, granted: Boolean, onGrant: () -> Unit) {
    val p = AtmosTheme.p
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(if (granted) p.tertiary else p.warn))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = raj(15.sp, tracking = 0.14f, color = p.text))
            Text(desc, style = mono(10.sp, 0.06f, p.muted))
        }
        if (granted) Text("OK", style = mono(11.sp, 0.16f, p.tertiary))
        else CyberButton("CONCEDER", height = 34.dp, onClick = onGrant)
    }
}

@Composable
fun PermissionsBlock() {
    val ctx = LocalContext.current
    val tick = rememberResumeTick()
    val requestHome = rememberHomeRoleRequest()
    val home = remember(tick) { Perms.isDefaultHome(ctx) }
    val notif = remember(tick) { Perms.notifications(ctx) }
    val dnd = remember(tick) { Perms.dnd(ctx) }
    val bright = remember(tick) { Perms.brightness(ctx) }
    PermRow("LAUNCHER PREDETERMINADO", "ATMOS//OS como pantalla de inicio", home, requestHome)
    PermRow("ACCESO A NOTIFICACIONES", "música, mensajes y panel", notif) { Perms.open(ctx, Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }
    PermRow("NO MOLESTAR", "casilla del panel rápido", dnd) { Perms.open(ctx, Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS) }
    PermRow("BRILLO DEL SISTEMA", "si no, solo cambia el de esta pantalla", bright) { Perms.open(ctx, Settings.ACTION_MANAGE_WRITE_SETTINGS, withPackage = true) }
}

// ───────────────────────── Ajustes ─────────────────────────

@Composable
fun SettingsScreen(settings: LauncherSettings, nav: NavHostController) {
    val c = LocalContainer.current
    val p = AtmosTheme.p
    val scope = rememberCoroutineScope()
    val weather by c.weather.state.collectAsStateWithLifecycle()

    Column(
        Modifier.fillMaxSize().glitchEnter().verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        BackBar("SISTEMA", p.primary, "CONFIGURACIÓN DEL LAUNCHER", onBack = { nav.popBackStack() })
        Text("AJUSTES", style = raj(44.sp, tracking = 0.04f, color = p.primary).copy(shadow = chroma()))

        HudPanel("01", "TEMA", trailing = "SINCRONIZADO CON ATMOS") {
            ThemeGrid(settings.themeId) { c.setTheme(it) }
        }
        HudPanel("02", "INICIO", accent = p.secondary) {
            Segmented(HomeVariant.entries, settings.homeVariant, { it.label }) { v -> scope.launch { c.settings.setHomeVariant(v) } }
            Text("Pulsación larga en una casilla para cambiarla, moverla o quitarla.", style = mono(10.sp, 0.06f, p.muted), modifier = Modifier.padding(top = 10.dp))
        }
        HudPanel("03", "EFECTOS") {
            Segmented(Effects.entries, settings.effects, { it.label }) { v -> scope.launch { c.settings.setEffects(v) } }
            Text("BAJO: sin scanlines ni aberración. ALTO: más intensos.", style = mono(10.sp, 0.06f, p.muted), modifier = Modifier.padding(top = 10.dp))
        }
        HudPanel("04", "ICONOS", accent = p.secondary) {
            Segmented(listOf(true, false), settings.tintedIcons, { if (it) "TEÑIDOS" else "ORIGINALES" }) { v -> scope.launch { c.settings.setTinted(v) } }
        }
        HudPanel("05", "MENSAJERÍA") {
            com.atmos.launcher.data.settings.LauncherSettings.KNOWN_MESSAGING.forEach { (pkg, label) ->
                val on = pkg in settings.messagingPackages
                Row(
                    Modifier.fillMaxWidth().clickable {
                        scope.launch { c.settings.setMessaging(if (on) settings.messagingPackages - pkg else settings.messagingPackages + pkg) }
                    }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(if (on) "[■]" else "[ ]", style = mono(13.sp, 0f, if (on) p.primary else p.muted))
                    Spacer(Modifier.width(10.dp))
                    Text(label, style = raj(15.sp, tracking = 0.14f, color = p.text), modifier = Modifier.weight(1f))
                    Text(if (c.apps.isInstalled(pkg)) "INSTALADA" else "—", style = mono(10.sp, 0.1f, p.muted))
                }
            }
        }
        HudPanel("06", "TIEMPO", accent = p.secondary) {
            val (txt, col) = when (weather.link) {
                WeatherLink.OK -> "ENLAZADO CON ATMOS · ${weather.data?.city?.uppercase().orEmpty()}" to p.tertiary
                WeatherLink.SIN_ATMOS -> "ATMOS NO ESTÁ INSTALADA" to p.warn
                WeatherLink.SIN_PERMISO -> "ATMOS FIRMADA CON OTRA CLAVE · reinstala ambas desde el mismo PC" to p.warn
                WeatherLink.SIN_DATOS -> "ATMOS SIN DATOS · ábrela para sincronizar" to p.muted
            }
            Text(txt, style = mono(11.sp, 0.1f, col))
            Text("La ciudad y el GPS se eligen en ATMOS; el launcher solo lee su caché (sin red propia).", style = mono(10.sp, 0.06f, p.muted), modifier = Modifier.padding(top = 6.dp))
            CyberButton("ABRIR ATMOS ↗", Modifier.fillMaxWidth().padding(top = 10.dp)) { c.weather.openAtmos() }
        }
        HudPanel("07", "HOLOGRAMA", trailing = "FONDO DEL INICIO") { HologramBlock(settings) }
        HudPanel("08", "PANTALLA DE BLOQUEO", accent = p.secondary, trailing = "FONDO ANIMADO") { LockBlock(settings) }
        HudPanel("09", "PERMISOS") { PermissionsBlock() }
        Text("ATMOS//OS LAUNCHER v0.1", style = mono(10.sp, 0.16f, p.muted))
    }
}

// ───────────────────────── Onboarding ─────────────────────────

@Composable
fun OnboardingScreen() {
    val c = LocalContainer.current
    val ctx = LocalContext.current
    val p = AtmosTheme.p
    val scope = rememberCoroutineScope()
    var step by remember { mutableIntStateOf(0) }
    val tick = rememberResumeTick()
    val requestHome = rememberHomeRoleRequest()
    val settings by c.settings.settings.collectAsStateWithLifecycle(null)

    Column(
        Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.background(p.primary).padding(horizontal = 7.dp, vertical = 1.dp)) {
                Text("ATMOS//OS", style = mono(11.sp, 0.16f, p.onPrimary))
            }
            Spacer(Modifier.width(10.dp))
            Text("ARRANQUE · PASO ${step + 1}/3", style = mono(11.sp, 0.16f, p.muted))
        }
        Text("LAUNCHER DE INTERFAZ", style = raj(40.sp, tracking = 0.04f, color = p.primary).copy(shadow = chroma()))
        SegBarSteps(step)

        when (step) {
            0 -> HudPanel("01", "PANTALLA DE INICIO") {
                val home = remember(tick) { Perms.isDefaultHome(ctx) }
                Text(
                    "ATMOS//OS sustituye a la pantalla de inicio. Puedes volver al launcher anterior cuando quieras desde Ajustes › Aplicaciones › Apps predeterminadas.",
                    style = raj(15.sp, FontWeight.Medium, 0.02f, p.text)
                )
                Spacer(Modifier.height(12.dp))
                if (home) Text("✓ YA ES EL LAUNCHER PREDETERMINADO", style = mono(11.sp, 0.14f, p.tertiary))
                else CyberButton("ESTABLECER COMO INICIO", Modifier.fillMaxWidth(), filled = true, onClick = requestHome)
            }
            1 -> HudPanel("02", "ACCESO A NOTIFICACIONES", accent = p.secondary) {
                val ok = remember(tick) { Perms.notifications(ctx) }
                Text(
                    "Android no deja cambiar el aspecto de otras apps. La «skin» de Spotify y WhatsApp son pantallas propias del launcher que leen sus datos (canción en curso, mensajes recibidos) a través de las notificaciones. Al abrir la app real, se verá como siempre.",
                    style = raj(15.sp, FontWeight.Medium, 0.02f, p.text)
                )
                Spacer(Modifier.height(8.dp))
                Text("// No se envía nada fuera del teléfono. El listener es pasivo: sin trabajo en segundo plano.", style = mono(10.sp, 0.06f, p.muted))
                Spacer(Modifier.height(12.dp))
                if (ok) Text("✓ ACCESO CONCEDIDO", style = mono(11.sp, 0.14f, p.tertiary))
                else CyberButton("CONCEDER ACCESO", Modifier.fillMaxWidth(), filled = true) { Perms.open(ctx, Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }
            }
            else -> HudPanel("03", "ELIGE TEMA") {
                ThemeGrid(settings?.themeId ?: "night_city") { c.setTheme(it) }
                Spacer(Modifier.height(14.dp))
                Text("OPCIONAL", style = mono(10.sp, 0.16f, p.muted))
                val dnd = remember(tick) { Perms.dnd(ctx) }
                val bright = remember(tick) { Perms.brightness(ctx) }
                PermRowCompact("NO MOLESTAR", dnd) { Perms.open(ctx, Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS) }
                PermRowCompact("BRILLO", bright) { Perms.open(ctx, Settings.ACTION_MANAGE_WRITE_SETTINGS, withPackage = true) }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (step > 0) CyberButton("◀ ATRÁS", Modifier.weight(1f)) { step-- }
            CyberButton(if (step < 2) "SIGUIENTE ▶" else "INICIAR SISTEMA", Modifier.weight(1f), filled = step == 2) {
                if (step < 2) step++ else scope.launch { c.settings.setOnboardingDone() }
            }
        }
    }
}

@Composable
private fun PermRowCompact(label: String, ok: Boolean, onGrant: () -> Unit) {
    val p = AtmosTheme.p
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = raj(14.sp, tracking = 0.14f, color = p.text), modifier = Modifier.weight(1f))
        if (ok) Text("OK", style = mono(11.sp, 0.16f, p.tertiary)) else CyberButton("CONCEDER", height = 34.dp, onClick = onGrant)
    }
}

@Composable
private fun SegBarSteps(step: Int) {
    val p = AtmosTheme.p
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(3) { i -> Box(Modifier.weight(1f).height(4.dp).background(if (i <= step) p.primary else p.primary.copy(alpha = 0.15f))) }
    }
}

@Composable
private fun HologramBlock(settings: LauncherSettings) {
    val c = LocalContainer.current
    val ctx = LocalContext.current
    val p = AtmosTheme.p
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                HologramStore.import(ctx, uri)?.let { c.settings.setHologram(it) }
                busy = false
            }
        }
    }
    Text(
        "Una imagen tuya (anime, personaje…) proyectada detrás de los módulos y teñida con la plantilla. Desliza el inicio hacia la derecha para verla entera. Funciona mejor sobre fondo oscuro.",
        style = mono(10.sp, 0.06f, p.muted)
    )
    if (settings.hologramPath != null) {
        val bmp = rememberHologramBitmap(settings.hologramPath)
        Box(Modifier.padding(top = 10.dp).fillMaxWidth().height(200.dp).background(p.bg)) {
            if (bmp != null) HologramBackdrop(bmp, alpha = { 1f }, modifier = Modifier.matchParentSize())
        }
        Spacer(Modifier.height(10.dp))
        Segmented(listOf(0, 1, 2), settings.hologramLevel, { listOf("SUTIL", "MEDIO", "INTENSO")[it] }) { l ->
            scope.launch { c.settings.setHologramLevel(l) }
        }
    }
    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CyberButton(if (busy) "CARGANDO…" else "ELEGIR IMAGEN", Modifier.weight(1f), filled = true) {
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        if (settings.hologramPath != null) CyberButton("QUITAR", Modifier.weight(1f), color = p.secondary) {
            scope.launch { HologramStore.clear(ctx); c.settings.setHologram(null) }
        }
    }
}

@Composable
private fun LockBlock(settings: LauncherSettings) {
    val c = LocalContainer.current
    val ctx = LocalContext.current
    val p = AtmosTheme.p
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                HologramStore.import(ctx, uri, slot = "lock")?.let { c.settings.setLockImage(it) }
                busy = false
            }
        }
    }
    Text(
        "Android no permite sustituir la pantalla de bloqueo: su reloj, PIN y notificaciones van siempre encima. " +
            "Esto es un fondo animado propio (sustituye a Wallpaper Engine) con holograma a pantalla completa, " +
            "tu música y tus notificaciones con la estética del tema.",
        style = mono(10.sp, 0.06f, p.muted)
    )
    val bmp = rememberHologramBitmap(settings.lockImagePath ?: settings.hologramPath)
    Box(Modifier.padding(top = 10.dp).fillMaxWidth().height(220.dp).background(p.bg)) {
        if (bmp != null) HologramBackdrop(bmp, alpha = { 1f }, modifier = Modifier.matchParentSize())
        Text(
            if (settings.lockImagePath == null) "USANDO LA IMAGEN DEL INICIO" else "IMAGEN PROPIA DEL BLOQUEO",
            style = mono(10.sp, 0.14f, p.muted), modifier = Modifier.align(Alignment.BottomStart).padding(8.dp)
        )
    }
    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CyberButton(if (busy) "CARGANDO…" else "ELEGIR IMAGEN", Modifier.weight(1f)) {
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        if (settings.lockImagePath != null) CyberButton("QUITAR", Modifier.weight(1f), color = p.secondary) {
            scope.launch { HologramStore.clear(ctx, "lock"); c.settings.setLockImage(null) }
        }
    }
    Spacer(Modifier.height(14.dp))
    Text("MÚSICA EN EL BLOQUEO", style = mono(10.sp, 0.16f, p.muted))
    Spacer(Modifier.height(6.dp))
    Segmented(listOf(true, false), settings.lockShowMusic, { if (it) "MOSTRAR" else "OCULTAR" }) { v -> scope.launch { c.settings.setLockMusic(v) } }
    Spacer(Modifier.height(14.dp))
    Text("NOTIFICACIONES EN EL BLOQUEO", style = mono(10.sp, 0.16f, p.muted))
    Spacer(Modifier.height(6.dp))
    Segmented(listOf(0, 1, 2), settings.lockNotifMode, { listOf("COMPLETAS", "REMITENTE", "NINGUNA")[it] }) { v -> scope.launch { c.settings.setLockNotifMode(v) } }
    Text(
        "Recomendado: NINGUNA y música OCULTA. En el bloqueo, el fondo no puede recibir toques, así que el reproductor y las notificaciones del sistema (con botones) son los útiles.",
        style = mono(10.sp, 0.06f, p.muted), modifier = Modifier.padding(top = 8.dp)
    )
    CyberButton("APLICAR COMO FONDO DE BLOQUEO", Modifier.fillMaxWidth().padding(top = 12.dp), filled = true) {
        val cn = android.content.ComponentName(ctx, com.atmos.launcher.wallpaper.AtmosWallpaperService::class.java)
        val i = Intent(android.app.WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
            .putExtra(android.app.WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, cn)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(i) } catch (_: Throwable) {
            Perms.open(ctx, android.app.WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)
        }
    }
}
