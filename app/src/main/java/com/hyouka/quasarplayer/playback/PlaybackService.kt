package com.hyouka.quasarplayer.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.File

/**
 * Owns the single ExoPlayer instance for the whole app and exposes it via
 * a MediaSession. This is what keeps audio playing when the app is
 * backgrounded and drives lock-screen / notification controls.
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        val player = ExoPlayer.Builder(this)
            .setHandleAudioBecomingNoisy(true)
            .build()

        player.repeatMode = Player.REPEAT_MODE_OFF

        mediaSession = MediaSession.Builder(this, player)
            .setCallback(MediaSessionCallback())
            .build()
    }

    private inner class MediaSessionCallback : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<List<MediaItem>> {
            val resolvedItems = mediaItems.map { item ->
                if (item.localConfiguration != null) {
                    item
                } else {
                    val uriString = item.requestMetadata.mediaUri?.toString() ?: item.mediaId
                    val uri = parseUri(uriString)
                    item.buildUpon()
                        .setUri(uri)
                        .build()
                }
            }
            return Futures.immediateFuture(resolvedItems)
        }
    }

    private fun parseUri(raw: String): Uri {
        val trimmed = raw.trim()
        return when {
            trimmed.startsWith("content://") || trimmed.startsWith("file://") || trimmed.startsWith("http://") || trimmed.startsWith("https://") -> Uri.parse(trimmed)
            trimmed.startsWith("/") -> Uri.fromFile(File(trimmed))
            else -> Uri.parse(trimmed)
        }
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

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        val player = mediaSession?.player ?: return
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }
}
