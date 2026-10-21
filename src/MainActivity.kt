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
import androidx.compose.ui.graphics.Shape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.activity.compose.BackHandler
import androidx.compose.ui.unit.Dp
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
val Bg = Color(0xFF0B0B0C)
val Panel = Color(0xFF1A1A1B)
val Muted = Color(0xFFA8B0AD)
val ChipBg = Color(0xFF262627)
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

fun prefs(ctx: Context) = ctx.getSharedPreferences("cris", Context.MODE_PRIVATE)
fun loadPlays(ctx: Context): Map<String, Int> =
    prefs(ctx).all.filterKeys { it.startsWith("p_") }.mapValues { (it.value as? Int) ?: 0 }.mapKeys { it.key.removePrefix("p_") }
fun loadRecents(ctx: Context): List<String> =
    (prefs(ctx).getString("recents", "") ?: "").split("\n").filter { it.isNotBlank() }
fun saveRecents(ctx: Context, l: List<String>) { prefs(ctx).edit().putString("recents", l.joinToString("\n")).apply() }
fun hueOf(s: Song) = ((s.title.hashCode() and 0x7fffffff) % 360).toFloat()
fun greeting(): String {
    val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    return if (h < 12) "Buenos días" else if (h < 20) "Buenas tardes" else "Buenas noches"
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
        t, color = if (on) Color(0xFF071108) else Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(if (on) Green else ChipBg)
            .clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 9.dp)
    )
}

@Composable
fun Cover(s: Song, modifier: Modifier, shape: Shape = RoundedCornerShape(8.dp)) {
    Box(modifier.clip(shape).background(Color.hsv(hueOf(s), 0.6f, 0.55f)), contentAlignment = Alignment.Center) {
        Text(s.title.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp)
        AsyncImage(model = s.art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

@Composable
fun PauseBars(color: Color, w: Dp, h: Dp) {
    Row(horizontalArrangement = Arrangement.spacedBy(w * 0.8f)) {
        repeat(2) { Box(Modifier.size(w, h).clip(RoundedCornerShape(2.dp)).background(color)) }
    }
}

@Composable
fun QBtn(t: String, onClick: () -> Unit) {
    Box(
        Modifier.padding(start = 4.dp).size(32.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF303735)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text(t, color = Color.White, fontSize = 13.sp) }
}

@Composable
fun Title(t: String) {
    Text(t, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 10.dp))
}

@Composable
fun Tile(label: String, glyph: String, c: Color, modifier: Modifier, onClick: () -> Unit) {
    Row(modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFF222223)).clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(50.dp).background(c), contentAlignment = Alignment.Center) { Text(glyph, color = Color.White, fontSize = 20.sp) }
        Text(label, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 2, modifier = Modifier.padding(horizontal = 10.dp))
    }
}

@Composable
fun SongRow(
    s: Song, rank: Int?, on: Boolean, fav: Boolean,
    onPlay: () -> Unit, onFav: () -> Unit, onNext: () -> Unit, onQueue: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable(onClick = onPlay).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (rank != null) Text(rank.toString(), color = Muted, fontSize = 14.sp, modifier = Modifier.width(26.dp))
        Cover(s, Modifier.size(56.dp), RoundedCornerShape(6.dp))
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(s.title, color = if (on) Green else Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(s.artist, color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(if (fav) "♥" else "♡", color = if (fav) Green else Color(0xFFDDDDDD), fontSize = 24.sp, modifier = Modifier.clickable(onClick = onFav).padding(8.dp))
        Box {
            Text("⋮", color = Color(0xFFDDDDDD), fontSize = 22.sp, modifier = Modifier.clickable { menu = true }.padding(horizontal = 10.dp, vertical = 8.dp))
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Reproducir a continuación") }, onClick = { menu = false; onNext() })
                DropdownMenuItem(text = { Text("Añadir a la cola") }, onClick = { menu = false; onQueue() })
            }
        }
    }
}

