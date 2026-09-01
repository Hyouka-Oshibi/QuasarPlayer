package com.hyouka.quasarplayer.source

import android.content.Context
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

enum class SourceType {
    YOUTUBE, SOUNDCLOUD
}

data class SearchResult(
    val id: String,
    val title: String,
    val uploader: String?,
    val durationMs: Long,
    val thumbnailUrl: String?,
    val source: SourceType,
    val webpageUrl: String
)

data class StreamInfo(
    val audioStreamUrl: String,
    val title: String,
    val uploader: String?,
    val durationMs: Long,
    val thumbnailUrl: String?,
    val webpageUrl: String
)

class SourceExtractor(private val context: Context) {

    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()

    init {
        try {
            YoutubeDL.getInstance().init(context.applicationContext)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun search(query: String, source: SourceType): List<SearchResult> = withContext(Dispatchers.IO) {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isEmpty()) return@withContext emptyList()

        val isDirectUrl = trimmedQuery.startsWith("http://") || trimmedQuery.startsWith("https://")
        val searchPrefix = if (isDirectUrl) "" else when (source) {
            SourceType.YOUTUBE -> "ytsearch5:"
            SourceType.SOUNDCLOUD -> "scsearch5:"
        }

        val request = YoutubeDLRequest(if (isDirectUrl) trimmedQuery else "$searchPrefix$trimmedQuery").apply {
            addOption("--dump-json")
            addOption("--flat-playlist")
            addOption("--skip-download")
            addOption("--no-warnings")
        }

        val results = mutableListOf<SearchResult>()
        try {
            val response = YoutubeDL.getInstance().execute(request)
            val jsonLines = response.out.lines().filter { it.isNotBlank() }

            for (line in jsonLines) {
                try {
                    val mapAdapter = moshi.adapter(Map::class.java)
                    val map = mapAdapter.fromJson(line) as? Map<*, *> ?: continue

                    val id = map["id"]?.toString() ?: continue
                    val title = map["title"]?.toString() ?: "Unknown Title"
                    val uploader = map["uploader"]?.toString() ?: map["channel"]?.toString()
                    val durationSec = (map["duration"] as? Number)?.toDouble() ?: 0.0
                    val durationMs = (durationSec * 1000).toLong()

                    val thumbnails = map["thumbnails"] as? List<*>
                    val thumbnailUrl = (thumbnails?.lastOrNull() as? Map<*, *>)?.get("url")?.toString()
                        ?: map["thumbnail"]?.toString()

                    val url = map["webpage_url"]?.toString()
                        ?: map["url"]?.toString()
                        ?: if (source == SourceType.YOUTUBE) "https://www.youtube.com/watch?v=$id" else trimmedQuery

                    results.add(
                        SearchResult(
                            id = id,
                            title = title,
                            uploader = uploader,
                            durationMs = durationMs,
                            thumbnailUrl = thumbnailUrl,
                            source = source,
                            webpageUrl = url
                        )
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        results
    }

    suspend fun getStreamInfo(result: SearchResult): StreamInfo? = withContext(Dispatchers.IO) {
        val request = YoutubeDLRequest(result.webpageUrl).apply {
            addOption("-f", "bestaudio/best")
            addOption("--get-url")
            addOption("--no-warnings")
        }

        try {
            val response = YoutubeDL.getInstance().execute(request)
            val streamUrl = response.out.lines().firstOrNull { it.isNotBlank() } ?: return@withContext null
            StreamInfo(
                audioStreamUrl = streamUrl,
                title = result.title,
                uploader = result.uploader,
                durationMs = result.durationMs,
                thumbnailUrl = result.thumbnailUrl,
                webpageUrl = result.webpageUrl
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
