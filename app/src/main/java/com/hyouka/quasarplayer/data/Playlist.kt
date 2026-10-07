package com.hyouka.quasarplayer.data

data class Playlist(
    val name: String,
    val created: Long,
    val trackIds: MutableList<String> = mutableListOf()
)
