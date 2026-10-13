package com.atmos.launcher.ui.neuro

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.atmos.launcher.data.media.MediaHub
import com.atmos.launcher.data.spotify.*
import com.atmos.launcher.ui.LocalContainer
import com.atmos.launcher.ui.components.*
import com.atmos.launcher.ui.screens.fmtMs
import com.atmos.launcher.ui.theme.AtmosTheme
import com.atmos.launcher.ui.theme.anton
import com.atmos.launcher.ui.theme.mono
import com.atmos.launcher.ui.theme.raj
import kotlinx.coroutines.delay
import java.util.Locale

enum class NTab(val label: String, val glyph: String) {
    NOW("EN CURSO", "▶"), LIB("BIBLIOTECA", "▦"), SEARCH("BUSCAR", ">_"), KILL("KILL COUNT", "☠")
}

/** Sub-páginas apiladas encima de las pestañas. */
sealed interface NPage {
    data class Artist(val id: String) : NPage
    data class Collection(val id: String, val uri: String, val name: String, val image: String?, val isAlbum: Boolean) : NPage
    data object Queue : NPage
}

@Composable
fun NeuroAudioScreen(nav: NavHostController, start: NTab = NTab.NOW) {
    val c = LocalContainer.current
    val ctx = LocalContext.current
    val p = AtmosTheme.p
    var tab by rememberSaveable { mutableStateOf(start) }
    val stack = remember { mutableStateListOf<NPage>() }
    val token by c.spotifyAuth.token.collectAsStateWithLifecycle()
    var loginError by remember { mutableStateOf<String?>(null) }

    val login = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        loginError = c.spotifyAuth.handleResult(r.resultCode, r.data)
        if (loginError == null) SpotifyRemote.connect(ctx)
    }
    val requestLogin: () -> Unit = { (ctx as? Activity)?.let { login.launch(c.spotifyAuth.loginIntent(it)) } }

    // App Remote: conectado solo mientras esta pantalla está en primer plano.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> SpotifyRemote.connect(ctx)
                Lifecycle.Event.ON_STOP -> SpotifyRemote.disconnect()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs); SpotifyRemote.disconnect() }
    }

    BackHandler(enabled = stack.isNotEmpty()) { stack.removeAt(stack.lastIndex) }
    val open: (NPage) -> Unit = { stack.add(it) }

    Column(Modifier.fillMaxSize().glitchEnter()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val page = stack.lastOrNull()
            val needsApi = page !is NPage.Queue && (page != null || tab != NTab.NOW)
            when {
                needsApi && token == null -> ConnectPanel(loginError, requestLogin)
                page is NPage.Artist -> ArtistPage(page.id, open) { stack.removeAt(stack.lastIndex) }
                page is NPage.Collection -> CollectionPage(page, open) { stack.removeAt(stack.lastIndex) }
                page is NPage.Queue -> QueuePage(token != null) { stack.removeAt(stack.lastIndex) }
                tab == NTab.NOW -> NowPage(open, onLogin = requestLogin, onBack = { nav.popBackStack() })
                tab == NTab.LIB -> LibraryPage(open)
                tab == NTab.SEARCH -> SearchPage(open)
                else -> KillCountPage(open)
            }
        }
        NeuroNav(tab) { t -> stack.clear(); tab = t }
    }
}

