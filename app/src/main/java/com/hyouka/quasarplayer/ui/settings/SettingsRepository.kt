package com.hyouka.quasarplayer.ui.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

enum class AppTheme {
    SYSTEM, LIGHT, DARK
}

class SettingsRepository(private val context: Context) {

    private object PreferencesKeys {
        val THEME = stringPreferencesKey("app_theme")
        val LOOP_DEFAULT = intPreferencesKey("loop_default")
        val SHUFFLE_DEFAULT = booleanPreferencesKey("shuffle_default")
        val MUSIC_FOLDER = stringPreferencesKey("music_folder")
        val LAST_YTDLP_CHECK = longPreferencesKey("last_ytdlp_check")
    }

    val themeFlow: Flow<AppTheme> = context.dataStore.data.map { prefs ->
        when (prefs[PreferencesKeys.THEME]) {
            "LIGHT" -> AppTheme.LIGHT
            "DARK" -> AppTheme.DARK
            else -> AppTheme.SYSTEM
        }
    }

    val loopDefaultFlow: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[PreferencesKeys.LOOP_DEFAULT] ?: 0
    }

    val shuffleDefaultFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[PreferencesKeys.SHUFFLE_DEFAULT] ?: false
    }

    val musicFolderFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[PreferencesKeys.MUSIC_FOLDER] ?: "Music/QuasarPlayer"
    }

    val lastYtdlpCheckFlow: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[PreferencesKeys.LAST_YTDLP_CHECK] ?: 0L
    }

    suspend fun setTheme(theme: AppTheme) {
        context.dataStore.edit { prefs ->
            prefs[PreferencesKeys.THEME] = theme.name
        }
    }

    suspend fun setLoopDefault(loopMode: Int) {
        context.dataStore.edit { prefs ->
            prefs[PreferencesKeys.LOOP_DEFAULT] = loopMode
        }
    }

    suspend fun setShuffleDefault(shuffle: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[PreferencesKeys.SHUFFLE_DEFAULT] = shuffle
        }
    }

    suspend fun setMusicFolder(pathOrUri: String) {
        context.dataStore.edit { prefs ->
            prefs[PreferencesKeys.MUSIC_FOLDER] = pathOrUri
        }
    }

    suspend fun setLastYtdlpCheck(timestamp: Long) {
        context.dataStore.edit { prefs ->
            prefs[PreferencesKeys.LAST_YTDLP_CHECK] = timestamp
        }
    }
}
