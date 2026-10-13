package com.atmos.launcher.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavHostController
import com.atmos.launcher.data.apps.AppEntry
import com.atmos.launcher.data.media.MediaHub
import com.atmos.launcher.data.media.MediaState
import com.atmos.launcher.data.notifications.NotificationHub
import com.atmos.launcher.data.settings.HomeVariant
import com.atmos.launcher.data.settings.LauncherSettings
import com.atmos.launcher.data.weather.WeatherLink
import com.atmos.launcher.data.weather.WeatherState
import com.atmos.launcher.ui.LocalContainer
import com.atmos.launcher.ui.Routes
import com.atmos.launcher.ui.components.*
import com.atmos.launcher.ui.go
import com.atmos.launcher.ui.rememberClock
import com.atmos.launcher.ui.resolveFavorites
import com.atmos.launcher.ui.theme.AtmosTheme
import com.atmos.launcher.ui.theme.mono
import com.atmos.launcher.ui.theme.raj
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

private val MUSIC_PKGS = setOf("com.spotify.music", "com.google.android.apps.youtube.music", "deezer.android.app", "com.soundcloud.android", "com.apple.android.music")

@Composable
fun HomeScreen(settings: LauncherSettings, nav: NavHostController, homeTick: Int, onOpenShade: () -> Unit) {
    val c = LocalContainer.current
    val ctx = LocalContext.current
    val p = AtmosTheme.p
    val scope = rememberCoroutineScope()
    val apps by c.apps.apps.collectAsStateWithLifecycle()
    val weather by c.weather.state.collectAsStateWithLifecycle()
    val media by MediaHub.state.collectAsStateWithLifecycle()
    val badges by NotificationHub.badges.collectAsStateWithLifecycle()
    val linked by NotificationHub.connected.collectAsStateWithLifecycle()
    val clock by rememberClock()

    val favs = remember(apps, settings.favorites) { resolveFavorites(apps, settings.favorites) }
    var menuSlot by remember { mutableStateOf(-1) }

    fun saveFavs(list: List<AppEntry?>) { scope.launch { c.settings.setFavorites(list.map { it?.favKey ?: "" }) } }
    fun open(a: AppEntry): Unit = when {
        a.packageName in settings.messagingPackages && linked -> nav.go(Routes.MESSAGES)
        (a.packageName in MUSIC_PKGS || a.packageName == media?.pkg) && linked -> nav.go(Routes.MUSIC)
        else -> c.apps.launch(a)
    }

    // Deslizar hacia abajo estando arriba del todo → panel.
    val threshold = with(LocalDensity.current) { 72.dp.toPx() }
    val pull = remember {
        object : NestedScrollConnection {
            var acc = 0f
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 0f) {
                    acc += available.y
                    if (acc > threshold) { acc = 0f; onOpenShade() }
                } else if (available.y < 0f) acc = 0f
                return Offset.Zero
            }
        }
    }

    val holo = rememberHologramBitmap(settings.hologramPath)
    val holoPages = if (holo != null) 1 else 0
    val appPages = remember(apps) { apps.chunked(12) }
    val homeIndex = holoPages
    val pager = rememberPagerState(initialPage = homeIndex) { holoPages + 1 + appPages.size }
    var appMenu by remember { mutableStateOf<AppEntry?>(null) }
    val glass = holo != null

    // Botón de inicio o activar/quitar holograma → volver a la página principal.
    LaunchedEffect(homeTick, homeIndex) { if (pager.currentPage != homeIndex) pager.scrollToPage(homeIndex) }

    Box(Modifier.fillMaxSize()) {
        if (holo != null) {
            val rest = hologramRestAlpha(settings.hologramLevel)
            HologramBackdrop(holo, alpha = {
                // Posición continua del pager: 0 = página holograma, 1 = inicio, >1 = módulos.
                val pos = pager.currentPage + pager.currentPageOffsetFraction
                if (pos <= 1f) 1f + (rest - 1f) * pos.coerceIn(0f, 1f)
                else rest + (rest * 0.45f - rest) * (pos - 1f).coerceIn(0f, 1f)
            }, modifier = Modifier.matchParentSize())
        }

        HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1, key = { it }) { page ->
            when {
                page < homeIndex -> HoloPage(clock.time, clock.date) { scope.launch { pager.animateScrollToPage(homeIndex) } }
                page == homeIndex -> Column(
                    Modifier
                        .fillMaxSize()
                        .glitchEnter()
                        .nestedScroll(pull)
                        .verticalScroll(rememberScrollState())
                        .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 40.dp)
                ) {
                    TopStrip(linked, weather, onTap = onOpenShade, onLong = { nav.go(Routes.SETTINGS) })

                    Row(Modifier.fillMaxWidth().padding(top = 12.dp).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1.15f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                            ScanlineText(clock.time, raj(70.sp, tracking = 0f), p.primary)
                            Text(clock.date, style = raj(13.sp, weight = androidx.compose.ui.text.font.FontWeight.SemiBold, tracking = 0.2f, color = p.secondary), maxLines = 1)
                        }
                        WeatherModule(weather, glass, Modifier.weight(1f).fillMaxHeight()) {
                            if (weather.data != null) nav.go(Routes.WEATHER) else c.weather.openAtmos()
                        }
                    }

                    media?.let { m ->
                        Spacer(Modifier.height(18.dp))
                        MiniPlayer(m, glass, onOpen = { nav.go(Routes.PLAYER) })
                    }

                    Spacer(Modifier.height(22.dp))
                    BlockLabel("02", if (settings.homeVariant == HomeVariant.MODULOS) "FAVORITOS" else "ACCESO RÁPIDO")
                    Spacer(Modifier.height(10.dp))

                    when (settings.homeVariant) {
                        HomeVariant.MODULOS -> SimpleGrid(3, 10.dp) {
                            favs.forEachIndexed { i, a ->
                                if (a == null) EmptyTile { nav.go(Routes.picker(i)) }
                                else AppTile(a, settings, media, badges[a.packageName] ?: 0, glass, onClick = { open(a) }, onLong = { menuSlot = i })
                            }
                        }
                        HomeVariant.LISTA -> Column {
                            favs.forEachIndexed { i, a ->
                                if (a == null) return@forEachIndexed
                                val (status, color) = tileStatus(a, settings, media, badges[a.packageName] ?: 0)
                                ActionRow(
                                    "%02d".format(i + 1), a.upper, a.category, status, color,
                                    onClick = { open(a) }, onLongClick = { menuSlot = i }
                                ) { AppIcon(a, settings.tintedIcons, 34.dp) }
                                Box(Modifier.fillMaxWidth().height(1.dp).background(p.text.copy(alpha = 0.07f)))
                            }
                        }
                    }

                    Spacer(Modifier.height(22.dp))
                    CyberButton("⌕ BUSCAR EN ${apps.size} APPS", Modifier.fillMaxWidth(), height = 48.dp) { nav.go(Routes.DRAWER) }
                }
                else -> {
                    val i = page - homeIndex - 1
                    AppsPage(appPages.getOrElse(i) { emptyList() }, i, appPages.size, settings, media, badges, glass,
                        onOpen = { open(it) }, onLong = { appMenu = it })
                }
            }
        }

        PagerDots(pager.currentPage, pager.pageCount, homeIndex, holoPages == 1, Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp))
    }

    appMenu?.let { a ->
        HudMenu(
            a.label,
            listOf(
                "AÑADIR A FAVORITOS" to {
                    val list = favs.toMutableList()
                    val slot = list.indexOfFirst { it == null }.takeIf { it >= 0 } ?: 8
                    list[slot] = a
                    saveFavs(list)
                },
                "INFO DE LA APP" to { c.apps.openInfo(a) },
                "DESINSTALAR" to { c.apps.uninstall(a) }
            ),
            onDismiss = { appMenu = null }
        )
    }

    if (menuSlot >= 0) {
        val slot = menuSlot
        val a = favs.getOrNull(slot)
        HudMenu(
            a?.label ?: "HUECO ${slot + 1}",
            buildList {
                add("CAMBIAR APP" to { nav.go(Routes.picker(slot)) })
                if (slot > 0) add("◀ MOVER" to { saveFavs(favs.toMutableList().apply { add(slot - 1, removeAt(slot)) }) })
                if (slot < 8) add("MOVER ▶" to { saveFavs(favs.toMutableList().apply { add(slot + 1, removeAt(slot)) }) })
                if (a != null) {
                    add("QUITAR" to { saveFavs(favs.toMutableList().apply { this[slot] = null }) })
                    add("INFO DE LA APP" to { c.apps.openInfo(a) })
                }
            },
            onDismiss = { menuSlot = -1 }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TopStrip(linked: Boolean, w: WeatherState, onTap: () -> Unit, onLong: () -> Unit) {
    val p = AtmosTheme.p
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onTap, onLongClick = onLong).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.background(p.primary).padding(horizontal = 7.dp, vertical = 1.dp)) {
            Text("ATMOS//OS", style = mono(11.sp, 0.16f, p.onPrimary), maxLines = 1)
        }
        Spacer(Modifier.width(10.dp))
        Box(Modifier.size(7.dp).background(if (linked) p.tertiary else p.warn))
        Spacer(Modifier.width(6.dp))
        Text(if (linked) "ENLACE OK" else "SIN ENLACE", style = mono(11.sp, 0.16f, if (linked) p.muted else p.warn), maxLines = 1)
        Spacer(Modifier.weight(1f))
        w.data?.let {
            Text(
                "%.2f%s %.2f%s".format(java.util.Locale.US, abs(it.lat), if (it.lat >= 0) "N" else "S", abs(it.lon), if (it.lon >= 0) "E" else "O"),
                style = mono(11.sp, 0.12f, p.muted), maxLines = 1
            )
        }
    }
}