@Composable
private fun NeuroNav(tab: NTab, onPick: (NTab) -> Unit) {
    val p = AtmosTheme.p
    Row(
        Modifier.fillMaxWidth().background(p.bg).drawBehind {
            drawLine(p.primary.copy(alpha = 0.18f), Offset.Zero, Offset(size.width, 0f), 1.dp.toPx())
        }
    ) {
        NTab.entries.forEach { t ->
            val on = t == tab
            Pressable(
                Modifier.weight(1f).height(54.dp), shape = CutShape(br = 12.dp),
                idle = if (on) p.primary else Color.Transparent, onClick = { onPick(t) }
            ) { pressed ->
                val col = if (on || pressed) p.onPrimary else p.muted
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(t.glyph, style = raj(14.sp, tracking = 0.06f, color = col))
                    Text(t.label, style = mono(9.sp, 0.14f, col), maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun ConnectPanel(error: String?, onLogin: () -> Unit) {
    val p = AtmosTheme.p
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.Center) {
        NHead("SPOTIFY", "ENLACE REQUERIDO")
        BigTitle("Conecta tu Spotify", modifier = Modifier.padding(top = 10.dp))
        Text(
            "Biblioteca, búsqueda, artistas y KILL COUNT usan tu cuenta. El login lo hace la app oficial de Spotify; aquí no se guarda tu contraseña.",
            style = raj(15.sp, FontWeight.Medium, 0.02f, p.muted), modifier = Modifier.padding(top = 10.dp)
        )
        if (error != null) Text("// $error", style = mono(11.sp, 0.1f, p.warn), modifier = Modifier.padding(top = 10.dp))
        CyberButton("CONECTAR SPOTIFY", Modifier.fillMaxWidth().padding(top = 18.dp), filled = true, onClick = onLogin)
    }
}

/** Datos cargados de la Web API con estado de carga/error y reintento de login. */
@Composable
private fun <T> rememberApi(vararg keys: Any?, load: suspend SpotifyApi.() -> T): State<Result<T>?> {
    val c = LocalContainer.current
    return produceState<Result<T>?>(null, *keys) {
        value = runCatching { c.spotify.load() }
    }
}

@Composable
private fun Loading(text: String = "// CARGANDO…") {
    Text(text, style = mono(11.sp, 0.16f, AtmosTheme.p.muted), modifier = Modifier.padding(20.dp))
}

@Composable
private fun ErrorLine(e: Throwable) {
    val msg = if (e is SpotifyAuthExpired) "SESIÓN CADUCADA · VUELVE A ENTRAR" else "SIN RESPUESTA DE SPOTIFY"
    Text("// $msg", style = mono(11.sp, 0.14f, AtmosTheme.p.warn), modifier = Modifier.padding(20.dp))
}

// ───────────────────────────── EN CURSO ─────────────────────────────

@Immutable
private data class NowModel(
    val key: String, val title: String, val artist: String, val artistUri: String?,
    val durationMs: Long, val positionMs: Long, val updatedAt: Long, val playing: Boolean,
    val shuffle: Boolean, val repeat: Boolean, val liked: Boolean, val spotify: Boolean, val app: String
) {
    fun positionAt(now: Long = android.os.SystemClock.elapsedRealtime()) =
        (if (playing) positionMs + (now - updatedAt) else positionMs).coerceIn(0, durationMs.coerceAtLeast(1))
}

@Composable
private fun NowPage(open: (NPage) -> Unit, onLogin: () -> Unit, onBack: () -> Unit) {
    val c = LocalContainer.current
    val ctx = LocalContext.current
    val p = AtmosTheme.p
    val sp by SpotifyRemote.player.collectAsStateWithLifecycle()
    val spArt by SpotifyRemote.art.collectAsStateWithLifecycle()
    val liked by SpotifyRemote.liked.collectAsStateWithLifecycle()
    val link by SpotifyRemote.link.collectAsStateWithLifecycle()
    val media by MediaHub.state.collectAsStateWithLifecycle()
    val token by c.spotifyAuth.token.collectAsStateWithLifecycle()

    // Spotify (App Remote) manda; si no hay, cualquier otra app que esté sonando (MediaSession).
    val s = sp
    val m = media
    val model: NowModel? = when {
        s != null && link == RemoteLink.CONECTADO -> NowModel(s.uri, s.title, s.artist, s.artistUri, s.durationMs, s.positionMs, s.updatedAt,
            !s.paused, s.shuffle, s.repeat != 0, liked, true, "SPOTIFY")
        m != null -> NowModel(m.title + m.artist, m.title, m.artist, null, m.durationMs, m.positionMs, m.updatedAt, m.isPlaying,
            false, false, false, false, m.app)
        else -> null
    }
    val art: Any? = if (model?.spotify == true) spArt else m?.art

    if (model == null) {
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            NHead("AUDIO", "SIN SEÑAL", onBack = onBack)
            BigTitle("Nada sonando", modifier = Modifier.padding(top = 16.dp))
            Text(
                when (link) {
                    RemoteLink.SIN_SPOTIFY -> "Instala la app oficial de Spotify: NEURO//AUDIO la usa como motor."
                    RemoteLink.ERROR -> "No se pudo enlazar con Spotify (${SpotifyRemote.error.value ?: "?"}). Ábrelo una vez y vuelve."
                    RemoteLink.CONECTANDO -> "Enlazando con Spotify…"
                    else -> "Pon algo desde BIBLIOTECA o BUSCAR, o desde la app de Spotify."
                },
                style = raj(15.sp, FontWeight.Medium, 0.02f, p.muted), modifier = Modifier.padding(top = 10.dp)
            )
            if (token == null) CyberButton("CONECTAR SPOTIFY", Modifier.fillMaxWidth().padding(top = 18.dp), filled = true, onClick = onLogin)
            CyberButton("ABRIR SPOTIFY ↗", Modifier.fillMaxWidth().padding(top = 10.dp)) { c.apps.launchPackage(SpotifyConfig.PACKAGE) }
        }
        return
    }

    val pos by produceState(model.positionAt(), model) {
        while (model.playing) { value = model.positionAt(); delay(1000) }
        value = model.positionAt()
    }
    val genre by produceState<String?>(null, model.artistUri, token) {
        value = spotifyId(model.artistUri)?.let { id -> runCatching { c.spotify.artist(id).genres.firstOrNull() }.getOrNull() }
    }

    fun next() = if (model.spotify) SpotifyRemote.next() else MediaHub.next()
    fun prev() = if (model.spotify) SpotifyRemote.previous() else MediaHub.previous()

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            val threshold = with(LocalDensity.current) { 90.dp.toPx() }
            Box(
                Modifier.fillMaxWidth().height(330.dp)
                    .clip(CutShape(br = 26.dp))
                    .pointerInput(model.key) {
                        var dx = 0f
                        detectHorizontalDragGestures(onDragEnd = {
                            if (dx < -threshold) next() else if (dx > threshold) prev()
                            dx = 0f
                        }) { _, d -> dx += d }
                    }
            ) {
                ThresholdCover(art, Modifier.matchParentSize(), glitchKey = model.key)
                Text(
                    model.artist.uppercase(), maxLines = 1,
                    style = anton(15.sp, 0.3f, p.text),
                    modifier = Modifier.align(Alignment.TopStart).padding(start = 4.dp, top = 120.dp)
                        .rotate(-90f)
                )
                Text(
                    if (model.playing) "▶ EN CURSO" else "❚❚ PAUSA", style = mono(10.sp, 0.16f, p.primary),
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).background(p.bg).padding(horizontal = 7.dp, vertical = 3.dp)
                )
                Text(
                    "◀ DESLIZA ▶", style = mono(10.sp, 0.14f, p.muted),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 34.dp).background(p.bg.copy(alpha = 0.8f)).padding(horizontal = 7.dp, vertical = 3.dp)
                )
                Text(
                    "‹", style = raj(22.sp, color = p.primary),
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp).background(p.bg.copy(alpha = 0.7f)).clickable(onClick = onBack).padding(horizontal = 10.dp)
                )
            }
            Tape()
            val shift = with(LocalDensity.current) { 3.dp.toPx() }
            Text(
                model.title.uppercase(), maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
                style = anton(58.sp, color = p.text).copy(shadow = Shadow(p.secondary, Offset(shift, 0f), 0f)),
                modifier = Modifier.padding(start = 18.dp, top = 12.dp)
            )
            Row(Modifier.padding(start = 18.dp, end = 18.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    model.artist.uppercase(), style = raj(17.sp, tracking = 0.16f, color = p.secondary), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).clickable(enabled = model.artistUri != null) {
                        spotifyId(model.artistUri)?.let { open(NPage.Artist(it)) }
                    }
                )
                genre?.let { NTag(it) }
                if (!model.spotify) NTag(model.app, p.tertiary)
            }
            JaggedWave(model.playing, model.key.hashCode() % 97, Modifier.padding(horizontal = 18.dp).padding(top = 12.dp).fillMaxWidth().height(52.dp))
            Seek(pos, model.durationMs) { if (model.spotify) SpotifyRemote.seekTo(it) else MediaHub.seekTo(it) }
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
                Text(fmtMs(pos), style = mono(11.sp, 0f, p.muted))
                Spacer(Modifier.weight(1f))
                Text("-" + fmtMs(model.durationMs - pos), style = mono(11.sp, 0f, p.muted))
            }
            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                SmallText("ALT", model.shuffle, enabled = model.spotify) { SpotifyRemote.toggleShuffle() }
                OutlineBtn("◀◀") { prev() }
                Pressable(Modifier.size(84.dp, 66.dp), shape = CutShape(br = 14.dp), idle = p.primary,
                    onClick = { if (model.spotify) SpotifyRemote.playPause() else MediaHub.playPause() }) {
                    Text(if (model.playing) "❚❚" else "▶", style = raj(26.sp, tracking = 0f, color = p.onPrimary), modifier = Modifier.align(Alignment.Center))
                }
                OutlineBtn("▶▶") { next() }
                SmallText("RPT", model.repeat, enabled = model.spotify) { SpotifyRemote.toggleRepeat() }
            }
            Row(Modifier.padding(start = 18.dp, end = 18.dp, top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (model.spotify) {
                    CyberButton(if (model.liked) "☠ GUARDADA" else "♥ GUARDAR", Modifier.weight(1f),
                        color = if (model.liked) p.secondary else p.text) { SpotifyRemote.toggleLike() }
                    CyberButton("COLA ▤", Modifier.weight(1f), color = p.text) { open(NPage.Queue) }
                }
            }
            CyberButton("ABRIR EN ${model.app} ↗", Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 20.dp), color = p.text) {
                if (model.spotify) c.apps.launchPackage(SpotifyConfig.PACKAGE) else media?.pkg?.let { c.apps.launchPackage(it) }
            }
        }
    }
}

