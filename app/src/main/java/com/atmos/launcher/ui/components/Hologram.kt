package com.atmos.launcher.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.atmos.launcher.ui.theme.AtmosTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object HologramStore {
    /**
     * Copia la imagen elegida a almacenamiento interno, reducida a 1080 px de ancho como máximo.
     * Así no dependemos del permiso de la galería y la decodificación posterior es barata.
     */
    suspend fun import(ctx: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 1080) sample *= 2
            val bmp = ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return@withContext null
            val scaled = if (bmp.width > 1080) Bitmap.createScaledBitmap(bmp, 1080, bmp.height * 1080 / bmp.width, true) else bmp
            val dir = File(ctx.filesDir, "hologram").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
            // Nombre con marca de tiempo: cambia la clave y fuerza a recargar la imagen nueva.
            val f = File(dir, "holo_${System.currentTimeMillis()}.png")
            f.outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
            f.absolutePath
        } catch (_: Throwable) { null }
    }

    fun clear(ctx: Context) {
        File(ctx.filesDir, "hologram").listFiles()?.forEach { it.delete() }
    }
}

/** Intensidad en reposo (inicio) según el ajuste SUTIL / MEDIO / INTENSO. */
fun hologramRestAlpha(level: Int) = floatArrayOf(0.30f, 0.45f, 0.62f)[level.coerceIn(0, 2)]

/**
 * Luminancia → transparencia, con el color fijo de la plantilla: lo negro desaparece y lo claro
 * brilla en ese color. Es literalmente una proyección monocroma.
 */
private fun hologramFilter(c: Color, gain: Float): ColorFilter = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            0f, 0f, 0f, 0f, c.red * 255f,
            0f, 0f, 0f, 0f, c.green * 255f,
            0f, 0f, 0f, 0f, c.blue * 255f,
            0.299f * gain, 0.587f * gain, 0.114f * gain, 0f, 0f
        )
    )
)

@Composable
fun rememberHologramBitmap(path: String?): ImageBitmap? {
    val img by produceState<ImageBitmap?>(null, path) {
        value = if (path == null) null else withContext(Dispatchers.IO) {
            try { BitmapFactory.decodeFile(path)?.asImageBitmap() } catch (_: Throwable) { null }
        }
    }
    return img
}

/**
 * Holograma de fondo. Se dibuja UNA vez en una capa propia (imagen teñida en el primario, fantasma
 * desplazado en el secundario, bordes difuminados y líneas de escaneo recortadas). La intensidad
 * llega por [alpha] y solo cambia la opacidad de esa capa: deslizar no redibuja nada.
 */
@Composable
fun HologramBackdrop(bmp: ImageBitmap, alpha: () -> Float, modifier: Modifier = Modifier) {
    val p = AtmosTheme.p
    val main = remember(p.primary) { hologramFilter(p.primary, 1.15f) }
    val ghost = remember(p.secondary) { hologramFilter(p.secondary, 0.8f) }
    val density = LocalDensity.current
    val lines = remember(density) {
        val step = with(density) { 3.dp.roundToPx() }.coerceAtLeast(3)
        val b = ImageBitmap(1, step)
        Canvas(b).drawRect(0f, 0f, 1f, 1f, Paint().apply { color = Color.Black })
        ShaderBrush(ImageShader(b, TileMode.Repeated, TileMode.Repeated))
    }
    Box(
        modifier
            .graphicsLayer {
                this.alpha = alpha().coerceIn(0f, 1f)
                compositingStrategy = CompositingStrategy.Offscreen
            }
            .drawWithCache {
                val w = size.width.toInt()
                val h = (bmp.height * size.width / bmp.width).toInt()
                val top = ((size.height - h) / 2f).toInt()
                val shift = 5.dp.toPx().toInt()
                val mask = Brush.verticalGradient(
                    0f to Color.Transparent, 0.16f to Color.Black, 0.78f to Color.Black, 1f to Color.Transparent,
                    startY = top.toFloat(), endY = (top + h).toFloat()
                )
                onDrawBehind {
                    val src = IntSize(bmp.width, bmp.height)
                    drawImage(bmp, IntOffset.Zero, src, IntOffset(shift, top), IntSize(w, h), alpha = 0.55f, colorFilter = ghost)
                    drawImage(bmp, IntOffset.Zero, src, IntOffset(0, top), IntSize(w, h), colorFilter = main)
                    drawRect(lines, alpha = 0.45f, blendMode = BlendMode.DstOut) // líneas de escaneo
                    drawRect(mask, blendMode = BlendMode.DstIn)                   // bordes difuminados
                }
            }
    )
}
