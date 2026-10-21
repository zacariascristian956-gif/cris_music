package com.cris.music

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

val Green = Color(0xFF1ED760)
val Bg = Color(0xFF080A0B)
val Panel = Color(0xFF171C1B)
val Muted = Color(0xFFA8B0AD)
val ChipBg = Color(0xFF242A28)
val PlayingBg = Color(0xFF1D2E24)

data class Song(val id: Long, val title: String, val artist: String, val uri: Uri, val art: Uri, val added: Long)

fun Song.toItem(): MediaItem = MediaItem.Builder()
    .setMediaId(id.toString())
    .setUri(uri)
    .setMediaMetadata(
        MediaMetadata.Builder().setTitle(title).setArtist(artist).setArtworkUri(art).build()
    ).build()

fun audioPerm() =
    if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
    else Manifest.permission.READ_EXTERNAL_STORAGE

fun hasPerm(ctx: Context) =
    ContextCompat.checkSelfPermission(ctx, audioPerm()) == PackageManager.PERMISSION_GRANTED

fun askPerms(): Array<String> =
    if (Build.VERSION.SDK_INT >= 33) arrayOf(audioPerm(), Manifest.permission.POST_NOTIFICATIONS)
    else arrayOf(audioPerm())

fun fmt(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

fun loadFavs(ctx: Context): Set<Long> =
    ctx.getSharedPreferences("cris", Context.MODE_PRIVATE)
        .getStringSet("favs", emptySet())!!.mapNotNull { it.toLongOrNull() }.toSet()

fun saveFavs(ctx: Context, s: Set<Long>) {
    ctx.getSharedPreferences("cris", Context.MODE_PRIVATE).edit()
        .putStringSet("favs", s.map { it.toString() }.toSet()).apply()
}

fun loadSongs(ctx: Context): List<Song> {
    val out = mutableListOf<Song>()
    val m = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val proj = arrayOf(
        MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM_ID,
        MediaStore.Audio.Media.DATE_ADDED
    )
    val sel = "${MediaStore.Audio.Media.IS_MUSIC}!=0"
    ctx.contentResolver.query(m, proj, sel, null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC")?.use { c ->
        val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val iT = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
        val iA = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
        val iAl = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
        val iD = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
        while (c.moveToNext()) {
            val id = c.getLong(iId)
            out.add(
                Song(
                    id,
                    c.getString(iT) ?: "Sin título",
                    c.getString(iA)?.takeIf { it != "<unknown>" } ?: "Artista desconocido",
                    ContentUris.withAppendedId(m, id),
                    ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), c.getLong(iAl)),
                    c.getLong(iD)
                )
            )
        }
    }
    return out
}

fun playList(c: MediaController, list: List<Song>, i: Int) {
    c.setMediaItems(list.map { it.toItem() }, i, 0L)
    c.prepare()
    c.play()
}

fun enqueue(c: MediaController, s: Song, next: Boolean) {
    if (c.mediaItemCount == 0) { c.setMediaItems(listOf(s.toItem())); c.prepare(); c.play() }
    else if (next) c.addMediaItem(c.currentMediaItemIndex + 1, s.toItem())
    else c.addMediaItem(s.toItem())
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { App() }
    }
}

@Composable
fun Chip(t: String, on: Boolean, onClick: () -> Unit) {
    Text(
        t, color = if (on) Color(0xFF071108) else Color.White, fontSize = 14.sp,
        modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(if (on) Green else ChipBg)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp)
    )
}

