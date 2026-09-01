package com.hyouka.quasarplayer.playback

import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Owns the single ExoPlayer instance for the whole app and exposes it via
 * a MediaSession. This is what keeps audio playing when the app is
 * backgrounded and drives lock-screen / notification controls — the UI
 * (now-playing screen, mini-player) is just a thin client that connects
 * a MediaController to this session, it never talks to ExoPlayer directly.
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        val player = ExoPlayer.Builder(this)
            .setHandleAudioBecomingNoisy(true) // pause on headphone unplug
            .build()

        // Repeat mode and shuffle are driven from Settings via the
        // MediaController rather than hardcoded here.
        player.repeatMode = Player.REPEAT_MODE_OFF

        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo
    ): MediaSession? = mediaSession

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }

    // Stop the service once playback finishes and the app task is swiped
    // away, rather than lingering as a silent foreground service.
    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        val player = mediaSession?.player ?: return
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }
}