@Composable
fun QueueView(ids: List<String>, byId: Map<String, Song>, curId: String?, ctrl: MediaController?, onClear: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Column(Modifier.padding(top = 8.dp, bottom = 6.dp)) {
                Text("Cola", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold)
                Text("Reordena con ▲ ▼, quita con ✕ o toca una canción para reproducirla.", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                Box(Modifier.padding(top = 12.dp)) { Chip("Vaciar cola", false, onClear) }
            }
        }
        if (ids.isEmpty()) item { Text("La cola está vacía. Usa ⋮ en cualquier canción para añadirla.", color = Muted, modifier = Modifier.padding(8.dp)) }
        itemsIndexed(ids) { i, id ->
            val s = byId[id]
            if (s != null) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (id == curId) PlayingBg else Panel).padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Cover(s, Modifier.size(44.dp))
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp).clickable { ctrl?.seekToDefaultPosition(i); ctrl?.play() }) {
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
fun NavItem(glyph: String, label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick).padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(glyph, color = if (on) Color.White else Muted, fontSize = 22.sp)
        Text(label, color = if (on) Color.White else Muted, fontSize = 11.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var favs by remember { mutableStateOf(loadFavs(ctx)) }
    var plays by remember { mutableStateOf(loadPlays(ctx)) }
    var recents by remember { mutableStateOf(loadRecents(ctx)) }
    var granted by remember { mutableStateOf(hasPerm(ctx)) }
    var reload by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var tab by remember { mutableIntStateOf(0) }
    var libTab by remember { mutableIntStateOf(0) }
    var expanded by remember { mutableStateOf(false) }
    var pmenu by remember { mutableStateOf(false) }
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

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted = hasPerm(ctx) }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(askPerms()) }
    LaunchedEffect(granted, reload) { if (granted) songs = withContext(Dispatchers.IO) { loadSongs(ctx) } }
    BackHandler(enabled = expanded) { expanded = false }

    DisposableEffect(Unit) {
        val future = MediaController.Builder(ctx, SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))).buildAsync()
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                curId = item?.mediaId; pos = 0L
                ctrl?.let { dur = it.duration.coerceAtLeast(0L) }
                item?.mediaId?.let { id ->
                    val n = prefs(ctx).getInt("p_$id", 0) + 1
                    prefs(ctx).edit().putInt("p_$id", n).apply()
                    plays = plays + (id to n)
                }
            }
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                ctrl?.let { c -> queueIds = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId } }
            }
            override fun onPlaybackStateChanged(state: Int) { ctrl?.let { dur = it.duration.coerceAtLeast(0L) } }
            override fun onShuffleModeEnabledChanged(enabled: Boolean) { shuffle = enabled }
            override fun onRepeatModeChanged(mode: Int) { repeat = mode }
            override fun onPlayerError(error: PlaybackException) {
                msg = "No se pudo reproducir esta canción"
                ctrl?.let {
                    if (it.hasNextMediaItem() && it.repeatMode == Player.REPEAT_MODE_OFF) { it.seekToNextMediaItem(); it.prepare(); it.play() }
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
            } catch (e: Exception) { msg = "No se pudo conectar con el reproductor" }
        }, ContextCompat.getMainExecutor(ctx))
        onDispose { ctrl?.removeListener(listener); MediaController.releaseFuture(future) }
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
    val recent = remember(songs) { songs.sortedByDescending { it.added } }
    val favList = remember(songs, favs) { songs.filter { it.id in favs } }
    val artists = remember(songs) { songs.groupBy { it.artist }.entries.sortedByDescending { it.value.size }.take(12).map { it.value.first() } }
    val top = remember(songs, plays) { songs.filter { (plays[it.id.toString()] ?: 0) > 0 }.sortedByDescending { plays[it.id.toString()] ?: 0 }.take(10) }
    val results = remember(songs, query) {
        if (query.isBlank()) emptyList() else songs.filter { it.title.contains(query, true) || it.artist.contains(query, true) }
    }

    fun play(list: List<Song>, i: Int) {
        val c = ctrl
        if (c == null) msg = "Iniciando el reproductor, intenta otra vez" else playList(c, list, i)
    }
    fun toggleFav(id: Long) { favs = if (id in favs) favs - id else favs + id; saveFavs(ctx, favs) }
    fun nextOf(s: Song) { val c = ctrl; if (c != null) { enqueue(c, s, true); msg = "Sonará a continuación" } }
    fun queueOf(s: Song) { val c = ctrl; if (c != null) { enqueue(c, s, false); msg = "Añadida a la cola" } }
    @Composable fun Row1(s: Song, i: Int, ranked: Boolean, list: List<Song>) {
        SongRow(s, if (ranked) i + 1 else null, s.id.toString() == curId, s.id in favs,
            { play(list, i) }, { toggleFav(s.id) }, { nextOf(s) }, { queueOf(s) })
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Green, background = Bg, surface = Panel)) {
        Box(Modifier.fillMaxSize().background(Bg)) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        !granted -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Necesito permiso para leer tu música.", color = Color.White)
                            Text("Si no aparece el aviso, actívalo en Ajustes > Apps > cris_music > Permisos.", color = Muted, fontSize = 13.sp)
                            Button(onClick = { launcher.launch(askPerms()) }, modifier = Modifier.padding(top = 12.dp)) { Text("Dar permiso") }
                        }
                        tab == 0 -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 12.dp)) {
                            item {
                                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(40.dp).clip(CircleShape).background(Green), contentAlignment = Alignment.Center) { Text("C", color = Color.Black, fontWeight = FontWeight.ExtraBold) }
                                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                        Text(greeting(), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                        Text("Descubre tu música, ${songs.size} canciones", color = Muted, fontSize = 12.sp)
                                    }
                                    Text("⟳", color = Color.White, fontSize = 24.sp, modifier = Modifier.clickable { reload++; msg = "Biblioteca actualizada" }.padding(8.dp))
                                }
                            }
                            item { Title("Reproducciones recientes") }
                            item {
                                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Tile("Tus me gusta", "♥", Color(0xFF6C4DFF), Modifier.weight(1f)) { libTab = 0; tab = 2 }
                                        Tile("En bucle", "∞", Color(0xFFC13FD0), Modifier.weight(1f)) {
                                            if (songs.isNotEmpty()) { play(songs, 0); ctrl?.setRepeatMode(Player.REPEAT_MODE_ALL) }
                                        }
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Tile("Mezcla aleatoria", "⇄", Color(0xFF14A8A0), Modifier.weight(1f)) {
                                            if (songs.isNotEmpty()) { play(songs.shuffled(), 0); ctrl?.setShuffleModeEnabled(true) }
                                        }
                                        Tile("Todas las canciones", "♫", Color(0xFF2E7D4F), Modifier.weight(1f)) { libTab = 1; tab = 2 }
                                    }
                                }
                            }
                            item { Title("Añadidas recientemente") }
                            item {
                                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    itemsIndexed(recent.take(15)) { i, s ->
                                        Column(Modifier.width(130.dp).clickable { play(recent, i) }) {
                                            Cover(s, Modifier.size(130.dp), RoundedCornerShape(6.dp))
                                            Text(s.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                                            Text(s.artist, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                            item { Title("Artistas populares") }
                            item {
                                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                    itemsIndexed(artists) { _, s ->
                                        Column(Modifier.width(100.dp).clickable { query = s.artist; tab = 1 }, horizontalAlignment = Alignment.CenterHorizontally) {
                                            Cover(s, Modifier.size(100.dp), CircleShape)
                                            Text(s.artist, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                                        }
                                    }
                                }
                            }
                            item { Title("Más escuchadas") }
                            if (top.isEmpty()) item { Text("Aquí aparecerán las canciones que más reproduzcas.", color = Muted, modifier = Modifier.padding(horizontal = 16.dp)) }
                            itemsIndexed(top) { i, s -> Row1(s, i, true, top) }
                        }
                        tab == 1 -> Column(Modifier.fillMaxSize()) {
                            Text("Buscar", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 10.dp))
                            TextField(
                                value = query, onValueChange = { query = it }, singleLine = true, shape = RoundedCornerShape(25.dp),
                                placeholder = { Text("Busca una canción o artista") },
                                leadingIcon = { Text("⌕", fontSize = 22.sp, color = Muted) },
                                trailingIcon = { if (query.isNotEmpty()) Text("✕", color = Muted, modifier = Modifier.clickable { query = "" }.padding(12.dp)) },
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = {
                                    val q = query.trim()
                                    if (q.isNotEmpty()) { recents = (listOf(q) + recents.filter { it != q }).take(8); saveRecents(ctx, recents) }
                                }),
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color(0xFF242424), unfocusedContainerColor = Color(0xFF242424),
                                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent
                                ),
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                            )
                            LazyColumn(Modifier.fillMaxSize()) {
                                if (query.isBlank()) {
                                    item { Title("Recientes") }
                                    if (recents.isEmpty()) item { Text("Tus búsquedas aparecerán aquí.", color = Muted, modifier = Modifier.padding(horizontal = 16.dp)) }
                                    itemsIndexed(recents) { _, r ->
                                        Row(Modifier.fillMaxWidth().clickable { query = r }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Text("⌕", color = Muted, fontSize = 20.sp)
                                            Text(r, color = Color.White, modifier = Modifier.weight(1f).padding(horizontal = 14.dp))
                                            Text("✕", color = Muted, modifier = Modifier.clickable { recents = recents - r; saveRecents(ctx, recents) }.padding(8.dp))
                                        }
                                    }
                                } else {
                                    item { Title("Canción") }
                                    if (results.isEmpty()) item { Text("Sin resultados para “$query”.", color = Muted, modifier = Modifier.padding(horizontal = 16.dp)) }
                                    itemsIndexed(results) { i, s -> Row1(s, i, false, results) }
                                }
                            }
                        }
                        tab == 2 -> Column(Modifier.fillMaxSize()) {
                            Text("Tu Biblioteca", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 10.dp))
                            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Chip("Favoritas", libTab == 0) { libTab = 0 }
                                Chip("Todas", libTab == 1) { libTab = 1 }
                            }
                            val l = if (libTab == 0) favList else songs
                            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 10.dp)) {
                                if (l.isEmpty()) item { Text("Todavía no guardas canciones. Pulsa ♡ en cualquier canción.", color = Muted, modifier = Modifier.padding(16.dp)) }
                                itemsIndexed(l) { i, s -> Row1(s, i, false, l) }
                            }
                        }
                        else -> QueueView(queueIds, byId, curId, ctrl) { ctrl?.clearMediaItems(); curId = null }
                    }
                }
                msg?.let {
                    Text(it, color = Color(0xFFFFD9A0), fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    LaunchedEffect(it) { delay(3000); msg = null }
                }
                val cs = byId[curId]
                if (cs != null) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).clip(RoundedCornerShape(14.dp))
                            .background(Color.hsv(hueOf(cs), 0.35f, 0.2f)).clickable { expanded = true }.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Cover(cs, Modifier.size(42.dp), RoundedCornerShape(6.dp))
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(cs.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(cs.artist, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(if (cs.id in favs) "♥" else "♡", color = if (cs.id in favs) Green else Color.White, fontSize = 24.sp, modifier = Modifier.clickable { toggleFav(cs.id) }.padding(8.dp))
                        Box(Modifier.size(40.dp).clickable { ctrl?.let { if (playing) it.pause() else it.play() } }, contentAlignment = Alignment.Center) {
                            if (playing) PauseBars(Color.White, 5.dp, 18.dp) else Text("▶", color = Color.White, fontSize = 20.sp)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().background(Color.Black)) {
                    NavItem("⌂", "Inicio", tab == 0, Modifier.weight(1f)) { tab = 0 }
                    NavItem("⌕", "Buscar", tab == 1, Modifier.weight(1f)) { tab = 1 }
                    NavItem("▤", "Tu Biblioteca", tab == 2, Modifier.weight(1f)) { tab = 2 }
                    NavItem("☰", "Cola", tab == 3, Modifier.weight(1f)) { tab = 3 }
                }
            }

            val cp = byId[curId]
            if (expanded && cp != null) {
                Column(
                    Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.hsv(hueOf(cp), 0.45f, 0.4f), Color(0xFF0B0B0C))))
                        .systemBarsPadding().padding(horizontal = 24.dp)
                ) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.width(36.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x66FFFFFF)))
                        Text("⌄", color = Color.White, fontSize = 26.sp, modifier = Modifier.align(Alignment.CenterStart).clickable { expanded = false }.padding(4.dp))
                    }
                    Spacer(Modifier.height(10.dp))
                    Cover(cp, Modifier.fillMaxWidth().aspectRatio(1f), RoundedCornerShape(14.dp))
                    Row(Modifier.fillMaxWidth().padding(top = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(cp.title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(cp.artist, color = Color(0xFFCCCCCC), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Box(Modifier.padding(start = 8.dp).size(38.dp).clip(CircleShape).background(Color(0x33FFFFFF)).clickable { toggleFav(cp.id) }, contentAlignment = Alignment.Center) {
                            Text(if (cp.id in favs) "♥" else "♡", color = if (cp.id in favs) Green else Color.White, fontSize = 20.sp)
                        }
                        Box(Modifier.padding(start = 8.dp)) {
                            Box(Modifier.size(38.dp).clip(CircleShape).background(Color(0x33FFFFFF)).clickable { pmenu = true }, contentAlignment = Alignment.Center) { Text("⋯", color = Color.White, fontSize = 20.sp) }
                            DropdownMenu(expanded = pmenu, onDismissRequest = { pmenu = false }) {
                                DropdownMenuItem(text = { Text("Ver cola") }, onClick = { pmenu = false; expanded = false; tab = 3 })
                            }
                        }
                    }
                    Slider(
                        value = pos.coerceIn(0L, maxOf(dur, 1L)).toFloat(),
                        onValueChange = { dragging = true; pos = it.toLong() },
                        onValueChangeFinished = { ctrl?.seekTo(pos); dragging = false },
                        valueRange = 0f..maxOf(dur, 1L).toFloat(),
                        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color(0x44FFFFFF)),
                        modifier = Modifier.padding(top = 10.dp)
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(fmt(pos), color = Color(0xFFCCCCCC), fontSize = 12.sp)
                        Text(if (playing) "REPRODUCIENDO" else "EN PAUSA", color = Color(0xFF999999), fontSize = 11.sp, letterSpacing = 1.sp)
                        Text("-" + fmt(dur - pos), color = Color(0xFFCCCCCC), fontSize = 12.sp)
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        Text("⏮", color = Color.White, fontSize = 30.sp, modifier = Modifier.clickable { ctrl?.seekToPrevious() }.padding(14.dp))
                        Box(Modifier.size(78.dp).clickable { ctrl?.let { if (playing) it.pause() else it.play() } }, contentAlignment = Alignment.Center) {
                            if (playing) PauseBars(Color.White, 12.dp, 44.dp) else Text("▶", color = Color.White, fontSize = 46.sp)
                        }
                        Text("⏭", color = Color.White, fontSize = 30.sp, modifier = Modifier.clickable { ctrl?.seekToNext() }.padding(14.dp))
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        Text("⇄", color = if (shuffle) Green else Color(0xFFDDDDDD), fontSize = 26.sp, modifier = Modifier.clickable { ctrl?.setShuffleModeEnabled(!shuffle) }.padding(12.dp))
                        Text("☰", color = Color(0xFFDDDDDD), fontSize = 26.sp, modifier = Modifier.clickable { expanded = false; tab = 3 }.padding(12.dp))
                        Text(if (repeat == Player.REPEAT_MODE_ONE) "↻¹" else "↻", color = if (repeat == Player.REPEAT_MODE_OFF) Color(0xFFDDDDDD) else Green, fontSize = 26.sp,
                            modifier = Modifier.clickable {
                                ctrl?.setRepeatMode(when (repeat) {
                                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                                    else -> Player.REPEAT_MODE_OFF
                                })
                            }.padding(12.dp))
                    }
                }
            }
        }
    }
}
