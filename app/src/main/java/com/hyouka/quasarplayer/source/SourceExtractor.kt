package com.hyouka.quasarplayer.source

import android.content.Context
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

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

object MetadataCleaner {
    fun cleanChannelName(rawChannel: String?): String {
        if (rawChannel.isNullOrBlank()) return "Unknown Artist"
        return rawChannel
            .replace(Regex("(?i)\\s*-\\s*Topic$"), "")
            .replace(Regex("(?i)VEVO$"), "")
            .trim()
            .ifBlank { "Unknown Artist" }
    }

    fun cleanTitle(rawTitle: String): String {
        var title = rawTitle
            .replace(Regex("(?i)[\\[\\(]\\s*(official\\s+(music\\s+)?video|official\\s+audio|lyric\\s+video|audio|video|hd|4k|mv|full\\s+song)\\s*[\\]\\)]"), "")
            .replace(Regex("(?i)\\|\\s*official\\s+(music\\s+)?video.*"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        return if (title.isBlank()) rawTitle else title
    }
}

class SourceExtractor(private val context: Context) {

    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val cache = ConcurrentHashMap<String, List<SearchResult>>()
    private val scope = CoroutineScope(Dispatchers.IO)

    init {
        try {
            YoutubeDL.getInstance().init(context.applicationContext)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun parseDirectUrlInstant(url: String): SearchResult? {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return null

        // 1. Check YouTube Video ID
        val ytPatterns = listOf(
            Regex("(?:youtube\\.com\\/watch\\?v=|youtu\\.be\\/|youtube\\.com\\/shorts\\/)([A-Za-z0-9_-]{11})"),
            Regex("youtube\\.com\\/embed\\/([A-Za-z0-9_-]{11})")
        )

        for (pattern in ytPatterns) {
            val match = pattern.find(trimmed)
            if (match != null) {
                val videoId = match.groupValues[1]
                return SearchResult(
                    id = videoId,
                    title = "Direct Video ($videoId)",
                    uploader = "YouTube Direct Link",
                    durationMs = 0L,
                    thumbnailUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
                    source = SourceType.YOUTUBE,
                    webpageUrl = "https://www.youtube.com/watch?v=$videoId"
                )
            }
        }

        // 2. Check SoundCloud Link
        if (trimmed.contains("soundcloud.com/")) {
            val pathSegments = trimmed.split("soundcloud.com/").getOrNull(1)?.split("?")?.getOrNull(0)?.split("/") ?: emptyList()
            val artist = pathSegments.getOrNull(0)?.replace("-", " ")?.replaceFirstChar { it.uppercase() } ?: "SoundCloud Artist"
            val trackTitle = pathSegments.getOrNull(1)?.replace("-", " ")?.replaceFirstChar { it.uppercase() } ?: "SoundCloud Track"
            val cleanId = (pathSegments.getOrNull(1) ?: trimmed).hashCode().toString()

            return SearchResult(
                id = cleanId,
                title = if (pathSegments.size >= 2) trackTitle else "SoundCloud Link",
                uploader = artist,
                durationMs = 0L,
                thumbnailUrl = null,
                source = SourceType.SOUNDCLOUD,
                webpageUrl = trimmed
            )
        }

        return null
    }

    suspend fun search(query: String, source: SourceType): List<SearchResult> = withContext(Dispatchers.IO) {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isEmpty()) return@withContext emptyList()

        val cacheKey = "$source:$trimmedQuery"
        cache[cacheKey]?.let { return@withContext it }

        // Fast Instant Resolution for Direct URLs (0ms)
        val instantResult = parseDirectUrlInstant(trimmedQuery)
        if (instantResult != null) {
            val instantList = listOf(instantResult)
            cache[cacheKey] = instantList

            // Asynchronously fetch rich metadata in background without blocking UI
            scope.launch {
                fetchFullMetadataForUrl(trimmedQuery, source, cacheKey)
            }
            return@withContext instantList
        }

        val isDirectUrl = trimmedQuery.startsWith("http://") || trimmedQuery.startsWith("https://")
        val searchPrefix = if (isDirectUrl) "" else when (source) {
            SourceType.YOUTUBE -> "ytsearch5:"
            SourceType.SOUNDCLOUD -> "scsearch5:"
        }

        val request = YoutubeDLRequest(if (isDirectUrl) trimmedQuery else "$searchPrefix$trimmedQuery").apply {
            addOption("--dump-json")
            addOption("--no-playlist")
            addOption("--skip-download")
            addOption("--no-warnings")
            addOption("--socket-timeout", "5")
            if (source == SourceType.YOUTUBE) {
                addOption("--extractor-args", "youtube:player_client=android")
            }
        }

        val results = mutableListOf<SearchResult>()
        try {
            val response = YoutubeDL.getInstance().execute(request)
            val jsonLines = response.out.lines().filter { it.isNotBlank() }

            for (line in jsonLines) {
                try {
                    val mapAdapter = moshi.adapter(Map::class.java)
                    val map = mapAdapter.fromJson(line) as? Map<*, *> ?: continue

                    // Skip live streams
                    val isLive = map["is_live"] as? Boolean == true || map["was_live"] as? Boolean == true
                    if (isLive) continue

                    val id = map["id"]?.toString() ?: continue
                    val rawTitle = map["title"]?.toString() ?: "Unknown Title"
                    val rawChannel = map["channel"]?.toString()
                        ?: map["uploader"]?.toString()
                        ?: map["artist"]?.toString()
                        ?: map["creator"]?.toString()

                    val cleanedTitle = MetadataCleaner.cleanTitle(rawTitle)
                    val cleanedArtist = MetadataCleaner.cleanChannelName(rawChannel)

                    val durationSec = (map["duration"] as? Number)?.toDouble() ?: 0.0
                    val durationMs = (durationSec * 1000).toLong()

                    val thumbnails = map["thumbnails"] as? List<*>
                    val thumbnailUrl = (thumbnails?.lastOrNull() as? Map<*, *>)?.get("url")?.toString()
                        ?: map["thumbnail"]?.toString()
                        ?: if (source == SourceType.YOUTUBE) "https://i.ytimg.com/vi/$id/hqdefault.jpg" else null

                    val url = map["webpage_url"]?.toString()
                        ?: map["url"]?.toString()
                        ?: if (source == SourceType.YOUTUBE) "https://www.youtube.com/watch?v=$id" else trimmedQuery

                    results.add(
                        SearchResult(
                            id = id,
                            title = cleanedTitle,
                            uploader = cleanedArtist,
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

        if (results.isNotEmpty()) {
            cache[cacheKey] = results
        }

        results
    }

    private fun fetchFullMetadataForUrl(url: String, source: SourceType, cacheKey: String) {
        try {
            val request = YoutubeDLRequest(url).apply {
                addOption("--dump-json")
                addOption("--no-playlist")
                addOption("--skip-download")
                addOption("--no-warnings")
                addOption("--socket-timeout", "5")
                if (source == SourceType.YOUTUBE) {
                    addOption("--extractor-args", "youtube:player_client=android")
                }
            }

            val response = YoutubeDL.getInstance().execute(request)
            val line = response.out.lines().firstOrNull { it.isNotBlank() } ?: return
            val mapAdapter = moshi.adapter(Map::class.java)
            val map = mapAdapter.fromJson(line) as? Map<*, *> ?: return

            val id = map["id"]?.toString() ?: return
            val rawTitle = map["title"]?.toString() ?: "Unknown Title"
            val rawChannel = map["channel"]?.toString()
                ?: map["uploader"]?.toString()
                ?: map["artist"]?.toString()
                ?: map["creator"]?.toString()

            val cleanedTitle = MetadataCleaner.cleanTitle(rawTitle)
            val cleanedArtist = MetadataCleaner.cleanChannelName(rawChannel)

            val durationSec = (map["duration"] as? Number)?.toDouble() ?: 0.0
            val durationMs = (durationSec * 1000).toLong()

            val thumbnails = map["thumbnails"] as? List<*>
            val thumbnailUrl = (thumbnails?.lastOrNull() as? Map<*, *>)?.get("url")?.toString()
                ?: map["thumbnail"]?.toString()
                ?: if (source == SourceType.YOUTUBE) "https://i.ytimg.com/vi/$id/hqdefault.jpg" else null

            val result = SearchResult(
                id = id,
                title = cleanedTitle,
                uploader = cleanedArtist,
                durationMs = durationMs,
                thumbnailUrl = thumbnailUrl,
                source = source,
                webpageUrl = url
            )

            cache[cacheKey] = listOf(result)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun getStreamInfo(result: SearchResult): StreamInfo? = withContext(Dispatchers.IO) {
        val request = YoutubeDLRequest(result.webpageUrl).apply {
            addOption("-f", "bestaudio[ext=m4a]/bestaudio/best")
            addOption("--get-url")
            addOption("--no-warnings")
            addOption("--socket-timeout", "5")
            if (result.source == SourceType.YOUTUBE) {
                addOption("--extractor-args", "youtube:player_client=android")
            }
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
