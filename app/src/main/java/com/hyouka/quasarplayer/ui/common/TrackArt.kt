package com.hyouka.quasarplayer.ui.common

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hyouka.quasarplayer.data.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun TrackArt(
    track: Track?,
    modifier: Modifier = Modifier,
    iconSize: Dp = 48.dp
) {
    val context = LocalContext.current
    val bitmapState = produceState<ImageBitmap?>(initialValue = null, key1 = track?.id ?: track?.uri) {
        value = withContext(Dispatchers.IO) {
            if (track != null) {
                // 1. Try local artwork file saved directly during download
                if (!track.artworkPath.isNullOrEmpty()) {
                    val artFile = File(track.artworkPath)
                    if (artFile.exists()) {
                        val bmp = BitmapFactory.decodeFile(artFile.absolutePath)
                        if (bmp != null) return@withContext bmp.asImageBitmap()
                    }
                }

                // 2. Fallback to MediaMetadataRetriever ID3 APIC embedded frame
                if (!track.uri.isNullOrEmpty()) {
                    try {
                        val retriever = MediaMetadataRetriever()
                        val uri = Uri.parse(track.uri)
                        if (uri.scheme == "content" || uri.scheme == "file") {
                            retriever.setDataSource(context, uri)
                        } else {
                            retriever.setDataSource(track.uri)
                        }
                        val artBytes = retriever.embeddedPicture
                        retriever.release()
                        artBytes?.let { bytes ->
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                        }
                    } catch (e: Exception) {
                        null
                    }
                } else {
                    null
                }
            } else {
                null
            }
        }
    }

    val bitmap = bitmapState.value

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = track?.title ?: "Album Art",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = Icons.Default.MusicNote,
                contentDescription = "Default Art",
                modifier = Modifier.size(iconSize),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
