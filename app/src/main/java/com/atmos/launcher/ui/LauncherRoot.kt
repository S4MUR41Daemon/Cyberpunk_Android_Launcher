package com.atmos.launcher.ui

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.service.notification.NotificationListenerService
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.atmos.launcher.Container
import com.atmos.launcher.data.apps.AppEntry
import com.atmos.launcher.data.notifications.AtmosNotificationListener
import com.atmos.launcher.data.notifications.NotificationHub
import com.atmos.launcher.data.settings.LauncherSettings
import com.atmos.launcher.ui.components.ScanlineOverlay
import com.atmos.launcher.ui.screens.*
import com.atmos.launcher.ui.theme.AtmosTheme
import com.atmos.launcher.ui.theme.Palettes
import kotlinx.coroutines.flow.SharedFlow
import java.util.Calendar

val LocalContainer = staticCompositionLocalOf<Container> { error("Container no disponible") }

object Routes {
    const val HOME = "home"
    const val DRAWER = "drawer"
    const val MUSIC = "music"
    const val PLAYER = "player"
    const val MESSAGES = "messages"
    const val CHAT = "chat/{key}"
    const val WEATHER = "weather"
    const val SETTINGS = "settings"
    const val PICKER = "picker/{slot}"
    fun chat(key: String) = "chat/" + Uri.encode(key)
    fun picker(slot: Int) = "picker/$slot"
}

@Composable
fun LauncherRoot(container: Container, homePressed: SharedFlow<Unit>) {
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val s = settings
    val palette = Palettes.byId(s?.themeId)
    CompositionLocalProvider(LocalContainer provides container) {
        AtmosTheme(palette, s?.effects ?: com.atmos.launcher.ui.theme.Effects.MEDIO) {
            Box(Modifier.fillMaxSize().background(palette.bg)) {
                when {
                    s == null -> Unit // un frame en negro mientras carga DataStore: evita parpadeos
                    !s.onboardingDone -> OnboardingScreen()
                    else -> MainNav(s, homePressed)
                }
                ScanlineOverlay(AtmosTheme.effects.scanAlpha, Modifier.fillMaxSize())
            }
        }
    }
    LifecycleRefresh(container)
}

/** Al volver al primer plano: lee el tiempo de la caché de ATMOS y re-vincula el listener si hace falta. */
@Composable
private fun LifecycleRefresh(container: Container) {
    val owner = LocalLifecycleOwner.current
    val ctx = LocalContext.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_START) {
                container.weather.refresh()
                if (NotificationHub.isAccessGranted(ctx) && !NotificationHub.connected.value) {
                    try {
                        NotificationListenerService.requestRebind(ComponentName(ctx, AtmosNotificationListener::class.java))
                    } catch (_: Throwable) {}
                }
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
}

@Composable
private fun MainNav(settings: LauncherSettings, homePressed: SharedFlow<Unit>) {
    val nav = rememberNavController()
    var shade by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        homePressed.collect {
            shade = false
            nav.popBackStack(Routes.HOME, inclusive = false)
        }
    }

    Box(Modifier.fillMaxSize()) {
        NavHost(
            nav, startDestination = Routes.HOME,
            modifier = Modifier.fillMaxSize().systemBarsPadding(),
            // Las transiciones las hace cada pantalla con Modifier.glitchEnter() (solo graphicsLayer).
            enterTransition = { EnterTransition.None }, exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None }, popExitTransition = { ExitTransition.None }
        ) {
            composable(Routes.HOME) {
                BackHandler(enabled = true) { /* el inicio no tiene «atrás» */ }
                HomeScreen(settings, nav, onOpenShade = { shade = true })
            }
            composable(Routes.DRAWER) { DrawerScreen(settings, nav, pickSlot = null) }
            composable(Routes.PICKER) { e ->
                DrawerScreen(settings, nav, pickSlot = e.arguments?.getString("slot")?.toIntOrNull())
            }
            composable(Routes.MUSIC) { MusicScreen(nav) }
            composable(Routes.PLAYER) { PlayerScreen(nav) }
            composable(Routes.MESSAGES) { MessagesScreen(settings, nav) }
            composable(Routes.CHAT) { e -> ChatScreen(Uri.decode(e.arguments?.getString("key").orEmpty()), nav) }
            composable(Routes.WEATHER) { WeatherScreen(nav) }
            composable(Routes.SETTINGS) { SettingsScreen(settings, nav) }
        }

        AnimatedVisibility(
            visible = shade,
            enter = slideInVertically(tween(200)) { -it },
            exit = slideOutVertically(tween(160)) { -it }
        ) {
            BackHandler { shade = false }
            ShadePanel(settings, nav, onClose = { shade = false })
        }
    }
}

