package com.hyouka.quasarplayer.ui.playing

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.hyouka.quasarplayer.playback.PlayerController
import com.hyouka.quasarplayer.ui.common.TrackArt

@Composable
fun PlayingScreen(playerController: PlayerController) {
    val state by playerController.state.collectAsState()
    val track = state.currentTrack

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Header (Displays playlist name on top if active)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Now Playing",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!state.activePlaylistName.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "(${state.activePlaylistName})",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.basicMarquee()
                )
            }
        }

        // Large Album Art Area
        TrackArt(
            track = track,
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .aspectRatio(1f),
            iconSize = 96.dp
        )

        // Track Info with Marquee Scrolling Title
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = track?.title ?: "No Track Selected",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                modifier = Modifier.basicMarquee()
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = track?.artist ?: "Unknown Artist",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }

        // Seek Bar & Time Display
        Column(modifier = Modifier.fillMaxWidth()) {
            var sliderPosition by remember { mutableFloatStateOf(0f) }
            var isUserSeeking by remember { mutableStateOf(false) }

            val currentPos = state.currentPositionMs.toFloat()
            val totalDuration = state.durationMs.coerceAtLeast(1L).toFloat()

            val displayPos = if (isUserSeeking) sliderPosition else currentPos

            Slider(
                value = displayPos.coerceIn(0f, totalDuration),
                onValueChange = { pos ->
                    isUserSeeking = true
                    sliderPosition = pos
                },
                onValueChangeFinished = {
                    playerController.seekTo(sliderPosition.toLong())
                    isUserSeeking = false
                },
                valueRange = 0f..totalDuration,
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatMs(displayPos.toLong()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = formatMs(state.durationMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Transport Controls Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Shuffle Button
            IconButton(onClick = { playerController.toggleShuffle() }) {
                Icon(
                    imageVector = Icons.Default.Shuffle,
                    contentDescription = "Shuffle",
                    tint = if (state.shuffleModeEnabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Skip Previous Button
            IconButton(onClick = { playerController.skipToPrevious() }) {
                Icon(
                    imageVector = Icons.Default.SkipPrevious,
                    contentDescription = "Previous",
                    modifier = Modifier.size(36.dp)
                )
            }

            // Play / Pause Floating Action Button
            FloatingActionButton(
                onClick = { playerController.togglePlayPause() },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Icon(
                    imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (state.isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(36.dp)
                )
            }

            // Skip Next Button
            IconButton(onClick = { playerController.skipToNext() }) {
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = "Next",
                    modifier = Modifier.size(36.dp)
                )
            }

            // Repeat Button
            IconButton(onClick = { playerController.toggleRepeatMode() }) {
                val (icon, tint) = when (state.repeatMode) {
                    Player.REPEAT_MODE_ONE -> Pair(Icons.Default.RepeatOne, MaterialTheme.colorScheme.primary)
                    Player.REPEAT_MODE_ALL -> Pair(Icons.Default.Repeat, MaterialTheme.colorScheme.primary)
                    else -> Pair(Icons.Default.Repeat, MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(
                    imageVector = icon,
                    contentDescription = "Repeat",
                    tint = tint
                )
            }
        }
    }
}

private fun formatMs(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
