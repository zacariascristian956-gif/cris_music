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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

val Green = Color(0xFF1ED760)
val Bg = Color(0xFF080A0B)
val Panel = Color(0xFF171C1B)
val Muted = Color(0xFFA8B0AD)

data class Song(val id: Long, val title: String, val artist: String, val uri: Uri, val art: Uri)

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

fun loadSongs(ctx: Context): List<Song> {
    val out = mutableListOf<Song>()
    val m = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val proj = arrayOf(
        MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM_ID
    )
    val sel = "${MediaStore.Audio.Media.IS_MUSIC}!=0"
    ctx.contentResolver.query(m, proj, sel, null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC")?.use { c ->
        val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val iT = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
        val iA = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
        val iAl = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
        while (c.moveToNext()) {
            val id = c.getLong(iId)
            out.add(
                Song(
                    id,
                    c.getString(iT) ?: "Sin título",
                    c.getString(iA)?.takeIf { it != "<unknown>" } ?: "Artista desconocido",
                    ContentUris.withAppendedId(m, id),
                    ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), c.getLong(iAl))
                )
            )
        }
    }
    return out
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { App() }
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var granted by remember { mutableStateOf(hasPerm(ctx)) }
    var reload by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var ctrl by remember { mutableStateOf<MediaController?>(null) }
    var playing by remember { mutableStateOf(false) }
    var curId by remember { mutableStateOf<String?>(null) }
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

    MaterialTheme(colorScheme = darkColorScheme(primary = Green, background = Bg, surface = Panel)) {
        Column(Modifier.fillMaxSize().background(Bg).systemBarsPadding()) {
            Text(
                "cris_music", color = Green, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp)
            )
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text("Buscar en tu música") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            )
            val list = remember(songs, query) {
                if (query.isBlank()) songs
                else songs.filter { it.title.contains(query, true) || it.artist.contains(query, true) }
            }
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
                    list.isEmpty() -> Column(
                        Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("No encontré canciones.", color = Color.White)
                        TextButton(onClick = { reload++ }) { Text("Actualizar") }
                    }
                    else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                        itemsIndexed(list, key = { _, s -> s.id }) { i, s ->
                            val on = s.id.toString() == curId
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 3.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (on) Color(0xFF1D2E24) else Panel)
                                    .clickable {
                                        val c = ctrl
                                        if (c == null) msg = "Iniciando el reproductor, intenta otra vez"
                                        else { c.setMediaItems(list.map { it.toItem() }, i, 0L); c.prepare(); c.play() }
                                    }.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    Modifier.size(46.dp).clip(RoundedCornerShape(8.dp))
                                        .background(Color.hsv(((s.title.hashCode() and 0x7fffffff) % 360).toFloat(), 0.6f, 0.6f)),
                                    contentAlignment = Alignment.Center
                                ) { Text(s.title.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold) }
                                Column(Modifier.padding(start = 12.dp)) {
                                    Text(s.title, color = if (on) Green else Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(s.artist, color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
            msg?.let {
                Text(it, color = Color(0xFFFFD9A0), fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                LaunchedEffect(it) { delay(3000); msg = null }
            }
            val cs = songs.firstOrNull { it.id.toString() == curId }
            if (cs != null) {
                Column(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(cs.title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(cs.artist, color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Slider(
                        value = pos.coerceIn(0L, maxOf(dur, 1L)).toFloat(),
                        onValueChange = { dragging = true; pos = it.toLong() },
                        onValueChangeFinished = { ctrl?.seekTo(pos); dragging = false },
                        valueRange = 0f..maxOf(dur, 1L).toFloat(),
                        colors = SliderDefaults.colors(thumbColor = Green, activeTrackColor = Green)
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(fmt(pos), color = Muted, fontSize = 12.sp)
                        Text(fmt(dur), color = Muted, fontSize = 12.sp)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        Text("⇄", color = if (shuffle) Green else Muted, fontSize = 24.sp,
                            modifier = Modifier.clickable { ctrl?.setShuffleModeEnabled(!shuffle) }.padding(12.dp))
                        Text("⏮", color = Color.White, fontSize = 26.sp,
                            modifier = Modifier.clickable { ctrl?.seekToPrevious() }.padding(12.dp))
                        Box(
                            Modifier.size(56.dp).clip(RoundedCornerShape(28.dp)).background(Green)
                                .clickable { ctrl?.let { if (playing) it.pause() else it.play() } },
                            contentAlignment = Alignment.Center
                        ) { Text(if (playing) "⏸" else "▶", color = Color.Black, fontSize = 24.sp) }
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