@Composable
fun Cover(s: Song, modifier: Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(8.dp))
            .background(Color.hsv(((s.title.hashCode() and 0x7fffffff) % 360).toFloat(), 0.6f, 0.55f)),
        contentAlignment = Alignment.Center
    ) {
        Text(s.title.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp)
        AsyncImage(model = s.art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

@Composable
fun QBtn(t: String, onClick: () -> Unit) {
    Box(
        Modifier.padding(start = 4.dp).size(32.dp).clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF303735)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text(t, color = Color.White, fontSize = 13.sp) }
}

@Composable
fun SongCard(
    s: Song, on: Boolean, fav: Boolean,
    onPlay: () -> Unit, onFav: () -> Unit, onNext: () -> Unit, onQueue: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(if (on) PlayingBg else Panel)
            .border(1.dp, if (on) Green else Color.Transparent, shape)
            .clickable(onClick = onPlay).padding(10.dp)
    ) {
        Cover(s, Modifier.fillMaxWidth().aspectRatio(1f))
        Text(s.title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
        Text(s.artist, color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(Green).clickable(onClick = onPlay),
                contentAlignment = Alignment.Center
            ) { Text("▶", color = Color(0xFF061108), fontSize = 16.sp, fontWeight = FontWeight.Black) }
            Box(Modifier.padding(start = 8.dp)) {
                Box(
                    Modifier.size(34.dp).clip(CircleShape).background(Color(0xFF303735)).clickable { menu = true },
                    contentAlignment = Alignment.Center
                ) { Text("＋", color = Color.White, fontSize = 16.sp) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Reproducir a continuación") }, onClick = { menu = false; onNext() })
                    DropdownMenuItem(text = { Text("Añadir a la cola") }, onClick = { menu = false; onQueue() })
                }
            }
            Spacer(Modifier.weight(1f))
            Text(
                if (fav) "♥" else "♡", color = if (fav) Green else Color(0xFFDCE3DF), fontSize = 24.sp,
                modifier = Modifier.clickable(onClick = onFav).padding(4.dp)
            )
        }
    }
}

@Composable
fun SongGrid(
    list: List<Song>, title: String, sub: String, home: Boolean, sort: Int, onSort: (Int) -> Unit,
    empty: String, curId: String?, favs: Set<Long>, ctrl: MediaController?,
    onFav: (Long) -> Unit, onReload: () -> Unit, onMsg: (String) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2), modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.padding(top = 8.dp)) {
                Text(title, color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 36.sp)
                Text(sub, color = Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
                if (home) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("Todas", sort == 0) { onSort(0) }
                        Chip("Recientes", sort == 1) { onSort(1) }
                        Chip("Por artista", sort == 2) { onSort(2) }
                    }
                    Text("Tu música", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 22.dp, bottom = 4.dp))
                }
            }
        }
        if (list.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel).padding(20.dp)) {
                    Text(empty, color = Muted)
                    if (home) TextButton(onClick = onReload) { Text("Actualizar", color = Green) }
                }
            }
        }
        itemsIndexed(list, key = { _, s -> s.id }) { i, s ->
            SongCard(
                s, s.id.toString() == curId, s.id in favs,
                onPlay = { val c = ctrl; if (c == null) onMsg("Iniciando el reproductor, intenta otra vez") else playList(c, list, i) },
                onFav = { onFav(s.id) },
                onNext = { val c = ctrl; if (c == null) onMsg("Iniciando el reproductor, intenta otra vez") else { enqueue(c, s, true); onMsg("Sonará a continuación") } },
                onQueue = { val c = ctrl; if (c == null) onMsg("Iniciando el reproductor, intenta otra vez") else { enqueue(c, s, false); onMsg("Añadida a la cola") } }
            )
        }
    }
}

