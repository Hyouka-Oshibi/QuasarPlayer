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
    private val sourceExtractor: SourceExtractor,
    private val settingsRepository: com.hyouka.quasarplayer.ui.settings.SettingsRepository
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

    fun enqueueDownload(searchResult: SearchResult, onComplete: ((File?) -> Unit)? = null) {
        scope.launch {
            val file = download(searchResult)
            withContext(Dispatchers.Main) {
                onComplete?.invoke(file)
            }
        }
    }

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
        var sanitizedTitle = streamInfo.title.replace(Regex("[\\\\/:*?\"<>|\\x00]"), "_").trim()
        if (sanitizedTitle.isBlank()) sanitizedTitle = "Unknown Track"

        val tempFallbackFile = File(context.cacheDir, "temp_${UUID.randomUUID()}.opus")
        var localArtworkFile: File? = null
        var jpegBytes: ByteArray? = null

        try {
            val ytdlReq = YoutubeDLRequest(streamInfo.webpageUrl).apply {
                addOption("-f", "251/bestaudio/best")
                addOption("-x")
                addOption("--audio-format", "opus")
                addOption("-o", tempFallbackFile.absolutePath)
                addOption("--no-playlist")
                addOption("--no-warnings")
                addOption("--socket-timeout", "15")
                addOption("--embed-metadata")
                addOption("--embed-thumbnail")
                if (streamInfo.webpageUrl.contains("youtube.com") || streamInfo.webpageUrl.contains("youtu.be")) {
                    addOption("--extractor-args", "youtube:player_client=android")
                }
            }

            YoutubeDL.getInstance().execute(ytdlReq) { progress, _, _ ->
                updateTaskProgress(taskId, (progress / 100f).coerceIn(0f, 1f))
            }

            val downloadedTempFile = if (tempFallbackFile.exists() && tempFallbackFile.length() > 0) {
                tempFallbackFile
            } else {
                context.cacheDir.listFiles { f -> f.isFile && f.nameWithoutExtension.equals(tempFallbackFile.nameWithoutExtension, ignoreCase = true) }?.firstOrNull()
            }

            if (downloadedTempFile == null || !downloadedTempFile.exists()) {
                updateTaskStatus(taskId, DownloadStatus.FAILED, 0f)
                return null
            }

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
                    val imgReq = Request.Builder().url(thumbUrl).header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)").build()
                    val imgResp = client.newCall(imgReq).execute()
                    if (imgResp.isSuccessful && imgResp.body != null) {
                        val rawBytes = imgResp.body!!.bytes()
                        val bitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size)
                        if (bitmap != null) {
                            val baos = ByteArrayOutputStream()
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, baos)
                            jpegBytes = baos.toByteArray()

                            val artFile = File(artworkDir, "${UUID.randomUUID()}.jpg")
                            val artFos = FileOutputStream(artFile)
                            artFos.write(jpegBytes)
                            artFos.flush()
                            artFos.close()
                            localArtworkFile = artFile
                            break
                        }
                    }
                } catch (e: Exception) {}
            }

            val ext = downloadedTempFile.extension.lowercase()
            if (ext == "mp3" || ext == "m4a" || ext == "mp4" || ext == "opus") {
                Id3TagWriter.embedId3Tags(
                    mp3File = downloadedTempFile,
                    title = streamInfo.title,
                    artist = streamInfo.uploader,
                    imageBytes = jpegBytes,
                    mimeType = "image/jpeg"
                )
            }

            val root = libraryRepository.getMusicDirDocument()
            val fileName = "$sanitizedTitle [${streamInfo.id}].opus"
            var targetDocFile = root?.findFile(fileName)
            if (targetDocFile == null) {
                targetDocFile = root?.createFile("audio/opus", fileName)
            }

            if (targetDocFile != null) {
                context.contentResolver.openOutputStream(targetDocFile.uri)?.use { out ->
                    downloadedTempFile.inputStream().use { input ->
                        input.copyTo(out)
                    }
                }
            }

            val actualFileName = targetDocFile?.name ?: fileName
            val fileUri = targetDocFile?.uri?.toString() ?: Uri.fromFile(downloadedTempFile).toString()
            val targetPath = if (!libraryRepository.currentMusicFolder.startsWith("content://")) {
                val pFile = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "QuasarPlayer/$actualFileName")
                if (pFile.exists()) {
                    MediaScannerConnection.scanFile(context, arrayOf(pFile.absolutePath), null) { _, _ -> }
                }
                pFile.absolutePath
            } else {
                actualFileName
            }

            downloadedTempFile.delete()

            val newTrack = Track(
                id = libraryRepository.generateStableId(if (libraryRepository.currentMusicFolder.startsWith("content://")) actualFileName else targetPath),
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
            scope.launch {
                delay(3000L)
                _activeDownloads.update { list -> list.filterNot { it.id == taskId } }
            }
            return if (!libraryRepository.currentMusicFolder.startsWith("content://")) File(targetPath) else null
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
