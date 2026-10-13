package com.atmos.launcher.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.atmos.launcher.ui.theme.AtmosTheme
import com.atmos.launcher.ui.theme.chroma
import com.atmos.launcher.ui.theme.mono
import com.atmos.launcher.ui.theme.raj

/** Forma base de todo el launcher: esquinas cortadas a 45° arriba-izquierda y/o abajo-derecha. */
fun CutShape(tl: Dp = 0.dp, br: Dp = 0.dp) = CutCornerShape(topStart = tl, bottomEnd = br)

/** Panel con borde de 1dp en el color de acento y cabecera numerada. */
@Composable
fun HudPanel(
    index: String,
    title: String,
    modifier: Modifier = Modifier,
    accent: Color = AtmosTheme.p.primary,
    trailing: String? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = CutShape(14.dp, 14.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(AtmosTheme.p.bg)
            .border(1.dp, accent, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp)
    ) {
        SectionHeader(index, title, trailing, accent)
        content()
    }
}

/** `01 // TÍTULO ───── etiqueta` */
@Composable
fun SectionHeader(index: String, title: String, trailing: String? = null, accent: Color = AtmosTheme.p.primary) {
    val p = AtmosTheme.p
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Box(Modifier.background(accent).padding(horizontal = 6.dp, vertical = 1.dp)) {
            Text(index, style = mono(11.sp, color = p.onPrimary))
        }
        Spacer(Modifier.width(8.dp))
        Text("//", style = mono(11.sp, color = p.muted))
        Spacer(Modifier.width(8.dp))
        Text(title, style = raj(15.sp, tracking = 0.22f, color = p.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(Modifier.weight(1f).padding(horizontal = 8.dp).height(1.dp).background(accent.copy(alpha = 0.4f)))
        if (trailing != null) Text(trailing, style = mono(11.sp, color = p.secondary), maxLines = 1)
    }
}

/** Cabecera ligera de bloque: `02 // MÓDULOS ─────` */
@Composable
fun BlockLabel(index: String, title: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    val p = AtmosTheme.p
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(index, style = mono(11.sp, 0.18f, p.primary))
        Text(" // $title", style = mono(11.sp, 0.18f, p.muted))
        Box(Modifier.weight(1f).padding(horizontal = 10.dp).height(1.dp).background(p.primary.copy(alpha = 0.25f)))
        trailing?.invoke()
    }
}

/**
 * Texto grande rayado (3 unidades de color, 1 transparente) con aberración cromática.
 * El shader se crea una sola vez por color y densidad.
 */
@Composable
fun ScanlineText(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val brush = remember(color, density) { stripeBrush(color, density) }
    Text(text, modifier = modifier, style = style.copy(brush = brush, shadow = chroma()), maxLines = 1)
}

private fun stripeBrush(color: Color, density: Density): Brush {
    val on = with(density) { 3.dp.roundToPx() }.coerceAtLeast(2)
    val off = with(density) { 1.dp.roundToPx() }.coerceAtLeast(1)
    val bmp = ImageBitmap(1, on + off)
    Canvas(bmp).drawRect(0f, 0f, 1f, on.toFloat(), Paint().apply { this.color = color })
    return ShaderBrush(ImageShader(bmp, TileMode.Repeated, TileMode.Repeated))
}

/** Código de 3 letras con borde fino. */
@Composable
fun TagChip(code: String, color: Color = AtmosTheme.p.primary, modifier: Modifier = Modifier, width: Dp? = null) {
    Box(
        modifier
            .then(if (width != null) Modifier.width(width) else Modifier)
            .border(1.dp, color.copy(alpha = 0.55f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(code, style = mono(12.sp, 0.06f, color), maxLines = 1)
    }
}

/**
 * Elemento pulsable estilo menú del juego: al pulsar, el fondo se rellena de primario y el
 * contenido recibe `onPrimary` como color de texto vía [pressed].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Pressable(
    modifier: Modifier = Modifier,
    shape: Shape = CutShape(br = 10.dp),
    idle: Color = Color.Transparent,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
    content: @Composable BoxScope.(pressed: Boolean) -> Unit
) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val p = AtmosTheme.p
    Box(
        modifier
            .clip(shape)
            .background(if (pressed) p.primary else idle)
            .combinedClickable(src, indication = null, onLongClick = onLongClick, onClick = onClick)
    ) { content(pressed) }
}

/** Fila de 56dp: número, icono/código, nombre, estado y `›`. */
@Composable
fun ActionRow(
    index: String,
    name: String,
    sub: String,
    status: String,
    statusColor: Color,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    icon: @Composable () -> Unit
) {
    val p = AtmosTheme.p
    Pressable(Modifier.fillMaxWidth().height(56.dp), onClick = onClick, onLongClick = onLongClick) { pressed ->
        val fg = if (pressed) p.onPrimary else p.text
        Row(Modifier.fillMaxSize().padding(start = 8.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(index, style = mono(11.sp, color = if (pressed) fg else p.muted), modifier = Modifier.width(20.dp))
            icon()
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = raj(18.sp, tracking = 0.14f, color = fg), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(sub, style = mono(10.sp, 0.12f, if (pressed) fg else p.muted), maxLines = 1)
            }
            Text(status, style = raj(12.sp, tracking = 0.16f, color = if (pressed) fg else statusColor), maxLines = 1)
            Spacer(Modifier.width(8.dp))
            Text("›", style = raj(14.sp, color = if (pressed) fg else p.muted))
        }
    }
}

/** Botón biselado; `filled` = acción primaria. */
@Composable
fun CyberButton(
    text: String,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    color: Color = AtmosTheme.p.primary,
    height: Dp = 46.dp,
    onClick: () -> Unit
) {
    val p = AtmosTheme.p
    val shape = CutShape(12.dp, 12.dp)
    Pressable(
        modifier.height(height).border(1.dp, color.copy(alpha = if (filled) 1f else 0.45f), shape),
        shape = shape, idle = if (filled) color else Color.Transparent, onClick = onClick
    ) { pressed ->
        Text(
            text, modifier = Modifier.align(Alignment.Center).padding(horizontal = 14.dp),
            style = raj(14.sp, tracking = 0.24f, color = if (filled || pressed) p.onPrimary else color), maxLines = 1
        )
    }
}

/** Interruptor de opciones tipo pestañas (TODOS / NUEVOS / GRUPOS, BAJO / MEDIO / ALTO…). */
@Composable
fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    val p = AtmosTheme.p
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { o ->
            val on = o == selected
            Box(
                Modifier
                    .height(34.dp)
                    .clip(CutShape(br = 8.dp))
                    .background(if (on) p.primary else Color.Transparent)
                    .border(1.dp, p.primary.copy(alpha = 0.3f), CutShape(br = 8.dp))
                    .clickable { onPick(o) }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center
            ) { Text(label(o), style = raj(13.sp, tracking = 0.2f, color = if (on) p.onPrimary else p.text)) }
        }
    }
}

