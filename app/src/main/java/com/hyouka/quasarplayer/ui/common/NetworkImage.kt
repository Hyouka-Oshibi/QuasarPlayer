package com.hyouka.quasarplayer.ui.common

import android.graphics.BitmapFactory
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

private val networkClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(10, TimeUnit.SECONDS)
    .followRedirects(true)
    .build()

@Composable
fun NetworkImage(
    url: String?,
    modifier: Modifier = Modifier,
    iconSize: Dp = 24.dp
) {
    val bitmapState = produceState<ImageBitmap?>(initialValue = null, key1 = url) {
        value = if (url.isNullOrEmpty()) {
            null
        } else {
            withContext(Dispatchers.IO) {
                try {
                    val request = Request.Builder()
                        .url(url)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                        .build()
                    val response = networkClient.newCall(request).execute()
                    if (response.isSuccessful && response.body != null) {
                        val bytes = response.body!!.bytes()
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                    } else {
                        null
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
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = "Thumbnail",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = Icons.Default.MusicNote,
                contentDescription = null,
                modifier = Modifier.size(iconSize),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
