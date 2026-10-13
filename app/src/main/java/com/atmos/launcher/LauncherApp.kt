package com.atmos.launcher

import android.app.Application
import com.atmos.launcher.data.apps.AppsRepository
import com.atmos.launcher.data.apps.IconCache
import com.atmos.launcher.data.db.LauncherDb
import com.atmos.launcher.data.notifications.NotificationHub
import com.atmos.launcher.data.settings.SettingsStore
import com.atmos.launcher.data.weather.WeatherBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class LauncherApp : Application() {
    lateinit var container: Container
        private set

    override fun onCreate() {
        super.onCreate()
        container = Container(this)
    }
}

class Container(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db: LauncherDb by lazy { LauncherDb.create(app) }
    val settings = SettingsStore(app)
    val apps = AppsRepository(app)
    val icons = IconCache(app, apps)

    /** Si el tema cambia en ATMOS, el launcher lo adopta (y al revés en [setTheme]). */
    val weather = WeatherBridge(app) { atmosTheme ->
        scope.launch {
            if (settings.settings.first().themeId != atmosTheme) settings.setTheme(atmosTheme)
        }
    }

    init {
        scope.launch {
            settings.settings.collect { NotificationHub.messagingPackages = it.messagingPackages }
        }
    }

    fun setTheme(id: String) {
        scope.launch { settings.setTheme(id) }
        weather.pushTheme(id)
    }
}
