package com.hyouka.quasarplayer.source

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import com.hyouka.quasarplayer.data.LibraryRepository
import com.hyouka.quasarplayer.data.Track
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

enum class DownloadStatus {
    DOWNLOADING, COMPLETED, FAILED
}

data class DownloadTask(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val progress: Float = 0f,
    val status: DownloadStatus = DownloadStatus.DOWNLOADING
)

class Downloader(
    private val context: Context,
    private val libraryRepository: LibraryRepository,
    private val sourceExtractor: SourceExtractor
) {
    private val scope = CoroutineScope(Dispatchers.IO)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val artworkDir = File(context.filesDir, "artworks").apply { mkdirs() }

    private val _activeDownloads = MutableStateFlow<List<DownloadTask>>(emptyList())
    val activeDownloads: StateFlow<List<DownloadTask>> = _activeDownloads.asStateFlow()

    suspend fun download(searchResult: SearchResult): File? = withContext(Dispatchers.IO) {
        val taskId = UUID.randomUUID().toString()
        val initialTask = DownloadTask(id = taskId, title = searchResult.title, progress = 0f)

        _activeDownloads.update { it + initialTask }

        try {
            val streamInfo = sourceExtractor.getStreamInfo(searchResult)
            if (streamInfo == null) {
                updateTaskStatus(taskId, DownloadStatus.FAILED, 0f)
                scope.launch {
                    delay(3000L)
                    _activeDownloads.update { list -> list.filterNot { it.id == taskId } }
                }
                return@withContext null
            }

            downloadInternal(taskId, streamInfo)
        } catch (e: Exception) {
            e.printStackTrace()
            updateTaskStatus(taskId, DownloadStatus.FAILED, 0f)
            scope.launch {
                delay(3000L)
                _activeDownloads.update { list -> list.filterNot { it.id == taskId } }
            }
            null
        }
    }

    suspend fun download(streamInfo: StreamInfo): File? = withContext(Dispatchers.IO) {
        val taskId = UUID.randomUUID().toString()
        val initialTask = DownloadTask(id = taskId, title = streamInfo.title, progress = 0f)

        _activeDownloads.update { it + initialTask }

        try {
            downloadInternal(taskId, streamInfo)
        } catch (e: Exception) {
            e.printStackTrace()
            updateTaskStatus(taskId, DownloadStatus.FAILED, 0f)
            scope.launch {
                delay(3000L)
                _activeDownloads.update { list -> list.filterNot { it.id == taskId } }
            }
            null
        }
    }

    private suspend fun downloadInternal(taskId: String, streamInfo: StreamInfo): File? {
        val sanitizedTitle = streamInfo.title.replace(Regex("[^A-Za-z0-9 _-]"), "_").trim()
        val musicDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            "QuasarPlayer"
        ).apply { mkdirs() }

        var destFile: File? = null
        var downloadSucceeded = false

        try {
            // 1. High-Speed Direct OkHttp Stream Fetch with Identity Encoding & 64KB Buffer
            if (!streamInfo.audioStreamUrl.isNullOrBlank()) {
                try {
                    val request = Request.Builder()
                        .url(streamInfo.audioStreamUrl)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                        .header("Accept", "*/*")
                        .header("Accept-Encoding", "identity")
                        .header("Connection", "keep-alive")
                        .build()

                    val response = client.newCall(request).execute()
                    if (response.isSuccessful && response.body != null) {
                        val contentType = response.header("Content-Type")?.lowercase() ?: ""
                        
                        // HLS Playlists cannot be downloaded via OkHttp stream copy. Let yt-dlp handle it natively.
                        if (contentType.contains("mpegurl") || contentType.contains("application/x-mpegurl") || streamInfo.audioStreamUrl.contains(".m3u8")) {
                            response.body?.close()
                            throw Exception("HLS Stream detected. Delegating to yt-dlp fallback.")
                        }

                        val ext = when {
                            contentType.contains("mpeg") || contentType.contains("mp3") -> ".mp3"
                            contentType.contains("webm") || contentType.contains("ogg") -> ".webm"
                            contentType.contains("mp4") || contentType.contains("m4a") || contentType.contains("aac") -> ".m4a"
                            streamInfo.audioStreamUrl.contains(".m4a") -> ".m4a"
                            streamInfo.audioStreamUrl.contains(".webm") -> ".webm"
                            else -> ".m4a"
                        }

                        val targetFile = File(musicDir, "$sanitizedTitle$ext")
                        val tempTargetFile = File(context.cacheDir, "temp_${UUID.randomUUID()}$ext")
                        val body = response.body!!
                        val totalBytes = body.contentLength()
                        val inputStream = body.byteStream()
                        val outputStream = FileOutputStream(tempTargetFile)

                        val buffer = ByteArray(65536) // 64KB high-speed buffer
                        var bytesRead: Int
                        var downloadedBytes = 0L

                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            outputStream.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead
                            if (totalBytes > 0) {
                                val progress = downloadedBytes.toFloat() / totalBytes.toFloat()
                                updateTaskProgress(taskId, progress)
                            }
                        }

                        outputStream.flush()
                        outputStream.close()
                        inputStream.close()

                        if (tempTargetFile.exists() && tempTargetFile.length() > 0) {
                            tempTargetFile.copyTo(targetFile, overwrite = true)
                            tempTargetFile.delete()
                            destFile = targetFile
                            downloadSucceeded = true
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            // Fallback to YoutubeDL native engine if direct stream fetch fails
            if (!downloadSucceeded || destFile == null) {
                val fallbackFile = File(musicDir, "$sanitizedTitle.m4a")
                val tempFallbackFile = File(context.cacheDir, "temp_${UUID.randomUUID()}.m4a")
                val ytdlReq = YoutubeDLRequest(streamInfo.webpageUrl).apply {
                    addOption("-f", "bestaudio/best")
                    addOption("-x")
                    addOption("--audio-format", "m4a")
                    addOption("-o", tempFallbackFile.absolutePath)
                    addOption("--no-playlist")
                    addOption("--no-warnings")
                    addOption("--socket-timeout", "15")
                    if (streamInfo.webpageUrl.contains("youtube.com") || streamInfo.webpageUrl.contains("youtu.be")) {
                        addOption("--extractor-args", "youtube:player_client=android")
                    }
                }

                YoutubeDL.getInstance().execute(ytdlReq) { progress, _, _ ->
                    updateTaskProgress(taskId, (progress / 100f).coerceIn(0f, 1f))
                }

                if (tempFallbackFile.exists() && tempFallbackFile.length() > 0) {
                    tempFallbackFile.copyTo(fallbackFile, overwrite = true)
                    tempFallbackFile.delete()
                    destFile = fallbackFile
                } else {
                    val candidate = context.cacheDir.listFiles { f -> f.isFile && f.nameWithoutExtension.equals(tempFallbackFile.nameWithoutExtension, ignoreCase = true) }?.firstOrNull()
                    if (candidate != null) {
                        val finalCandidate = File(musicDir, candidate.name)
                        candidate.copyTo(finalCandidate, overwrite = true)
                        candidate.delete()
                        destFile = finalCandidate
                    }
                }
            }

            if (destFile == null || !destFile.exists()) {
                updateTaskStatus(taskId, DownloadStatus.FAILED, 0f)
                return null
            }

            // 2. Fetch Thumbnail & Convert to JPEG Bytes with Guaranteed Fallback Candidates
            var jpegBytes: ByteArray? = null
            var localArtworkFile: File? = null

            val candidateUrls = mutableListOf<String>()
            if (!streamInfo.thumbnailUrl.isNullOrBlank()) {
                candidateUrls.add(streamInfo.thumbnailUrl)
            }

            val ytMatch = Regex("(?:v=|\\/|shorts\\/)([A-Za-z0-9_-]{11})").find(streamInfo.webpageUrl)
            if (ytMatch != null) {
                val videoId = ytMatch.groupValues[1]
                candidateUrls.add("https://i.ytimg.com/vi/$videoId/maxresdefault.jpg")
                candidateUrls.add("https://i.ytimg.com/vi/$videoId/hqdefault.jpg")
                candidateUrls.add("https://i.ytimg.com/vi/$videoId/sddefault.jpg")
                candidateUrls.add("https://i.ytimg.com/vi/$videoId/mqdefault.jpg")
            }

            for (thumbUrl in candidateUrls.distinct()) {
                try {
                    val imgReq = Request.Builder()
                        .url(thumbUrl)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                        .build()

                    val imgResp = client.newCall(imgReq).execute()
                    if (imgResp.isSuccessful && imgResp.body != null) {
                        val rawBytes = imgResp.body!!.bytes()
                        val bitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size)
                        if (bitmap != null) {
                            val baos = ByteArrayOutputStream()
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, baos)
                            jpegBytes = baos.toByteArray()

                            val tempArtFile = File(context.cacheDir, "temp_art_${UUID.randomUUID()}.jpg")
                            val artFos = FileOutputStream(tempArtFile)
                            artFos.write(jpegBytes)
                            artFos.flush()
                            artFos.close()
                            
                            val artFile = File(artworkDir, "${UUID.randomUUID()}.jpg")
                            tempArtFile.copyTo(artFile, overwrite = true)
                            tempArtFile.delete()
                            localArtworkFile = artFile
                            break
                        }
                    }
                } catch (e: Exception) {
                    // Try next candidate
                }
            }

            // 3. Embed ID3 Tags ONLY for MP3 files
            if (destFile.extension.equals("mp3", ignoreCase = true)) {
                Id3TagWriter.embedId3Tags(
                    mp3File = destFile,
                    title = streamInfo.title,
                    artist = streamInfo.uploader,
                    imageBytes = jpegBytes,
                    mimeType = "image/jpeg"
                )
            }

            // 4. Trigger background MediaScanner scan
            MediaScannerConnection.scanFile(
                context,
                arrayOf(destFile.absolutePath),
                null
            ) { _, _ -> }

            val fileUri = Uri.fromFile(destFile).toString()

            // 5. Instantly index the new track in LibraryRepository with full metadata
            val newTrack = Track(
                id = libraryRepository.generateStableId(destFile.absolutePath),
                uri = fileUri,
                title = streamInfo.title,
                artist = streamInfo.uploader ?: "Unknown Artist",
                durationMs = streamInfo.durationMs,
                hasEmbeddedArt = jpegBytes != null,
                artworkPath = localArtworkFile?.absolutePath,
                lastModified = System.currentTimeMillis()
            )
            libraryRepository.addTrackToIndex(newTrack)

            updateTaskStatus(taskId, DownloadStatus.COMPLETED, 1f)

            // Remove task after 3 seconds
            scope.launch {
                delay(3000L)
                _activeDownloads.update { list -> list.filterNot { it.id == taskId } }
            }

            return destFile
        } catch (e: Exception) {
            e.printStackTrace()
            updateTaskStatus(taskId, DownloadStatus.FAILED, 0f)
            scope.launch {
                delay(3000L)
                _activeDownloads.update { list -> list.filterNot { it.id == taskId } }
            }
            return null
        }
    }

    private fun updateTaskProgress(taskId: String, progress: Float) {
        _activeDownloads.update { list ->
            list.map { if (it.id == taskId) it.copy(progress = progress) else it }
        }
    }

    private fun updateTaskStatus(taskId: String, status: DownloadStatus, progress: Float) {
        _activeDownloads.update { list ->
            list.map { if (it.id == taskId) it.copy(status = status, progress = progress) else it }
        }
    }
}
