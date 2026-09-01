package com.hyouka.quasarplayer.data

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.adapter
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Single source of truth for the music library: scans MediaStore for the
 * configured folder, caches results in library_index.json so we don't
 * re-read tags on every launch, and reads/writes per-playlist JSON files.
 *
 * First pass — folder path is hardcoded to a dedicated subfolder for now.
 * Swap for a user-configurable SAF folder pick once the settings screen
 * is wired up.
 */
class LibraryRepository(private val context: Context) {

    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()

    private val indexFile: File
        get() = File(context.filesDir, "library_index.json")

    private val playlistsDir: File
        get() = File(context.filesDir, "playlists").apply { mkdirs() }

    // --- scanning -----------------------------------------------------

    suspend fun scanLibrary(): List<Track> = withContext(Dispatchers.IO) {
        val tracks = mutableListOf<Track>()

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.RELATIVE_PATH
        )

        // Restrict to our dedicated folder so we only ever see files the
        // app itself manages, never the user's whole phone library.
        val selection = "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf("%Music/QuasarPlayer%")

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

                tracks += Track(
                    id = stableId(uri),
                    uri = uri,
                    title = cursor.getString(titleCol) ?: "Unknown",
                    artist = cursor.getString(artistCol),
                    durationMs = cursor.getLong(durationCol),
                    lastModified = cursor.getLong(modifiedCol)
                )
            }
        }

        saveIndex(tracks)
        tracks
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

    private fun saveIndex(tracks: List<Track>) {
        indexFile.writeText(trackListAdapter.toJson(tracks))
    }

    fun loadCachedIndex(): List<Track> {
        if (!indexFile.exists()) return emptyList()
        return trackListAdapter.fromJson(indexFile.readText()) ?: emptyList()
    }

    // --- playlists --------------------------------------------------------

    private val playlistAdapter get() = moshi.adapter(Playlist::class.java)

    fun listPlaylists(): List<Playlist> =
        playlistsDir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { f -> playlistAdapter.fromJson(f.readText()) }
            ?: emptyList()

    fun savePlaylist(playlist: Playlist) {
        val safeName = playlist.name.replace(Regex("[^A-Za-z0-9_-]"), "_")
        File(playlistsDir, "$safeName.json").writeText(playlistAdapter.toJson(playlist))
    }

    fun deletePlaylist(name: String) {
        val safeName = name.replace(Regex("[^A-Za-z0-9_-]"), "_")
        File(playlistsDir, "$safeName.json").delete()
    }
}
