package com.atmos.launcher.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.atmos.launcher.data.apps.AppEntry
import com.atmos.launcher.data.settings.LauncherSettings
import com.atmos.launcher.ui.LocalContainer
import com.atmos.launcher.ui.components.*
import com.atmos.launcher.ui.resolveFavorites
import com.atmos.launcher.ui.theme.AtmosTheme
import com.atmos.launcher.ui.theme.mono
import com.atmos.launcher.ui.theme.raj
import kotlinx.coroutines.launch
import java.text.Normalizer
import java.util.Locale

private sealed interface DrawerRow { val key: String }
private data class LetterRow(val letter: String) : DrawerRow { override val key = "h:$letter" }
private data class AppRow(val app: AppEntry) : DrawerRow { override val key = app.key }

private fun letterOf(label: String): String {
    val ch = Normalizer.normalize(label.take(1), Normalizer.Form.NFD).firstOrNull()?.uppercaseChar() ?: '#'
    return if (ch in 'A'..'Z') ch.toString() else "#"
}

/** Cajón de apps. Con [pickSlot] != null funciona como selector para un hueco de favoritos. */
@Composable
fun DrawerScreen(settings: LauncherSettings, nav: NavHostController, pickSlot: Int?) {
    val c = LocalContainer.current
    val p = AtmosTheme.p
    val scope = rememberCoroutineScope()
    val apps by c.apps.apps.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var menuFor by remember { mutableStateOf<AppEntry?>(null) }

    val rows = remember(apps, query) {
        val q = query.trim().lowercase(Locale("es"))
        val filtered = if (q.isEmpty()) apps else apps.filter {
            it.label.lowercase(Locale("es")).contains(q) || it.packageName.contains(q)
        }
        buildList<DrawerRow> {
            var last = ""
            for (a in filtered) {
                val l = letterOf(a.label)
                if (l != last) { add(LetterRow(l)); last = l }
                add(AppRow(a))
            }
        }
    }

    fun addFavorite(a: AppEntry, slot: Int?) {
        scope.launch {
            val favs = resolveFavorites(apps, settings.favorites).toMutableList()
            val target = slot ?: favs.indexOfFirst { it == null }.takeIf { it >= 0 } ?: 8
            favs[target] = a
            c.settings.setFavorites(favs.map { it?.favKey ?: "" })
        }
    }

    Column(Modifier.fillMaxSize().glitchEnter()) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 10.dp)) {
            if (pickSlot != null) {
                BackBar("HUECO ${pickSlot + 1}", p.secondary, "ELIGE UN MÓDULO", onBack = { nav.popBackStack() })
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.background(p.primary).padding(horizontal = 7.dp, vertical = 1.dp)) {
                        Text("ÍNDICE", style = mono(11.sp, 0.16f, p.onPrimary))
                    }
                    Spacer(Modifier.width(10.dp))
                    Text("${apps.size} MÓDULOS INSTALADOS", style = mono(11.sp, 0.16f, p.muted))
                }
            }
            val shape = CutShape(br = 10.dp)
            Row(
                Modifier
                    .padding(top = 12.dp)
                    .fillMaxWidth()
                    .height(46.dp)
                    .clip(shape)
                    .background(p.panel)
                    .border(1.dp, p.primary.copy(alpha = 0.4f), shape)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(">", style = mono(15.sp, 0f, p.primary))
                Spacer(Modifier.width(8.dp))
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = raj(16.sp, weight = androidx.compose.ui.text.font.FontWeight.SemiBold, tracking = 0.18f, color = p.text),
                    cursorBrush = SolidColor(p.primary),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        Box {
                            if (query.isEmpty()) Text("BUSCAR_", style = raj(16.sp, weight = androidx.compose.ui.text.font.FontWeight.SemiBold, tracking = 0.18f, color = p.muted))
                            inner()
                        }
                    }
                )
            }
        }

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 16.dp)) {
            items(rows, key = { it.key }, contentType = { if (it is LetterRow) 0 else 1 }) { row ->
                when (row) {
                    is LetterRow -> Row(Modifier.padding(top = 14.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(row.letter, style = mono(12.sp, 0f, p.secondary))
                        Box(Modifier.weight(1f).padding(start = 10.dp).height(1.dp).background(p.secondary.copy(alpha = 0.3f)))
                    }
                    is AppRow -> DrawerItem(
                        row.app, settings.tintedIcons,
                        onClick = {
                            if (pickSlot != null) { addFavorite(row.app, pickSlot); nav.popBackStack() }
                            else c.apps.launch(row.app)
                        },
                        onLong = { menuFor = row.app }
                    )
                }
            }
            if (rows.isEmpty()) item {
                Text(
                    "SIN COINCIDENCIAS // 0 MÓDULOS", style = mono(12.sp, 0.16f, p.muted),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp).wrapContentWidth()
                )
            }
        }
    }

    menuFor?.let { a ->
        HudMenu(
            a.label,
            listOf(
                "AÑADIR A FAVORITOS" to { addFavorite(a, null) },
                "INFO DE LA APP" to { c.apps.openInfo(a) },
                "DESINSTALAR" to { c.apps.uninstall(a) }
            ),
            onDismiss = { menuFor = null }
        )
    }
}

@Composable
private fun DrawerItem(a: AppEntry, tinted: Boolean, onClick: () -> Unit, onLong: () -> Unit) {
    val p = AtmosTheme.p
    Pressable(Modifier.fillMaxWidth().height(52.dp), onClick = onClick, onLongClick = onLong) { pressed ->
        Row(Modifier.fillMaxSize().padding(start = 4.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(a, tinted, 36.dp)
            Spacer(Modifier.width(12.dp))
            Text(
                a.upper, style = raj(17.sp, tracking = 0.14f, color = if (pressed) p.onPrimary else p.text),
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
            )
            Text(a.category, style = mono(10.sp, 0.1f, if (pressed) p.onPrimary else p.muted), maxLines = 1)
        }
    }
}
