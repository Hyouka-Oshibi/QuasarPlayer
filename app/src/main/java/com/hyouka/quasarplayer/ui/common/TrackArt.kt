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

@Composable
fun TrackArt(
    track: Track?,
    modifier: Modifier = Modifier,
    iconSize: Dp = 48.dp
) {
    val context = LocalContext.current
    val bitmapState = produceState<ImageBitmap?>(initialValue = null, key1 = track?.uri) {
        value = if (track?.uri.isNullOrEmpty()) {
            null
        } else {
            withContext(Dispatchers.IO) {
                try {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(context, Uri.parse(track!!.uri))
                    val artBytes = retriever.embeddedPicture
                    retriever.release()
                    artBytes?.let { bytes ->
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                    }
                } catch (e: Exception) {
                    null
                }
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
