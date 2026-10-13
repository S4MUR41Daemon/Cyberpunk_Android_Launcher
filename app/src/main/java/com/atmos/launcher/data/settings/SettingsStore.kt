package com.atmos.launcher.data.settings

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.atmos.launcher.ui.theme.Effects
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.store by preferencesDataStore("launcher_prefs")

enum class HomeVariant(val label: String, val sub: String) {
    MODULOS("MÓDULOS", "cuadrícula 3×3"),
    LISTA("LISTA", "menú del juego")
}

@Immutable
data class LauncherSettings(
    val themeId: String = "night_city",
    val effects: Effects = Effects.MEDIO,
    val homeVariant: HomeVariant = HomeVariant.MODULOS,
    val tintedIcons: Boolean = true,
    /** Claves «paquete/clase», 9 huecos; "" = hueco vacío. null = aún sin elegir (usar los por defecto). */
    val favorites: List<String>? = null,
    val messagingPackages: Set<String> = DEFAULT_MESSAGING,
    val onboardingDone: Boolean = false
) {
    companion object {
        val DEFAULT_MESSAGING = setOf("com.whatsapp", "com.whatsapp.w4b")
        val KNOWN_MESSAGING = listOf(
            "com.whatsapp" to "WHATSAPP",
            "com.whatsapp.w4b" to "WHATSAPP BUSINESS",
            "org.telegram.messenger" to "TELEGRAM",
            "org.thoughtcrime.securesms" to "SIGNAL"
        )
    }
}

class SettingsStore(private val ctx: Context) {
    private val THEME = stringPreferencesKey("theme")
    private val EFFECTS = stringPreferencesKey("effects")
    private val HOME = stringPreferencesKey("home_variant")
    private val TINT = booleanPreferencesKey("tinted_icons")
    private val FAVS = stringPreferencesKey("favorites")
    private val MSG = stringSetPreferencesKey("messaging")
    private val ONBOARD = booleanPreferencesKey("onboarding_done")

    val settings: Flow<LauncherSettings> = ctx.store.data.map(::read)

    suspend fun current(): LauncherSettings = read(ctx.store.data.first())

    private fun read(p: Preferences) = LauncherSettings(
        themeId = p[THEME] ?: "night_city",
        effects = Effects.byName(p[EFFECTS]),
        homeVariant = HomeVariant.entries.firstOrNull { it.name == p[HOME] } ?: HomeVariant.MODULOS,
        tintedIcons = p[TINT] ?: true,
        favorites = p[FAVS]?.split('|'),
        messagingPackages = p[MSG] ?: LauncherSettings.DEFAULT_MESSAGING,
        onboardingDone = p[ONBOARD] ?: false
    )

    suspend fun setTheme(id: String) = ctx.store.edit { it[THEME] = id }
    suspend fun setEffects(e: Effects) = ctx.store.edit { it[EFFECTS] = e.name }
    suspend fun setHomeVariant(v: HomeVariant) = ctx.store.edit { it[HOME] = v.name }
    suspend fun setTinted(v: Boolean) = ctx.store.edit { it[TINT] = v }
    suspend fun setFavorites(list: List<String>) = ctx.store.edit { it[FAVS] = list.joinToString("|") }
    suspend fun setMessaging(pkgs: Set<String>) = ctx.store.edit { it[MSG] = pkgs }
    suspend fun setOnboardingDone() = ctx.store.edit { it[ONBOARD] = true }
}
