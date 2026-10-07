package com.hyouka.quasarplayer.ui.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.hyouka.quasarplayer.source.SearchResult
import com.hyouka.quasarplayer.source.SourceExtractor
import com.hyouka.quasarplayer.source.StreamInfo
import com.hyouka.quasarplayer.ui.common.NetworkImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.hyouka.quasarplayer.playback.PlayerController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewSheet(
    searchResult: SearchResult,
    sourceExtractor: SourceExtractor,
    playerController: PlayerController,
    onDismiss: () -> Unit,
    onDownloadRequested: (SearchResult) -> Unit
) {
    val context = LocalContext.current

    var streamInfo by remember { mutableStateOf<StreamInfo?>(null) }
    var isLoadingStream by remember { mutableStateOf(true) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentPositionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(searchResult.durationMs) }

    var exoPlayer by remember { mutableStateOf<ExoPlayer?>(null) }
    var wasPlayingMain by remember { mutableStateOf(false) }

    // Resolve stream info and setup throwaway ExoPlayer
    LaunchedEffect(searchResult) {
        withContext(Dispatchers.IO) {
            val resolved = sourceExtractor.getStreamInfo(searchResult)
            withContext(Dispatchers.Main) {
                streamInfo = resolved
                isLoadingStream = false
                if (resolved != null) {
                    wasPlayingMain = playerController.state.value.isPlaying
                    if (wasPlayingMain) {
                        playerController.pause()
                    }
                    val player = ExoPlayer.Builder(context).build()
                    exoPlayer = player
                    val mediaItem = MediaItem.fromUri(resolved.audioStreamUrl)
                    player.setMediaItem(mediaItem)
                    player.prepare()
                    player.play()
                    isPlaying = true

                    player.addListener(object : Player.Listener {
                        override fun onIsPlayingChanged(playing: Boolean) {
                            isPlaying = playing
                        }
                        override fun onPlaybackStateChanged(state: Int) {
                            if (state == Player.STATE_READY) {
                                durationMs = player.duration.coerceAtLeast(0L)
                            }
                        }
                    })
                }
            }
        }
    }

    // Ticker for position
    LaunchedEffect(exoPlayer, isPlaying) {
        val player = exoPlayer ?: return@LaunchedEffect
        while (isPlaying) {
            currentPositionMs = player.currentPosition.coerceAtLeast(0L)
            delay(500L)
        }
    }

    // Clean up throwaway player when sheet is dismissed
    DisposableEffect(Unit) {
        onDispose {
            exoPlayer?.stop()
            exoPlayer?.release()
            exoPlayer = null
            if (wasPlayingMain) {
                playerController.play()
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Preview Track",
                style = MaterialTheme.typography.titleLarge
            )

            Spacer(modifier = Modifier.height(16.dp))

            NetworkImage(
                url = searchResult.thumbnailUrl,
                modifier = Modifier
                    .size(120.dp)
                    .clip(RoundedCornerShape(12.dp)),
                iconSize = 48.dp
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = searchResult.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2
            )
            Text(
                text = searchResult.uploader ?: "Unknown Artist",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Download Button (Available immediately!)
            Button(
                onClick = {
                    onDownloadRequested(searchResult)
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Download, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Download to Library")
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (isLoadingStream) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("Resolving audio stream...", style = MaterialTheme.typography.bodySmall)
                }
            } else if (streamInfo == null) {
                Text(
                    "Could not extract playable audio preview stream.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                // Play / Pause & Seek
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    IconButton(
                        onClick = {
                            val player = exoPlayer ?: return@IconButton
                            if (isPlaying) player.pause() else player.play()
                        }
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Toggle Preview"
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    val totalDur = durationMs.coerceAtLeast(1L).toFloat()
                    Slider(
                        value = currentPositionMs.toFloat().coerceIn(0f, totalDur),
                        onValueChange = { pos ->
                            currentPositionMs = pos.toLong()
                            exoPlayer?.seekTo(pos.toLong())
                        },
                        valueRange = 0f..totalDur,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
