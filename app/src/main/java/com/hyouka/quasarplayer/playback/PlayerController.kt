package com.hyouka.quasarplayer.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.hyouka.quasarplayer.data.Track
import com.hyouka.quasarplayer.ui.settings.SettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

data class PlayerState(
    val currentTrack: Track? = null,
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffleModeEnabled: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val queue: List<Track> = emptyList(),
    val activePlaylistName: String? = null
)

class PlayerController(private val context: Context) {
    
    private val settingsRepository = SettingsRepository(context)

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null

    private var positionUpdateJob: Job? = null
    private var activeQueueTracks: List<Track> = emptyList()
    private var activePlaylistName: String? = null

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(isPlaying = isPlaying) }
            if (isPlaying) {
                startPositionTicker()
            } else {
                stopPositionTicker()
            }
            updateStateFromController()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            updateStateFromController()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            updateStateFromController()
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            _state.update { it.copy(repeatMode = repeatMode) }
            scope.launch { settingsRepository.setLoopDefault(repeatMode) }
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            _state.update { it.copy(shuffleModeEnabled = shuffleModeEnabled) }
            scope.launch { settingsRepository.setShuffleDefault(shuffleModeEnabled) }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e("PlayerController", "Playback Error: ${error.message}", error)
        }
    }

    init {
        connect()
    }

    private fun connect() {
        val sessionToken = SessionToken(
            context,
            ComponentName(context, PlaybackService::class.java)
        )
        controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture?.addListener({
            try {
                val controller = controllerFuture?.get()
                mediaController = controller
                controller?.addListener(playerListener)
                updateStateFromController()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, MoreExecutors.directExecutor())
    }

    private fun updateStateFromController() {
        val controller = mediaController ?: return
        val currentMediaItem = controller.currentMediaItem
        val currentMediaId = currentMediaItem?.mediaId

        val currentTrack = activeQueueTracks.find { it.id == currentMediaId }
            ?: currentMediaItem?.let { item ->
                Track(
                    id = item.mediaId,
                    uri = item.requestMetadata.mediaUri?.toString() ?: "",
                    title = item.mediaMetadata.title?.toString() ?: "Unknown Title",
                    artist = item.mediaMetadata.artist?.toString(),
                    durationMs = controller.duration.coerceAtLeast(0L)
                )
            }

        _state.update {
            it.copy(
                currentTrack = currentTrack,
                isPlaying = controller.isPlaying,
                currentPositionMs = controller.currentPosition.coerceAtLeast(0L),
                durationMs = controller.duration.coerceAtLeast(0L),
                shuffleModeEnabled = controller.shuffleModeEnabled,
                repeatMode = controller.repeatMode,
                queue = activeQueueTracks,
                activePlaylistName = activePlaylistName
            )
        }
    }

    private fun startPositionTicker() {
        positionUpdateJob?.cancel()
        positionUpdateJob = scope.launch {
            while (isActive) {
                mediaController?.let { controller ->
                    if (controller.isPlaying) {
                        _state.update {
                            it.copy(
                                currentPositionMs = controller.currentPosition.coerceAtLeast(0L),
                                durationMs = controller.duration.coerceAtLeast(0L)
                            )
                        }
                    }
                }
                delay(500L)
            }
        }
    }

    private fun stopPositionTicker() {
        positionUpdateJob?.cancel()
        positionUpdateJob = null
    }

    fun playTrackList(tracks: List<Track>, startIndex: Int = 0, playlistName: String? = null) {
        val controller = mediaController ?: return
        if (tracks.isEmpty()) return
        activeQueueTracks = tracks
        activePlaylistName = playlistName

        val mediaItems = tracks.map { track ->
            val uri = parseUri(track.uri)
            val metadataBuilder = MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(track.artist ?: "Unknown Artist")
            if (!track.artworkPath.isNullOrBlank()) {
                val artFile = File(track.artworkPath)
                if (artFile.exists() && artFile.length() > 0) {
                    metadataBuilder.setArtworkUri(Uri.fromFile(artFile))
                }
            }
            val metadata = metadataBuilder.build()

            MediaItem.Builder()
                .setMediaId(track.id)
                .setUri(uri)
                .setMediaMetadata(metadata)
                .build()
        }

        val index = startIndex.coerceIn(0, mediaItems.lastIndex)
        controller.setMediaItems(mediaItems, index, 0L)
        controller.prepare()
        controller.play()
        updateStateFromController()
    }

    private fun parseUri(raw: String): Uri {
        val trimmed = raw.trim()
        return when {
            trimmed.startsWith("content://") || trimmed.startsWith("file://") || trimmed.startsWith("http://") || trimmed.startsWith("https://") -> Uri.parse(trimmed)
            trimmed.startsWith("/") -> Uri.fromFile(File(trimmed))
            else -> Uri.parse(trimmed)
        }
    }

    fun play() {
        mediaController?.play()
    }

    fun pause() {
        mediaController?.pause()
    }

    fun togglePlayPause() {
        val controller = mediaController ?: return
        if (controller.isPlaying) {
            controller.pause()
        } else {
            controller.play()
        }
    }

    fun seekTo(positionMs: Long) {
        mediaController?.seekTo(positionMs)
        _state.update { it.copy(currentPositionMs = positionMs) }
    }

    fun skipToNext() {
        val controller = mediaController ?: return
        if (controller.hasNextMediaItem()) {
            controller.seekToNextMediaItem()
        }
    }

    fun skipToPrevious() {
        val controller = mediaController ?: return
        if (controller.hasPreviousMediaItem()) {
            controller.seekToPreviousMediaItem()
        } else {
            controller.seekTo(0L)
        }
    }

    fun toggleShuffle() {
        val controller = mediaController ?: return
        val nextShuffle = !controller.shuffleModeEnabled
        controller.shuffleModeEnabled = nextShuffle
        _state.update { it.copy(shuffleModeEnabled = nextShuffle) }
    }

    fun toggleRepeatMode() {
        val controller = mediaController ?: return
        val nextMode = when (controller.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        controller.repeatMode = nextMode
        _state.update { it.copy(repeatMode = nextMode) }
    }

    fun setRepeatMode(mode: Int) {
        mediaController?.repeatMode = mode
        _state.update { it.copy(repeatMode = mode) }
    }

    fun setShuffleMode(enabled: Boolean) {
        mediaController?.shuffleModeEnabled = enabled
        _state.update { it.copy(shuffleModeEnabled = enabled) }
    }

    fun release() {
        stopPositionTicker()
        mediaController?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        scope.cancel()
    }
}