@Composable
fun QueueView(ids: List<String>, byId: Map<String, Song>, curId: String?, ctrl: MediaController?, onClear: () -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item {
            Column(Modifier.padding(top = 8.dp, bottom = 6.dp)) {
                Text("Cola", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold)
                Text("Reordena con ▲ ▼, quita con ✕ o toca una canción para reproducirla.", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                Box(Modifier.padding(top = 12.dp)) { Chip("Vaciar cola", false, onClear) }
            }
        }
        if (ids.isEmpty()) {
            item { Text("La cola está vacía. Usa ＋ en cualquier canción para añadirla.", color = Muted, modifier = Modifier.padding(8.dp)) }
        }
        itemsIndexed(ids) { i, id ->
            val s = byId[id]
            if (s != null) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(if (id == curId) PlayingBg else Panel).padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Cover(s, Modifier.size(44.dp))
                    Column(
                        Modifier.weight(1f).padding(horizontal = 10.dp)
                            .clickable { ctrl?.seekToDefaultPosition(i); ctrl?.play() }
                    ) {
                        Text(s.title, color = if (id == curId) Green else Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(s.artist, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    QBtn("▲") { if (i > 0) ctrl?.moveMediaItem(i, i - 1) }
                    QBtn("▼") { if (i < ids.size - 1) ctrl?.moveMediaItem(i, i + 1) }
                    QBtn("✕") { ctrl?.removeMediaItem(i) }
                }
            }
        }
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var favs by remember { mutableStateOf(loadFavs(ctx)) }
    var granted by remember { mutableStateOf(hasPerm(ctx)) }
    var reload by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var tab by remember { mutableIntStateOf(0) }
    var sort by remember { mutableIntStateOf(0) }
    var ctrl by remember { mutableStateOf<MediaController?>(null) }
    var playing by remember { mutableStateOf(false) }
    var curId by remember { mutableStateOf<String?>(null) }
    var queueIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var shuffle by remember { mutableStateOf(false) }
    var repeat by remember { mutableIntStateOf(Player.REPEAT_MODE_OFF) }
    var dragging by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasPerm(ctx)
    }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(askPerms()) }
    LaunchedEffect(granted, reload) {
        if (granted) songs = withContext(Dispatchers.IO) { loadSongs(ctx) }
    }

    DisposableEffect(Unit) {
        val future = MediaController.Builder(
            ctx, SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
        ).buildAsync()
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                curId = item?.mediaId; pos = 0L
                ctrl?.let { dur = it.duration.coerceAtLeast(0L) }
            }
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                ctrl?.let { c -> queueIds = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId } }
            }
            override fun onPlaybackStateChanged(state: Int) {
                ctrl?.let { dur = it.duration.coerceAtLeast(0L) }
            }
            override fun onShuffleModeEnabledChanged(enabled: Boolean) { shuffle = enabled }
            override fun onRepeatModeChanged(mode: Int) { repeat = mode }
            override fun onPlayerError(error: PlaybackException) {
                msg = "No se pudo reproducir esta canción"
                ctrl?.let {
                    if (it.hasNextMediaItem() && it.repeatMode == Player.REPEAT_MODE_OFF) {
                        it.seekToNextMediaItem(); it.prepare(); it.play()
                    }
                }
            }
        }
        future.addListener({
            try {
                val c = future.get()
                c.addListener(listener)
                ctrl = c
                playing = c.isPlaying
                curId = c.currentMediaItem?.mediaId
                queueIds = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId }
                shuffle = c.shuffleModeEnabled
                repeat = c.repeatMode
                dur = c.duration.coerceAtLeast(0L)
            } catch (e: Exception) {
                msg = "No se pudo conectar con el reproductor"
            }
        }, ContextCompat.getMainExecutor(ctx))
        onDispose {
            ctrl?.removeListener(listener)
            MediaController.releaseFuture(future)
        }
    }

    LaunchedEffect(ctrl, playing) {
        val c = ctrl ?: return@LaunchedEffect
        while (true) {
            if (!dragging) { pos = c.currentPosition; dur = c.duration.coerceAtLeast(0L) }
            if (!playing) break
            delay(400)
        }
    }

    val byId = remember(songs) { songs.associateBy { it.id.toString() } }
    val base = remember(songs, sort) {
        when (sort) {
            1 -> songs.sortedByDescending { it.added }
            2 -> songs.sortedBy { it.artist.lowercase() }
            else -> songs
        }
    }
    val filtered = remember(base, query) {
        if (query.isBlank()) base
        else base.filter { it.title.contains(query, true) || it.artist.contains(query, true) }
    }
    val favList = remember(songs, favs) { songs.filter { it.id in favs } }

    MaterialTheme(colorScheme = darkColorScheme(primary = Green, background = Bg, surface = Panel)) {
        Box(
            Modifier.fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFF173B2B), Bg), endY = 1100f))
                .systemBarsPadding()
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("♫ cris_music", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                    Text(".", color = Green, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                }
                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("⌂ Inicio", tab == 0) { tab = 0 }
                    Chip("♡ Favoritos", tab == 1) { tab = 1 }
                    Chip("☰ Cola", tab == 2) { tab = 2 }
                }
                TextField(
                    value = query, onValueChange = { query = it; if (it.isNotBlank()) tab = 0 },
                    singleLine = true, shape = RoundedCornerShape(25.dp),
                    placeholder = { Text("Busca una canción o artista") },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFF242929), unfocusedContainerColor = Color(0xFF242929),
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent
                    ),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                )
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        !granted -> Column(
                            Modifier.fillMaxSize().padding(24.dp),
                            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("Necesito permiso para leer tu música.", color = Color.White)
                            Text("Si no aparece el aviso, actívalo en Ajustes > Apps > cris_music > Permisos.", color = Muted, fontSize = 13.sp)
                            Button(onClick = { launcher.launch(askPerms()) }, modifier = Modifier.padding(top = 12.dp)) { Text("Dar permiso") }
                        }
                        tab == 2 -> QueueView(queueIds, byId, curId, ctrl) {
                            ctrl?.clearMediaItems(); curId = null
                        }
                        else -> SongGrid(
                            list = if (tab == 0) filtered else favList,
                            title = if (tab == 0) "Música para cada momento." else "Mis favoritos",
                            sub = if (tab == 0) "Escucha tu música completa, también con la pantalla apagada." else "Tus canciones guardadas en este dispositivo.",
                            home = tab == 0, sort = sort, onSort = { sort = it },
                            empty = if (tab == 0) "No encontré canciones." else "Todavía no guardas canciones. Pulsa ♡ en cualquier canción para añadirla aquí.",
                            curId = curId, favs = favs, ctrl = ctrl,
                            onFav = { id ->
                                favs = if (id in favs) favs - id else favs + id
                                saveFavs(ctx, favs)
                            },
                            onReload = { reload++ }, onMsg = { msg = it }
                        )
                    }
                }
                msg?.let {
                    Text(it, color = Color(0xFFFFD9A0), fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    LaunchedEffect(it) { delay(3000); msg = null }
                }
                val cs = byId[curId]
                if (cs != null) {
                    Column(Modifier.fillMaxWidth().background(Color(0xFF101413))) {
                        HorizontalDivider(color = Color(0xFF303735))
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Cover(cs, Modifier.size(52.dp))
                                Column(Modifier.padding(start = 12.dp)) {
                                    Text(cs.title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(cs.artist, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            Slider(
                                value = pos.coerceIn(0L, maxOf(dur, 1L)).toFloat(),
                                onValueChange = { dragging = true; pos = it.toLong() },
                                onValueChangeFinished = { ctrl?.seekTo(pos); dragging = false },
                                valueRange = 0f..maxOf(dur, 1L).toFloat(),
                                colors = SliderDefaults.colors(thumbColor = Green, activeTrackColor = Green)
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(fmt(pos), color = Muted, fontSize = 11.sp)
                                Text(fmt(dur), color = Muted, fontSize = 11.sp)
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                                Text("⇄", color = if (shuffle) Green else Muted, fontSize = 24.sp,
                                    modifier = Modifier.clickable { ctrl?.setShuffleModeEnabled(!shuffle) }.padding(12.dp))
                                Text("⏮", color = Color.White, fontSize = 26.sp,
                                    modifier = Modifier.clickable { ctrl?.seekToPrevious() }.padding(12.dp))
                                Box(
                                    Modifier.size(46.dp).clip(CircleShape).background(Color.White)
                                        .clickable { ctrl?.let { if (playing) it.pause() else it.play() } },
                                    contentAlignment = Alignment.Center
                                ) { Text(if (playing) "⏸" else "▶", color = Color(0xFF101312), fontSize = 20.sp) }
                                Text("⏭", color = Color.White, fontSize = 26.sp,
                                    modifier = Modifier.clickable { ctrl?.seekToNext() }.padding(12.dp))
                                Text(if (repeat == Player.REPEAT_MODE_ONE) "↻¹" else "↻",
                                    color = if (repeat == Player.REPEAT_MODE_OFF) Muted else Green, fontSize = 24.sp,
                                    modifier = Modifier.clickable {
                                        ctrl?.setRepeatMode(
                                            when (repeat) {
                                                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                                                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                                                else -> Player.REPEAT_MODE_OFF
                                            }
                                        )
                                    }.padding(12.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
