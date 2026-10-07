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
import java.io.InputStreamReader
import java.io.BufferedReader
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

class LibraryRepository(private val context: Context, private val settingsRepository: com.hyouka.quasarplayer.ui.settings.SettingsRepository) {
    var savedSearchQuery: String = ""
    var savedSelectedSource: SourceType = SourceType.YOUTUBE

    private val prefs = context.getSharedPreferences("library_prefs", Context.MODE_PRIVATE)

    var savedSortMode: String
        get() = prefs.getString("sort_mode", "DATE_ADDED") ?: "DATE_ADDED"
        set(value) {
            prefs.edit().putString("sort_mode", value).apply()
        }

    private val resultsCache = java.util.concurrent.ConcurrentHashMap<Pair<SourceType, String>, List<SearchResult>>()

    fun getCachedResults(source: SourceType, query: String): List<SearchResult>? {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        return resultsCache[source to trimmed]
    }

    fun setCachedResults(source: SourceType, query: String, results: List<SearchResult>) {
        val trimmed = query.trim()
        if (trimmed.isNotEmpty()) {
            resultsCache[source to trimmed] = results
        }
    }

    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()

    private val indexFile: File
        get() = File(context.filesDir, "library_index.json")

    var currentMusicFolder: String = "Music/QuasarPlayer"
        private set

