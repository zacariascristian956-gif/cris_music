package com.cris.music

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
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
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors
import kotlinx.coroutines.delay

/* ---------------------------------------------------------------
   ESTADÍSTICAS: guarda segundos escuchados por día (misma
   SharedPreferences "cris" que ya usa la app).
   --------------------------------------------------------------- */
object CyaStats {
    const val MIN_DAY = 60L   // segundos mínimos para que un día cuente en la racha

    private fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("cris", Context.MODE_PRIVATE)

    fun dayKey(offset: Int): String {
        val c = Calendar.getInstance()
        c.add(Calendar.DAY_OF_YEAR, -offset)
        return String.format(
            Locale.US, "%04d%02d%02d",
            c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH)
        )
    }

    fun addSeconds(ctx: Context, sec: Int) {
        if (sec <= 0) return
        val p = sp(ctx)
        val k = "sd_" + dayKey(0)
        p.edit()
            .putLong(k, p.getLong(k, 0L) + sec)
            .putLong("st_total", p.getLong("st_total", 0L) + sec)
            .apply()
    }

    fun secondsOn(ctx: Context, offset: Int): Long = sp(ctx).getLong("sd_" + dayKey(offset), 0L)

    fun total(ctx: Context): Long = sp(ctx).getLong("st_total", 0L)

    fun streak(ctx: Context): Int {
        var off = if (secondsOn(ctx, 0) >= MIN_DAY) 0 else 1
        var n = 0
        while (n < 3650 && secondsOn(ctx, off) >= MIN_DAY) { n++; off++ }
        return n
    }

    fun last7(ctx: Context): List<Long> = (6 downTo 0).map { secondsOn(ctx, it) }

    fun durText(sec: Long): String {
        val m = sec / 60
        return if (m < 60) "$m min" else "${m / 60} h ${m % 60} min"
    }
}

fun dayLetter(offset: Int): String {
    val c = Calendar.getInstance()
    c.add(Calendar.DAY_OF_YEAR, -offset)
    return arrayOf("D", "L", "M", "M", "J", "V", "S")[c.get(Calendar.DAY_OF_WEEK) - 1]
}

/* ---------------------------------------------------------------
   PANTALLA DE ESTADÍSTICAS (Tu Biblioteca > Estadísticas)
   --------------------------------------------------------------- */