@Composable
private fun OutlineBtn(text: String, onClick: () -> Unit) {
    val p = AtmosTheme.p
    Pressable(Modifier.size(52.dp).border(1.dp, p.primary.copy(alpha = 0.45f)), shape = androidx.compose.ui.graphics.RectangleShape, onClick = onClick) { pressed ->
        Text(text, style = raj(15.sp, tracking = 0f, color = if (pressed) p.onPrimary else p.primary), modifier = Modifier.align(Alignment.Center))
    }
}

@Composable
private fun SmallText(text: String, on: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val p = AtmosTheme.p
    Box(Modifier.size(44.dp).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text, style = mono(11.sp, 0.06f, when { !enabled -> p.muted.copy(alpha = 0.4f); on -> p.primary; else -> p.muted }))
    }
}

@Composable
private fun Seek(pos: Long, duration: Long, onSeek: (Long) -> Unit) {
    val p = AtmosTheme.p
    var drag by remember { mutableStateOf<Float?>(null) }
    val frac = (drag ?: if (duration > 0) pos.toFloat() / duration else 0f).coerceIn(0f, 1f)
    Box(
        Modifier.padding(horizontal = 18.dp).padding(top = 8.dp).fillMaxWidth().height(22.dp)
            .pointerInput(duration) { detectTapGestures { o -> if (duration > 0) onSeek((o.x / size.width * duration).toLong()) } }
            .pointerInput(duration) {
                detectHorizontalDragGestures(
                    onDragEnd = { drag?.let { if (duration > 0) onSeek((it * duration).toLong()) }; drag = null },
                    onDragCancel = { drag = null }
                ) { ch, _ -> drag = (ch.position.x / size.width).coerceIn(0f, 1f) }
            }
            .drawBehind {
                val y = size.height / 2; val h = 3.dp.toPx()
                drawRect(p.text.copy(alpha = 0.14f), Offset(0f, y - h / 2), Size(size.width, h))
                drawRect(p.primary, Offset(0f, y - h / 2), Size(size.width * frac, h))
                drawRect(p.text, Offset(size.width * frac - 2.dp.toPx(), y - 7.dp.toPx()), Size(4.dp.toPx(), 14.dp.toPx()))
            }
    )
}

