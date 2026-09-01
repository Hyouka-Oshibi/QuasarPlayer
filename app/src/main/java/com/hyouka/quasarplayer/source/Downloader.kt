package com.hyouka.quasarplayer.source

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import com.hyouka.quasarplayer.data.LibraryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class Downloader(
    private val context: Context,
    private val libraryRepository: LibraryRepository
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun download(
        streamInfo: StreamInfo,
        onProgress: (Float) -> Unit = {}
    ): File? = withContext(Dispatchers.IO) {
        val sanitizedTitle = streamInfo.title.replace(Regex("[^A-Za-z0-9 _-]"), "_").trim()
        val musicDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            "QuasarPlayer"
        ).apply { mkdirs() }

        val destFile = File(musicDir, "$sanitizedTitle.mp3")

        try {
            // 1. Download Audio Stream
            val request = Request.Builder().url(streamInfo.audioStreamUrl).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful || response.body == null) {
                return@withContext null
            }

            val body = response.body!!
            val totalBytes = body.contentLength()
            val inputStream = body.byteStream()
            val outputStream = FileOutputStream(destFile)

            val buffer = ByteArray(8192)
            var bytesRead: Int
            var downloadedBytes = 0L

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                downloadedBytes += bytesRead
                if (totalBytes > 0) {
                    val progress = downloadedBytes.toFloat() / totalBytes.toFloat()
                    onProgress(progress)
                }
            }

            outputStream.flush()
            outputStream.close()
            inputStream.close()

            // 2. Download Thumbnail Image Bytes
            var imageBytes: ByteArray? = null
            var mimeType = "image/jpeg"
            if (!streamInfo.thumbnailUrl.isNullOrEmpty()) {
                try {
                    val imgReq = Request.Builder().url(streamInfo.thumbnailUrl).build()
                    val imgResp = client.newCall(imgReq).execute()
                    if (imgResp.isSuccessful && imgResp.body != null) {
                        imageBytes = imgResp.body!!.bytes()
                        val contentType = imgResp.header("Content-Type")
                        if (contentType != null && contentType.contains("png")) {
                            mimeType = "image/png"
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            // 3. Embed ID3 Tags & Cover Art
            Id3TagWriter.embedId3Tags(
                mp3File = destFile,
                title = streamInfo.title,
                artist = streamInfo.uploader,
                imageBytes = imageBytes,
                mimeType = mimeType
            )

            // 4. Trigger MediaStore scan
            MediaScannerConnection.scanFile(
                context,
                arrayOf(destFile.absolutePath),
                arrayOf("audio/mpeg")
            ) { _, _ -> }

            // 5. Rescan Library Index
            libraryRepository.scanLibrary()

            destFile
        } catch (e: Exception) {
            e.printStackTrace()
            if (destFile.exists()) destFile.delete()
            null
        }
    }
}
