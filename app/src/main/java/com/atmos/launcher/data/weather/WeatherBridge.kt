package com.atmos.launcher.data.weather

import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Immutable @Serializable
data class SharedHour(val h: String, val t: Double, val p: Int)

@Immutable @Serializable
data class SharedDay(val d: String, val c: String, val lo: Int, val hi: Int, val p: Int)

/** Mismo contrato JSON que `AtmosDataProvider` en la app ATMOS. */
@Immutable @Serializable
data class SharedWeather(
    val city: String,
    val region: String = "",
    val gps: Boolean = false,
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val temp: Int,
    val code: String,
    val label: String,
    val hi: Int,
    val lo: Int,
    val isDay: Boolean = true,
    val feels: Int = 0,
    val humidity: Int = 0,
    val pressure: Int = 0,
    val uv: Int = 0,
    val uvLabel: String = "",
    val visKm: Int = 0,
    val dew: Int = 0,
    val hours: List<SharedHour> = emptyList(),
    val days: List<SharedDay> = emptyList(),
    val alerts: List<String> = emptyList(),
    val fetchedAt: Long = 0
)

enum class WeatherLink { OK, SIN_DATOS, SIN_ATMOS, SIN_PERMISO }

@Immutable
data class WeatherState(val link: WeatherLink, val data: SharedWeather?)

/**
 * Lee el tiempo que ya tiene en caché la app ATMOS (nunca pide red al abrir el launcher).
 * ATMOS hace notifyChange tras cada sync o cambio de tema → refresco sin sondeos.
 */
class WeatherBridge(private val ctx: Context, private val onThemeFromAtmos: (String) -> Unit) {
    private val uri = Uri.parse("content://$AUTHORITY")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val _state = MutableStateFlow(WeatherState(WeatherLink.SIN_DATOS, null))
    val state: StateFlow<WeatherState> = _state
    private var observing = false

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            refresh()
            scope.launch { theme()?.let(onThemeFromAtmos) }
        }
    }

    fun refresh() {
        scope.launch {
            ensureObserver()
            _state.value = try {
                val b = ctx.contentResolver.call(uri, "getWeather", null, null)
                val s = b?.getString("json")
                if (s == null) WeatherState(WeatherLink.SIN_DATOS, _state.value.data)
                else WeatherState(WeatherLink.OK, json.decodeFromString<SharedWeather>(s))
            } catch (_: SecurityException) {
                WeatherState(WeatherLink.SIN_PERMISO, null)
            } catch (_: IllegalArgumentException) {
                WeatherState(WeatherLink.SIN_ATMOS, null) // provider desconocido = ATMOS no instalada
            } catch (_: Throwable) {
                WeatherState(WeatherLink.SIN_DATOS, _state.value.data)
            }
        }
    }

    private fun ensureObserver() {
        if (observing) return
        try {
            ctx.contentResolver.registerContentObserver(uri, true, observer)
            observing = true
        } catch (_: Throwable) {}
    }

    /** Pide a ATMOS un sync en segundo plano; ATMOS solo lo hace si su caché tiene > 30 min. */
    fun requestRefresh() {
        scope.launch { try { ctx.contentResolver.call(uri, "requestRefresh", null, null) } catch (_: Throwable) {} }
    }

    suspend fun theme(): String? = withContext(Dispatchers.IO) {
        try { ctx.contentResolver.call(uri, "getTheme", null, null)?.getString("theme") } catch (_: Throwable) { null }
    }

    fun pushTheme(id: String) {
        scope.launch {
            try { ctx.contentResolver.call(uri, "setTheme", id, null) } catch (_: Throwable) {}
        }
    }

    fun openAtmos(): Boolean {
        val i = ctx.packageManager.getLaunchIntentForPackage(ATMOS_PKG) ?: return false
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    companion object {
        const val AUTHORITY = "com.atmos.weather.data"
        const val ATMOS_PKG = "com.atmos.weather"
    }
}
