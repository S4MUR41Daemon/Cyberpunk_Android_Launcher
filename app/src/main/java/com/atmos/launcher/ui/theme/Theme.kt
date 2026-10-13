package com.atmos.launcher.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.atmos.launcher.R

/**
 * Plantilla de color, idéntica a la de la app ATMOS (sus valores mandan sobre los del documento
 * de diseño). Los ids coinciden para poder sincronizar el tema entre ambas apps.
 */
@Immutable
data class Palette(
    val id: String,
    val name: String,
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
    val bg: Color,
    val panel: Color,
    val onPrimary: Color,
    val text: Color,
    val muted: Color,
    val warn: Color
)

object Palettes {
    val NIGHT_CITY = Palette(
        "night_city", "NIGHT CITY",
        primary = Color(0xFFFCEE0A), secondary = Color(0xFFFF003C), tertiary = Color(0xFF02D7F2),
        bg = Color(0xFF060608), panel = Color(0xFF0E0C10), onPrimary = Color(0xFF0A0A0A),
        text = Color(0xFFF2EEDC), muted = Color(0xFF8C887C), warn = Color(0xFFFF003C)
    )
    val EVA_001 = Palette(
        "eva001", "EVA001",
        primary = Color(0xFF6BFF3B), secondary = Color(0xFF9B4DFF), tertiary = Color(0xFFFF9A1F),
        bg = Color(0xFF07050C), panel = Color(0xFF100A1A), onPrimary = Color(0xFF0A1404),
        text = Color(0xFFEFE8FF), muted = Color(0xFF8E83A8), warn = Color(0xFFFF3D3D)
    )
    val NOCHE_NEON = Palette(
        "noche_neon", "NOCHE DE NEÓN",
        primary = Color(0xFF00F0FF), secondary = Color(0xFFFF2BD6), tertiary = Color(0xFF7C8CFF),
        bg = Color(0xFF03070C), panel = Color(0xFF09111A), onPrimary = Color(0xFF04121A),
        text = Color(0xFFE6FBFF), muted = Color(0xFF7E95A1), warn = Color(0xFFFF2B5E)
    )
    val GREEN_LANTERN = Palette(
        "green_lantern", "GREEN LANTERN",
        primary = Color(0xFF4DFF5E), secondary = Color(0xFF00C853), tertiary = Color(0xFF00E5C0),
        bg = Color(0xFF030805), panel = Color(0xFF08110B), onPrimary = Color(0xFF03140A),
        text = Color(0xFFE9FFEC), muted = Color(0xFF7FA088), warn = Color(0xFFFF3B3B)
    )
    /** Hueso, sangre y brasa: pensada para metal, hardcore y darkwave. */
    val HELLFIRE = Palette(
        "hellfire", "HELLFIRE",
        primary = Color(0xFFE9E2D3), secondary = Color(0xFFB3001B), tertiary = Color(0xFFFF6A2B),
        bg = Color(0xFF070606), panel = Color(0xFF110E0E), onPrimary = Color(0xFF0A0808),
        text = Color(0xFFEDE7DA), muted = Color(0xFF8A837A), warn = Color(0xFFFF2A2A)
    )
    val all = listOf(NIGHT_CITY, EVA_001, NOCHE_NEON, GREEN_LANTERN, HELLFIRE)
    fun byId(id: String?) = all.firstOrNull { it.id == id } ?: NIGHT_CITY
}

/** Intensidad de efectos: BAJO = sin scanlines ni aberración. */
enum class Effects(val label: String, val scanAlpha: Float, val chromaDp: Float) {
    BAJO("BAJO", 0f, 0f),
    MEDIO("MEDIO", 0.035f, 1.5f),
    ALTO("ALTO", 0.06f, 2.5f);

    companion object { fun byName(n: String?) = entries.firstOrNull { it.name == n } ?: MEDIO }
}

val LocalPalette = staticCompositionLocalOf { Palettes.NIGHT_CITY }
val LocalEffects = staticCompositionLocalOf { Effects.MEDIO }

object AtmosTheme {
    val p: Palette
        @Composable @ReadOnlyComposable get() = LocalPalette.current
    val effects: Effects
        @Composable @ReadOnlyComposable get() = LocalEffects.current
}

@Composable
fun AtmosTheme(palette: Palette, effects: Effects, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPalette provides palette, LocalEffects provides effects, content = content)
}

// ── Tipografía empaquetada en res/font (sin descargas en tiempo de ejecución) ──

val Rajdhani = FontFamily(
    Font(R.font.rajdhani_medium, FontWeight.Medium),
    Font(R.font.rajdhani_semibold, FontWeight.SemiBold),
    Font(R.font.rajdhani_bold, FontWeight.Bold)
)
val ShareTechMono = FontFamily(Font(R.font.share_tech_mono, FontWeight.Normal))
/** Anton: grotesca ultracondensada para los títulos gigantes de NEURO//AUDIO. */
val Anton = FontFamily(Font(R.font.anton_regular, FontWeight.Normal))

fun anton(size: TextUnit, tracking: Float = 0.01f, color: Color = Color.Unspecified) =
    TextStyle(fontFamily = Anton, fontSize = size, letterSpacing = tracking.em, color = color, lineHeight = size * 0.9f)

fun raj(size: TextUnit, weight: FontWeight = FontWeight.Bold, tracking: Float = 0.1f, color: Color = Color.Unspecified) =
    TextStyle(fontFamily = Rajdhani, fontWeight = weight, fontSize = size, letterSpacing = tracking.em, color = color)

fun mono(size: TextUnit = 11.sp, tracking: Float = 0.14f, color: Color = Color.Unspecified) =
    TextStyle(fontFamily = ShareTechMono, fontSize = size, letterSpacing = tracking.em, color = color)

/** Sombra desplazada en X con el color secundario (aberración cromática), según el ajuste de efectos. */
@Composable
fun chroma(): Shadow? {
    val fx = AtmosTheme.effects
    if (fx.chromaDp == 0f) return null
    val px = with(LocalDensity.current) { fx.chromaDp.dp.toPx() }
    return Shadow(AtmosTheme.p.secondary, Offset(px, 0f), 0f)
}
