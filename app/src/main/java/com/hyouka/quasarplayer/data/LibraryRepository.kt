package com.hyouka.quasarplayer.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.hyouka.quasarplayer.source.SearchResult
import com.hyouka.quasarplayer.source.SourceType
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.adapter
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

class LibraryRepository(private val context: Context) {

    var savedSearchQuery: String = ""
    var savedSelectedSource: SourceType = SourceType.YOUTUBE
    var savedSearchResults: List<SearchResult> = emptyList()

    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()

    private val indexFile: File
        get() = File(context.filesDir, "library_index.json")

    // --- scanning -----------------------------------------------------

    suspend fun scanLibrary(): List<Track> = withContext(Dispatchers.IO) {
        val cachedIndex = loadCachedIndex().associateBy { it.id }.toMutableMap()
        val scannedTracksMap = mutableMapOf<String, Track>()

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.RELATIVE_PATH
        )

        val selection = "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf("%Music/QuasarPlayer%")

        // 1. Query MediaStore
        try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val modifiedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)

                while (cursor.moveToNext()) {
                    val mediaStoreId = cursor.getLong(idCol)
                    val uri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaStoreId
                    ).toString()

                    val trackId = stableId(uri)
                    val cached = cachedIndex[trackId]

                    val mediaStoreTitle = cursor.getString(titleCol)
                    val mediaStoreArtist = cursor.getString(artistCol)
                    val mediaStoreDuration = cursor.getLong(durationCol)

                    val finalTitle = cached?.title ?: mediaStoreTitle ?: "Unknown Title"
                    val finalArtist = if (!cached?.artist.isNullOrBlank() && cached?.artist != "<unknown>") {
                        cached.artist
                    } else if (!mediaStoreArtist.isNullOrBlank() && mediaStoreArtist != "<unknown>") {
                        mediaStoreArtist
                    } else {
                        "Unknown Artist"
                    }

                    val finalDuration = if ((cached?.durationMs ?: 0L) > 0L) {
                        cached!!.durationMs
                    } else {
                        mediaStoreDuration
                    }

                    scannedTracksMap[trackId] = Track(
                        id = trackId,
                        uri = uri,
                        title = finalTitle,
                        artist = finalArtist,
                        durationMs = finalDuration,
                        hasEmbeddedArt = cached?.hasEmbeddedArt ?: false,
                        artworkPath = cached?.artworkPath,
                        lastModified = cursor.getLong(modifiedCol)
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Direct Physical File Scan in Music/QuasarPlayer (to catch newly downloaded files immediately)
        try {
            val musicDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                "QuasarPlayer"
            )
            if (musicDir.exists() && musicDir.isDirectory) {
                val mp3Files = musicDir.listFiles { file -> file.isFile && file.extension.equals("mp3", ignoreCase = true) } ?: emptyArray()
                for (file in mp3Files) {
                    val fileUri = Uri.fromFile(file).toString()
                    val trackId = stableId(fileUri)

                    val cached = cachedIndex[trackId] ?: cachedIndex.values.firstOrNull { it.uri.endsWith(file.name) }

                    if (!scannedTracksMap.containsKey(trackId)) {
                        scannedTracksMap[trackId] = Track(
                            id = trackId,
                            uri = fileUri,
                            title = cached?.title ?: file.nameWithoutExtension,
                            artist = cached?.artist ?: "Unknown Artist",
                            durationMs = cached?.durationMs ?: 0L,
                            hasEmbeddedArt = cached?.hasEmbeddedArt ?: false,
                            artworkPath = cached?.artworkPath,
                            lastModified = file.lastModified()
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Preserve any cached downloaded tracks that weren't caught in query
        for ((id, cachedTrack) in cachedIndex) {
            if (!scannedTracksMap.containsKey(id)) {
                scannedTracksMap[id] = cachedTrack
            }
        }

        val resultList = scannedTracksMap.values.sortedByDescending { it.lastModified }
        saveIndex(resultList)
        resultList
    }

    /** Stable ID from the content URI — survives filename changes. */
    private fun stableId(uri: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(uri.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(12)
    }

    // --- index cache ----------------------------------------------------

    private val trackListAdapter
        get() = moshi.adapter<List<Track>>(
            Types.newParameterizedType(List::class.java, Track::class.java)
        )

    fun saveIndex(tracks: List<Track>) {
        indexFile.writeText(trackListAdapter.toJson(tracks))
    }

    fun addTrackToIndex(track: Track) {
        val current = loadCachedIndex().toMutableList()
        current.removeAll { it.id == track.id || it.uri == track.uri }
        current.add(0, track)
        saveIndex(current)
    }

    fun deleteTrack(track: Track) {
        val uri = Uri.parse(track.uri)

        // 1. Delete physical file if file URI or direct path
        if (uri.scheme == "file" || track.uri.startsWith("/")) {
            val path = uri.path ?: track.uri
            val file = File(path)
            if (file.exists()) file.delete()
        }

        // 2. Delete artwork file if present
        if (!track.artworkPath.isNullOrEmpty()) {
            val artFile = File(track.artworkPath)
            if (artFile.exists()) artFile.delete()
        }

        // 3. Delete from MediaStore if content URI
        if (uri.scheme == "content") {
            try {
                context.contentResolver.delete(uri, null, null)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 4. Scan physical music directory for matching file
        try {
            val musicDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                "QuasarPlayer"
            )
            if (musicDir.exists()) {
                musicDir.listFiles()?.forEach { f ->
                    if (f.nameWithoutExtension.equals(track.title.replace(Regex("[^A-Za-z0-9 _-]"), "_").trim(), ignoreCase = true)) {
                        f.delete()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 5. Remove from index cache
        val current = loadCachedIndex().toMutableList()
        current.removeAll { it.id == track.id || it.uri == track.uri }
        saveIndex(current)
    }

    fun generateStableId(uri: String): String = stableId(uri)

    fun loadCachedIndex(): List<Track> {
        if (!indexFile.exists()) return emptyList()
        return try {
            trackListAdapter.fromJson(indexFile.readText()) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    // --- playlists ------------------------------------------------------

    private val playlistDir: File
        get() = File(context.filesDir, "playlists").apply { mkdirs() }

    private val playlistAdapter
        get() = moshi.adapter(Playlist::class.java)

    fun listPlaylists(): List<Playlist> {
        val files = playlistDir.listFiles { _, name -> name.endsWith(".json") } ?: return emptyList()
        return files.mapNotNull { file ->
            try {
                playlistAdapter.fromJson(file.readText())
            } catch (e: Exception) {
                null
            }
        }.sortedByDescending { it.created }
    }

    fun savePlaylist(playlist: Playlist) {
        val file = File(playlistDir, "${playlist.name}.json")
        file.writeText(playlistAdapter.toJson(playlist))
    }

    fun deletePlaylist(name: String) {
        File(playlistDir, "$name.json").delete()
    }
}
