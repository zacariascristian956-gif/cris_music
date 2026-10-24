package com.cris.music

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class PlaybackService : MediaSessionService() {
    companion object {
        /** Lo usan los botones del widget para controlar la reproducción. */
        @Volatile var player: Player? = null
    }

    private var session: MediaSession? = null
    private val handler = Handler(Looper.getMainLooper())
    private var curSec = 0
    private var counted = false
    private var ticks = 0

    // Cada segundo que suena música: suma tiempo a las estadísticas y cuenta la reproducción.
    private val tick = object : Runnable {
        override fun run() {
            val p = session?.player ?: return
            if (!p.isPlaying) return
            CyaStats.addSeconds(applicationContext, 1)
            curSec++
            if (!counted && curSec.toLong() >= needSeconds(p)) {
                counted = true
                val id = p.currentMediaItem?.mediaId ?: ""
                if (id.isNotEmpty()) {
                    val sp = getSharedPreferences("cris", Context.MODE_PRIVATE)
                    sp.edit().putInt("p_$id", sp.getInt("p_$id", 0) + 1).apply()
                }
            }
            ticks++
            if (ticks % 60 == 0) CyaWidgets.refresh(applicationContext)
            handler.postDelayed(this, 1000)
        }
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            handler.removeCallbacks(tick)
            if (isPlaying) handler.postDelayed(tick, 1000)
            saveNow()
            CyaWidgets.refresh(applicationContext)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            curSec = 0
            counted = false
            saveNow()
            CyaWidgets.refresh(applicationContext)
        }
    }

    private fun needSeconds(p: Player): Long {
        val d = if (p.duration > 0) p.duration / 1000 else 60L
        return minOf(30L, maxOf(5L, d / 2))
    }

    // Guarda lo que está sonando para que el widget lo pueda mostrar.
    private fun saveNow() {
        val p = session?.player ?: return
        val m = p.currentMediaItem?.mediaMetadata
        getSharedPreferences("cris", Context.MODE_PRIVATE).edit()
            .putString("w_title", m?.title?.toString() ?: "")
            .putString("w_artist", m?.artist?.toString() ?: "")
            .putString("w_art", m?.artworkUri?.toString() ?: "")
            .putBoolean("w_playing", p.isPlaying)
            .apply()
    }

    override fun onCreate() {
        super.onCreate()
        val exo = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        exo.addListener(listener)
        PlaybackService.player = exo
        session = MediaSession.Builder(this, exo).build()
        saveNow()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        PlaybackService.player = null
        getSharedPreferences("cris", Context.MODE_PRIVATE).edit().putBoolean("w_playing", false).apply()
        session?.let { it.player.release(); it.release() }
        session = null
        CyaWidgets.refresh(applicationContext)
        super.onDestroy()
    }
}