// ───────────────────────────── COLA ─────────────────────────────

@Composable
private fun QueuePage(hasToken: Boolean, onBack: () -> Unit) {
    val q by rememberApi("queue") { queue() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp)) {
        item { NHead("COLA", "A CONTINUACIÓN", onBack = onBack); BigTitle("Lo siguiente", modifier = Modifier.padding(top = 10.dp, bottom = 8.dp)) }
        val r = q
        when {
            !hasToken -> item { Loading("// CONECTA SPOTIFY PARA VER LA COLA") }
            r == null -> item { Loading() }
            r.isFailure -> item { ErrorLine(r.exceptionOrNull()!!) }
            else -> itemsIndexed(r.getOrThrow().queue.take(30)) { i, t ->
                TrackRow(i + 1, t, showCover = true) { SpotifyRemote.play(t.uri) }
            }
        }
    }
}

// ───────────────────────────── BIBLIOTECA ─────────────────────────────

private enum class LibKind(val label: String) { PLAYLISTS("PLAYLISTS"), ALBUMS("ÁLBUMES"), ARTISTS("ARTISTAS") }

@Composable
private fun LibraryPage(open: (NPage) -> Unit) {
    var kind by rememberSaveable { mutableStateOf(LibKind.PLAYLISTS) }
    val data by rememberApi(kind) {
        when (kind) {
            LibKind.PLAYLISTS -> playlists().map { Cart(it.name, "TRK %03d // %s".format(it.tracks?.total ?: 0, (it.owner?.name ?: "").uppercase()), it.images.best(), NPage.Collection(it.id, it.uri, it.name, it.images.best(), false)) }
            LibKind.ALBUMS -> savedAlbums().map { Cart(it.name, "TRK %03d // %s".format(it.totalTracks, it.releaseDate.take(4)), it.images.best(), NPage.Collection(it.id, it.uri, it.name, it.images.best(), true)) }
            LibKind.ARTISTS -> followedArtists().map { Cart(it.name, "%s SEGUIDORES".format(compact(it.followers?.total ?: 0)), it.images.best(), NPage.Artist(it.id)) }
        }
    }
    LazyVerticalGrid(GridCells.Fixed(2), Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                NHead("BIBLIOTECA", "TU COLECCIÓN", trailing = data?.getOrNull()?.size?.let { "$it" })
                BigTitle("Biblioteca", modifier = Modifier.padding(top = 10.dp))
                Box(Modifier.padding(top = 12.dp)) { Segmented(LibKind.entries, kind, { it.label }) { kind = it } }
            }
        }
        val r = data
        when {
            r == null -> item(span = { GridItemSpan(maxLineSpan) }) { Loading() }
            r.isFailure -> item(span = { GridItemSpan(maxLineSpan) }) { ErrorLine(r.exceptionOrNull()!!) }
            else -> items(r.getOrThrow(), key = { it.title + it.target.hashCode() }) { cart ->
                val n = r.getOrThrow().indexOf(cart) + 1
                Cartridge(n, cart) { open(cart.target) }
            }
        }
    }
}