@Composable
fun ProgressLine(fraction: Float, color: Color, modifier: Modifier = Modifier, height: Dp = 3.dp) {
    val track = AtmosTheme.p.text.copy(alpha = 0.12f)
    Box(
        modifier.fillMaxWidth().height(height).drawBehind {
            drawRect(track)
            drawRect(color, size = Size(size.width * fraction.coerceIn(0f, 1f), size.height))
        }
    )
}

/** Patrón rayado a 45° (marcador de carátula/icono vacío). */
fun DrawScope.hatch(color: Color, step: Float, width: Float) {
    var x = -size.height
    while (x < size.width) {
        drawLine(color, androidx.compose.ui.geometry.Offset(x, size.height), androidx.compose.ui.geometry.Offset(x + size.height, 0f), width)
        x += step
    }
}

/** Scanlines globales: un bitmap 1×3 en mosaico y un único drawRect. Nada animado. */
@Composable
fun ScanlineOverlay(alpha: Float, modifier: Modifier = Modifier) {
    if (alpha <= 0f) return
    val density = LocalDensity.current
    val brush = remember(density, alpha) {
        val step = with(density) { 3.dp.roundToPx() }.coerceAtLeast(3)
        val bmp = ImageBitmap(1, step)
        Canvas(bmp).drawRect(0f, 0f, 1f, 1f, Paint().apply { color = Color.White.copy(alpha = alpha) })
        ShaderBrush(ImageShader(bmp, TileMode.Repeated, TileMode.Repeated))
    }
    Box(modifier.drawBehind { drawRect(brush) })
}

/**
 * Barrido «glitch» de entrada (≤250 ms): recorte que baja de arriba a abajo, 6dp de desplazamiento
 * en X y opacidad. Solo toca graphicsLayer: el contenido no se recompone durante la animación.
 */
fun Modifier.glitchEnter(): Modifier = composed {
    val anim = remember { Animatable(0f) }
    val shift = with(LocalDensity.current) { 6.dp.toPx() }
    LaunchedEffect(Unit) { anim.animateTo(1f, tween(240, easing = FastOutSlowInEasing)) }
    graphicsLayer {
        val t = anim.value
        if (t < 1f) {
            alpha = (t * 1.6f).coerceAtMost(1f)
            translationX = (1f - t) * -shift * if (t < 0.35f) 1f else -0.5f
            clip = true
            shape = RevealShape(t)
        } else {
            clip = false
        }
    }
}

private class RevealShape(private val t: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rectangle(Rect(0f, 0f, size.width, size.height * (0.15f + 0.85f * t)))
}

/** Rejilla simple de n columnas sin lazy (para 9 casillas no compensa un LazyGrid). */
@Composable
fun SimpleGrid(columns: Int, spacing: Dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val gap = spacing.roundToPx()
        val w = (constraints.maxWidth - gap * (columns - 1)) / columns
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = w, maxWidth = w, minHeight = 0)) }
        val rows = placeables.chunked(columns)
        val rowH = rows.map { r -> r.maxOf { it.height } }
        val h = rowH.sum() + gap * (rows.size - 1).coerceAtLeast(0)
        layout(constraints.maxWidth, h) {
            var y = 0
            rows.forEachIndexed { ri, r ->
                r.forEachIndexed { ci, pl -> pl.place(ci * (w + gap), y) }
                y += rowH[ri] + gap
            }
        }
    }
}
