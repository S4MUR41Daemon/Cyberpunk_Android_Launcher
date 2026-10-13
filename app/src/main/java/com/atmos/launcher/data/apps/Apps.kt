package com.atmos.launcher.data.apps

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.Collator
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Una actividad lanzable. `key` = paquete/clase (+ perfil si no es el principal). */
@Immutable
data class AppEntry(
    val key: String,
    val packageName: String,
    val className: String,
    val label: String,
    val code: String,
    val category: String,
    val isWork: Boolean
) {
    val upper: String = label.uppercase(Locale("es"))
    val favKey: String get() = "$packageName/$className"
}

/** Código y etiqueta «del juego» por paquete conocido. */
object GameCategories {
    private val known: Map<String, Pair<String, String>> = buildMap {
        fun put(code: String, label: String, vararg pkgs: String) = pkgs.forEach { put(it, code to label) }
        put("MSJ", "MENSAJES", "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.thoughtcrime.securesms")
        put("SMS", "SMS", "com.google.android.apps.messaging", "com.android.mms", "com.samsung.android.messaging")
        put("AUD", "MÚSICA", "com.spotify.music", "com.google.android.apps.youtube.music", "deezer.android.app", "com.apple.android.music", "com.soundcloud.android")
        put("TEL", "TELÉFONO", "com.google.android.dialer", "com.android.dialer", "com.samsung.android.dialer", "com.android.incallui")
        put("CON", "CONTACTOS", "com.google.android.contacts", "com.android.contacts")
        put("CAM", "CÁMARA", "com.android.camera", "com.google.android.GoogleCamera", "com.android.camera2", "com.sec.android.app.camera")
        put("NET", "NAVEGADOR", "com.android.chrome", "org.mozilla.firefox", "com.brave.browser", "com.mi.globalbrowser", "com.opera.browser")
        put("MAP", "MAPAS", "com.google.android.apps.maps", "com.waze")
        put("IMG", "GALERÍA", "com.google.android.apps.photos", "com.miui.gallery", "com.sec.android.gallery3d")
        put("COR", "CORREO", "com.google.android.gm", "com.microsoft.office.outlook")
        put("CAL", "CALENDARIO", "com.google.android.calendar", "com.android.calendar", "com.xiaomi.calendar")
        put("VID", "VÍDEO", "com.google.android.youtube", "com.netflix.mediaclient", "com.amazon.avod.thirdpartyclient", "com.disney.disneyplus", "tv.twitch.android.app")
        put("SOC", "RED SOCIAL", "com.instagram.android", "com.twitter.android", "com.zhiliaoapp.musically", "com.facebook.katana", "com.reddit.frontpage")
        put("SYS", "SISTEMA", "com.android.settings", "com.miui.securitycenter")
        put("STR", "TIENDA", "com.android.vending", "com.xiaomi.mipicks")
        put("ATM", "TIEMPO", "com.atmos.weather")
        put("CLK", "RELOJ", "com.google.android.deskclock", "com.android.deskclock")
        put("CAL", "CALCULADORA", "com.google.android.calculator", "com.miui.calculator")
        put("ARC", "ARCHIVOS", "com.google.android.apps.nbu.files", "com.mi.android.globalFileexplorer")
        put("BNK", "BANCO", "com.bbva.bbvacontigo", "es.lacaixa.mobile.android.newwapicon", "com.santander.app")
    }

    fun of(pkg: String, label: String, info: ApplicationInfo?): Pair<String, String> {
        known[pkg]?.let { return it }
        return when (info?.category) {
            ApplicationInfo.CATEGORY_GAME -> "JGO" to "JUEGO"
            ApplicationInfo.CATEGORY_AUDIO -> "AUD" to "MÚSICA"
            ApplicationInfo.CATEGORY_VIDEO -> "VID" to "VÍDEO"
            ApplicationInfo.CATEGORY_IMAGE -> "IMG" to "IMAGEN"
            ApplicationInfo.CATEGORY_SOCIAL -> "SOC" to "SOCIAL"
            ApplicationInfo.CATEGORY_NEWS -> "NEW" to "NOTICIAS"
            ApplicationInfo.CATEGORY_MAPS -> "MAP" to "MAPAS"
            ApplicationInfo.CATEGORY_PRODUCTIVITY -> "PRD" to "PRODUCTIVIDAD"
            else -> {
                val letters = label.uppercase(Locale("es")).filter { it.isLetter() }
                (letters.take(3).padEnd(3, 'X')) to "MÓDULO"
            }
        }
    }
}

