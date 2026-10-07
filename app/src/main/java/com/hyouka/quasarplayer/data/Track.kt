package com.hyouka.quasarplayer.data

data class Track(
    val id: String,
    val uri: String,
    val title: String,
    val artist: String? = null,
    val durationMs: Long = 0L,
    val hasEmbeddedArt: Boolean = false,
    val artworkPath: String? = null,
    val lastModified: Long = 0L
)
