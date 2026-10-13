package com.atmos.launcher.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.atmos.launcher.data.apps.AppEntry
import com.atmos.launcher.ui.LocalContainer
import com.atmos.launcher.ui.theme.AtmosTheme
import com.atmos.launcher.ui.theme.raj

/**
 * Icono real de la app dentro de un marco CutShape(tl=8) con borde primario al 55 %.
 * El bitmap se genera fuera del hilo principal y se cachea; mientras tanto se ve el rayado.
 */
@Composable
fun AppIcon(app: AppEntry, tinted: Boolean, size: Dp = 40.dp) {
    val c = LocalContainer.current
    val p = AtmosTheme.p
    val px = with(LocalDensity.current) { (size - 6.dp).roundToPx() }
    val version by c.apps.version.collectAsStateWithLifecycle()
    val tint = p.primary
    val bmp by produceState(c.icons.peek(c.icons.cacheKey(app.key, tint, tinted, px, version)), app.key, tint, tinted, px, version) {
        if (value == null) value = c.icons.load(app.key, tint, tinted, px, version)
    }
    IconFrame(size) {
        bmp?.let { Image(it, contentDescription = app.label, modifier = Modifier.fillMaxSize()) }
            ?: Text(app.label.take(1).uppercase(), style = raj(20.sp, color = p.primary), modifier = Modifier.align(Alignment.Center))
    }
}

@Composable
fun IconFrame(size: Dp, content: @Composable BoxScope.() -> Unit) {
    val p = AtmosTheme.p
    val shape = CutShape(tl = 8.dp)
    Box(
        Modifier
            .size(size)
            .clip(shape)
            .border(1.dp, p.primary.copy(alpha = 0.55f), shape)
            .drawBehind { hatch(p.primary.copy(alpha = 0.10f), 6.dp.toPx(), 2.dp.toPx()) }
            .padding(3.dp)
    ) { content() }
}

/** Avatar con iniciales o foto. */
@Composable
fun Avatar(initials: String, photo: ImageBitmap?, size: Dp = 46.dp, filled: Boolean = false) {
    val p = AtmosTheme.p
    val shape = CutShape(tl = 9.dp)
    Box(
        Modifier
            .size(size)
            .clip(shape)
            .background(if (filled) p.primary else p.panel)
            .border(1.dp, p.primary.copy(alpha = 0.4f), shape),
        contentAlignment = Alignment.Center
    ) {
        if (photo != null) Image(photo, null, Modifier.fillMaxSize())
        else Text(initials, style = com.atmos.launcher.ui.theme.mono(15.sp, 0.04f, if (filled) p.onPrimary else p.primary))
    }
}

/** Menú contextual con estilo HUD (pulsación larga). */
@Composable
fun HudMenu(title: String, options: List<Pair<String, () -> Unit>>, onDismiss: () -> Unit) {
    val p = AtmosTheme.p
    Dialog(onDismissRequest = onDismiss) {
        HudPanel("◢", title.uppercase(), trailing = "MENÚ") {
            options.forEach { (label, action) ->
                Pressable(Modifier.fillMaxWidth().height(48.dp), onClick = { onDismiss(); action() }) { pressed ->
                    Text(
                        label, style = raj(16.sp, tracking = 0.18f, color = if (pressed) p.onPrimary else p.text),
                        modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 10.dp)
                    )
                }
            }
        }
    }
}

fun initials(name: String): String {
    val parts = name.trim().split(' ', '-', '_').filter { it.isNotEmpty() }
    return when {
        parts.isEmpty() -> "?"
        parts.size == 1 -> parts[0].take(2).uppercase()
        else -> (parts[0].take(1) + parts[1].take(1)).uppercase()
    }
}

@Composable
fun BackBar(tag: String, tagColor: androidx.compose.ui.graphics.Color, text: String, onBack: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    val p = AtmosTheme.p
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text("‹", style = raj(22.sp, color = p.primary), modifier = Modifier.clickable(onClick = onBack).padding(end = 10.dp, top = 4.dp, bottom = 4.dp))
        Box(Modifier.background(tagColor).padding(horizontal = 7.dp, vertical = 1.dp)) {
            Text(tag, style = com.atmos.launcher.ui.theme.mono(11.sp, 0.16f, p.onPrimary))
        }
        Spacer(Modifier.width(10.dp))
        Text(text, style = com.atmos.launcher.ui.theme.mono(11.sp, 0.16f, p.muted), maxLines = 1, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}
