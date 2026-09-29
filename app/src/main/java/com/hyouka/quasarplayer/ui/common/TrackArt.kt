package com.hyouka.quasarplayer.ui.common

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
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
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.hyouka.quasarplayer.data.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val embeddedArtCache = LruCache<String, ImageBitmap>(100)

@Composable
fun TrackArt(
    track: Track?,
    modifier: Modifier = Modifier,
    iconSize: Dp = 48.dp
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (track == null) {
            Icon(
                imageVector = Icons.Default.MusicNote,
                contentDescription = "Default Art",
                modifier = Modifier.size(iconSize),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@Box
        }

        if (!track.artworkPath.isNullOrEmpty() && File(track.artworkPath).exists()) {
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(File(track.artworkPath))
                    .crossfade(true)
                    .build(),
                contentDescription = track.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                error = {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = "Default Art",
                        modifier = Modifier.size(iconSize),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            )
        } else if (track.hasEmbeddedArt && !track.uri.isNullOrEmpty()) {
            val context = LocalContext.current
            val bitmapState = produceState<ImageBitmap?>(initialValue = embeddedArtCache.get(track.id), key1 = track.id) {
                if (value == null) {
                    value = withContext(Dispatchers.IO) {
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
                                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                                if (bmp != null) embeddedArtCache.put(track.id, bmp)
                                bmp
                            }
                        } catch (e: Exception) {
                            null
                        }
                    }
                }
            }

            if (bitmapState.value != null) {
                Image(
                    bitmap = bitmapState.value!!,
                    contentDescription = track.title,
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
