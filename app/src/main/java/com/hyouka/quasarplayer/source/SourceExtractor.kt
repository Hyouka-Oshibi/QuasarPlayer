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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
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
            com.yausername.ffmpeg.FFmpeg.getInstance().init(context.applicationContext)
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

    private suspend fun nativeYouTubeSearch(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        val client = okhttp3.OkHttpClient()
        val jsonBody = """
            {
                "context": {
                    "client": {
                        "clientName": "WEB",
                        "clientVersion": "2.20230810.05.00"
                    }
                },
                "query": "$query"
            }
        """.trimIndent()

        val request = okhttp3.Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/search?prettyPrint=false")
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()

        val results = mutableListOf<SearchResult>()
        try {
            val response = client.newCall(request).execute()
            val jsonResponse = response.body?.string() ?: return@withContext emptyList()
            
            val mapAdapter = moshi.adapter(Map::class.java)
            val map = mapAdapter.fromJson(jsonResponse) as? Map<*, *> ?: return@withContext emptyList()
            
            fun findVideoRenderers(node: Any?) {
                if (results.size >= 20) return
                if (node is Map<*, *>) {
                    if (node.containsKey("videoRenderer")) {
                        val renderer = node["videoRenderer"] as? Map<*, *>
                        if (renderer != null) {
                            try {
                                val id = renderer["videoId"]?.toString() ?: return
                                val titleRuns = (renderer["title"] as? Map<*, *>)?.get("runs") as? List<*>
                                val title = (titleRuns?.firstOrNull() as? Map<*, *>)?.get("text")?.toString() ?: "Unknown Title"
                                
                                val ownerRuns = (renderer["ownerText"] as? Map<*, *>)?.get("runs") as? List<*>
                                val channel = (ownerRuns?.firstOrNull() as? Map<*, *>)?.get("text")?.toString() ?: "Unknown Channel"
                                
                                val lengthText = (renderer["lengthText"] as? Map<*, *>)?.get("simpleText")?.toString()
                                var durationMs = 0L
                                if (lengthText != null) {
                                    val parts = lengthText.split(":")
                                    if (parts.size == 2) {
                                        durationMs = (parts[0].toLong() * 60 + parts[1].toLong()) * 1000
                                    } else if (parts.size == 3) {
                                        durationMs = (parts[0].toLong() * 3600 + parts[1].toLong() * 60 + parts[2].toLong()) * 1000
                                    }
                                }
                                
                                val thumbnails = (renderer["thumbnail"] as? Map<*, *>)?.get("thumbnails") as? List<*>
                                val thumbUrl = (thumbnails?.lastOrNull() as? Map<*, *>)?.get("url")?.toString()?.split("?")?.firstOrNull() ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg"

                                results.add(
                                    SearchResult(
                                        id = id,
                                        title = MetadataCleaner.cleanTitle(title),
                                        uploader = MetadataCleaner.cleanChannelName(channel),
                                        durationMs = durationMs,
                                        thumbnailUrl = thumbUrl,
                                        source = SourceType.YOUTUBE,
                                        webpageUrl = "https://www.youtube.com/watch?v=$id"
                                    )
                                )
                            } catch (e: Exception) {}
                        }
                    }
                    node.values.forEach { findVideoRenderers(it) }
                } else if (node is List<*>) {
                    node.forEach { findVideoRenderers(it) }
                }
            }
            
            findVideoRenderers(map)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        return@withContext results
    }

    private var scClientId: String? = null

    private suspend fun getSoundCloudClientId(client: okhttp3.OkHttpClient): String? {
        scClientId?.let { return it }
        try {
            val req = okhttp3.Request.Builder().url("https://soundcloud.com").build()
            val html = client.newCall(req).execute().body?.string() ?: return null
            
            val jsPattern = Regex("""<script crossorigin src="(https://a-v2\.sndcdn\.com/assets/.*?.js)"></script>""")
            val matches = jsPattern.findAll(html).toList()
            
            for (match in matches) {
                val jsUrl = match.groupValues[1]
                val jsReq = okhttp3.Request.Builder().url(jsUrl).build()
                val jsContent = client.newCall(jsReq).execute().body?.string() ?: continue
                
                val idMatch = Regex("""client_id:"([^"]+)"""").find(jsContent)
                if (idMatch != null) {
                    scClientId = idMatch.groupValues[1]
                    return scClientId
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private suspend fun nativeSoundCloudSearch(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        val client = okhttp3.OkHttpClient()
        val clientId = getSoundCloudClientId(client) ?: return@withContext emptyList()
        
        val url = okhttp3.HttpUrl.Builder()
            .scheme("https")
            .host("api-v2.soundcloud.com")
            .addPathSegment("search")
            .addPathSegment("tracks")
            .addQueryParameter("q", query)
            .addQueryParameter("client_id", clientId)
            .addQueryParameter("limit", "20")
            .build()
            
        val request = okhttp3.Request.Builder().url(url).build()
        val results = mutableListOf<SearchResult>()
        
        try {
            val response = client.newCall(request).execute()
            val jsonResponse = response.body?.string() ?: return@withContext emptyList()
            
            val mapAdapter = moshi.adapter(Map::class.java)
            val map = mapAdapter.fromJson(jsonResponse) as? Map<*, *> ?: return@withContext emptyList()
            
            val collection = map["collection"] as? List<*> ?: return@withContext emptyList()
            for (item in collection) {
                val track = item as? Map<*, *> ?: continue
                try {
                    val id = track["id"]?.toString() ?: continue
                    val title = track["title"]?.toString() ?: "Unknown Title"
                    val user = track["user"] as? Map<*, *>
                    val artist = user?.get("username")?.toString() ?: "Unknown Artist"
                    val durationMs = (track["duration"] as? Number)?.toLong() ?: 0L
                    val artworkUrl = track["artwork_url"]?.toString()?.replace("-large", "-t500x500") 
                    val permalink = track["permalink_url"]?.toString() ?: "https://soundcloud.com"
                    
                    results.add(
                        SearchResult(
                            id = id,
                            title = MetadataCleaner.cleanTitle(title),
                            uploader = MetadataCleaner.cleanChannelName(artist),
                            durationMs = durationMs,
                            thumbnailUrl = artworkUrl,
                            source = SourceType.SOUNDCLOUD,
                            webpageUrl = permalink
                        )
                    )
                } catch (e: Exception) {}
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext results
    }

    suspend fun search(query: String, source: SourceType): List<SearchResult> = withContext(Dispatchers.IO) {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isEmpty()) return@withContext emptyList()

        val cacheKey = "$source:$trimmedQuery"
        cache[cacheKey]?.let { return@withContext it }

        // Fast Instant Resolution for Direct URLs (0ms)
        val instantResult = parseDirectUrlInstant(trimmedQuery)
        if (instantResult != null) {
            val actualSource = instantResult.source
            val instantList = listOf(instantResult)
            cache[cacheKey] = instantList

            // Asynchronously fetch rich metadata in background without blocking UI
            scope.launch {
                fetchFullMetadataForUrl(trimmedQuery, actualSource, cacheKey)
            }
            return@withContext instantList
        }

        val isDirectUrl = trimmedQuery.startsWith("http://") || trimmedQuery.startsWith("https://")
        
        if (!isDirectUrl && source == SourceType.YOUTUBE) {
            val results = nativeYouTubeSearch(trimmedQuery)
            if (results.isNotEmpty()) {
                cache[cacheKey] = results
            }
            return@withContext results
        }
        
        if (!isDirectUrl && source == SourceType.SOUNDCLOUD) {
            val results = nativeSoundCloudSearch(trimmedQuery)
            if (results.isNotEmpty()) {
                cache[cacheKey] = results
            }
            return@withContext results
        }

        val searchPrefix = if (isDirectUrl) "" else when (source) {
            SourceType.YOUTUBE -> "ytsearch20:"
            SourceType.SOUNDCLOUD -> "scsearch20:"
        }

        val request = YoutubeDLRequest(if (isDirectUrl) trimmedQuery else "$searchPrefix$trimmedQuery").apply {
            addOption("--dump-json")
            if (isDirectUrl) {
                addOption("--no-playlist")
            } else {
                addOption("--flat-playlist")
            }
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
                    val thumbnailUrl = (thumbnails?.filterIsInstance<Map<*, *>>()?.lastOrNull {
                        val u = it["url"]?.toString() ?: ""
                        !u.contains(".webp") && !u.contains(".avif")
                    } ?: thumbnails?.lastOrNull() as? Map<*, *>)?.get("url")?.toString()
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
            val thumbnailUrl = (thumbnails?.filterIsInstance<Map<*, *>>()?.lastOrNull {
                val u = it["url"]?.toString() ?: ""
                !u.contains(".webp") && !u.contains(".avif")
            } ?: thumbnails?.lastOrNull() as? Map<*, *>)?.get("url")?.toString()
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
            addOption("--dump-json")
            addOption("--no-warnings")
            addOption("--socket-timeout", "8")
            if (result.source == SourceType.YOUTUBE) {
                addOption("--extractor-args", "youtube:player_client=android")
            }
        }

        try {
            val response = YoutubeDL.getInstance().execute(request)
            val jsonLine = response.out.lines().firstOrNull { it.trim().startsWith("{") }
                ?: response.out.lines().firstOrNull { it.isNotBlank() }
                ?: return@withContext null

            val mapAdapter = moshi.adapter(Map::class.java)
            val map = mapAdapter.fromJson(jsonLine) as? Map<*, *> ?: return@withContext null

            val streamUrl = map["url"]?.toString()
                ?: (map["requested_formats"] as? List<*>)?.firstOrNull()?.let { (it as? Map<*, *>)?.get("url")?.toString() }
                ?: return@withContext null

            val id = map["id"]?.toString()
            val rawTitle = map["title"]?.toString()
            val rawChannel = map["channel"]?.toString()
                ?: map["uploader"]?.toString()
                ?: map["artist"]?.toString()
                ?: map["creator"]?.toString()

            val cleanedTitle = if (!rawTitle.isNullOrBlank()) MetadataCleaner.cleanTitle(rawTitle) else result.title
            val cleanedArtist = if (!rawChannel.isNullOrBlank()) MetadataCleaner.cleanChannelName(rawChannel) else (result.uploader ?: "Unknown Artist")

            val durationSec = (map["duration"] as? Number)?.toDouble() ?: 0.0
            val durationMs = if (durationSec > 0) (durationSec * 1000).toLong() else result.durationMs

            val thumbnails = map["thumbnails"] as? List<*>
            val thumbnailUrl = (thumbnails?.filterIsInstance<Map<*, *>>()?.lastOrNull {
                val u = it["url"]?.toString() ?: ""
                !u.contains(".webp") && !u.contains(".avif")
            } ?: thumbnails?.lastOrNull() as? Map<*, *>)?.get("url")?.toString()
                ?: map["thumbnail"]?.toString()
                ?: if (!id.isNullOrBlank()) "https://i.ytimg.com/vi/$id/hqdefault.jpg" else result.thumbnailUrl

            StreamInfo(
                audioStreamUrl = streamUrl,
                title = cleanedTitle,
                uploader = cleanedArtist,
                durationMs = durationMs,
                thumbnailUrl = thumbnailUrl,
                webpageUrl = result.webpageUrl
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
