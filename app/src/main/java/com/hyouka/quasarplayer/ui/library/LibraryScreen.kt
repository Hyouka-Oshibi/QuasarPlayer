package com.hyouka.quasarplayer.ui.library

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.draw.shadow
import org.burnoutcrew.reorderable.*
import com.hyouka.quasarplayer.data.LibraryRepository
import com.hyouka.quasarplayer.data.Playlist
import com.hyouka.quasarplayer.data.Track
import com.hyouka.quasarplayer.playback.PlayerController
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

enum class SortMode {
    NAME, DATE_ADDED
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    libraryRepository: LibraryRepository,
    playerController: PlayerController
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var searchQuery by remember { mutableStateOf(libraryRepository.savedSearchQuery) }
    var sortMode by remember { 
        mutableStateOf(
            try { 
                SortMode.valueOf(libraryRepository.savedSortMode) 
            } catch (e: Exception) { 
                SortMode.DATE_ADDED 
            }
        ) 
    }

    var selectedTab by remember { mutableStateOf(LibraryTab.TRACKS) }
    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var playlists by remember { mutableStateOf<List<Playlist>>(emptyList()) }

    // Sync search query changes from other screens
    LaunchedEffect(libraryRepository.savedSearchQuery) {
        if (searchQuery != libraryRepository.savedSearchQuery) {
            searchQuery = libraryRepository.savedSearchQuery
        }
    }

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

    val filteredAndSortedTracks = remember(tracks, searchQuery, sortMode) {
        val filtered = if (searchQuery.isBlank()) tracks else tracks.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
            (it.artist?.contains(searchQuery, ignoreCase = true) == true)
        }
        when (sortMode) {
            SortMode.DATE_ADDED -> filtered.sortedByDescending { it.lastModified }
            SortMode.NAME -> filtered.sortedBy { it.title.lowercase() }
        }
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
                            if (selectedTrackIds.size == filteredAndSortedTracks.size) {
                                selectedTrackIds = emptySet()
                            } else {
                                selectedTrackIds = filteredAndSortedTracks.map { it.id }.toSet()
                            }
                        }) {
                            Icon(
                                if (selectedTrackIds.size == filteredAndSortedTracks.size) Icons.Default.SelectAll else Icons.Default.DoneAll,
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
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = {
                        searchQuery = ""
                        libraryRepository.savedSearchQuery = ""
                    }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear")
                    }
                }
            },
            singleLine = true
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Segmented Control Tabs
        PrimaryTabRow(selectedTabIndex = selectedTab.ordinal) {
            Tab(
                selected = selectedTab == LibraryTab.TRACKS,
                onClick = { selectedTab = LibraryTab.TRACKS },
                text = { Text("Tracks (${filteredAndSortedTracks.size})") }
            )
            Tab(
                selected = selectedTab == LibraryTab.PLAYLISTS,
                onClick = { selectedTab = LibraryTab.PLAYLISTS },
                text = { Text("Playlists (${playlists.size})") }
            )
        }

        // Tab Content
        when (selectedTab) {
            LibraryTab.TRACKS -> {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = {
                            sortMode = if (sortMode == SortMode.DATE_ADDED) SortMode.NAME else SortMode.DATE_ADDED
                            libraryRepository.savedSortMode = sortMode.name
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Sort by: " + if (sortMode == SortMode.DATE_ADDED) "Date Added" else "Name")
                    }
                }

                if (filteredAndSortedTracks.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(if (searchQuery.isBlank()) "No tracks found in Music/QuasarPlayer" else "No matching tracks")
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        itemsIndexed(filteredAndSortedTracks) { index, track ->
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
                                            playerController.playTrackList(filteredAndSortedTracks, index)
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
        var playlistTracks by remember(activePlaylist.trackIds, tracks) {
            mutableStateOf(activePlaylist.trackIds.mapNotNull { id -> tracks.find { it.id == id } })
        }
        val totalDurationMs = playlistTracks.sumOf { it.durationMs }
        val durationText = formatPlaylistDuration(totalDurationMs)
        val subtitleText = if (playlistTracks.isEmpty()) "0 tracks" else "${playlistTracks.size} tracks • $durationText"

        val reorderState = rememberReorderableLazyListState(onMove = { from, to ->
            playlistTracks = playlistTracks.toMutableList().apply {
                add(to.index, removeAt(from.index))
            }
        })

        val saveAndDismiss = {
            activePlaylist.trackIds.clear()
            activePlaylist.trackIds.addAll(playlistTracks.map { it.id })
            libraryRepository.savePlaylist(activePlaylist)
            refreshLibrary()
            playlistForDetailSheet = null
        }

        ModalBottomSheet(
            onDismissRequest = { saveAndDismiss() },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            modifier = Modifier.fillMaxHeight()
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
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
                                saveAndDismiss()
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
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No tracks in this playlist")
                    }
                } else {
                    LazyColumn(
                        state = reorderState.listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .reorderable(reorderState)
                    ) {
                        items(playlistTracks, key = { it.id }) { pTrack ->
                            ReorderableItem(reorderState, key = pTrack.id) { isDragging ->
                                val elevation by animateDpAsState(if (isDragging) 8.dp else 0.dp)
                                
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
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            IconButton(
                                                onClick = {
                                                    val updated = playlistTracks.toMutableList()
                                                    updated.remove(pTrack)
                                                    playlistTracks = updated
                                                }
                                            ) {
                                                Icon(
                                                    Icons.Default.Close,
                                                    contentDescription = "Remove from Playlist",
                                                    tint = MaterialTheme.colorScheme.error
                                                )
                                            }
                                            Icon(
                                                Icons.Default.DragHandle,
                                                contentDescription = "Reorder",
                                                modifier = Modifier.detectReorder(reorderState)
                                            )
                                        }
                                    },
                                    modifier = Modifier
                                        .shadow(elevation)
                                        .background(MaterialTheme.colorScheme.surface)
                                        .combinedClickable(
                                            onClick = {
                                                val pIndex = playlistTracks.indexOf(pTrack)
                                                playerController.playTrackList(playlistTracks, pIndex, playlistName = activePlaylist.name)
                                                saveAndDismiss()
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
