package com.hyouka.quasarplayer.source

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

object Id3TagWriter {

    /**
     * Embeds ID3v2.3 tags (Title, Artist, APIC cover art) into an MP3 file.
     */
    fun embedId3Tags(mp3File: File, title: String, artist: String?, imageBytes: ByteArray?, mimeType: String = "image/jpeg") {
        if (!mp3File.exists()) return

        try {
            val framesStream = ByteArrayOutputStream()

            // 1. TIT2 Frame (Title)
            writeTextFrame(framesStream, "TIT2", title)

            // 2. TPE1 Frame (Artist)
            if (!artist.isNullOrBlank()) {
                writeTextFrame(framesStream, "TPE1", artist)
            }

            // 3. APIC Frame (Cover Art)
            if (imageBytes != null && imageBytes.isNotEmpty()) {
                writeApicFrame(framesStream, imageBytes, mimeType)
            }

            val framesData = framesStream.toByteArray()
            val tagSize = framesData.size

            // ID3v2.3 Header (10 bytes)
            val header = ByteArray(10)
            header[0] = 'I'.code.toByte()
            header[1] = 'D'.code.toByte()
            header[2] = '3'.code.toByte()
            header[3] = 3 // ID3v2.3
            header[4] = 0 // Revision
            header[5] = 0 // Flags

            // Syncsafe integer for total tag size
            val syncsafe = encodeSyncsafe(tagSize)
            System.arraycopy(syncsafe, 0, header, 6, 4)

            // Read original file audio content
            val originalAudioBytes = mp3File.readBytes()

            // Skip existing ID3 tag if present
            var audioOffset = 0
            if (originalAudioBytes.size >= 10 &&
                originalAudioBytes[0] == 'I'.code.toByte() &&
                originalAudioBytes[1] == 'D'.code.toByte() &&
                originalAudioBytes[2] == '3'.code.toByte()) {
                val existingSize = decodeSyncsafe(originalAudioBytes.copyOfRange(6, 10))
                audioOffset = 10 + existingSize
            }

            val raf = RandomAccessFile(mp3File, "rw")
            raf.setLength(0) // Truncate
            raf.write(header)
            raf.write(framesData)
            if (audioOffset < originalAudioBytes.size) {
                raf.write(originalAudioBytes, audioOffset, originalAudioBytes.size - audioOffset)
            }
            raf.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun writeTextFrame(out: ByteArrayOutputStream, frameId: String, text: String) {
        val textBytes = text.toByteArray(Charsets.UTF_8)
        val payload = ByteArray(1 + textBytes.size)
        payload[0] = 3 // UTF-8 encoding
        System.arraycopy(textBytes, 0, payload, 1, textBytes.size)

        writeFrameHeader(out, frameId, payload.size)
        out.write(payload)
    }

    private fun writeApicFrame(out: ByteArrayOutputStream, imageBytes: ByteArray, mimeType: String) {
        val mimeBytes = mimeType.toByteArray(Charsets.ISO_8859_1)
        val payloadStream = ByteArrayOutputStream()

        payloadStream.write(0) // Encoding: ISO-8859-1
        payloadStream.write(mimeBytes)
        payloadStream.write(0) // Null terminator for MIME type
        payloadStream.write(3) // Picture type: 0x03 (Cover Front)
        payloadStream.write(0) // Description: empty string null terminator
        payloadStream.write(imageBytes)

        val payload = payloadStream.toByteArray()
        writeFrameHeader(out, "APIC", payload.size)
        out.write(payload)
    }

    private fun writeFrameHeader(out: ByteArrayOutputStream, frameId: String, size: Int) {
        out.write(frameId.toByteArray(Charsets.ISO_8859_1))
        out.write((size shr 24 and 0xFF))
        out.write((size shr 16 and 0xFF))
        out.write((size shr 8 and 0xFF))
        out.write((size and 0xFF))
        out.write(0) // Flags byte 1
        out.write(0) // Flags byte 2
    }

    private fun encodeSyncsafe(size: Int): ByteArray {
        val b = ByteArray(4)
        b[0] = ((size shr 21) and 0x7F).toByte()
        b[1] = ((size shr 14) and 0x7F).toByte()
        b[2] = ((size shr 7) and 0x7F).toByte()
        b[3] = (size and 0x7F).toByte()
        return b
    }

    private fun decodeSyncsafe(b: ByteArray): Int {
        return ((b[0].toInt() and 0x7F) shl 21) or
                ((b[1].toInt() and 0x7F) shl 14) or
                ((b[2].toInt() and 0x7F) shl 7) or
                (b[3].toInt() and 0x7F)
    }
}
