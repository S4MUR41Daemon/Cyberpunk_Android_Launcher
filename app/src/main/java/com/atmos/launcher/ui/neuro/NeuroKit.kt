package com.atmos.launcher.ui.neuro

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.atmos.launcher.ui.components.hatch
import com.atmos.launcher.ui.theme.AtmosTheme
import com.atmos.launcher.ui.theme.anton
import com.atmos.launcher.ui.theme.mono
import kotlin.math.PI
import kotlin.math.sin

/**
 * «Umbral» en GPU: la luminancia decide la opacidad con una pendiente muy fuerte (casi blanco/negro)
 * y el color es fijo del tema. Sobre fondo negro da el aspecto de portada fotocopiada de metal.
 */
fun thresholdFilter(c: Color, slope: Float = 4.2f, cut: Float = 0.40f): ColorFilter = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            0f, 0f, 0f, 0f, c.red * 255f,
            0f, 0f, 0f, 0f, c.green * 255f,
            0f, 0f, 0f, 0f, c.blue * 255f,
            0.299f * slope, 0.587f * slope, 0.114f * slope, 0f, -cut * slope * 255f
        )
    )
)

/**
 * Portada tratada: fantasma en el secundario desplazado 5 dp + imagen principal en el primario.
 * [model] puede ser una URL (Coil) o un ImageBitmap (carátula del App Remote). [glitchKey]: al
 * cambiar, la capa principal hace un barrido glitch de 320 ms (solo graphicsLayer).
 */
@Composable
fun ThresholdCover(model: Any?, modifier: Modifier = Modifier, glitchKey: Any? = null, ghostShift: Dp = 5.dp) {
    val p = AtmosTheme.p
    val main = remember(p.primary) { thresholdFilter(p.primary) }
    val ghost = remember(p.secondary) { thresholdFilter(p.secondary, slope = 3.6f) }
    val g = remember { Animatable(1f) }
    LaunchedEffect(glitchKey) { if (glitchKey != null) { g.snapTo(0f); g.animateTo(1f, tween(320, easing = LinearEasing)) } }
    val shiftPx = with(LocalDensity.current) { ghostShift.toPx() }
    val jolt = with(LocalDensity.current) { 14.dp.toPx() }
    Box(modifier.clipToBounds().background(p.bg).drawBehind { hatch(p.secondary.copy(alpha = 0.18f), 9.dp.toPx(), 3.dp.toPx()) }) {
        if (model != null) {
            CoverLayer(model, ghost, Modifier.matchParentSize().graphicsLayer { translationX = shiftPx; alpha = 0.85f })
            CoverLayer(model, main, Modifier.matchParentSize().graphicsLayer {
                val t = g.value
                if (t < 1f) {
                    val step = (t * 3).toInt()
                    translationX = jolt * (if (step % 2 == 0) -1f else 1f) * (1f - t)
                    clip = true
                    shape = BandShape(listOf(0.10f to 0.40f, 0.55f to 0.80f, 0.30f to 0.60f)[step.coerceAtMost(2)])
                } else {
                    translationX = 0f; clip = false
                }
            })
        }
    }
}

@Composable
private fun CoverLayer(model: Any, filter: ColorFilter, modifier: Modifier) {
    if (model is ImageBitmap) Image(model, null, modifier, contentScale = ContentScale.Crop, colorFilter = filter)
    else AsyncImage(model = model, contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop, colorFilter = filter)
}

/** Recorta una franja horizontal (para el glitch). */
private class BandShape(private val band: Pair<Float, Float>) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density) =
        Outline.Rectangle(Rect(0f, size.height * band.first, size.width, size.height * band.second))
}

/** Cinta de advertencia en diagonal (secundario / fondo). */
@Composable
fun Tape(modifier: Modifier = Modifier, height: Dp = 14.dp) {
    val p = AtmosTheme.p
    Box(
        modifier.fillMaxWidth().height(height).clipToBounds().drawBehind {
            drawRect(p.bg)
            val step = 20.dp.toPx(); val w = 10.dp.toPx()
            var x = -size.height
            while (x < size.width + size.height) {
                val path = Path().apply {
                    moveTo(x, size.height); lineTo(x + w, size.height); lineTo(x + w + size.height, 0f); lineTo(x + size.height, 0f); close()
                }
                drawPath(path, p.secondary)
                x += step
            }
        }
    )
}

@Composable
fun NTag(text: String, color: Color = AtmosTheme.p.primary) {
    Box(Modifier.border(1.dp, color.copy(alpha = 0.6f)).padding(horizontal = 6.dp, vertical = 2.dp)) {
        Text(text.uppercase(), style = mono(10.sp, 0.12f, color), maxLines = 1)
    }
}

/** `[CHIP] TEXTO ───── trailing` */
@Composable
fun NHead(chip: String?, text: String, trailing: String? = null, onBack: (() -> Unit)? = null) {
    val p = AtmosTheme.p
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        if (onBack != null) {
            Text("‹", style = com.atmos.launcher.ui.theme.raj(20.sp, color = p.primary),
                modifier = Modifier.padding(end = 8.dp).clickableNoRipple(onBack))
        }
        if (chip != null) {
            Box(Modifier.background(p.secondary).padding(horizontal = 7.dp, vertical = 1.dp)) {
                Text(chip, style = mono(11.sp, 0.16f, p.text))
            }
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = mono(11.sp, 0.16f, p.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(Modifier.weight(1f).padding(horizontal = 8.dp).height(1.dp).background(p.primary.copy(alpha = 0.25f)))
        if (trailing != null) Text(trailing, style = mono(11.sp, 0.12f, p.muted), maxLines = 1)
    }
}

@Composable
fun BigTitle(text: String, size: Int = 46, modifier: Modifier = Modifier) {
    val p = AtmosTheme.p
    val shift = with(LocalDensity.current) { 2.dp.toPx() }
    Text(
        text.uppercase(), modifier = modifier,
        style = anton(size.sp, color = p.primary).copy(shadow = Shadow(p.secondary, Offset(shift, 0f), 0f)),
        maxLines = 2, overflow = TextOverflow.Ellipsis
    )
}

/**
 * Onda dentada (en vez de barras): dos trazos, el secundario desplazado. Solo anima si suena;
 * Compose deja de pedir frames cuando la pantalla no se ve.
 */
@Composable
fun JaggedWave(playing: Boolean, seed: Int, modifier: Modifier) {
    val p = AtmosTheme.p
    val t: State<Float> = if (playing) {
        rememberInfiniteTransition(label = "wave").animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart), label = "t")
    } else remember { mutableFloatStateOf(0.2f) }
    Canvas(modifier) {
        val tt = t.value * 2 * PI.toFloat()
        val pts = 64
        val amp = if (playing) 1f else 0.25f
        val stroke = Stroke(2.5.dp.toPx(), join = StrokeJoin.Miter)
        listOf(p.secondary to 6.dp.toPx(), p.primary to 0f).forEach { (col, off) ->
            val path = Path()
            for (k in 0..pts) {
                val v = sin(k * 0.9f + tt * 3 + seed) * sin(k * 0.23f + tt) + sin(k * 7.1f + seed * 3 + (tt * 6).toInt()) * 0.6f
                val y = size.height / 2 + v * size.height * 0.36f * amp * (if (k % 2 == 1) 1f else -0.6f)
                val x = k.toFloat() / pts * size.width + off
                if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, col, style = stroke)
        }
    }
}

fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)