@Composable
private fun WeatherModule(w: WeatherState, glass: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val p = AtmosTheme.p
    val shape = CutShape(12.dp, 12.dp)
    Column(
        modifier
            .clip(shape)
            .background(if (glass) p.bg.copy(alpha = 0.55f) else p.bg)
            .border(1.dp, p.secondary, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        val d = w.data
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.background(p.secondary).padding(horizontal = 5.dp)) { Text("ATMOS", style = mono(10.sp, 0.12f, p.onPrimary)) }
            Spacer(Modifier.width(6.dp))
            Text(d?.city?.uppercase() ?: "—", style = mono(10.sp, 0.12f, p.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (d == null) {
            Text(
                when (w.link) {
                    WeatherLink.SIN_ATMOS -> "INSTALA ATMOS PARA VER EL TIEMPO"
                    WeatherLink.SIN_PERMISO -> "ATMOS FIRMADA CON OTRA CLAVE"
                    else -> "ABRE ATMOS PARA SINCRONIZAR"
                },
                style = raj(12.sp, tracking = 0.12f, color = p.muted)
            )
        } else {
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
                ScanlineText("${d.temp}", raj(46.sp, tracking = 0f), p.text)
                Text("°", style = raj(26.sp, tracking = 0f, color = p.primary), modifier = Modifier.align(Alignment.Top).padding(top = 2.dp))
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    TagChip(d.code, p.tertiary)
                    Row(Modifier.padding(top = 3.dp)) {
                        Text("▲${d.hi}", style = raj(13.sp, tracking = 0f, color = p.secondary))
                        Spacer(Modifier.width(4.dp))
                        Text("▼${d.lo}", style = raj(13.sp, tracking = 0f, color = p.tertiary))
                    }
                }
            }
            Text(d.label, style = raj(11.sp, tracking = 0.18f, color = p.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Posición de la pista: se recalcula 1 vez/s solo si suena y la pantalla está visible. */
@Composable
fun rememberPosition(m: MediaState): State<Long> {
    val owner = LocalLifecycleOwner.current
    return produceState(m.positionAt(), m) {
        value = m.positionAt()
        if (!m.isPlaying) return@produceState
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                value = m.positionAt()
                delay(1000)
            }
        }
    }
}

@Composable
private fun MiniPlayer(m: MediaState, glass: Boolean, onOpen: () -> Unit) {
    val p = AtmosTheme.p
    val pos by rememberPosition(m)
    val shape = CutShape(br = 12.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (glass) p.panel.copy(alpha = 0.6f) else p.panel)
            .border(1.dp, p.secondary.copy(alpha = 0.7f), shape)
            .clickable(onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).background(p.secondary))
            Spacer(Modifier.width(8.dp))
            Text("AUDIO // ${m.app}", style = mono(10.sp, 0.18f, p.muted), maxLines = 1, modifier = Modifier.weight(1f))
            Text(if (m.isPlaying) "▶ EN CURSO" else "❚❚ PAUSA", style = mono(10.sp, 0.18f, p.secondary))
        }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(m, 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(m.title.uppercase(), style = raj(18.sp, tracking = 0.1f, color = p.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(m.artist.uppercase(), style = raj(13.sp, tracking = 0.12f, color = p.secondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(6.dp))
            SquareBtn(if (m.isPlaying) "❚❚" else "▶", filled = true) { MediaHub.playPause() }
            Spacer(Modifier.width(6.dp))
            SquareBtn("▶▶", filled = false) { MediaHub.next() }
        }
        ProgressLine(if (m.durationMs > 0) pos.toFloat() / m.durationMs else 0f, p.secondary, Modifier.padding(top = 10.dp))
    }
}

@Composable
fun SquareBtn(text: String, filled: Boolean, size: androidx.compose.ui.unit.Dp = 44.dp, onClick: () -> Unit) {
    val p = AtmosTheme.p
    val shape = if (filled) CutShape(br = 9.dp) else androidx.compose.ui.graphics.RectangleShape
    Pressable(
        Modifier.size(size).border(1.dp, p.primary.copy(alpha = if (filled) 1f else 0.5f), shape),
        shape = shape, idle = if (filled) p.primary else Color.Transparent, onClick = onClick
    ) { pressed ->
        Text(text, style = raj(15.sp, tracking = 0f, color = if (filled || pressed) p.onPrimary else p.primary), modifier = Modifier.align(Alignment.Center))
    }
}

@Composable
fun Cover(m: MediaState?, size: androidx.compose.ui.unit.Dp, label: Boolean = false, fill: Boolean = false) {
    val p = AtmosTheme.p
    Box(
        Modifier
            .then(if (fill) Modifier.fillMaxSize() else Modifier.size(size))
            .clipToBounds()
            .border(1.dp, p.secondary.copy(alpha = 0.6f))
            .drawBehind { hatch(p.secondary.copy(alpha = 0.3f), 7.dp.toPx(), 3.dp.toPx()) },
        contentAlignment = Alignment.Center
    ) {
        val art = m?.art
        if (art != null) Image(art, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else if (label) Text("CARÁTULA", style = mono(9.sp, 0.1f, p.muted))
    }
}

private fun tileStatusRaw(a: AppEntry, s: LauncherSettings, m: MediaState?, badge: Int): Pair<String, Int> = when {
    a.packageName in s.messagingPackages && badge > 0 -> "$badge SIN LEER" to 1
    m != null && m.pkg == a.packageName -> (if (m.isPlaying) "▶ " else "❚❚ ") + m.title.uppercase() to 0
    else -> a.category to 2
}

@Composable
private fun tileStatus(a: AppEntry, s: LauncherSettings, m: MediaState?, badge: Int): Pair<String, Color> {
    val p = AtmosTheme.p
    val (t, k) = tileStatusRaw(a, s, m, badge)
    return t to when (k) { 0 -> p.primary; 1 -> p.secondary; else -> p.tertiary }
}

@Composable
private fun AppTile(a: AppEntry, s: LauncherSettings, m: MediaState?, badge: Int, glass: Boolean, onClick: () -> Unit, onLong: () -> Unit) {
    val p = AtmosTheme.p
    val shape = CutShape(br = 12.dp)
    val (status, statusColor) = tileStatus(a, s, m, badge)
    Pressable(
        Modifier.height(104.dp).border(1.dp, p.primary.copy(alpha = if (glass) 0.35f else 0.22f), shape),
        shape = shape, idle = if (glass) p.panel.copy(alpha = 0.55f) else p.panel, onClick = onClick, onLongClick = onLong
    ) { pressed ->
        val fg = if (pressed) p.onPrimary else p.text
        Column(Modifier.fillMaxSize().padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 9.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.Top) {
                AppIcon(a, s.tintedIcons)
                Spacer(Modifier.weight(1f))
                Text(a.code, style = mono(9.sp, 0.08f, if (pressed) fg else p.muted))
            }
            Column {
                Text(a.upper, style = raj(14.sp, tracking = 0.08f, color = fg), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(status, style = raj(10.sp, weight = androidx.compose.ui.text.font.FontWeight.SemiBold, tracking = 0.12f, color = if (pressed) fg else statusColor), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (badge > 0) {
            Box(
                Modifier.padding(start = 42.dp, top = 6.dp).defaultMinSize(16.dp, 16.dp).background(p.secondary).padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center
            ) { Text("$badge", style = mono(10.sp, 0f, Color(0xFF0A0A0A))) }
        }
    }
}

@Composable
private fun EmptyTile(onClick: () -> Unit) {
    val p = AtmosTheme.p
    val shape = CutShape(br = 12.dp)
    Pressable(Modifier.height(104.dp).border(1.dp, p.primary.copy(alpha = 0.12f), shape), shape = shape, onClick = onClick) { pressed ->
        Text("+ AÑADIR", style = mono(11.sp, 0.16f, if (pressed) p.onPrimary else p.muted), modifier = Modifier.align(Alignment.Center))
    }
}

/** Página del holograma: casi vacía a propósito, con un marco de visor para «enmarcar» la proyección. */
@Composable
private fun HoloPage(time: String, date: String, onBack: () -> Unit) {
    val p = AtmosTheme.p
    Box(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .drawBehind {
                val arm = 26.dp.toPx(); val sw = 2.dp.toPx(); val w = size.width; val h = size.height
                val c = p.primary
                fun r(x: Float, y: Float, rw: Float, rh: Float) = drawRect(c, Offset(x, y), androidx.compose.ui.geometry.Size(rw, rh))
                r(0f, 0f, arm, sw); r(0f, 0f, sw, arm)
                r(w - arm, 0f, arm, sw); r(w - sw, 0f, sw, arm)
                r(0f, h - sw, arm, sw); r(0f, h - arm, sw, arm)
                r(w - arm, h - sw, arm, sw); r(w - sw, h - arm, sw, arm)
            }
            .padding(14.dp)
    ) {
        Row(Modifier.align(Alignment.TopStart), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.background(p.secondary).padding(horizontal = 6.dp, vertical = 1.dp)) { Text("REC", style = mono(10.sp, 0.16f, p.onPrimary)) }
            Spacer(Modifier.width(8.dp))
            Text("00 // PROYECCIÓN HOLOGRÁFICA", style = mono(10.sp, 0.16f, p.muted))
        }
        Column(Modifier.align(Alignment.BottomStart)) {
            Text(time, style = raj(34.sp, tracking = 0f, color = p.primary))
            Text(date, style = mono(11.sp, 0.2f, p.secondary))
        }
        Text("INICIO ▶", style = mono(11.sp, 0.2f, p.primary), modifier = Modifier.align(Alignment.BottomEnd).clickable(onClick = onBack).padding(6.dp))
    }
}

/** Todas las apps como módulos, 12 por página (3×4), en orden alfabético. */
@Composable
private fun AppsPage(
    apps: List<AppEntry>, index: Int, total: Int, s: LauncherSettings, m: MediaState?, badges: Map<String, Int>, glass: Boolean,
    onOpen: (AppEntry) -> Unit, onLong: (AppEntry) -> Unit
) {
    Column(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 40.dp)) {
        val first = apps.firstOrNull()?.upper?.take(1) ?: ""
        val last = apps.lastOrNull()?.upper?.take(1) ?: ""
        BlockLabel("%02d".format(index + 3), "MÓDULOS  $first–$last") {
            Text("${index + 1}/$total", style = mono(11.sp, 0.16f, AtmosTheme.p.secondary))
        }
        Spacer(Modifier.height(12.dp))
        SimpleGrid(3, 10.dp) {
            apps.forEach { a -> AppTile(a, s, m, badges[a.packageName] ?: 0, glass, onClick = { onOpen(a) }, onLong = { onLong(a) }) }
        }
    }
}

/** Indicador de páginas: ◈ holograma, barra larga = inicio, barras cortas = módulos. */
@Composable
private fun PagerDots(current: Int, count: Int, home: Int, hasHolo: Boolean, modifier: Modifier) {
    val p = AtmosTheme.p
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until count) {
            val on = i == current
            val c = if (on) p.primary else p.primary.copy(alpha = 0.25f)
            when {
                hasHolo && i == 0 -> Text("◈", style = mono(11.sp, 0f, if (on) p.secondary else p.secondary.copy(alpha = 0.4f)))
                i == home -> Box(Modifier.size(22.dp, 4.dp).background(c))
                else -> Box(Modifier.size(10.dp, 4.dp).background(c))
            }
        }
    }
}
