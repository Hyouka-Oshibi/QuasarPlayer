package com.hyouka.quasarplayer.ui.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsRepository: SettingsRepository,
    onRescanLibrary: suspend () -> Unit,
    onCheckIntegrity: suspend () -> Int
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val currentTheme by settingsRepository.themeFlow.collectAsState(initial = AppTheme.SYSTEM)
    val currentFolder by settingsRepository.musicFolderFlow.collectAsState(initial = "Music/QuasarPlayer")

    var isUpdatingDownloader by remember { mutableStateOf(false) }
    var isScanning by remember { mutableStateOf(false) }
    var ytdlpVersion by remember { mutableStateOf("Checking...") }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                ytdlpVersion = YoutubeDL.getInstance().version(context) ?: "Unknown"
            } catch (e: Exception) {
                ytdlpVersion = "Not initialized"
            }
        }
    }

    val safFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                context.contentResolver.takePersistableUriPermission(it, takeFlags)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            scope.launch {
                settingsRepository.setMusicFolder(it.toString())
                Toast.makeText(context, "Music folder updated", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // Theme Setting
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "Theme", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AppTheme.entries.forEach { theme ->
                        FilterChip(
                            selected = currentTheme == theme,
                            onClick = {
                                scope.launch { settingsRepository.setTheme(theme) }
                            },
                            label = { Text(theme.name.lowercase().replaceFirstChar { it.uppercase() }) }
                        )
                    }
                }
            }
        }

        // Music Folder Setting
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "Music Folder", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = currentFolder,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { safFolderLauncher.launch(null) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Change Folder")
                }
            }
        }

        // Rescan Library Button
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "Library Index", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        scope.launch {
                            isScanning = true
                            try {
                                onRescanLibrary()
                                Toast.makeText(context, "Library rescanned", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                Toast.makeText(context, "Rescan failed: ${e.message}", Toast.LENGTH_SHORT).show()
                            } finally {
                                isScanning = false
                            }
                        }
                    },
                    enabled = !isScanning,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isScanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Scanning...")
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Rescan Library")
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                var isCheckingIntegrity by remember { mutableStateOf(false) }
                
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            isCheckingIntegrity = true
                            try {
                                val brokenCount = onCheckIntegrity()
                                if (brokenCount > 0) {
                                    Toast.makeText(context, "Found and deleted $brokenCount broken files", Toast.LENGTH_LONG).show()
                                } else {
                                    Toast.makeText(context, "All files are healthy!", Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: Exception) {
                                Toast.makeText(context, "Integrity check failed: ${e.message}", Toast.LENGTH_SHORT).show()
                            } finally {
                                isCheckingIntegrity = false
                            }
                        }
                    },
                    enabled = !isCheckingIntegrity && !isScanning,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isCheckingIntegrity) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Checking Integrity...")
                    } else {
                        Icon(Icons.Default.Warning, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Check File Integrity")
                    }
                }
            }
        }

        // Update Downloader Button
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "yt-dlp Downloader", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Current version: $ytdlpVersion",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        scope.launch {
                            isUpdatingDownloader = true
                            withContext(Dispatchers.IO) {
                                try {
                                    val status = YoutubeDL.getInstance().updateYoutubeDL(
                                        context,
                                        YoutubeDL.UpdateChannel.STABLE
                                    )
                                    val newVer = YoutubeDL.getInstance().version(context) ?: "Unknown"
                                    withContext(Dispatchers.Main) {
                                        ytdlpVersion = newVer
                                        Toast.makeText(
                                            context,
                                            "yt-dlp updated: ${status?.name ?: "Done"}",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(
                                            context,
                                            "Update failed: ${e.message}",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                } finally {
                                    withContext(Dispatchers.Main) {
                                        isUpdatingDownloader = false
                                    }
                                }
                            }
                        }
                    },
                    enabled = !isUpdatingDownloader,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isUpdatingDownloader) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Updating yt-dlp...")
                    } else {
                        Icon(Icons.Default.SystemUpdate, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Update Downloader")
                    }
                }
            }
        }
    }
}
