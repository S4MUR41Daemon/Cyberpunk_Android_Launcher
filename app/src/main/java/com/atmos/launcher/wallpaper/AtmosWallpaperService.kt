package com.atmos.launcher.wallpaper

import android.content.ComponentName
import android.graphics.*
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.text.TextPaint
import android.text.TextUtils
import android.view.SurfaceHolder
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.res.ResourcesCompat
import com.atmos.launcher.LauncherApp
import com.atmos.launcher.R
import com.atmos.launcher.data.media.MediaHub
import com.atmos.launcher.data.media.MediaState
import com.atmos.launcher.data.notifications.AtmosNotificationListener
import com.atmos.launcher.data.notifications.NotifItem
import com.atmos.launcher.data.notifications.NotificationHub
import com.atmos.launcher.data.settings.LauncherSettings
import com.atmos.launcher.data.weather.WeatherState
import com.atmos.launcher.ui.theme.Palette
import com.atmos.launcher.ui.theme.Palettes
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * Fondo animado de ATMOS//OS para la pantalla de bloqueo (sustituye a Wallpaper Engine y similares).
 *
 * Batería: nada se anima de forma continua. Se redibuja solo cuando cambia algo (canción,
 * notificaciones, tema, tiempo), 1 vez/s mientras suena música (barra de progreso) y un glitch de
 * ~150 ms cada 10–18 s. Con la pantalla apagada o tapado por el launcher, todo se detiene.
 * El holograma (lo caro) se compone una sola vez en un bitmap y se reutiliza.
 */
class AtmosWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = AtmosEngine()

    private data class Snapshot(
        val settings: LauncherSettings,
        val media: MediaState?,
        val notifs: List<NotifItem>,
        val weather: WeatherState
    )

    inner class AtmosEngine : Engine() {
        private val container = (application as LauncherApp).container
        private val scope = MainScope()
        private var jobs = emptyList<Job>()
        private var width = 0
        private var height = 0
        private var snap: Snapshot? = null
        private val d = resources.displayMetrics.density

        private val rajBold = ResourcesCompat.getFont(this@AtmosWallpaperService, R.font.rajdhani_bold) ?: Typeface.DEFAULT_BOLD
        private val rajMed = ResourcesCompat.getFont(this@AtmosWallpaperService, R.font.rajdhani_medium) ?: Typeface.DEFAULT
        private val mono = ResourcesCompat.getFont(this@AtmosWallpaperService, R.font.share_tech_mono) ?: Typeface.MONOSPACE

        // Holograma compuesto y cacheado (se rehace solo si cambia imagen, tema o tamaño).
        private var holo: Bitmap? = null
        private var holoKey = ""
        private var srcImage: Bitmap? = null
        private var srcPath: String? = null

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(false)
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
            super.onSurfaceChanged(holder, format, w, h)
            width = w; height = h
            draw()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            if (visible) start() else stop()
        }

        override fun onDestroy() {
            stop()
            scope.cancel()
            super.onDestroy()
        }

        private fun start() {
            stop()
            val ctx = this@AtmosWallpaperService
            if (NotificationHub.isAccessGranted(ctx)) {
                MediaHub.attach(ctx.applicationContext, ComponentName(ctx, AtmosNotificationListener::class.java))
            }
            container.weather.refresh()
            val data = scope.launch {
                combine(container.settings.settings, MediaHub.state, NotificationHub.items, container.weather.state) { s, m, n, w ->
                    Snapshot(s, m, n, w)
                }.collect { snap = it; draw() }
            }
            val tick = scope.launch {
                while (isActive) {
                    delay(1000)
                    // Progreso de la canción: solo mientras suena y se ve.
                    if (snap?.media?.isPlaying == true) draw()
                }
            }
            val glitch = scope.launch {
                while (isActive) {
                    delay(10_000L + Random.nextLong(8_000))
                    draw(glitch = true); delay(90); draw(glitch = true); delay(70); draw()
                }
            }
            jobs = listOf(data, tick, glitch)
            draw()
        }

        private fun stop() {
            jobs.forEach { it.cancel() }
            jobs = emptyList()
        }

        private fun draw(glitch: Boolean = false) {
            if (width == 0 || height == 0) return
            val s = snap ?: return
            val holder = surfaceHolder
            val c = try { holder.lockCanvas() } catch (_: Throwable) { null } ?: return
            try {
                render(c, s, glitch)
            } finally {
                try { holder.unlockCanvasAndPost(c) } catch (_: Throwable) {}
            }
        }

        // ───────────────────────── Render ─────────────────────────

        private fun render(c: Canvas, s: Snapshot, glitch: Boolean) {
            val p = Palettes.byId(s.settings.themeId)
            c.drawColor(p.bg.toArgb())
            val h = hologram(s.settings.lockImagePath ?: s.settings.hologramPath, p)
            if (h != null) {
                c.drawBitmap(h, 0f, 0f, null)
                if (glitch) glitchSlices(c, h, p)
            }
            frame(c, p)

            // Composición de abajo arriba, respetando los atajos y el texto de desbloqueo del sistema.
            var y = height - 150 * d
            y = weatherStrip(c, p, s.weather, y)
            if (s.settings.lockShowMusic) s.media?.let { y = musicCard(c, p, it, y - 14 * d) }
            if (s.settings.lockNotifMode < 2) notifications(c, p, s.notifs, s.settings.lockNotifMode, y - 14 * d)
        }

        /** Imagen → luminancia como alfa, teñida (primario + fantasma secundario), scanlines y bordes fundidos. */
        private fun hologram(path: String?, p: Palette): Bitmap? {
            if (path == null) return null
            val key = "$path|${p.id}|$width|$height"
            if (key == holoKey && holo != null) return holo
            if (path != srcPath) {
                srcImage = try { BitmapFactory.decodeFile(path) } catch (_: Throwable) { null }
                srcPath = path
            }
            val src = srcImage ?: return null
            val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            // Cubrir toda la pantalla (center-crop).
            val scale = maxOf(width.toFloat() / src.width, height.toFloat() / src.height)
            val dw = src.width * scale
            val dh = src.height * scale
            val dst = RectF((width - dw) / 2f, (height - dh) / 2f, (width + dw) / 2f, (height + dh) / 2f)
            val layer = c.saveLayer(null, null)
            val shift = 7 * d
            c.drawBitmap(src, null, RectF(dst.left + shift, dst.top, dst.right + shift, dst.bottom),
                Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = lumaFilter(p.secondary.toArgb(), 0.85f); alpha = 150 })
            c.drawBitmap(src, null, dst, Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = lumaFilter(p.primary.toArgb(), 1.2f) })
            // Líneas de escaneo recortadas en la propia imagen.
            val line = Paint().apply { color = Color.BLACK; alpha = 120; xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
            val step = (3 * d).coerceAtLeast(3f)
            var yy = 0f
            while (yy < height) { c.drawRect(0f, yy, width.toFloat(), yy + d, line); yy += step }
            // Fundido: arriba (reloj del sistema) más suave, abajo hacia el panel de música.
            val mask = Paint().apply {
                shader = LinearGradient(0f, 0f, 0f, height.toFloat(),
                    intArrayOf(0x55000000, Color.BLACK, Color.BLACK, 0x33000000),
                    floatArrayOf(0f, 0.25f, 0.62f, 1f), Shader.TileMode.CLAMP)
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            }
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), mask)
            c.restoreToCount(layer)
            holo?.recycle()
            holo = out
            holoKey = key
            return out
        }

        private fun lumaFilter(color: Int, gain: Float) = ColorMatrixColorFilter(
            ColorMatrix(floatArrayOf(
                0f, 0f, 0f, 0f, Color.red(color).toFloat(),
                0f, 0f, 0f, 0f, Color.green(color).toFloat(),
                0f, 0f, 0f, 0f, Color.blue(color).toFloat(),
                0.299f * gain, 0.587f * gain, 0.114f * gain, 0f, 0f
            ))
        )

        private fun glitchSlices(c: Canvas, h: Bitmap, p: Palette) {
            val tint = Paint().apply { color = p.secondary.toArgb(); alpha = 60 }
            repeat(5) {
                val y0 = Random.nextInt(height)
                val sh = (6 + Random.nextInt(40)) * d.toInt()
                val dx = (Random.nextInt(-28, 28) * d).toInt()
                val src = Rect(0, y0, width, (y0 + sh).coerceAtMost(height))
                val dst = Rect(dx, y0, width + dx, (y0 + sh).coerceAtMost(height))
                c.drawRect(0f, y0.toFloat(), width.toFloat(), (y0 + sh).toFloat(), Paint().apply { color = p.bg.toArgb() })
                c.drawBitmap(h, src, dst, null)
                if (it % 2 == 0) c.drawRect(dst, tint)
            }
        }

        /** Esquinas de visor y etiqueta lateral. */
        private fun frame(c: Canvas, p: Palette) {
            val paint = Paint().apply { color = p.primary.toArgb() }
            val m = 14 * d; val arm = 30 * d; val sw = 2 * d
            val w = width.toFloat(); val hh = height.toFloat()
            fun r(x: Float, y: Float, rw: Float, rh: Float) = c.drawRect(x, y, x + rw, y + rh, paint)
            r(m, m + 30 * d, arm, sw); r(m, m + 30 * d, sw, arm)
            r(w - m - arm, m + 30 * d, arm, sw); r(w - m - sw, m + 30 * d, sw, arm)
            r(m, hh - m - sw, arm, sw); r(m, hh - m - arm, sw, arm)
            r(w - m - arm, hh - m - sw, arm, sw); r(w - m - sw, hh - m - arm, sw, arm)
            val t = text(mono, 10f, p.muted.toArgb(), 0.2f)
            c.save()
            c.rotate(-90f, w - m - 6 * d, hh / 2f)
            c.drawText("ATMOS//OS · ENLACE SEGURO · ${p.name}", w - m - 6 * d - 120 * d, hh / 2f, t)
            c.restore()
        }

        private fun weatherStrip(c: Canvas, p: Palette, w: WeatherState, bottom: Float): Float {
            val data = w.data ?: return bottom
            val x = 22 * d
            val chip = text(mono, 11f, p.onPrimary.toArgb(), 0.16f)
            val label = "ATMOS"
            val cw = chip.measureText(label) + 12 * d
            c.drawRect(x, bottom - 18 * d, x + cw, bottom, Paint().apply { color = p.secondary.toArgb() })
            c.drawText(label, x + 6 * d, bottom - 5 * d, chip)
            val info = text(mono, 12f, p.text.toArgb(), 0.14f)
            c.drawText("${data.city.uppercase()} · ${data.temp}° · ${data.code} · ▲${data.hi} ▼${data.lo}", x + cw + 10 * d, bottom - 5 * d, info)
            return bottom - 18 * d
        }

        private fun cutPath(l: Float, t: Float, r: Float, b: Float, cut: Float) = Path().apply {
            moveTo(l + cut, t); lineTo(r, t); lineTo(r, b - cut); lineTo(r - cut, b); lineTo(l, b); lineTo(l, t + cut); close()
        }

        /** Tarjeta «EN CURSO»: carátula, título con aberración, artista, visualizador y progreso. */
        private fun musicCard(c: Canvas, p: Palette, m: MediaState, bottom: Float): Float {
            val l = 18 * d; val r = width - 18 * d
            val hgt = 168 * d
            val t = bottom - hgt
            val path = cutPath(l, t, r, bottom, 16 * d)
            c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.panel.toArgb(); alpha = 205 })
            c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.secondary.toArgb(); style = Paint.Style.STROKE; strokeWidth = 1.5f * d })
            c.drawRect(r - 80 * d, t, r - 30 * d, t + 4 * d, Paint().apply { color = p.primary.toArgb() })

            val pad = 16 * d
            val head = text(mono, 11f, p.secondary.toArgb(), 0.18f)
            c.drawText((if (m.isPlaying) "▶ EN CURSO" else "❚❚ PAUSA") + "  //  ${m.app}", l + pad, t + pad + 10 * d, head)

            val art = 92 * d
            val ax = l + pad; val ay = t + pad + 22 * d
            val artRect = RectF(ax, ay, ax + art, ay + art)
            val artBmp = m.art?.asAndroidBitmap()
            if (artBmp != null) c.drawBitmap(artBmp, null, artRect, Paint(Paint.FILTER_BITMAP_FLAG))
            else c.drawRect(artRect, Paint().apply { color = p.secondary.toArgb(); alpha = 50 })
            c.drawRect(artRect, Paint().apply { color = p.secondary.toArgb(); style = Paint.Style.STROKE; strokeWidth = d })

            val tx = ax + art + 14 * d
            val tw = r - pad - tx
            val title = text(rajBold, 24f, p.text.toArgb(), 0.06f).apply { setShadowLayer(0.01f, 2f * d, 0f, p.secondary.toArgb()) }
            c.drawText(ellipsize(m.title.uppercase(), title, tw), tx, ay + 24 * d, title)
            val artist = text(rajBold, 15f, p.secondary.toArgb(), 0.12f)
            c.drawText(ellipsize(m.artist.uppercase(), artist, tw), tx, ay + 46 * d, artist)

            // Visualizador: barras pseudoaleatorias, se «mueven» solo con el tick de 1 s y si suena.
            val bars = 22
            val vb = ay + art - 14 * d
            val gap = 2 * d
            val bw = (tw - gap * (bars - 1)) / bars
            val rnd = Random(if (m.isPlaying) SystemClock.elapsedRealtime() / 1000 else 7L)
            val barPaint = Paint()
            for (i in 0 until bars) {
                val v = 0.15f + 0.85f * rnd.nextFloat() * if (m.isPlaying) 1f else 0.35f
                barPaint.color = (if (i % 5 == 0) p.secondary else p.primary).toArgb()
                c.drawRect(tx + i * (bw + gap), vb - 30 * d * v, tx + i * (bw + gap) + bw, vb, barPaint)
            }

            // Progreso calculado, no consultado.
            val pos = m.positionAt()
            val frac = if (m.durationMs > 0) (pos.toFloat() / m.durationMs).coerceIn(0f, 1f) else 0f
            val py = bottom - 18 * d
            c.drawRect(l + pad, py, r - pad, py + 3 * d, Paint().apply { color = p.text.toArgb(); alpha = 30 })
            c.drawRect(l + pad, py, l + pad + (r - l - 2 * pad) * frac, py + 3 * d, Paint().apply { color = p.primary.toArgb() })
            val small = text(mono, 10f, p.muted.toArgb(), 0f)
            c.drawText(fmt(pos), l + pad, py - 5 * d, small)
            val dur = fmt(m.durationMs)
            c.drawText(dur, r - pad - small.measureText(dur), py - 5 * d, small)
            return t
        }

        /** Hasta 3 notificaciones con el borde del color de su categoría. */
        private fun notifications(c: Canvas, p: Palette, items: List<NotifItem>, mode: Int, bottom: Float) {
            if (items.isEmpty()) return
            val show = items.take(3)
            val l = 18 * d; val r = width - 18 * d
            val cardH = if (mode == 0) 70 * d else 52 * d
            val gap = 8 * d
            var t = bottom - show.size * (cardH + gap)
            val head = text(mono, 11f, p.muted.toArgb(), 0.18f)
            c.drawText("// COMMS [${items.size}]" + if (items.size > 3) "  +${items.size - 3} MÁS" else "", l, t - 8 * d, head)
            val fmtTime = SimpleDateFormat("HH:mm", Locale("es"))
            for (n in show) {
                val b = t + cardH
                val path = cutPath(l, t, r, b, 10 * d)
                c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.panel.toArgb(); alpha = 200 })
                val accent = when (n.category) {
                    android.app.Notification.CATEGORY_MESSAGE, android.app.Notification.CATEGORY_EMAIL -> p.primary
                    android.app.Notification.CATEGORY_CALL, android.app.Notification.CATEGORY_ALARM -> p.warn
                    android.app.Notification.CATEGORY_SOCIAL -> p.secondary
                    else -> p.tertiary
                }
                c.drawRect(l, t, l + 3 * d, b, Paint().apply { color = accent.toArgb() })
                val x = l + 14 * d
                val w = r - x - 12 * d
                val meta = text(mono, 10f, p.muted.toArgb(), 0.14f)
                c.drawText(n.app.uppercase(), x, t + 16 * d, meta)
                val time = fmtTime.format(Date(n.time))
                c.drawText(time, r - 12 * d - meta.measureText(time), t + 16 * d, meta)
                val title = text(rajBold, 16f, p.text.toArgb(), 0.08f)
                c.drawText(ellipsize(n.title.uppercase().ifEmpty { "NUEVA NOTIFICACIÓN" }, title, w), x, t + 36 * d, title)
                if (mode == 0 && n.body.isNotEmpty()) {
                    val body = text(rajMed, 14f, p.muted.toArgb(), 0f)
                    c.drawText(ellipsize(n.body.replace('\n', ' '), body, w), x, t + 56 * d, body)
                }
                t = b + gap
            }
        }

        private fun text(tf: Typeface, sp: Float, color: Int, tracking: Float) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = tf
            textSize = sp * resources.displayMetrics.scaledDensity
            this.color = color
            letterSpacing = tracking
        }

        private fun ellipsize(s: String, paint: TextPaint, w: Float) =
            TextUtils.ellipsize(s, paint, w, TextUtils.TruncateAt.END).toString()

        private fun fmt(ms: Long): String {
            val s = (ms / 1000).coerceAtLeast(0)
            return "%d:%02d".format(s / 60, s % 60)
        }
    }
}
