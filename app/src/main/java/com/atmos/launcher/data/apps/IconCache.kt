package com.atmos.launcher.data.apps

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Build
import android.util.LruCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Iconos reales rasterizados una vez y cacheados por app + tema + modo.
 *  1. Android 13+: capa monocroma del icono adaptativo teñida con el primario (la que mejor encaja).
 *  2. Si no la tiene: escala de grises con contraste alto y multiplicado por el primario.
 *  3. Ajuste «originales a color»: el icono tal cual.
 */
class IconCache(private val ctx: Context, private val apps: AppsRepository) {
    private val cache = LruCache<String, ImageBitmap>(220)
    private val dpi = ctx.resources.displayMetrics.densityDpi

    fun peek(key: String) = cache.get(key)

    fun cacheKey(appKey: String, tint: Color, tinted: Boolean, sizePx: Int, version: Int) =
        "$appKey|${tint.toArgb()}|$tinted|$sizePx|$version"

    suspend fun load(appKey: String, tint: Color, tinted: Boolean, sizePx: Int, version: Int): ImageBitmap? {
        val k = cacheKey(appKey, tint, tinted, sizePx, version)
        cache.get(k)?.let { return it }
        val info = apps.info(appKey) ?: return null
        return withContext(Dispatchers.Default) {
            try {
                val d = info.getIcon(dpi) ?: return@withContext null
                val s = sizePx
                val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
                val c = Canvas(bmp)
                val mono = if (tinted && Build.VERSION.SDK_INT >= 33 && d is AdaptiveIconDrawable) d.monochrome else null
                when {
                    mono != null -> {
                        // La capa monocroma es de 108dp con 72dp visibles: se amplía 1,5× para recortar el margen.
                        val extra = (s * 0.25f).toInt()
                        mono.mutate().apply {
                            setBounds(-extra, -extra, s + extra, s + extra)
                            colorFilter = PorterDuffColorFilter(tint.toArgb(), PorterDuff.Mode.SRC_IN)
                            draw(c)
                        }
                    }
                    tinted -> {
                        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(tintMatrix(tint)) }
                        c.saveLayer(null, paint)
                        d.setBounds(0, 0, s, s)
                        d.draw(c)
                        c.restore()
                    }
                    else -> {
                        d.setBounds(0, 0, s, s)
                        d.draw(c)
                    }
                }
                bmp.asImageBitmap().also { cache.put(k, it) }
            } catch (_: Throwable) { null }
        }
    }

    fun clear() = cache.evictAll()

    /** Luminancia con contraste ×1.35 y luego multiplicada por el color (con un 25 % de base para que lo oscuro no desaparezca). */
    private fun tintMatrix(tint: Color): ColorMatrix {
        val k = 1.35f
        fun row(ch: Float): FloatArray {
            val a = ch * 0.75f * k
            val off = ch * 255f * 0.25f - ch * 0.75f * (k - 1f) * 128f
            return floatArrayOf(a * 0.299f, a * 0.587f, a * 0.114f, 0f, off)
        }
        return ColorMatrix(row(tint.red) + row(tint.green) + row(tint.blue) + floatArrayOf(0f, 0f, 0f, 1f, 0f))
    }
}
