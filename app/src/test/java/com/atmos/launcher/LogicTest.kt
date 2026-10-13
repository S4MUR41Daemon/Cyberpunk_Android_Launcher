package com.atmos.launcher

import com.atmos.launcher.data.apps.AppEntry
import com.atmos.launcher.ui.components.initials
import com.atmos.launcher.ui.resolveFavorites
import com.atmos.launcher.ui.screens.fmtMs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LogicTest {
    private fun app(pkg: String, label: String) = AppEntry("$pkg/.Main", pkg, ".Main", label, "XXX", "MÓDULO", false)

    @Test fun initialsFromNames() {
        assertEquals("AR", initials("Ana Ruiz"))
        assertEquals("MA", initials("mamá"))
        assertEquals("?", initials("  "))
    }

    @Test fun formatsTrackTime() {
        assertEquals("0:00", fmtMs(0))
        assertEquals("3:05", fmtMs(185_000))
    }

    @Test fun defaultFavoritesPreferKnownApps() {
        val apps = listOf(app("com.zeta", "Zeta"), app("com.whatsapp", "WhatsApp"), app("com.spotify.music", "Spotify"))
        val favs = resolveFavorites(apps, null)
        assertEquals(9, favs.size)
        assertEquals("com.whatsapp", favs[0]?.packageName)
        assertEquals("com.spotify.music", favs[1]?.packageName)
        assertEquals("com.zeta", favs[2]?.packageName) // rellena huecos con el resto
    }

    @Test fun savedFavoritesKeepEmptySlots() {
        val apps = listOf(app("com.whatsapp", "WhatsApp"))
        val favs = resolveFavorites(apps, listOf("", "com.whatsapp/.Main"))
        assertNull(favs[0])
        assertEquals("com.whatsapp", favs[1]?.packageName)
    }
}