/**
 * Lista de apps de todos los perfiles (incluido el de trabajo) vía LauncherApps.
 * Se recarga solo cuando el sistema avisa de altas/bajas/cambios: nada de sondeos.
 */
class AppsRepository(private val ctx: Context) {
    private val la = ctx.getSystemService(LauncherApps::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val infos = ConcurrentHashMap<String, LauncherActivityInfo>()
    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps
    /** Se incrementa cuando una app cambia (para regenerar su icono). */
    val version = MutableStateFlow(0)
    private var reloadJob: Job? = null

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) = scheduleReload()
        override fun onPackageAdded(packageName: String, user: UserHandle) = scheduleReload()
        override fun onPackageChanged(packageName: String, user: UserHandle) = scheduleReload()
        override fun onPackagesAvailable(p: Array<out String>, user: UserHandle, replacing: Boolean) = scheduleReload()
        override fun onPackagesUnavailable(p: Array<out String>, user: UserHandle, replacing: Boolean) = scheduleReload()
    }

    init {
        la.registerCallback(callback, Handler(Looper.getMainLooper()))
        scheduleReload(0)
    }

    private fun scheduleReload(wait: Long = 300) {
        reloadJob?.cancel()
        reloadJob = scope.launch {
            delay(wait) // agrupa ráfagas de eventos (p. ej. al actualizar varias apps)
            reload()
        }
    }

    private fun reload() {
        val me = android.os.Process.myUserHandle()
        val collator = Collator.getInstance(Locale("es")).apply { strength = Collator.PRIMARY }
        val out = ArrayList<AppEntry>()
        infos.clear()
        for (user in la.profiles) {
            for (info in la.getActivityList(null, user)) {
                val pkg = info.componentName.packageName
                if (pkg == ctx.packageName) continue
                val work = user != me
                val key = info.componentName.flattenToShortString() + if (work) "@${user.hashCode()}" else ""
                val label = info.label?.toString()?.trim().orEmpty().ifEmpty { pkg }
                val (code, cat) = GameCategories.of(pkg, label, info.applicationInfo)
                infos[key] = info
                out += AppEntry(key, pkg, info.componentName.className, label, code, if (work) "$cat · TRABAJO" else cat, work)
            }
        }
        out.sortWith { a, b -> collator.compare(a.label, b.label) }
        _apps.value = out
        version.value++
    }

    fun info(key: String): LauncherActivityInfo? = infos[key]

    fun launch(e: AppEntry) {
        val info = infos[e.key] ?: return
        try {
            la.startMainActivity(info.componentName, info.user, null, null)
        } catch (_: Throwable) {
            ctx.packageManager.getLaunchIntentForPackage(e.packageName)?.let {
                ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    fun launchPackage(pkg: String): Boolean {
        val e = _apps.value.firstOrNull { it.packageName == pkg && !it.isWork } ?: return false
        launch(e)
        return true
    }

    fun openInfo(e: AppEntry) {
        val info = infos[e.key] ?: return
        try { la.startAppDetailsActivity(info.componentName, info.user, null, null) } catch (_: Throwable) {}
    }

    fun uninstall(e: AppEntry) {
        try {
            ctx.startActivity(
                Intent(Intent.ACTION_DELETE, Uri.parse("package:${e.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: ActivityNotFoundException) {}
    }

    fun labelOf(pkg: String): String =
        _apps.value.firstOrNull { it.packageName == pkg }?.label
            ?: try { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Throwable) { pkg }

    fun isInstalled(pkg: String) = _apps.value.any { it.packageName == pkg }

    fun component(e: AppEntry) = ComponentName(e.packageName, e.className)
}
