package com.hyouka.quasarplayer.ui.library

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
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

    var searchResults by remember {
        mutableStateOf<List<SearchResult>>(
            libraryRepository.getCachedResults(selectedSource, searchQuery) ?: emptyList()
        )
    }

    var isSearching by remember { mutableStateOf(false) }
    var previewResult by remember { mutableStateOf<SearchResult?>(null) }

    val activeDownloads by downloader.activeDownloads.collectAsState()

    var selectedTab by remember { mutableStateOf(LibraryTab.TRACKS) }
    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var playlists by remember { mutableStateOf<List<Playlist>>(emptyList()) }

    // Multi-Selection State
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedTrackIds by remember { mutableStateOf(setOf<String>()) }
    var showBatchDeleteConfirmation by remember { mutableStateOf(false) }
    var showBatchAddToPlaylistDialog by remember { mutableStateOf(false) }

    // Dialog & Sheet States
    var trackForActionSheet by remember { mutableStateOf<Track?>(null) }
    var playlistForDetailSheet by remember { mutableStateOf<Playlist?>(null) }
    var playlistToDelete by remember { mutableStateOf<Playlist?>(null) }
    var showRenameDialog by remember { mutableStateOf<Track?>(null) }
    var showAddToPlaylistDialog by remember { mutableStateOf<Track?>(null) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }

    val performSearch: (String, SourceType) -> Unit = { queryToSearch, sourceToSearch ->
        val trimmed = queryToSearch.trim()
        if (trimmed.isNotBlank()) {
            scope.launch {
                isSearching = true
                val cached = libraryRepository.getCachedResults(sourceToSearch, trimmed)
                if (cached != null) {
                    if (selectedSource == sourceToSearch && searchQuery.trim() == trimmed) {
                        searchResults = cached
                    }
                    isSearching = false
                } else {
                    val res = sourceExtractor.search(trimmed, sourceToSearch)
                    libraryRepository.setCachedResults(sourceToSearch, trimmed, res)
                    if (selectedSource == sourceToSearch && searchQuery.trim() == trimmed) {
                        searchResults = res
                    }
                    isSearching = false
                }
            }
        }
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
        // Multi-Select Action Bar (shown when tracks are selected)
        if (isSelectionMode) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = {
                            isSelectionMode = false
                            selectedTrackIds = emptySet()
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "Exit Selection")
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "${selectedTrackIds.size} Selected",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Select All / Deselect All
                        IconButton(onClick = {
                            if (selectedTrackIds.size == tracks.size) {
                                selectedTrackIds = emptySet()
                            } else {
                                selectedTrackIds = tracks.map { it.id }.toSet()
                            }
                        }) {
                            Icon(
                                if (selectedTrackIds.size == tracks.size) Icons.Default.SelectAll else Icons.Default.DoneAll,
                                contentDescription = "Select All"
                            )
                        }

                        // Add Selected to Playlist
                        IconButton(
                            onClick = { showBatchAddToPlaylistDialog = true },
                            enabled = selectedTrackIds.isNotEmpty()
                        ) {
                            Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = "Add Selected to Playlist")
                        }

                        // Delete Selected
                        IconButton(
                            onClick = { showBatchDeleteConfirmation = true },
                            enabled = selectedTrackIds.isNotEmpty()
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Delete Selected",
                                tint = if (selectedTrackIds.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            )
                        }
                    }
                }
            }
        }

        // Search Bar (Persistent search term across tabs)
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { newQuery ->
                searchQuery = newQuery
                libraryRepository.savedSearchQuery = newQuery

                val instantDirectResult = sourceExtractor.parseDirectUrlInstant(newQuery)
                if (instantDirectResult != null) {
                    val instantList = listOf(instantDirectResult)
                    searchResults = instantList
                    libraryRepository.setCachedResults(selectedSource, newQuery, instantList)
                } else {
                    val cached = libraryRepository.getCachedResults(selectedSource, newQuery)
                    if (cached != null) {
                        searchResults = cached
                    } else if (newQuery.isBlank()) {
                        searchResults = emptyList()
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search or link") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = {
                        searchQuery = ""
                        searchResults = emptyList()
                        libraryRepository.savedSearchQuery = ""
                    }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear")
                    }
                }
            },
            singleLine = true
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Source Chips and Search Button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = selectedSource == SourceType.YOUTUBE,
                    onClick = {
                        if (selectedSource != SourceType.YOUTUBE) {
                            selectedSource = SourceType.YOUTUBE
                            libraryRepository.savedSelectedSource = SourceType.YOUTUBE

                            val cached = libraryRepository.getCachedResults(SourceType.YOUTUBE, searchQuery)
                            if (cached != null) {
                                searchResults = cached
                            } else if (searchQuery.isNotBlank()) {
                                searchResults = emptyList()
                                performSearch(searchQuery, SourceType.YOUTUBE)
                            }
                        }
                    },
                    label = { Text("YouTube") }
                )
                FilterChip(
                    selected = selectedSource == SourceType.SOUNDCLOUD,
                    onClick = {
                        if (selectedSource != SourceType.SOUNDCLOUD) {
                            selectedSource = SourceType.SOUNDCLOUD
                            libraryRepository.savedSelectedSource = SourceType.SOUNDCLOUD

                            val cached = libraryRepository.getCachedResults(SourceType.SOUNDCLOUD, searchQuery)
                            if (cached != null) {
                                searchResults = cached
                            } else if (searchQuery.isNotBlank()) {
                                searchResults = emptyList()
                                performSearch(searchQuery, SourceType.SOUNDCLOUD)
                            }
                        }
                    },
                    label = { Text("SoundCloud") }
                )
            }

            // Search button matching FilterChip styling
            FilterChip(
                selected = isSearching,
                onClick = {
                    if (searchQuery.isNotBlank() && !isSearching) {
                        performSearch(searchQuery, selectedSource)
                    }
                },
                enabled = searchQuery.isNotBlank(),
                label = {
                    if (isSearching) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Search")
                    }
                },
                leadingIcon = {
                    if (!isSearching) {
                        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                }
            )
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
                            headlineContent = {
                                Text(
                                    text = result.title,
                                    maxLines = 1,
                                    modifier = Modifier.basicMarquee()
                                )
                            },
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
                                    modifier = Modifier
                                        .weight(1f)
                                        .basicMarquee()
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
                            val isSelected = selectedTrackIds.contains(track.id)

                            ListItem(
                                headlineContent = {
                                    Text(
                                        text = track.title,
                                        maxLines = 1,
                                        modifier = Modifier.basicMarquee()
                                    )
                                },
                                supportingContent = {
                                    Text(
                                        "${track.artist ?: "Unknown Artist"} • ${formatMs(track.durationMs)}",
                                        maxLines = 1
                                    )
                                },
                                leadingContent = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (isSelectionMode) {
                                            Checkbox(
                                                checked = isSelected,
                                                onCheckedChange = { checked ->
                                                    selectedTrackIds = if (checked) {
                                                        selectedTrackIds + track.id
                                                    } else {
                                                        selectedTrackIds - track.id
                                                    }
                                                }
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                        }
                                        TrackArt(
                                            track = track,
                                            modifier = Modifier.size(48.dp),
                                            iconSize = 24.dp
                                        )
                                    }
                                },
                                modifier = Modifier.combinedClickable(
                                    onClick = {
                                        if (isSelectionMode) {
                                            selectedTrackIds = if (isSelected) {
                                                selectedTrackIds - track.id
                                            } else {
                                                selectedTrackIds + track.id
                                            }
                                        } else {
                                            playerController.playTrackList(tracks, index)
                                        }
                                    },
                                    onLongClick = {
                                        if (!isSelectionMode) {
                                            trackForActionSheet = track
                                        }
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
                                val playlistTracks = tracks.filter { playlist.trackIds.contains(it.id) }
                                val totalDurationMs = playlistTracks.sumOf { it.durationMs }
                                val durationText = formatPlaylistDuration(totalDurationMs)
                                val subtitleText = if (playlistTracks.isEmpty()) "0 tracks" else "${playlistTracks.size} tracks • $durationText"

                                ListItem(
                                    headlineContent = { Text(playlist.name) },
                                    supportingContent = { Text(subtitleText) },
                                    leadingContent = { Icon(Icons.Default.QueueMusic, contentDescription = null) },
                                    trailingContent = {
                                        IconButton(onClick = {
                                            playlistToDelete = playlist
                                        }) {
                                            Icon(
                                                Icons.Default.Delete,
                                                contentDescription = "Delete Playlist",
                                                tint = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    },
                                    modifier = Modifier.combinedClickable(
                                        onClick = {
                                            playlistForDetailSheet = playlist
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

    // Playlist Detail & Editing Bottom Sheet
    playlistForDetailSheet?.let { activePlaylist ->
        val playlistTracks = tracks.filter { activePlaylist.trackIds.contains(it.id) }
        val totalDurationMs = playlistTracks.sumOf { it.durationMs }
        val durationText = formatPlaylistDuration(totalDurationMs)
        val subtitleText = if (playlistTracks.isEmpty()) "0 tracks" else "${playlistTracks.size} tracks • $durationText"

        ModalBottomSheet(onDismissRequest = { playlistForDetailSheet = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = activePlaylist.name,
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.basicMarquee()
                        )
                        Text(
                            text = subtitleText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Button(
                        onClick = {
                            if (playlistTracks.isNotEmpty()) {
                                playerController.playTrackList(playlistTracks, 0, playlistName = activePlaylist.name)
                                playlistForDetailSheet = null
                            } else {
                                Toast.makeText(context, "Playlist is empty", Toast.LENGTH_SHORT).show()
                            }
                        },
                        enabled = playlistTracks.isNotEmpty()
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Play All")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (playlistTracks.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No tracks in this playlist")
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp)
                    ) {
                        itemsIndexed(playlistTracks) { pIndex, pTrack ->
                            ListItem(
                                headlineContent = {
                                    Text(
                                        text = pTrack.title,
                                        maxLines = 1,
                                        modifier = Modifier.basicMarquee()
                                    )
                                },
                                supportingContent = {
                                    Text(
                                        text = pTrack.artist ?: "Unknown Artist",
                                        maxLines = 1
                                    )
                                },
                                leadingContent = {
                                    TrackArt(
                                        track = pTrack,
                                        modifier = Modifier.size(40.dp),
                                        iconSize = 20.dp
                                    )
                                },
                                trailingContent = {
                                    IconButton(
                                        onClick = {
                                            activePlaylist.trackIds.remove(pTrack.id)
                                            libraryRepository.savePlaylist(activePlaylist)
                                            refreshLibrary()
                                        }
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Remove from Playlist",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                },
                                modifier = Modifier.combinedClickable(
                                    onClick = {
                                        playerController.playTrackList(playlistTracks, pIndex, playlistName = activePlaylist.name)
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

    // Single Track Long-Press Action Sheet
    trackForActionSheet?.let { targetTrack ->
        ModalBottomSheet(onDismissRequest = { trackForActionSheet = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                Text(
                    text = targetTrack.title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.basicMarquee()
                )
                Spacer(modifier = Modifier.height(16.dp))

                // Enter Multi-Select Mode
                ListItem(
                    headlineContent = { Text("Select / Multi-Select") },
                    leadingContent = { Icon(Icons.Default.CheckBox, contentDescription = null) },
                    modifier = Modifier.combinedClickable(
                        onClick = {
                            isSelectionMode = true
                            selectedTrackIds = setOf(targetTrack.id)
                            trackForActionSheet = null
                        }
                    )
                )

                // Rename Track
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
                    leadingContent = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null) },
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

    // Playlist Deletion Confirmation Dialog
    playlistToDelete?.let { targetPlaylist ->
        val pCount = tracks.count { targetPlaylist.trackIds.contains(it.id) }
        AlertDialog(
            onDismissRequest = { playlistToDelete = null },
            title = { Text("Delete Playlist '${targetPlaylist.name}'?") },
            text = { Text("Are you sure you want to delete this playlist with $pCount tracks? (Your audio files will not be deleted)") },
            confirmButton = {
                Button(
                    onClick = {
                        libraryRepository.deletePlaylist(targetPlaylist.name)
                        refreshLibrary()
                        playlistToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { playlistToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Batch Track Deletion Confirmation Dialog
    if (showBatchDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showBatchDeleteConfirmation = false },
            title = { Text("Delete ${selectedTrackIds.size} Tracks?") },
            text = { Text("Are you sure you want to permanently delete ${selectedTrackIds.size} tracks from storage?") },
            confirmButton = {
                Button(
                    onClick = {
                        val tracksToDelete = tracks.filter { selectedTrackIds.contains(it.id) }
                        scope.launch(Dispatchers.IO) {
                            tracksToDelete.forEach { libraryRepository.deleteTrack(it) }
                            withContext(Dispatchers.Main) {
                                isSelectionMode = false
                                selectedTrackIds = emptySet()
                                refreshLibrary()
                            }
                        }
                        showBatchDeleteConfirmation = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDeleteConfirmation = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Batch Add to Playlist Dialog
    if (showBatchAddToPlaylistDialog) {
        AlertDialog(
            onDismissRequest = { showBatchAddToPlaylistDialog = false },
            title = { Text("Add ${selectedTrackIds.size} Tracks to Playlist") },
            text = {
                if (playlists.isEmpty()) {
                    Text("No playlists created yet. Create one first.")
                } else {
                    Column {
                        playlists.forEach { playlist ->
                            TextButton(
                                onClick = {
                                    val newTrackIds = selectedTrackIds.filterNot { playlist.trackIds.contains(it) }
                                    if (newTrackIds.isNotEmpty()) {
                                        playlist.trackIds.addAll(newTrackIds)
                                        libraryRepository.savePlaylist(playlist)
                                        Toast.makeText(context, "Added ${newTrackIds.size} tracks to ${playlist.name}", Toast.LENGTH_SHORT).show()
                                        refreshLibrary()
                                    }
                                    showBatchAddToPlaylistDialog = false
                                    isSelectionMode = false
                                    selectedTrackIds = emptySet()
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
                TextButton(onClick = { showBatchAddToPlaylistDialog = false }) {
                    Text("Cancel")
                }
            }
        )
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

    // Add Single Track to Playlist Dialog
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
                                        refreshLibrary()
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

private fun formatPlaylistDuration(totalMs: Long): String {
    if (totalMs <= 0) return "0:00"
    val totalSeconds = totalMs / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    return when {
        hours > 0 -> "%d hr %d min".format(hours, minutes)
        minutes > 0 -> "%d:%02d".format(minutes, seconds)
        else -> "%d sec".format(seconds)
    }
}
