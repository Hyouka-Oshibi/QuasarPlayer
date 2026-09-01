package com.hyouka.quasarplayer.data

/**
 * A single audio file in the library.
 *
 * [id] is derived from the content URI, not the filename, so renames and
 * playlist references stay valid across a rescan.
 */
data class Track(
    val id: String,
    val uri: String,
    val title: String,
    val artist: String? = null,
    val durationMs: Long = 0L,
    val hasEmbeddedArt: Boolean = false,
    val lastModified: Long = 0L
)
