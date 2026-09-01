package com.hyouka.quasarplayer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.hyouka.quasarplayer.data.LibraryRepository
import com.hyouka.quasarplayer.playback.PlayerController
import com.hyouka.quasarplayer.source.Downloader
import com.hyouka.quasarplayer.source.SourceExtractor
import com.hyouka.quasarplayer.ui.library.LibraryScreen
import com.hyouka.quasarplayer.ui.playing.PlayingScreen
import com.hyouka.quasarplayer.ui.settings.AppTheme
import com.hyouka.quasarplayer.ui.settings.SettingsRepository
import com.hyouka.quasarplayer.ui.settings.SettingsScreen
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext

sealed class Destination(val route: String, val label: String) {
    data object Library : Destination("library", "Library")
    data object Playing : Destination("playing", "Playing")
    data object Settings : Destination("settings", "Settings")
}

private val destinations = listOf(Destination.Library, Destination.Playing, Destination.Settings)

class MainActivity : ComponentActivity() {

    private lateinit var libraryRepository: LibraryRepository
    private lateinit var playerController: PlayerController
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var sourceExtractor: SourceExtractor
    private lateinit var downloader: Downloader

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        libraryRepository = LibraryRepository(applicationContext)
        playerController = PlayerController(applicationContext)
        settingsRepository = SettingsRepository(applicationContext)
        sourceExtractor = SourceExtractor(applicationContext)
        downloader = Downloader(applicationContext, libraryRepository)

        setContent {
            val themeState by settingsRepository.themeFlow.collectAsState(initial = AppTheme.SYSTEM)
            val darkTheme = when (themeState) {
                AppTheme.LIGHT -> false
                AppTheme.DARK -> true
                AppTheme.SYSTEM -> isSystemInDarkTheme()
            }

            val colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()

            // Request runtime permissions on launch
            RequestPermissionsOnLaunch()

            // Throttled yt-dlp update check on app launch
            LaunchedEffect(Unit) {
                withContext(Dispatchers.IO) {
                    try {
                        val lastCheck = settingsRepository.lastYtdlpCheckFlow.firstOrNull() ?: 0L
                        val now = System.currentTimeMillis()
                        if (now - lastCheck > 3 * 24 * 3600 * 1000L) { // 3 days
                            YoutubeDL.getInstance().updateYoutubeDL(applicationContext, YoutubeDL.UpdateChannel.STABLE)
                            settingsRepository.setLastYtdlpCheck(now)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }

            MaterialTheme(colorScheme = colorScheme) {
                AppScaffold(
                    libraryRepository = libraryRepository,
                    playerController = playerController,
                    settingsRepository = settingsRepository,
                    sourceExtractor = sourceExtractor,
                    downloader = downloader
                )
            }
        }
    }

    override fun onDestroy() {
        playerController.release()
        super.onDestroy()
    }
}

@Composable
private fun RequestPermissionsOnLaunch() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val permissionsToRequest = remember {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.READ_MEDIA_AUDIO)
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            list.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        list
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }

    LaunchedEffect(Unit) {
        val missing = permissionsToRequest.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            launcher.launch(missing.toTypedArray())
        }
    }
}

@Composable
private fun AppScaffold(
    libraryRepository: LibraryRepository,
    playerController: PlayerController,
    settingsRepository: SettingsRepository,
    sourceExtractor: SourceExtractor,
    downloader: Downloader
) {
    val navController = rememberNavController()

    Scaffold(
        bottomBar = {
            NavigationBar {
                val backStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = backStackEntry?.destination

                destinations.forEach { dest ->
                    val icon = when (dest) {
                        Destination.Library -> Icons.Filled.Folder
                        Destination.Playing -> Icons.Filled.PlayArrow
                        Destination.Settings -> Icons.Filled.Settings
                    }
                    val selected = currentDestination?.hierarchy?.any { it.route == dest.route } == true

                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(dest.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(icon, contentDescription = dest.label) },
                        label = { Text(dest.label) }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Destination.Library.route,
            modifier = Modifier.padding(padding),
            enterTransition = {
                val initialIndex = destinations.indexOfFirst { it.route == initialState.destination.route }
                val targetIndex = destinations.indexOfFirst { it.route == targetState.destination.route }
                if (targetIndex > initialIndex) {
                    slideInHorizontally(
                        initialOffsetX = { fullWidth -> fullWidth },
                        animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing)
                    )
                } else {
                    slideInHorizontally(
                        initialOffsetX = { fullWidth -> -fullWidth },
                        animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing)
                    )
                }
            },
            exitTransition = {
                val initialIndex = destinations.indexOfFirst { it.route == initialState.destination.route }
                val targetIndex = destinations.indexOfFirst { it.route == targetState.destination.route }
                if (targetIndex > initialIndex) {
                    slideOutHorizontally(
                        targetOffsetX = { fullWidth -> -fullWidth },
                        animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing)
                    )
                } else {
                    slideOutHorizontally(
                        targetOffsetX = { fullWidth -> fullWidth },
                        animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing)
                    )
                }
            }
        ) {
            composable(Destination.Library.route) {
                LibraryScreen(
                    libraryRepository = libraryRepository,
                    playerController = playerController,
                    sourceExtractor = sourceExtractor,
                    downloader = downloader
                )
            }
            composable(Destination.Playing.route) {
                PlayingScreen(playerController = playerController)
            }
            composable(Destination.Settings.route) {
                SettingsScreen(
                    settingsRepository = settingsRepository,
                    onRescanLibrary = { libraryRepository.scanLibrary() }
                )
            }
        }
    }
}