@Composable
fun StatBox(label: String, value: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(Panel).padding(12.dp)) {
        Text(label, color = Muted, fontSize = 12.sp)
        Text(value, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun StatsView(songs: List<Song>, plays: Map<String, Int>) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(5000); tick++ } }
    val today = remember(tick) { CyaStats.secondsOn(ctx, 0) }
    val streak = remember(tick) { CyaStats.streak(ctx) }
    val total = remember(tick) { CyaStats.total(ctx) }
    val week = remember(tick) { CyaStats.last7(ctx) }
    val topSongs = remember(songs, plays) {
        songs.filter { (plays[it.id.toString()] ?: 0) > 0 }
            .sortedByDescending { plays[it.id.toString()] ?: 0 }
            .take(5)
    }
    val topArtists = remember(songs, plays) {
        songs.groupBy { it.artist }
            .mapValues { e -> e.value.sumOf { plays[it.id.toString()] ?: 0 } }
            .filter { it.value > 0 }
            .entries.sortedByDescending { it.value }
            .take(5)
    }
    val distinct = remember(songs, plays) { songs.count { (plays[it.id.toString()] ?: 0) > 0 } }
    val totalPlays = remember(plays) { plays.values.sum() }
    val maxSec = (week.maxOrNull() ?: 0L).coerceAtLeast(60L).toFloat()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp)) {
        item { Title("Tu actividad") }
        item {
            Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatBox("Hoy", CyaStats.durText(today), Modifier.weight(1f))
                StatBox("Racha", if (streak == 1) "1 día 🔥" else "$streak días 🔥", Modifier.weight(1f))
                StatBox("Total", CyaStats.durText(total), Modifier.weight(1f))
            }
        }
        item {
            Text(
                "$totalPlays reproducciones · $distinct canciones distintas",
                color = Muted, fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
        item { Title("Últimos 7 días (minutos)") }
        item {
            Row(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(160.dp)
                    .clip(RoundedCornerShape(12.dp)).background(Panel).padding(12.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                week.forEachIndexed { i, s ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                        Text("${s / 60}", color = Muted, fontSize = 10.sp)
                        Box(
                            Modifier.padding(vertical = 3.dp).width(18.dp)
                                .height((4f + 80f * (s.toFloat() / maxSec)).dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (i == 6) Accent else Color(0xFF7A2A30))
                        )
                        Text(dayLetter(6 - i), color = Muted, fontSize = 11.sp)
                    }
                }
            }
        }
        item { Title("Canciones más escuchadas") }
        if (topSongs.isEmpty()) item {
            Text("Aún no hay datos. Escucha una canción unos 30 segundos para empezar.", color = Muted, modifier = Modifier.padding(horizontal = 16.dp))
        }
        itemsIndexed(topSongs) { i, s ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", color = Accent, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, modifier = Modifier.width(24.dp))
                Cover(s, Modifier.size(46.dp), RoundedCornerShape(6.dp))
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(s.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(s.artist, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text("${plays[s.id.toString()] ?: 0} rep.", color = Muted, fontSize = 12.sp)
            }
        }
        item { Title("Artistas más escuchados") }
        if (topArtists.isEmpty()) item {
            Text("Aquí aparecerán tus artistas favoritos.", color = Muted, modifier = Modifier.padding(horizontal = 16.dp))
        }
        itemsIndexed(topArtists) { i, e ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", color = Accent, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, modifier = Modifier.width(24.dp))
                Text(e.key, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text("${e.value} rep.", color = Muted, fontSize = 12.sp)
            }
        }
        item {
            Text(
                "Las reproducciones cuentan después de escuchar unos 30 segundos. El tiempo de YouTube suma a tus minutos, pero no a los rankings.",
                color = Muted, fontSize = 11.sp, modifier = Modifier.padding(16.dp)
            )
        }
    }
}

/* ---------------------------------------------------------------
   WIDGETS
   --------------------------------------------------------------- */
object CyaWidgets {
    private val io = Executors.newSingleThreadExecutor()
    private var artKey = ""
    private var artBmp: Bitmap? = null

    /** Actualiza todos los widgets de CYA (en segundo plano). */
    fun refresh(ctx: Context) {
        val app = ctx.applicationContext
        io.execute { try { update(app) } catch (e: Exception) { } }
    }

    private fun update(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx)
        val sp = ctx.getSharedPreferences("cris", Context.MODE_PRIVATE)
        val pIds = mgr.getAppWidgetIds(ComponentName(ctx, PlayerWidget::class.java))
        if (pIds.isNotEmpty()) mgr.updateAppWidget(pIds, playerViews(ctx, sp))
        val sIds = mgr.getAppWidgetIds(ComponentName(ctx, StatsWidget::class.java))
        if (sIds.isNotEmpty()) mgr.updateAppWidget(sIds, statsViews(ctx, sp))
    }

    private fun base(ctx: Context): RemoteViews {
        val rv = RemoteViews(ctx.packageName, R.layout.cya_widget)
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        rv.setOnClickPendingIntent(R.id.w_root, open)
        return rv
    }

    private fun act(ctx: Context, action: String, code: Int): PendingIntent {
        val i = Intent(ctx, WidgetActions::class.java).setAction(action)
        return PendingIntent.getBroadcast(ctx, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun playerViews(ctx: Context, sp: SharedPreferences): RemoteViews {
        val rv = base(ctx)
        val title = sp.getString("w_title", "") ?: ""
        val artist = sp.getString("w_artist", "") ?: ""
        val playing = sp.getBoolean("w_playing", false)
        rv.setTextViewText(R.id.w_title, if (title.isNotEmpty()) title else "CYA")
        rv.setTextViewText(R.id.w_sub, if (title.isNotEmpty()) artist else "Toca para elegir música")
        rv.setViewVisibility(R.id.w_extra, View.GONE)
        rv.setViewVisibility(R.id.w_controls, View.VISIBLE)
        rv.setViewVisibility(R.id.w_art, View.VISIBLE)
        rv.setImageViewResource(R.id.w_play, if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
        rv.setOnClickPendingIntent(R.id.w_prev, act(ctx, WidgetActions.PREV, 1))
        rv.setOnClickPendingIntent(R.id.w_play, act(ctx, WidgetActions.PLAY, 2))
        rv.setOnClickPendingIntent(R.id.w_next, act(ctx, WidgetActions.NEXT, 3))
        val bmp = art(ctx, sp.getString("w_art", "") ?: "")
        if (bmp != null) rv.setImageViewBitmap(R.id.w_art, bmp)
        else rv.setImageViewResource(R.id.w_art, R.drawable.cya_widget_bg)
        return rv
    }

    private fun statsViews(ctx: Context, sp: SharedPreferences): RemoteViews {
        val rv = base(ctx)
        val today = CyaStats.secondsOn(ctx, 0)
        val streak = CyaStats.streak(ctx)
        val top = sp.getString("w_top", "") ?: ""
        rv.setTextViewText(R.id.w_title, "Hoy: " + CyaStats.durText(today))
        rv.setTextViewText(
            R.id.w_sub,
            if (streak > 0) "🔥 Racha de $streak " + (if (streak == 1) "día" else "días") else "Escucha hoy para iniciar tu racha"
        )
        rv.setTextViewText(
            R.id.w_extra,
            "Total: " + CyaStats.durText(CyaStats.total(ctx)) + (if (top.isNotEmpty()) " · Top: $top" else "")
        )
        rv.setViewVisibility(R.id.w_extra, View.VISIBLE)
        rv.setViewVisibility(R.id.w_controls, View.GONE)
        rv.setViewVisibility(R.id.w_art, View.GONE)
        return rv
    }

    /** Carga la portada (máx. 192 px, con esquinas redondeadas). */
    private fun art(ctx: Context, uri: String): Bitmap? {
        if (uri.isEmpty()) return null
        if (uri == artKey) return artBmp
        var out: Bitmap? = null
        try {
            val u = Uri.parse(uri)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 256 && bounds.outHeight / (sample * 2) >= 256) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val raw = ctx.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, opts) }
            if (raw != null) out = round(raw)
        } catch (e: Exception) { }
        artKey = uri
        artBmp = out
        return out
    }

    private fun round(src: Bitmap): Bitmap {
        val size = 192
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        c.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), 28f, 28f, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        val s = minOf(src.width, src.height)
        val x = (src.width - s) / 2
        val y = (src.height - s) / 2
        c.drawBitmap(src, Rect(x, y, x + s, y + s), Rect(0, 0, size, size), paint)
        return out
    }
}

class PlayerWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        CyaWidgets.refresh(context)
    }
    override fun onEnabled(context: Context) { CyaWidgets.refresh(context) }
}

class StatsWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        CyaWidgets.refresh(context)
    }
    override fun onEnabled(context: Context) { CyaWidgets.refresh(context) }
}

/** Recibe los toques de ⏮ ⏯ ⏭ del widget y los aplica al reproductor. */
class WidgetActions : BroadcastReceiver() {
    companion object {
        const val PLAY = "com.cris.music.W_PLAY"
        const val NEXT = "com.cris.music.W_NEXT"
        const val PREV = "com.cris.music.W_PREV"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val p = PlaybackService.player
        if (p == null) {
            context.getSharedPreferences("cris", Context.MODE_PRIVATE).edit().putBoolean("w_playing", false).apply()
            CyaWidgets.refresh(context)
            return
        }
        when (intent.action) {
            PLAY -> if (p.isPlaying) p.pause() else p.play()
            NEXT -> p.seekToNextMediaItem()
            PREV -> p.seekToPreviousMediaItem()
        }
    }
}