// ───────────────────────── Reloj ─────────────────────────

@Immutable
data class ClockState(val time: String, val date: String, val hour: Int)

private val DOW = arrayOf("DOM", "LUN", "MAR", "MIÉ", "JUE", "VIE", "SÁB")
private val MON = arrayOf("ENE", "FEB", "MAR", "ABR", "MAY", "JUN", "JUL", "AGO", "SEP", "OCT", "NOV", "DIC")

private fun now(): ClockState {
    val c = Calendar.getInstance()
    val h = c.get(Calendar.HOUR_OF_DAY)
    return ClockState(
        "%02d:%02d".format(h, c.get(Calendar.MINUTE)),
        "${DOW[c.get(Calendar.DAY_OF_WEEK) - 1]} ${c.get(Calendar.DAY_OF_MONTH)} ${MON[c.get(Calendar.MONTH)]}",
        h
    )
}

/**
 * Reloj con ACTION_TIME_TICK (una vez por minuto), registrado solo mientras la app está en
 * primer plano. Nada de bucles con delay.
 */
@Composable
fun rememberClock(): State<ClockState> {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val state = remember { mutableStateOf(now()) }
    DisposableEffect(owner) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) { state.value = now() }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        var registered = false
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> if (!registered) {
                    ContextCompat.registerReceiver(ctx, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
                    registered = true
                    state.value = now()
                }
                Lifecycle.Event.ON_STOP -> if (registered) {
                    ctx.unregisterReceiver(receiver)
                    registered = false
                }
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose {
            owner.lifecycle.removeObserver(obs)
            if (registered) ctx.unregisterReceiver(receiver)
        }
    }
    return state
}

// ───────────────────────── Favoritos ─────────────────────────

private val DEFAULT_FAVS = listOf(
    listOf("com.whatsapp", "org.telegram.messenger", "com.google.android.apps.messaging"),
    listOf("com.spotify.music", "com.google.android.apps.youtube.music"),
    listOf("com.google.android.dialer", "com.android.dialer", "com.samsung.android.dialer"),
    listOf("com.android.camera", "com.google.android.GoogleCamera", "com.sec.android.app.camera"),
    listOf("com.android.chrome", "com.mi.globalbrowser", "org.mozilla.firefox"),
    listOf("com.google.android.apps.maps"),
    listOf("com.google.android.apps.photos", "com.miui.gallery"),
    listOf("com.google.android.gm"),
    listOf("com.atmos.weather", "com.google.android.calendar", "com.xiaomi.calendar")
)

/** 9 huecos: los elegidos por el usuario o, la primera vez, una selección por defecto con lo instalado. */
fun resolveFavorites(apps: List<AppEntry>, favs: List<String>?): List<AppEntry?> {
    val byFav = apps.filter { !it.isWork }.associateBy { it.favKey }
    if (favs != null) return List(9) { i -> favs.getOrNull(i)?.takeIf { it.isNotEmpty() }?.let(byFav::get) }
    val byPkg = apps.filter { !it.isWork }.groupBy { it.packageName }
    val used = HashSet<String>()
    val out = DEFAULT_FAVS.map { prefs ->
        prefs.firstNotNullOfOrNull { byPkg[it]?.firstOrNull() }?.also { used += it.key }
    }.toMutableList()
    val rest = apps.filter { !it.isWork && it.key !in used }.iterator()
    for (i in out.indices) if (out[i] == null && rest.hasNext()) out[i] = rest.next()
    return out
}

fun NavHostController.go(route: String) = navigate(route) { launchSingleTop = true }