    init {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            settingsRepository.musicFolderFlow.collect { folder ->
                currentMusicFolder = folder
            }
        }
    }

    fun getMusicDirDocument(): DocumentFile? {
        val folderStr = currentMusicFolder
        return if (folderStr.startsWith("content://")) {
            DocumentFile.fromTreeUri(context, Uri.parse(folderStr))
        } else {
            val file = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                "QuasarPlayer"
            )
            file.mkdirs()
            DocumentFile.fromFile(file)
        }
    }

    fun getPlaylistDirDocument(): DocumentFile? {
        val root = getMusicDirDocument() ?: return null
        var pDir = root.findFile("playlists")
        if (pDir == null) {
            pDir = root.createDirectory("playlists")
        }
        return pDir
    }

    suspend fun scanLibrary(): List<Track> = withContext(Dispatchers.IO) {
        val cachedIndex = loadCachedIndex().associateBy { it.id }.toMutableMap()
        val scannedTracksMap = mutableMapOf<String, Track>()
        
        val folderStr = currentMusicFolder
        val isSaf = folderStr.startsWith("content://")

        try {
            val root = getMusicDirDocument()
            if (root != null) {
                val mediaFiles = root.listFiles().filter { file ->
                    val ext = file.name?.substringAfterLast('.', "")?.lowercase()
                    file.isFile && (ext == "mp3" || ext == "m4a" || ext == "webm" || ext == "opus" || ext == "oga" || ext == "ogg")
                }

                for (file in mediaFiles) {
                    val fileUriStr = file.uri.toString()
                    val stableIdInput = if (isSaf) (file.name ?: fileUriStr) else (file.uri.path ?: fileUriStr)
                    val trackId = generateStableId(stableIdInput)

                    val cached = cachedIndex[trackId]

                    if (!scannedTracksMap.containsKey(trackId)) {
                        scannedTracksMap[trackId] = Track(
                            id = trackId,
                            uri = fileUriStr,
                            title = cached?.title ?: (file.name?.substringBeforeLast('.') ?: "Unknown Title"),
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

        if (!isSaf) {
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATE_MODIFIED,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.RELATIVE_PATH
            )
            val selection = "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?"
            val selectionArgs = arrayOf("%Music/QuasarPlayer%")
            try {
                context.contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, selection, selectionArgs, null
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                    val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                    val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                    val modifiedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
                    val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)

                    while (cursor.moveToNext()) {
                        val mediaStoreId = cursor.getLong(idCol)
                        val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaStoreId).toString()
                        val rawDataPath = if (dataCol != -1) cursor.getString(dataCol) else null
                        val fileUri = if (!rawDataPath.isNullOrBlank()) Uri.fromFile(File(rawDataPath)).toString() else contentUri

                        val trackId = generateStableId(rawDataPath ?: contentUri)
                        val cached = cachedIndex[trackId] ?: cachedIndex.values.firstOrNull { it.id == trackId }
                        
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
                        
                        val finalDuration = if ((cached?.durationMs ?: 0L) > 0L) cached!!.durationMs else mediaStoreDuration

                        if (!scannedTracksMap.containsKey(trackId)) {
                            scannedTracksMap[trackId] = Track(
                                id = trackId, uri = fileUri, title = finalTitle, artist = finalArtist,
                                durationMs = finalDuration, hasEmbeddedArt = cached?.hasEmbeddedArt ?: false,
                                artworkPath = cached?.artworkPath, lastModified = cursor.getLong(modifiedCol)
                            )
                        } else {
                            val existing = scannedTracksMap[trackId]!!
                            scannedTracksMap[trackId] = existing.copy(
                                title = finalTitle, artist = finalArtist,
                                durationMs = if (existing.durationMs > 0) existing.durationMs else finalDuration
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        for ((id, cachedTrack) in cachedIndex) {
            if (!scannedTracksMap.containsKey(id)) {
                val uri = Uri.parse(cachedTrack.uri)
                if (uri.scheme == "content" && DocumentFile.isDocumentUri(context, uri)) {
                    if (DocumentFile.fromSingleUri(context, uri)?.exists() == true) {
                        scannedTracksMap[id] = cachedTrack
                    }
                } else {
                    val file = File(uri.path ?: cachedTrack.uri)
                    if (file.exists()) {
                        scannedTracksMap[id] = cachedTrack
                    }
                }
            }
        }

        val retriever = android.media.MediaMetadataRetriever()
        for ((id, track) in scannedTracksMap) {
            val artPath = track.artworkPath
            if (!artPath.isNullOrBlank() && File(artPath).exists()) continue

            try {
                retriever.setDataSource(context, Uri.parse(track.uri))
                val picture = retriever.embeddedPicture
                val artDir = File(context.filesDir, "artworks").apply { mkdirs() }
                val artFile = File(artDir, "${track.id}.jpg")

                if (picture != null) {
                    artFile.writeBytes(picture)
                    scannedTracksMap[id] = track.copy(artworkPath = artFile.absolutePath, hasEmbeddedArt = true)
                } else {
                    artFile.createNewFile()
                    scannedTracksMap[id] = track.copy(artworkPath = artFile.absolutePath, hasEmbeddedArt = false)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        try { retriever.release() } catch (e: Exception) {}

        val resultList = scannedTracksMap.values
            .associateBy { it.uri }.values
            .sortedByDescending { it.lastModified }
        saveIndex(resultList)
        resultList
    }

    fun generateStableId(rawUriOrPath: String): String {
        val path = try {
            Uri.parse(rawUriOrPath).path ?: rawUriOrPath
        } catch (e: Exception) {
            rawUriOrPath
        }
        val filename = File(path).name.lowercase().trim()
        val digest = MessageDigest.getInstance("SHA-256").digest(filename.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(12)
    }

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

        if (uri.scheme == "file" || track.uri.startsWith("/")) {
            val path = uri.path ?: track.uri
            val file = File(path)
            if (file.exists()) file.delete()
        } else if (uri.scheme == "content") {
            try {
                if (DocumentFile.isDocumentUri(context, uri) || uri.toString().contains("document")) {
                    DocumentFile.fromSingleUri(context, uri)?.delete()
                } else {
                    context.contentResolver.delete(uri, null, null)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (!track.artworkPath.isNullOrEmpty()) {
            val artFile = File(track.artworkPath)
            if (artFile.exists()) artFile.delete()
        }

        try {
            val root = getMusicDirDocument()
            if (root != null) {
                root.listFiles().forEach { f ->
                    val nameWithoutExt = f.name?.substringBeforeLast(".") ?: ""
                    if (nameWithoutExt.equals(track.title.replace(Regex("[^A-Za-z0-9 _-]"), "_").trim(), ignoreCase = true)) {
                        f.delete()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val current = loadCachedIndex().toMutableList()
        current.removeAll { it.id == track.id || it.uri == track.uri }
        saveIndex(current)

        val playlists = listPlaylists()
        for (playlist in playlists) {
            if (playlist.trackIds.remove(track.id)) {
                savePlaylist(playlist)
            }
        }
    }

    fun loadCachedIndex(): List<Track> {
        if (!indexFile.exists()) return emptyList()
        return try {
            trackListAdapter.fromJson(indexFile.readText()) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun checkIntegrity(): Int = withContext(Dispatchers.IO) {
        val tracks = loadCachedIndex().toList()
        var brokenCount = 0
        for (track in tracks) {
            val uri = Uri.parse(track.uri)
            var isBroken = false

            if (uri.scheme == "file" || track.uri.startsWith("/")) {
                val path = uri.path ?: track.uri
                val file = File(path)
                if (!file.exists() || file.length() == 0L) {
                    isBroken = true
                }
            } else if (uri.scheme == "content") {
                if (DocumentFile.isDocumentUri(context, uri) || uri.toString().contains("document")) {
                    val doc = DocumentFile.fromSingleUri(context, uri)
                    if (doc == null || !doc.exists() || doc.length() == 0L) {
                        isBroken = true
                    }
                }
            }

            if (!isBroken) {
                try {
                    val retriever = android.media.MediaMetadataRetriever()
                    retriever.setDataSource(context, uri)
                    retriever.release()
                } catch (e: Exception) {
                    isBroken = true
                }
            }

            if (isBroken) {
                deleteTrack(track)
                brokenCount++
            }
        }
        brokenCount
    }

    fun listPlaylists(): List<Playlist> {
        val pDir = getPlaylistDirDocument() ?: return emptyList()
        val files = pDir.listFiles().filter { it.name?.endsWith(".m3u") == true }
        
        return files.mapNotNull { file ->
            try {
                context.contentResolver.openInputStream(file.uri)?.use { stream ->
                    val reader = BufferedReader(InputStreamReader(stream))
                    val lines = reader.readLines()
                    if (lines.size >= 2) {
                        val name = lines[0]
                        val trackIds = if (lines.size > 2) lines.subList(2, lines.size).toMutableList() else mutableListOf()
                        Playlist(name = name, created = file.lastModified(), trackIds = trackIds)
                    } else null
                }
            } catch (e: Exception) {
                null
            }
        }.sortedByDescending { it.created }
    }

    fun savePlaylist(playlist: Playlist) {
        val pDir = getPlaylistDirDocument() ?: return
        val sanitizedName = playlist.name.replace(Regex("[\\\\/:*?\"<>|\\x00]"), "_").trim()
        val fileName = "$sanitizedName.m3u"
        
        var file = pDir.findFile(fileName)
        if (file == null) {
            file = pDir.createFile("audio/x-mpegurl", fileName)
        }
        if (file == null) return
        
        val allTracks = loadCachedIndex().associateBy { it.id }
        val totalSeconds = playlist.trackIds.sumOf { allTracks[it]?.durationMs ?: 0L } / 1000L

        try {
            context.contentResolver.openOutputStream(file.uri)?.use { stream ->
                stream.bufferedWriter().use { out ->
                    out.write(playlist.name + "\n")
                    out.write(totalSeconds.toString() + "\n")
                    playlist.trackIds.forEach { trackId ->
                        out.write(trackId + "\n")
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun deletePlaylist(name: String) {
        val sanitizedName = name.replace(Regex("[\\\\/:*?\"<>|\\x00]"), "_").trim()
        val pDir = getPlaylistDirDocument() ?: return
        pDir.findFile("$sanitizedName.m3u")?.delete()
    }
}
