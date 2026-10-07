package com.hyouka.quasarplayer.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.hyouka.quasarplayer.ui.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var sleepModeEnabled = false
    private var sleepModeEndTimestamp = 0L

    override fun onCreate() {
        super.onCreate()

        val dataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(this)
        val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(this)
            .setDataSourceFactory(dataSourceFactory)

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(androidx.media3.common.C.WAKE_MODE_LOCAL)
            .build()

        val audioOffloadPreferences = androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences.Builder()
            .setAudioOffloadMode(androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED)
            .setIsGaplessSupportRequired(true)
            .build()

        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setAudioOffloadPreferences(audioOffloadPreferences)
            .build()

        val intent = android.content.Intent(this, com.hyouka.quasarplayer.MainActivity::class.java).apply {
            flags = android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = android.app.PendingIntent.getActivity(
            this,
            0,
            intent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(pendingIntent)
            .setCallback(MediaSessionCallback())
            .build()

        val settingsRepo = SettingsRepository(this@PlaybackService)

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (sleepModeEnabled) {
                    val now = System.currentTimeMillis()
                    if (now >= sleepModeEndTimestamp) {
                        player.pause()
                        CoroutineScope(Dispatchers.IO).launch {
                            settingsRepo.setSleepModeEnabled(false)
                        }
                    }
                }
            }
        })

        CoroutineScope(Dispatchers.IO).launch {
            launch {
                settingsRepo.sleepModeEnabledFlow.collect { enabled ->
                    sleepModeEnabled = enabled
                }
            }
            launch {
                settingsRepo.sleepModeEndTimestampFlow.collect { timestamp ->
                    sleepModeEndTimestamp = timestamp
                }
            }
            val loop = settingsRepo.loopDefaultFlow.first()
            val shuffle = settingsRepo.shuffleDefaultFlow.first()
            withContext(Dispatchers.Main) {
                player.repeatMode = loop
                player.shuffleModeEnabled = shuffle
            }
        }
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