@Immutable
private data class Cart(val title: String, val stats: String, val image: String?, val target: NPage)

@Composable
private fun Cartridge(n: Int, cart: Cart, onClick: () -> Unit) {
    val p = AtmosTheme.p
    val shape = CutShape(br = 12.dp)
    Pressable(Modifier.fillMaxWidth().border(1.dp, p.primary.copy(alpha = 0.2f), shape), shape = shape, idle = p.panel, onClick = onClick) { pressed ->
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                ThresholdCover(cart.image, Modifier.matchParentSize())
                Text("%02d".format(n), style = mono(10.sp, 0f, p.primary), modifier = Modifier.padding(6.dp).background(p.bg).padding(horizontal = 5.dp, vertical = 1.dp))
            }
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(cart.title.uppercase(), style = raj(15.sp, tracking = 0.08f, color = if (pressed) p.onPrimary else p.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(cart.stats, style = mono(10.sp, 0.1f, if (pressed) p.onPrimary else p.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun CollectionPage(page: NPage.Collection, open: (NPage) -> Unit, onBack: () -> Unit) {
    val p = AtmosTheme.p
    val tracks by rememberApi(page.id) { if (page.isAlbum) albumTracks(page.id) else playlistTracks(page.id) }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Box(Modifier.fillMaxWidth().height(260.dp)) {
                ThresholdCover(page.image, Modifier.matchParentSize(), glitchKey = page.id)
                Text("‹", style = raj(22.sp, color = p.primary),
                    modifier = Modifier.padding(8.dp).background(p.bg.copy(alpha = 0.7f)).clickable(onClick = onBack).padding(horizontal = 10.dp))
            }
            Tape()
            Column(Modifier.padding(18.dp)) {
                NHead(if (page.isAlbum) "ÁLBUM" else "PLAYLIST", "${tracks?.getOrNull()?.size ?: "…"} PISTAS")
                BigTitle(page.name, modifier = Modifier.padding(top = 10.dp))
                CyberButton("▶ REPRODUCIR", Modifier.fillMaxWidth().padding(top = 12.dp), filled = true) { SpotifyRemote.play(page.uri) }
            }
        }
        val r = tracks
        when {
            r == null -> item { Loading() }
            r.isFailure -> item { ErrorLine(r.exceptionOrNull()!!) }
            else -> itemsIndexed(r.getOrThrow()) { i, t ->
                Box(Modifier.padding(horizontal = 18.dp)) { TrackRow(i + 1, t, showCover = !page.isAlbum) { SpotifyRemote.playAt(page.uri, i) } }
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

@Composable
private fun TrackRow(n: Int?, t: SpTrack, showCover: Boolean, onClick: () -> Unit) {
    val p = AtmosTheme.p
    Pressable(Modifier.fillMaxWidth().heightIn(min = 58.dp), shape = CutShape(br = 8.dp), onClick = onClick) { pressed ->
        val fg = if (pressed) p.onPrimary else p.text
        Row(
            Modifier.fillMaxWidth().drawBehind {
                drawLine(p.text.copy(alpha = 0.07f), Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
            }.padding(vertical = 6.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (n != null) Text("%02d".format(n), style = mono(11.sp, 0f, if (pressed) fg else p.muted), modifier = Modifier.width(26.dp))
            if (showCover) { ThresholdCover(t.album?.images.best(64), Modifier.size(44.dp), ghostShift = 2.dp); Spacer(Modifier.width(10.dp)) }
            Column(Modifier.weight(1f)) {
                Text(t.name.uppercase(), style = raj(16.sp, tracking = 0.1f, color = fg), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(t.artists.joinToString(", ") { it.name }.uppercase(), style = raj(12.sp, FontWeight.SemiBold, 0.12f, if (pressed) fg else p.secondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (t.explicit) { NTag("E", if (pressed) fg else p.muted); Spacer(Modifier.width(8.dp)) }
            Text(fmtMs(t.durationMs), style = mono(10.sp, 0f, if (pressed) fg else p.muted))
        }
    }
}

// ───────────────────────────── BUSCAR ─────────────────────────────

private val GENRES = listOf("METAL", "HARDCORE", "PUNK", "DEATHCORE", "DEATHGRIND", "DARKWAVE")

@Composable
private fun SearchPage(open: (NPage) -> Unit) {
    val c = LocalContainer.current
    val p = AtmosTheme.p
    var q by rememberSaveable { mutableStateOf("") }
    var genre by rememberSaveable { mutableStateOf<String?>(null) }
    var res by remember { mutableStateOf<Result<SearchResult>?>(null) }
    LaunchedEffect(q, genre) {
        val query = listOfNotNull(q.trim().takeIf { it.isNotEmpty() }, genre?.let { "genre:\"${it.lowercase()}\"" }).joinToString(" ")
        if (query.isEmpty()) { res = null; return@LaunchedEffect }
        delay(350)
        res = runCatching { c.spotify.search(query) }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp)) {
        item {
            NHead("BUSCAR", "CATÁLOGO COMPLETO DE SPOTIFY")
            BigTitle("Buscar", modifier = Modifier.padding(top = 10.dp))
            Row(
                Modifier.padding(top = 12.dp).fillMaxWidth().height(46.dp).clip(CutShape(br = 10.dp)).background(p.panel)
                    .border(1.dp, p.primary.copy(alpha = 0.4f), CutShape(br = 10.dp)).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(">", style = mono(15.sp, 0f, p.primary))
                Spacer(Modifier.width(8.dp))
                BasicTextField(
                    q, { q = it }, singleLine = true, modifier = Modifier.weight(1f),
                    textStyle = raj(16.sp, FontWeight.SemiBold, 0.18f, p.text), cursorBrush = SolidColor(p.primary),
                    decorationBox = { inner -> Box { if (q.isEmpty()) Text("BUSCAR_", style = raj(16.sp, FontWeight.SemiBold, 0.18f, p.muted)); inner() } }
                )
            }
            SimpleGrid(2, 8.dp, Modifier.padding(top = 12.dp)) {
                GENRES.forEach { g ->
                    val on = genre == g
                    val shape = CutShape(br = 12.dp)
                    Pressable(Modifier.height(58.dp).border(1.dp, if (on) p.secondary else p.primary.copy(alpha = 0.25f), shape),
                        shape = shape, idle = if (on) p.secondary else p.panel, onClick = { genre = if (on) null else g }) { pressed ->
                        Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.SpaceBetween) {
                            Text(g, style = anton(21.sp, 0.04f, if (pressed) p.onPrimary else p.text))
                            Text(if (on) "FILTRO ACTIVO" else "genre:\"${g.lowercase()}\"", style = mono(9.sp, 0.1f, if (pressed) p.onPrimary else p.muted))
                        }
                    }
                }
            }
        }
        val r = res
        when {
            r == null -> item { Text("// ESCRIBE O ELIGE UN GÉNERO", style = mono(11.sp, 0.16f, p.muted), modifier = Modifier.padding(vertical = 18.dp)) }
            r.isFailure -> item { ErrorLine(r.exceptionOrNull()!!) }
            else -> {
                val artists = r.getOrThrow().artists?.items.orEmpty()
                val tracks = r.getOrThrow().tracks?.items.orEmpty()
                if (artists.isNotEmpty()) item {
                    Box(Modifier.padding(top = 16.dp, bottom = 8.dp)) { NHead(null, "ARTISTAS", trailing = "${artists.size}") }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(artists, key = { it.id }) { a ->
                            Column(Modifier.width(92.dp).clickable { open(NPage.Artist(a.id)) }) {
                                ThresholdCover(a.images.best(160), Modifier.size(92.dp), ghostShift = 3.dp)
                                Text(a.name.uppercase(), style = raj(12.sp, tracking = 0.08f, color = p.text), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
                            }
                        }
                    }
                }
                item { Box(Modifier.padding(top = 16.dp)) { NHead(null, if (genre != null) "genre:\"${genre!!.lowercase()}\"" else "PISTAS", trailing = "${tracks.size}") } }
                items(tracks, key = { it.uri }) { t -> TrackRow(null, t, showCover = true) { SpotifyRemote.play(t.uri) } }
                if (tracks.isEmpty() && artists.isEmpty()) item { Text("SIN COINCIDENCIAS // 0 PISTAS", style = mono(11.sp, 0.16f, p.muted), modifier = Modifier.padding(vertical = 24.dp)) }
            }
        }
    }
}

// ───────────────────────────── ARTISTA (expediente) ─────────────────────────────

private fun compact(n: Int): String = when {
    n >= 1_000_000 -> "%.1fM".format(Locale.US, n / 1_000_000f)
    n >= 1_000 -> "%.1fK".format(Locale.US, n / 1_000f)
    else -> "$n"
}

@Composable
private fun ArtistPage(id: String, open: (NPage) -> Unit, onBack: () -> Unit) {
    val p = AtmosTheme.p
    val artist by rememberApi(id) { artist(id) }
    val top by rememberApi(id, "top") { artistTop(id) }
    val albums by rememberApi(id, "albums") { artistAlbums(id) }
    val mine by rememberApi("mine-top") { topArtists(TopRange.MEDIO) }
    val a = artist?.getOrNull()
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Box(Modifier.fillMaxWidth().height(300.dp)) {
                ThresholdCover(a?.images.best(600), Modifier.matchParentSize(), glitchKey = id)
                Text(
                    "SE BUSCA", style = anton(22.sp, 0.08f, p.secondary),
                    modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).rotate(-8f)
                        .border(3.dp, p.secondary).background(p.bg.copy(alpha = 0.6f)).padding(horizontal = 10.dp, vertical = 4.dp)
                )
                Text("‹", style = raj(22.sp, color = p.primary),
                    modifier = Modifier.padding(8.dp).background(p.bg.copy(alpha = 0.7f)).clickable(onClick = onBack).padding(horizontal = 10.dp))
                val shift = with(LocalDensity.current) { 3.dp.toPx() }
                Text(
                    (a?.name ?: "…").uppercase(), maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = anton(54.sp, color = p.text).copy(shadow = Shadow(p.secondary, Offset(shift, 0f), 0f)),
                    modifier = Modifier.align(Alignment.BottomStart).padding(16.dp)
                )
            }
            Tape()
            Column(Modifier.padding(18.dp)) {
                NHead("EXPEDIENTE", "ARTISTA")
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    a?.genres?.take(2)?.forEach { NTag(it) }
                    if (mine?.getOrNull()?.any { it.id == id } == true) NTag("EN TU TOP", p.secondary)
                }
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Fact("SEGUIDORES", compact(a?.followers?.total ?: 0), Modifier.weight(1f))
                    Fact("POPULARIDAD", "${a?.popularity ?: 0}/100", Modifier.weight(1f))
                    Fact("LANZAMIENTOS", "${albums?.getOrNull()?.size ?: "…"}", Modifier.weight(1f))
                }
                if (a != null) CyberButton("▶ REPRODUCIR ARTISTA", Modifier.fillMaxWidth().padding(top = 12.dp), filled = true) { SpotifyRemote.play(a.uri) }
                Box(Modifier.padding(top = 18.dp)) { NHead(null, "TOP 5") }
            }
        }
        val t = top
        when {
            t == null -> item { Loading() }
            t.isFailure -> item { ErrorLine(t.exceptionOrNull()!!) }
            else -> itemsIndexed(t.getOrThrow().take(5)) { i, tr ->
                Box(Modifier.padding(horizontal = 18.dp)) { TrackRow(i + 1, tr, showCover = false) { SpotifyRemote.play(tr.uri) } }
            }
        }
        item {
            Box(Modifier.padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 10.dp)) { NHead(null, "DISCOGRAFÍA") }
            LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(albums?.getOrNull().orEmpty(), key = { it.id }) { al ->
                    Column(Modifier.width(100.dp).clickable { open(NPage.Collection(al.id, al.uri, al.name, al.images.best(), true)) }) {
                        ThresholdCover(al.images.best(200), Modifier.size(100.dp), ghostShift = 3.dp)
                        Text(al.name.uppercase(), style = raj(12.sp, tracking = 0.08f, color = p.text), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
                        Text(al.releaseDate.take(4) + " · " + al.albumType.uppercase(), style = mono(9.sp, 0.06f, p.muted))
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Fact(label: String, value: String, modifier: Modifier) {
    val p = AtmosTheme.p
    Column(modifier.background(p.panel).drawBehind { drawRect(p.secondary, Offset.Zero, Size(2.dp.toPx(), size.height)) }.padding(start = 9.dp, top = 7.dp, bottom = 7.dp, end = 4.dp)) {
        Text(label, style = mono(9.sp, 0.1f, p.muted), maxLines = 1)
        Text(value, style = raj(18.sp, tracking = 0f, color = p.text), maxLines = 1)
    }
}

// ───────────────────────────── KILL COUNT ─────────────────────────────

@Composable
private fun KillCountPage(open: (NPage) -> Unit) {
    val p = AtmosTheme.p
    var range by rememberSaveable { mutableStateOf(TopRange.CORTO) }
    val artists by rememberApi(range) { topArtists(range) }
    val tracks by rememberApi(range, "t") { topTracks(range) }
    val list = artists?.getOrNull().orEmpty()
    // Spotify no da número de escuchas: el peso sale de la posición en tu top (1º pesa más).
    val genres = remember(list) {
        val w = HashMap<String, Float>()
        list.forEachIndexed { i, a -> a.genres.forEach { g -> w[g] = (w[g] ?: 0f) + (list.size - i) } }
        val total = w.values.sum().coerceAtLeast(1f)
        w.entries.sortedByDescending { it.value }.take(5).map { it.key to (it.value / total * 100).toInt() }
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.fillMaxWidth().background(p.panel).drawBehind {
                drawLine(p.secondary, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
            }.padding(18.dp)) {
                NHead("KILL COUNT", "TUS ESTADÍSTICAS")
                Text("Nº 1 // ${range.label}", style = mono(11.sp, 0.16f, p.muted), modifier = Modifier.padding(top = 12.dp))
                val shift = with(LocalDensity.current) { 3.dp.toPx() }
                Text(
                    (list.firstOrNull()?.name ?: "…").uppercase(), maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = anton(56.sp, color = p.primary).copy(shadow = Shadow(p.secondary, Offset(shift, 0f), 0f))
                )
                Text("TU ARTISTA MÁS ESCUCHADO", style = raj(13.sp, FontWeight.SemiBold, 0.2f, p.secondary))
                Box(Modifier.padding(top = 12.dp)) { Segmented(TopRange.entries, range, { it.label }) { range = it } }
            }
        }
        val r = artists
        when {
            r == null -> item { Loading() }
            r.isFailure -> item { ErrorLine(r.exceptionOrNull()!!) }
            else -> {
                item { Box(Modifier.padding(start = 18.dp, end = 18.dp, top = 16.dp)) { NHead(null, "TOP ARTISTAS") } }
                itemsIndexed(list.take(10)) { i, a ->
                    Row(
                        Modifier.fillMaxWidth().clickable { open(NPage.Artist(a.id)) }.padding(horizontal = 18.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("%02d".format(i + 1), style = anton(32.sp, color = if (i == 0) p.primary else p.secondary), modifier = Modifier.width(48.dp))
                        Column(Modifier.weight(1f)) {
                            Text(a.name.uppercase(), style = raj(17.sp, tracking = 0.12f, color = p.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Box(Modifier.padding(top = 5.dp).fillMaxWidth().height(5.dp).background(p.text.copy(alpha = 0.1f))) {
                                Box(Modifier.fillMaxHeight().fillMaxWidth(1f - i * 0.085f).background(p.primary))
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(a.genres.firstOrNull()?.uppercase() ?: "", style = mono(9.sp, 0.08f, p.muted), maxLines = 1, modifier = Modifier.widthIn(max = 90.dp))
                    }
                }
                if (genres.isNotEmpty()) {
                    item { Box(Modifier.padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 8.dp)) { NHead(null, "GÉNERO DOMINANTE", trailing = genres.first().first.uppercase()) } }
                    items(genres) { (g, pct) ->
                        Row(Modifier.padding(horizontal = 18.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(g.uppercase(), style = mono(10.sp, 0.08f, p.text), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(110.dp))
                            Box(Modifier.weight(1f).height(9.dp).background(p.text.copy(alpha = 0.1f))) {
                                Box(Modifier.fillMaxHeight().fillMaxWidth((pct / 100f * 2.2f).coerceIn(0.03f, 1f)).background(p.secondary))
                            }
                            Text("$pct%", style = mono(10.sp, 0f, p.muted), modifier = Modifier.width(40.dp).wrapContentWidth(Alignment.End))
                        }
                    }
                }
                item { Box(Modifier.padding(start = 18.dp, end = 18.dp, top = 18.dp)) { NHead(null, "TOP PISTAS") } }
                val tr = tracks?.getOrNull().orEmpty()
                itemsIndexed(tr.take(5)) { i, t -> Box(Modifier.padding(horizontal = 18.dp)) { TrackRow(i + 1, t, showCover = true) { SpotifyRemote.play(t.uri) } } }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}
