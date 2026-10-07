package com.hyouka.quasarplayer.source

import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.ArtworkFactory
import java.io.File

object Id3TagWriter {
    fun embedId3Tags(mp3File: File, title: String, artist: String?, imageBytes: ByteArray?, mimeType: String = "image/jpeg") {
        if (!mp3File.exists()) return

        try {
            val audioFile = AudioFileIO.read(mp3File)
            var tag = audioFile.tagOrCreateAndSetDefault

            tag.setField(FieldKey.TITLE, title)

            if (!artist.isNullOrBlank()) {
                tag.setField(FieldKey.ARTIST, artist)
            }

            if (imageBytes != null && imageBytes.isNotEmpty()) {
                val artwork = ArtworkFactory.getNew()
                artwork.binaryData = imageBytes
                artwork.mimeType = mimeType
                artwork.description = ""
                artwork.isLinked = false

                tag.deleteArtworkField()
                tag.setField(artwork)
            }

            audioFile.commit()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
