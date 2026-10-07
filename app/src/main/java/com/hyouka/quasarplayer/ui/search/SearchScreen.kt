package com.hyouka.quasarplayer.ui.search

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.hyouka.quasarplayer.data.LibraryRepository
import com.hyouka.quasarplayer.source.DownloadStatus
import com.hyouka.quasarplayer.source.Downloader
import com.hyouka.quasarplayer.source.SearchResult
import com.hyouka.quasarplayer.source.SourceExtractor
import com.hyouka.quasarplayer.source.SourceType
import com.hyouka.quasarplayer.ui.common.NetworkImage
import com.hyouka.quasarplayer.ui.library.PreviewSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.hyouka.quasarplayer.playback.PlayerController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    libraryRepository: LibraryRepository,
    sourceExtractor: SourceExtractor,
    downloader: Downloader,
    playerController: PlayerController,
    onLibraryUpdated: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current

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

    // Sync search query changes from other screens
    LaunchedEffect(libraryRepository.savedSearchQuery) {
        if (searchQuery != libraryRepository.savedSearchQuery) {
            searchQuery = libraryRepository.savedSearchQuery
            val cached = libraryRepository.getCachedResults(selectedSource, searchQuery)
            if (cached != null) {
                searchResults = cached
            } else if (searchQuery.isNotBlank()) {
                searchResults = emptyList()
            }
        }
    }

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Search Bar (Persistent search term across tabs)
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { newQuery ->
                searchQuery = newQuery
                libraryRepository.savedSearchQuery = newQuery

                if (newQuery.startsWith("http://") || newQuery.startsWith("https://")) {
                    scope.launch {
                        val directResult = sourceExtractor.parseDirectUrl(newQuery)
                        if (directResult != null) {
                            val instantList = listOf(directResult)
                            searchResults = instantList
                            libraryRepository.setCachedResults(selectedSource, newQuery, instantList)
                        }
                    }
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
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = {
                    keyboardController?.hide()
                    if (searchQuery.isNotBlank() && !isSearching) {
                        performSearch(searchQuery, selectedSource)
                    }
                }
            )
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

        Spacer(modifier = Modifier.height(8.dp))

        if (searchResults.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 8.dp)
            ) {
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
                                    Toast.makeText(context, "Downloading ${result.title}", Toast.LENGTH_SHORT).show()
                                    downloader.enqueueDownload(result) { file ->
                                        if (file != null) {
                                            onLibraryUpdated()
                                        }
                                    }
                                }
                            ) {
                                Icon(Icons.Default.Download, contentDescription = "Download Immediately")
                            }
                        },
                        modifier = Modifier.clickable {
                            previewResult = result
                        }
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    // Preview Sheet
    previewResult?.let { result ->
        PreviewSheet(
            searchResult = result,
            sourceExtractor = sourceExtractor,
            playerController = playerController,
            onDismiss = { previewResult = null },
            onDownloadRequested = { searchRes ->
                Toast.makeText(context, "Started download for ${searchRes.title}", Toast.LENGTH_SHORT).show()
                downloader.enqueueDownload(searchRes) { file ->
                    if (file != null) {
                        onLibraryUpdated()
                    } else {
                        Toast.makeText(context, "Download failed", Toast.LENGTH_SHORT).show()
                    }
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
