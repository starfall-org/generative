package org.starfall.generative.shared.helpers.audio

/** Container signatures only; raw PCM is intentionally excluded. */
fun audioMime(bytes: ByteArray): String? {
    fun at(offset: Int, value: String) = bytes.size >= offset + value.length &&
        bytes.copyOfRange(offset, offset + value.length).contentEquals(value.toByteArray(Charsets.US_ASCII))
    return when {
        at(0, "RIFF") && at(8, "WAVE") -> "audio/wav"
        at(0, "fLaC") -> "audio/flac"
        at(0, "OggS") -> "audio/ogg"
        at(0, "ID3") -> "audio/mpeg"
        bytes.size >= 2 && (bytes[0].toInt() and 255) == 255 && (bytes[1].toInt() and 246) == 240 -> "audio/aac"
        bytes.size >= 2 && (bytes[0].toInt() and 255) == 255 && (bytes[1].toInt() and 224) == 224 -> "audio/mpeg"
        at(4, "ftyp") && (at(8, "M4A ") || at(8, "M4B ")) -> "audio/mp4"
        else -> null
    }
}
