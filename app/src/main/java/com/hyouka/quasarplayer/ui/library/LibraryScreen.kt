package com.hyouka.quasarplayer.ui.library

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hyouka.quasarplayer.data.LibraryRepository
import com.hyouka.quasarplayer.data.Playlist
import com.hyouka.quasarplayer.data.Track
import com.hyouka.quasarplayer.playback.PlayerController
import com.hyouka.quasarplayer.source.*
import com.hyouka.quasarplayer.ui.common.NetworkImage
import com.hyouka.quasarplayer.ui.common.TrackArt
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class LibraryTab {
    TRACKS, PLAYLISTS
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    libraryRepository: LibraryRepository,
    playerController: PlayerController,
    sourceExtractor: SourceExtractor,
    downloader: Downloader
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var searchQuery by remember { mutableStateOf(libraryRepository.savedSearchQuery) }
    var selectedSource by remember { mutableStateOf(libraryRepository.savedSelectedSource) }
    var isSearching by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<List<SearchResult>>(libraryRepository.savedSearchResults) }
    var previewResult by remember { mutableStateOf<SearchResult?>(null) }

    val activeDownloads by downloader.activeDownloads.collectAsState()

    var selectedTab by remember { mutableStateOf(LibraryTab.TRACKS) }
    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var playlists by remember { mutableStateOf<List<Playlist>>(emptyList()) }

    var trackForActionSheet by remember { mutableStateOf<Track?>(null) }
    var showRenameDialog by remember { mutableStateOf<Track?>(null) }
    var showAddToPlaylistDialog by remember { mutableStateOf<Track?>(null) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }

    // Synchronize search state to repository so it persists across tab switches
    LaunchedEffect(searchQuery, selectedSource, searchResults) {
        libraryRepository.savedSearchQuery = searchQuery
        libraryRepository.savedSelectedSource = selectedSource
        libraryRepository.savedSearchResults = searchResults
    }

    val refreshLibrary: () -> Unit = {
        scope.launch(Dispatchers.IO) {
            val loadedTracks = libraryRepository.loadCachedIndex().ifEmpty {
                libraryRepository.scanLibrary()
            }
            val loadedPlaylists = libraryRepository.listPlaylists()
            withContext(Dispatchers.Main) {
                tracks = loadedTracks
                playlists = loadedPlaylists
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshLibrary()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Search Bar & Source Chips
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search YouTube / SoundCloud or paste link...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = {
                        searchQuery = ""
                        searchResults = emptyList()
                    }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear")
                    }
                }
            },
            singleLine = true
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = selectedSource == SourceType.YOUTUBE,
                    onClick = { selectedSource = SourceType.YOUTUBE },
                    label = { Text("YouTube") }
                )
                FilterChip(
                    selected = selectedSource == SourceType.SOUNDCLOUD,
                    onClick = { selectedSource = SourceType.SOUNDCLOUD },
                    label = { Text("SoundCloud") }
                )
            }

            Button(
                onClick = {
                    if (searchQuery.isNotBlank()) {
                        scope.launch {
                            isSearching = true
                            searchResults = sourceExtractor.search(searchQuery, selectedSource)
                            isSearching = false
                        }
                    }
                },
                enabled = !isSearching && searchQuery.isNotBlank()
            ) {
                if (isSearching) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text("Search")
                }
            }
        }

        // Search Results Dropdown List
        if (searchResults.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(searchResults) { result ->
                        ListItem(
                            headlineContent = { Text(result.title, maxLines = 1) },
                            supportingContent = {
                                Text(
                                    "${result.uploader ?: "Unknown"} • ${formatMs(result.durationMs)}",
                                    maxLines = 1
                                )
                            },
                            leadingContent = {
                                NetworkImage(
                                    url = result.thumbnailUrl,
                                    modifier = Modifier.size(48.dp),
                                    iconSize = 24.dp
                                )
                            },
                            trailingContent = {
                                IconButton(
                                    onClick = {
                                        scope.launch {
                                            Toast.makeText(context, "Downloading ${result.title}", Toast.LENGTH_SHORT).show()
                                            val file = downloader.download(result)
                                            if (file != null) {
                                                refreshLibrary()
                                            }
                                        }
                                    }
                                ) {
                                    Icon(Icons.Default.Download, contentDescription = "Download Immediately")
                                }
                            },
                            modifier = Modifier.combinedClickable(
                                onClick = { previewResult = result }
                            )
                        )
                        HorizontalDivider()
                    }
                }
            }
        }

        // Active Downloads Card List
        if (activeDownloads.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Active Downloads (${activeDownloads.size})",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    activeDownloads.forEach { task ->
                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = task.title,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = when (task.status) {
                                        DownloadStatus.DOWNLOADING -> "${(task.progress * 100).toInt()}%"
                                        DownloadStatus.COMPLETED -> "Done"
                                        DownloadStatus.FAILED -> "Failed"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (task.status == DownloadStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            if (task.status == DownloadStatus.DOWNLOADING) {
                                LinearProgressIndicator(
                                    progress = { task.progress },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Segmented Control Tabs
        PrimaryTabRow(selectedTabIndex = selectedTab.ordinal) {
            Tab(
                selected = selectedTab == LibraryTab.TRACKS,
                onClick = { selectedTab = LibraryTab.TRACKS },
                text = { Text("Tracks (${tracks.size})") }
            )
            Tab(
                selected = selectedTab == LibraryTab.PLAYLISTS,
                onClick = { selectedTab = LibraryTab.PLAYLISTS },
                text = { Text("Playlists (${playlists.size})") }
            )
        }

        Text(
            text = "Tap a track to play, hold for details",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 6.dp)
        )

        // Tab Content
        when (selectedTab) {
            LibraryTab.TRACKS -> {
                if (tracks.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No tracks found in Music/QuasarPlayer")
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        itemsIndexed(tracks) { index, track ->
                            ListItem(
                                headlineContent = { Text(track.title, maxLines = 1) },
                                supportingContent = {
                                    Text(
                                        "${track.artist ?: "Unknown Artist"} • ${formatMs(track.durationMs)}",
                                        maxLines = 1
                                    )
                                },
                                leadingContent = {
                                    TrackArt(
                                        track = track,
                                        modifier = Modifier.size(48.dp),
                                        iconSize = 24.dp
                                    )
                                },
                                modifier = Modifier.combinedClickable(
                                    onClick = {
                                        playerController.playTrackList(tracks, index)
                                    },
                                    onLongClick = {
                                        trackForActionSheet = track
                                    }
                                )
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }

            LibraryTab.PLAYLISTS -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    OutlinedButton(
                        onClick = { showCreatePlaylistDialog = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Create Playlist")
                    }

                    if (playlists.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No playlists created yet")
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(playlists) { playlist ->
                                ListItem(
                                    headlineContent = { Text(playlist.name) },
                                    supportingContent = { Text("${playlist.trackIds.size} tracks") },
                                    leadingContent = { Icon(Icons.Default.QueueMusic, contentDescription = null) },
                                    trailingContent = {
                                        IconButton(onClick = {
                                            libraryRepository.deletePlaylist(playlist.name)
                                            refreshLibrary()
                                        }) {
                                            Icon(Icons.Default.Delete, contentDescription = "Delete Playlist")
                                        }
                                    },
                                    modifier = Modifier.combinedClickable(
                                        onClick = {
                                            val playlistTracks = tracks.filter { playlist.trackIds.contains(it.id) }
                                            if (playlistTracks.isNotEmpty()) {
                                                playerController.playTrackList(playlistTracks, 0)
                                            } else {
                                                Toast.makeText(context, "Playlist is empty", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    )
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }

    // Preview Sheet
    previewResult?.let { result ->
        PreviewSheet(
            searchResult = result,
            sourceExtractor = sourceExtractor,
            onDismiss = { previewResult = null },
            onDownloadRequested = { searchRes ->
                scope.launch {
                    Toast.makeText(context, "Started download for ${searchRes.title}", Toast.LENGTH_SHORT).show()
                    val file = downloader.download(searchRes)
                    if (file != null) {
                        Toast.makeText(context, "Downloaded ${file.name}", Toast.LENGTH_SHORT).show()
                        refreshLibrary()
                    } else {
                        Toast.makeText(context, "Download failed", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    // Track Long-Press Action Sheet
    trackForActionSheet?.let { targetTrack ->
        ModalBottomSheet(onDismissRequest = { trackForActionSheet = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                Text(
                    text = targetTrack.title,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(16.dp))

                // Rename
                ListItem(
                    headlineContent = { Text("Rename Track") },
                    leadingContent = { Icon(Icons.Default.Edit, contentDescription = null) },
                    modifier = Modifier.combinedClickable(
                        onClick = {
                            showRenameDialog = targetTrack
                            trackForActionSheet = null
                        }
                    )
                )

                // Add to Playlist
                ListItem(
                    headlineContent = { Text("Add to Playlist") },
                    leadingContent = { Icon(Icons.Default.PlaylistAdd, contentDescription = null) },
                    modifier = Modifier.combinedClickable(
                        onClick = {
                            showAddToPlaylistDialog = targetTrack
                            trackForActionSheet = null
                        }
                    )
                )

                // Delete Track
                ListItem(
                    headlineContent = {
                        Text("Delete Track", color = MaterialTheme.colorScheme.error)
                    },
                    leadingContent = {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                    },
                    modifier = Modifier.combinedClickable(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                libraryRepository.deleteTrack(targetTrack)
                                refreshLibrary()
                            }
                            trackForActionSheet = null
                        }
                    )
                )
            }
        }
    }

    // Create Playlist Dialog
    if (showCreatePlaylistDialog) {
        var playlistName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreatePlaylistDialog = false },
            title = { Text("Create Playlist") },
            text = {
                OutlinedTextField(
                    value = playlistName,
                    onValueChange = { playlistName = it },
                    label = { Text("Playlist Name") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (playlistName.isNotBlank()) {
                            libraryRepository.savePlaylist(
                                Playlist(name = playlistName.trim(), created = System.currentTimeMillis())
                            )
                            refreshLibrary()
                            showCreatePlaylistDialog = false
                        }
                    }
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreatePlaylistDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Add to Playlist Dialog
    showAddToPlaylistDialog?.let { targetTrack ->
        AlertDialog(
            onDismissRequest = { showAddToPlaylistDialog = null },
            title = { Text("Select Playlist") },
            text = {
                if (playlists.isEmpty()) {
                    Text("No playlists created yet. Create one first.")
                } else {
                    Column {
                        playlists.forEach { playlist ->
                            TextButton(
                                onClick = {
                                    if (!playlist.trackIds.contains(targetTrack.id)) {
                                        playlist.trackIds.add(targetTrack.id)
                                        libraryRepository.savePlaylist(playlist)
                                        Toast.makeText(context, "Added to ${playlist.name}", Toast.LENGTH_SHORT).show()
                                    }
                                    showAddToPlaylistDialog = null
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(playlist.name)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showAddToPlaylistDialog = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Rename Dialog
    showRenameDialog?.let { targetTrack ->
        var newTitle by remember { mutableStateOf(targetTrack.title) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = null },
            title = { Text("Rename Track") },
            text = {
                OutlinedTextField(
                    value = newTitle,
                    onValueChange = { newTitle = it },
                    label = { Text("Title") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val updated = tracks.map {
                            if (it.id == targetTrack.id) it.copy(title = newTitle) else it
                        }
                        scope.launch(Dispatchers.IO) {
                            val adapter = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build().adapter<List<Track>>(
                                Types.newParameterizedType(List::class.java, Track::class.java)
                            )
                            val indexFile = File(context.filesDir, "library_index.json")
                            indexFile.writeText(adapter.toJson(updated))
                            refreshLibrary()
                        }
                        showRenameDialog = null
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

private fun formatMs(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
