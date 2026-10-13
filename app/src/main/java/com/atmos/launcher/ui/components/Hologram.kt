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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
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

private val LEVEL_ALPHA = floatArrayOf(0.16f, 0.28f, 0.42f)

/** Luminancia → color de la plantilla. Lo negro queda negro y con BlendMode.Screen desaparece. */
private fun lumaTint(c: Color, gain: Float): ColorFilter {
    fun row(ch: Float) = floatArrayOf(0.299f * ch * gain, 0.587f * ch * gain, 0.114f * ch * gain, 0f, 0f)
    return ColorFilter.colorMatrix(ColorMatrix(row(c.red) + row(c.green) + row(c.blue) + floatArrayOf(0f, 0f, 0f, 1f, 0f)))
}

/**
 * Holograma de fondo: la imagen del usuario teñida con el primario, con un «fantasma» desplazado en el
 * color secundario (aberración) y fundida en modo pantalla. Ajustada al ancho y anclada abajo, con la
 * parte superior difuminada para que la hora se lea. Todo estático: se dibuja una vez y se cachea.
 */
@Composable
fun HologramBackdrop(path: String?, level: Int, modifier: Modifier = Modifier) {
    if (path == null) return
    val img by produceState<ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.IO) {
            try { BitmapFactory.decodeFile(path)?.asImageBitmap() } catch (_: Throwable) { null }
        }
    }
    val bmp = img ?: return
    val p = AtmosTheme.p
    val alpha = LEVEL_ALPHA[level.coerceIn(0, 2)]
    val main = remember(p.primary) { lumaTint(p.primary, 1.25f) }
    val ghost = remember(p.secondary) { lumaTint(p.secondary, 1.1f) }
    Box(
        modifier.drawWithCache {
            val scale = size.width / bmp.width
            val h = (bmp.height * scale).toInt()
            val w = size.width.toInt()
            val top = (size.height - h).toInt().coerceAtMost((size.height * 0.18f).toInt())
            val shift = 4.dp.toPx().toInt()
            val fade = Brush.verticalGradient(
                0f to p.bg, 0.30f to p.bg.copy(alpha = 0.85f), 0.55f to Color.Transparent,
                startY = 0f, endY = size.height
            )
            val bottomFade = Brush.verticalGradient(listOf(Color.Transparent, p.bg), startY = size.height * 0.85f, endY = size.height)
            onDrawBehind {
                drawImage(
                    bmp, srcOffset = IntOffset.Zero, srcSize = IntSize(bmp.width, bmp.height),
                    dstOffset = IntOffset(shift, top), dstSize = IntSize(w, h),
                    alpha = alpha * 0.7f, colorFilter = ghost, blendMode = BlendMode.Screen
                )
                drawImage(
                    bmp, srcOffset = IntOffset.Zero, srcSize = IntSize(bmp.width, bmp.height),
                    dstOffset = IntOffset(0, top), dstSize = IntSize(w, h),
                    alpha = alpha, colorFilter = main, blendMode = BlendMode.Screen
                )
                drawRect(fade)
                drawRect(bottomFade, topLeft = Offset(0f, size.height * 0.85f))
            }
        }
    )
}
