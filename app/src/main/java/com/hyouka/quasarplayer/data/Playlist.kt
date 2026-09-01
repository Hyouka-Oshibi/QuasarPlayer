package com.hyouka.quasarplayer.data

/**
 * Stored as one JSON file per playlist under the app's playlists directory.
 * trackIds reference Track.id, never filenames directly.
 */
data class Playlist(
    val name: String,
    val created: Long,
    val trackIds: MutableList<String> = mutableListOf()
)
