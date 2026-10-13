package com.atmos.launcher.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.atmos.launcher.data.media.MediaHub
import com.atmos.launcher.data.media.MediaState
import com.atmos.launcher.ui.LocalContainer
import com.atmos.launcher.ui.Routes
import com.atmos.launcher.ui.components.*
import com.atmos.launcher.ui.go
import com.atmos.launcher.ui.theme.AtmosTheme
import com.atmos.launcher.ui.theme.chroma
import com.atmos.launcher.ui.theme.mono
import com.atmos.launcher.ui.theme.raj
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

fun fmtMs(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
fun MusicScreen(nav: NavHostController) {
    val c = LocalContainer.current
    val p = AtmosTheme.p
    val media by MediaHub.state.collectAsStateWithLifecycle()
    val history by remember { c.db.tracks().recent() }.collectAsStateWithLifecycle(emptyList())
    val m = media
    val src = m?.app ?: "SPOTIFY"

    Column(
        Modifier.fillMaxSize().glitchEnter().verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 18.dp)
    ) {
        BackBar("AUDIO", p.secondary, "FUENTE: $src", onBack = { nav.popBackStack() })
        Text("NEURO//AUDIO", style = raj(44.sp, tracking = 0.04f, color = p.primary).copy(shadow = chroma()), modifier = Modifier.padding(top = 12.dp))
        Text(if (m != null) "SESIÓN ACTIVA · 1 FUENTE" else "SIN SESIÓN ACTIVA", style = raj(13.sp, tracking = 0.22f, color = p.secondary))

        Spacer(Modifier.height(18.dp))
        if (m != null) {
            val pos by rememberPosition(m)
            HudPanel("01", "EN CURSO", trailing = if (m.isPlaying) "▶ EN CURSO" else "❚❚ PAUSA", onClick = { nav.go(Routes.PLAYER) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Cover(m, 92.dp, label = true)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(m.title.uppercase(), style = raj(22.sp, tracking = 0.1f, color = p.text), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(m.artist.uppercase(), style = raj(14.sp, tracking = 0.14f, color = p.secondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Visualizer(m.isPlaying, bars = 16, modifier = Modifier.padding(top = 10.dp).fillMaxWidth().height(28.dp))
                    }
                }
                ProgressLine(if (m.durationMs > 0) pos.toFloat() / m.durationMs else 0f, p.primary, Modifier.padding(top = 12.dp))
                Row(Modifier.fillMaxWidth().padding(top = 5.dp)) {
                    Text(fmtMs(pos), style = mono(10.sp, 0f, p.muted))
                    Spacer(Modifier.weight(1f))
                    Text(fmtMs(m.durationMs), style = mono(10.sp, 0f, p.muted))
                }
            }
        } else {
            HudPanel("01", "EN CURSO", trailing = "—") {
                Text("// Reproduce algo en Spotify u otra app de música y aparecerá aquí.", style = mono(11.sp, 0.06f, p.muted))
            }
        }

        Spacer(Modifier.height(24.dp))
        BlockLabel("02", "REGISTRO DE ESCUCHA")
        Spacer(Modifier.height(6.dp))
        if (history.isEmpty()) Text("// Vacío. El launcher anota cada pista que suena.", style = mono(11.sp, 0.06f, p.muted), modifier = Modifier.padding(vertical = 10.dp))
        val fmt = remember { SimpleDateFormat("HH:mm", Locale("es")) }
        history.forEachIndexed { i, h ->
            Row(
                Modifier.fillMaxWidth().height(56.dp).drawBehind {
                    drawLine(p.text.copy(alpha = 0.07f), Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
                },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("%02d".format(i + 1), style = mono(11.sp, 0f, p.muted), modifier = Modifier.width(26.dp))
                Column(Modifier.weight(1f)) {
                    Text(h.title.uppercase(), style = raj(16.sp, tracking = 0.12f, color = p.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(h.artist.uppercase(), style = raj(12.sp, tracking = 0.12f, color = p.secondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(fmt.format(Date(h.playedAt)), style = mono(10.sp, 0f, p.muted))
            }
        }
        Spacer(Modifier.height(16.dp))
        CyberButton("ABRIR EN ${src} ↗", Modifier.fillMaxWidth(), color = p.text) {
            val pkg = m?.pkg ?: "com.spotify.music"
            c.apps.launchPackage(pkg)
        }
    }
}

@Composable
fun PlayerScreen(nav: NavHostController) {
    val p = AtmosTheme.p
    val media by MediaHub.state.collectAsStateWithLifecycle()
    val m = media
    if (m == null) {
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            BackBar("AUDIO", p.secondary, "SIN SESIÓN", onBack = { nav.popBackStack() })
            Text("// No hay nada sonando.", style = mono(12.sp, 0.06f, p.muted), modifier = Modifier.padding(top = 20.dp))
        }
        return
    }
    val pos by rememberPosition(m)
    var liked by remember(m.title) { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().glitchEnter().verticalScroll(rememberScrollState())
            .padding(start = 22.dp, end = 22.dp, top = 10.dp, bottom = 18.dp)
    ) {
        BackBar("REPRODUCIENDO", p.primary, "// ${m.app}", onBack = { nav.popBackStack() })

        val shape = CutShape(22.dp, 22.dp)
        Box(
            Modifier.padding(top = 16.dp).fillMaxWidth().aspectRatio(1f).clip(shape).background(p.bg).border(1.dp, p.primary, shape)
        ) {
            Cover(m, 0.dp, label = true, fill = true)
            Box(Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 28.dp).size(64.dp, 4.dp).background(p.secondary))
            if (m.album.isNotEmpty()) {
                Text("◤ ${m.album.uppercase()}", style = mono(10.sp, 0.16f, p.primary), maxLines = 1,
                    modifier = Modifier.align(Alignment.BottomStart).background(p.bg.copy(alpha = 0.7f)).padding(horizontal = 14.dp, vertical = 8.dp))
            }
        }

        Text(m.title.uppercase(), style = raj(32.sp, tracking = 0.08f, color = p.text).copy(shadow = chroma()), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 18.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            Text(m.artist.uppercase(), style = raj(16.sp, tracking = 0.16f, color = p.secondary), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            val like = m.likeAction
            if (like != null) {
                val col = if (liked) p.secondary else p.primary
                Text(
                    if (liked) "♥ GUARDADA" else "+ GUARDAR", style = mono(12.sp, 0.12f, col),
                    modifier = Modifier.border(1.dp, col).clickable { MediaHub.custom(like); liked = !liked }.padding(horizontal = 8.dp, vertical = 6.dp)
                )
            }
        }

        Visualizer(m.isPlaying, bars = 32, modifier = Modifier.padding(top = 14.dp).fillMaxWidth().height(46.dp))
        SeekBar(pos, m.durationMs, Modifier.padding(top = 10.dp))
        Row(Modifier.fillMaxWidth()) {
            Text(fmtMs(pos), style = mono(11.sp, 0f, p.muted))
            Spacer(Modifier.weight(1f))
            Text("-" + fmtMs((m.durationMs - pos).coerceAtLeast(0)), style = mono(11.sp, 0f, p.muted))
        }

        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            SmallToggle("ALT", m.shuffleAction) { MediaHub.custom(it) }
            SquareBtn("◀◀", filled = false, size = 52.dp) { MediaHub.previous() }
            val big = CutShape(br = 14.dp)
            Pressable(Modifier.size(84.dp, 68.dp), shape = big, idle = p.primary, onClick = { MediaHub.playPause() }) {
                Text(if (m.isPlaying) "❚❚" else "▶", style = raj(26.sp, tracking = 0f, color = p.onPrimary), modifier = Modifier.align(Alignment.Center))
            }
            SquareBtn("▶▶", filled = false, size = 52.dp) { MediaHub.next() }
            SmallToggle("RPT", m.repeatAction) { MediaHub.custom(it) }
        }

        if (m.queue.isNotEmpty()) {
            Spacer(Modifier.height(22.dp))
            BlockLabel("", "A CONTINUACIÓN")
            m.queue.forEachIndexed { i, q ->
                Row(
                    Modifier.fillMaxWidth().height(48.dp).clickable { MediaHub.skipToQueueItem(q.id) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("%02d".format(i + 1), style = mono(11.sp, 0f, p.primary), modifier = Modifier.width(26.dp))
                    Text(q.title.uppercase(), style = raj(15.sp, tracking = 0.12f, color = p.text), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(q.artist.uppercase(), style = raj(12.sp, tracking = 0.1f, color = p.secondary), maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun SmallToggle(label: String, action: String?, onClick: (String) -> Unit) {
    val p = AtmosTheme.p
    Box(
        Modifier.size(44.dp).then(if (action != null) Modifier.clickable { onClick(action) } else Modifier),
        contentAlignment = Alignment.Center
    ) { Text(label, style = mono(11.sp, 0.06f, if (action != null) p.primary else p.muted.copy(alpha = 0.4f))) }
}

/** Barra arrastrable → transportControls.seekTo. */
@Composable
private fun SeekBar(pos: Long, duration: Long, modifier: Modifier) {
    val p = AtmosTheme.p
    var drag by remember { mutableStateOf<Float?>(null) }
    val frac = drag ?: if (duration > 0) pos.toFloat() / duration else 0f
    Box(
        modifier
            .fillMaxWidth()
            .height(22.dp)
            .pointerInput(duration) {
                detectTapGestures { o -> if (duration > 0) MediaHub.seekTo((o.x / size.width * duration).toLong()) }
            }
            .pointerInput(duration) {
                detectHorizontalDragGestures(
                    onDragEnd = { drag?.let { if (duration > 0) MediaHub.seekTo((it * duration).toLong()) }; drag = null },
                    onDragCancel = { drag = null }
                ) { change, _ -> drag = (change.position.x / size.width).coerceIn(0f, 1f) }
            }
            .drawBehind {
                val y = size.height / 2
                val h = 3.dp.toPx()
                drawRect(p.text.copy(alpha = 0.14f), Offset(0f, y - h / 2), Size(size.width, h))
                drawRect(p.primary, Offset(0f, y - h / 2), Size(size.width * frac.coerceIn(0f, 1f), h))
                val x = size.width * frac.coerceIn(0f, 1f)
                drawRect(p.text, Offset(x - 2.dp.toPx(), y - 6.5f.dp.toPx()), Size(4.dp.toPx(), 13.dp.toPx()))
            }
    )
}

/**
 * Visualizador pseudoaleatorio (no analiza audio: evitaría el permiso RECORD_AUDIO y gastaría más).
 * Un único InfiniteTransition y una fase distinta por barra; en pausa se congela y no anima nada.
 */
@Composable
fun Visualizer(playing: Boolean, bars: Int, modifier: Modifier) {
    val p = AtmosTheme.p
    val phases = remember(bars) { FloatArray(bars) { (it * 0.618f * 7f) % 1f } }
    val speeds = remember(bars) { FloatArray(bars) { 1f + ((it * 37) % 5) * 0.35f } }
    val t: State<Float> = if (playing) {
        rememberInfiniteTransition(label = "viz").animateFloat(
            0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "t"
        )
    } else remember { mutableFloatStateOf(0.35f) }
    Box(
        modifier.drawBehind {
            val tt = t.value
            val gap = 2.dp.toPx()
            val w = (size.width - gap * (bars - 1)) / bars
            for (i in 0 until bars) {
                val v = abs(sin(2 * PI * (tt * speeds[i] + phases[i]))).toFloat()
                val h = size.height * (0.12f + 0.88f * v * if (playing) 1f else 0.4f)
                drawRect(
                    if (i % 5 == 0) p.secondary else p.primary,
                    Offset(i * (w + gap), size.height - h), Size(w, h)
                )
            }
        }
    )
}
