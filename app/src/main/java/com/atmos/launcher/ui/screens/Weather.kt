package com.atmos.launcher.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.atmos.launcher.data.weather.SharedWeather
import com.atmos.launcher.ui.LocalContainer
import com.atmos.launcher.ui.components.*
import com.atmos.launcher.ui.theme.AtmosTheme
import com.atmos.launcher.ui.theme.chroma
import com.atmos.launcher.ui.theme.mono
import com.atmos.launcher.ui.theme.raj
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun WeatherScreen(nav: NavHostController) {
    val c = LocalContainer.current
    val p = AtmosTheme.p
    val st by c.weather.state.collectAsStateWithLifecycle()
    val w = st.data
    if (w == null) {
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            BackBar("ATMOS", p.secondary, "SIN DATOS", onBack = { nav.popBackStack() })
            Spacer(Modifier.height(20.dp))
            CyberButton("ABRIR ATMOS ↗", Modifier.fillMaxWidth(), filled = true) { c.weather.openAtmos() }
        }
        return
    }
    val open = { c.weather.openAtmos(); Unit }

    Column(
        Modifier.fillMaxSize().glitchEnter().verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 18.dp)
    ) {
        BackBar("ATMOS", p.secondary, if (w.gps) "GPS · FIJADO" else "UBICACIÓN MANUAL", onBack = { nav.popBackStack() }) {
            val t = remember(w.fetchedAt) { if (w.fetchedAt > 0) SimpleDateFormat("HH:mm", Locale("es")).format(Date(w.fetchedAt)) else "--:--" }
            Text("SYNC $t", style = mono(11.sp, 0.12f, p.muted))
        }
        Column(Modifier.clickable(onClick = open)) {
            Text(w.city.uppercase(), style = raj(56.sp, tracking = 0.02f, color = p.primary).copy(shadow = chroma()), maxLines = 1, modifier = Modifier.padding(top = 12.dp))
            if (w.region.isNotEmpty()) Text(w.region.uppercase(), style = raj(13.sp, FontWeight.SemiBold, 0.22f, p.secondary))
        }
        Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(if (w.alerts.isEmpty()) p.tertiary else p.warn))
            Spacer(Modifier.width(10.dp))
            Text(
                if (w.alerts.isEmpty()) "SISTEMA NOMINAL // SIN AVISOS ACTIVOS" else "AVISO // " + w.alerts.first(),
                style = mono(11.sp, 0.14f, if (w.alerts.isEmpty()) p.muted else p.warn), maxLines = 1
            )
        }

        Spacer(Modifier.height(14.dp))
        HudPanel("01", "AHORA", trailing = if (w.isDay) "DÍA" else "NOCHE", onClick = open) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                ScanlineText("${w.temp}", raj(104.sp, tracking = 0f), p.text)
                Text("°C", style = raj(22.sp, tracking = 0f, color = p.primary), modifier = Modifier.align(Alignment.Top).padding(top = 18.dp))
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    TagChip(w.code, p.tertiary)
                    Text(w.label, style = raj(20.sp, tracking = 0.2f, color = p.text), modifier = Modifier.padding(top = 6.dp), maxLines = 1)
                    Row {
                        Text("▲${w.hi}°", style = raj(16.sp, FontWeight.SemiBold, 0f, p.secondary))
                        Spacer(Modifier.width(8.dp))
                        Text("▼${w.lo}°", style = raj(16.sp, FontWeight.SemiBold, 0f, p.tertiary))
                    }
                }
            }
            val stats = listOf(
                "SENSACIÓN" to "${w.feels}°", "HUMEDAD" to "${w.humidity}%", "PRESIÓN" to "${w.pressure} hPa",
                "ÍNDICE UV" to "${w.uv} · ${w.uvLabel}", "VISIBILIDAD" to "${w.visKm} km", "P. ROCÍO" to "${w.dew}°"
            )
            Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                stats.chunked(3).forEachIndexed { r, row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEachIndexed { i, (k, v) ->
                            val accent = if ((r + i) % 2 == 0) p.primary else p.secondary
                            Column(
                                Modifier.weight(1f).background(p.panel)
                                    .drawBehind { drawRect(accent, Offset.Zero, Size(2.dp.toPx(), size.height)) }
                                    .padding(start = 9.dp, end = 6.dp, top = 7.dp, bottom = 7.dp)
                            ) {
                                Text(k, style = mono(10.sp, 0.12f, p.muted), maxLines = 1)
                                Text(v, style = raj(18.sp, tracking = 0f, color = p.text), maxLines = 1)
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionHeader("02", "PRÓXIMAS HORAS", accent = p.secondary)
        HoursChart(w, Modifier.fillMaxWidth().height(100.dp))
        val hours = w.hours.take(8)
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            hours.forEach { h ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${Math.round(h.t)}°", style = raj(14.sp, tracking = 0f, color = p.text))
                    Text(h.h, style = mono(9.sp, 0f, p.muted))
                }
            }
        }

        Spacer(Modifier.height(22.dp))
        SectionHeader("03", "${minOf(5, w.days.size)} DÍAS", accent = p.tertiary)
        val days = w.days.take(5)
        val gmin = days.minOfOrNull { it.lo } ?: 0
        val gmax = days.maxOfOrNull { it.hi } ?: 1
        val span = (gmax - gmin).coerceAtLeast(1).toFloat()
        days.forEach { d ->
            Row(
                Modifier.fillMaxWidth().height(44.dp).drawBehind {
                    drawLine(p.text.copy(alpha = 0.07f), Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
                },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(d.d, style = raj(15.sp, tracking = 0.18f, color = p.text), modifier = Modifier.width(52.dp))
                TagChip(d.c, p.tertiary, width = 48.dp)
                Box(
                    Modifier.weight(1f).padding(horizontal = 12.dp).height(3.dp).drawBehind {
                        drawRect(p.text.copy(alpha = 0.1f))
                        val x0 = (d.lo - gmin) / span * size.width
                        val x1 = (d.hi - gmin) / span * size.width
                        drawRect(p.primary, Offset(x0, 0f), Size((x1 - x0).coerceAtLeast(3.dp.toPx()), size.height))
                    }
                )
                Row(Modifier.width(66.dp), horizontalArrangement = Arrangement.End) {
                    Text("${d.lo}", style = raj(14.sp, FontWeight.SemiBold, 0f, p.tertiary))
                    Text(" / ${d.hi}", style = raj(14.sp, FontWeight.SemiBold, 0f, p.text))
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        CyberButton("ABRIR ATMOS COMPLETA ↗", Modifier.fillMaxWidth(), filled = true, onClick = open)
    }
}

/** Línea + área de las próximas 8 horas en un único nodo de dibujo cacheado. */
@Composable
private fun HoursChart(w: SharedWeather, modifier: Modifier) {
    val p = AtmosTheme.p
    val temps = remember(w) { w.hours.take(8).map { it.t } }
    Box(
        modifier.drawWithCache {
            if (temps.size < 2) return@drawWithCache onDrawBehind { }
            val mn = temps.min() - 0.5
            val mx = temps.max() + 0.5
            val step = size.width / temps.size
            val pts = temps.mapIndexed { i, t ->
                Offset(i * step + step / 2, (size.height * 0.9f - ((t - mn) / (mx - mn)) * size.height * 0.8f).toFloat())
            }
            val line = Path().apply { pts.forEachIndexed { i, o -> if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) } }
            val area = Path().apply { addPath(line); lineTo(pts.last().x, size.height); lineTo(pts.first().x, size.height); close() }
            val fill = Brush.verticalGradient(listOf(p.primary.copy(alpha = 0.2f), Color.Transparent))
            val stroke = Stroke(2.5.dp.toPx())
            onDrawBehind {
                drawPath(area, fill)
                drawPath(line, p.primary, style = stroke)
            }
        }
    )
}
