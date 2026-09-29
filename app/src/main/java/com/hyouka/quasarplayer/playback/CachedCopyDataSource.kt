package com.hyouka.quasarplayer.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.TransferListener
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

/**
 * A custom DataSource that mitigates file handle exhaustion and MediaStore
 * read timeouts by copying local media files (content:// or file://) to a
 * temporary file in the app's internal cache before playback, playing from
 * the cache, and then deleting the temporary file when closed.
 */
class CachedCopyDataSource(private val context: Context) : DataSource {

    private var activeDataSource: DataSource? = null
    private var tempFile: File? = null
    private val defaultDataSource = DefaultDataSource.Factory(context).createDataSource()
    private val fileDataSource = FileDataSource()

    override fun addTransferListener(transferListener: TransferListener) {
        defaultDataSource.addTransferListener(transferListener)
        fileDataSource.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val originalUri = dataSpec.uri
        val scheme = originalUri.scheme

        // Don't cache remote streams or unexpected schemes
        if (scheme == "http" || scheme == "https" || scheme == "rtmp") {
            activeDataSource = defaultDataSource
            return activeDataSource!!.open(dataSpec)
        }

        // Copy local file/content to temp file
        tempFile = File(context.cacheDir, "quasar_playback_${UUID.randomUUID()}.tmp")
        
        val inputStream: InputStream? = try {
            if (scheme == "content") {
                context.contentResolver.openInputStream(originalUri)
            } else {
                val file = if (scheme == "file") {
                    File(originalUri.path!!)
                } else {
                    File(originalUri.toString())
                }
                if (file.exists()) file.inputStream() else null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }

        if (inputStream != null) {
            try {
                inputStream.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                // If copy fails, fallback to default data source
                tempFile?.delete()
                tempFile = null
                activeDataSource = defaultDataSource
                return activeDataSource!!.open(dataSpec)
            }
        } else {
            // Fallback if we couldn't open input stream
            activeDataSource = defaultDataSource
            return activeDataSource!!.open(dataSpec)
        }

        activeDataSource = fileDataSource
        val tempFileDataSpec = dataSpec.buildUpon().setUri(Uri.fromFile(tempFile)).build()
        return activeDataSource!!.open(tempFileDataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        return activeDataSource?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT
    }

    override fun getUri(): Uri? {
        return activeDataSource?.uri
    }

    override fun close() {
        try {
            activeDataSource?.close()
        } finally {
            activeDataSource = null
            tempFile?.delete()
            tempFile = null
        }
    }

    class Factory(private val context: Context) : DataSource.Factory {
        override fun createDataSource(): DataSource {
            return CachedCopyDataSource(context)
        }
    }
}
