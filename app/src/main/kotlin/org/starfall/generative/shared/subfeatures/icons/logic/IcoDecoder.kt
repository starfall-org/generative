package org.starfall.generative.shared.subfeatures.icons.logic

import android.graphics.Bitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** ICO entries contain either PNG or Windows DIB pixels plus an optional transparency mask. */
internal fun decodeIco(bytes: ByteArray, decodePng: (ByteArray) -> Bitmap?): Bitmap? = runCatching {
    val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    fun u16(at: Int) = data.getShort(at).toInt() and 0xffff
    val count = u16(4)
    require(count in 1..256 && 6L + count * 16L <= bytes.size)
    val entries = (0 until count).sortedByDescending { i ->
        val at = 6 + i * 16
        val width = (bytes[at].toInt() and 255).let { if (it == 0) 256 else it }
        val height = (bytes[at + 1].toInt() and 255).let { if (it == 0) 256 else it }
        width * height
    }
    for (i in entries) {
        val at = 6 + i * 16
        val length = data.getInt(at + 8)
        val offset = data.getInt(at + 12)
        if (length <= 0 || offset < 6 + count * 16 || offset.toLong() + length > bytes.size) continue
        val image = bytes.copyOfRange(offset, offset + length)
        val bitmap = if (image.size >= 8 && image[0] == 0x89.toByte() && image[1] == 0x50.toByte()) decodePng(image)
            else runCatching { decodeIconDib(image) }.getOrNull()
        if (bitmap != null) return@runCatching bitmap
    }
    null
}.getOrNull()

private fun decodeIconDib(bytes: ByteArray): Bitmap {
    val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val header = data.getInt(0)
    require(header >= 40 && header <= bytes.size)
    val width = data.getInt(4)
    val storedHeight = data.getInt(8)
    require(width in 1..256 && storedHeight in 2..512 && storedHeight % 2 == 0)
    val height = storedHeight / 2
    require((data.getShort(12).toInt() and 0xffff) == 1 && data.getInt(16) == 0)
    val bits = data.getShort(14).toInt() and 0xffff
    require(bits in listOf(1, 4, 8, 24, 32))
    val colors = if (bits <= 8) data.getInt(32).takeIf { it != 0 } ?: (1 shl bits) else 0
    require(colors in 0..256)
    val start = header + colors * 4
    val stride = ((width * bits + 31) / 32) * 4
    val maskStart = start + stride * height
    require(maskStart <= bytes.size)
    val pixels = IntArray(width * height)
    var hasAlpha = false
    fun u8(at: Int) = bytes[at].toInt() and 255
    for (y in 0 until height) for (x in 0 until width) {
        val row = start + (height - 1 - y) * stride
        val pos = if (bits <= 8) {
            val packed = u8(row + x * bits / 8)
            val index = (packed shr (8 - bits - (x * bits % 8))) and ((1 shl bits) - 1)
            require(index < colors)
            header + index * 4
        } else row + x * (bits / 8)
        val alpha = if (bits == 32) u8(pos + 3) else 255
        if (alpha > 0) hasAlpha = true
        pixels[y * width + x] = (alpha shl 24) or (u8(pos + 2) shl 16) or (u8(pos + 1) shl 8) or u8(pos)
    }
    val maskStride = ((width + 31) / 32) * 4
    val hasMask = maskStart + maskStride * height <= bytes.size
    require(hasMask || bits == 32 && hasAlpha)
    for (y in 0 until height) for (x in 0 until width) {
        val index = y * width + x
        if (bits == 32 && !hasAlpha) pixels[index] = pixels[index] or (255 shl 24)
        if (hasMask && (u8(maskStart + (height - 1 - y) * maskStride + x / 8) and (128 shr (x % 8))) != 0)
            pixels[index] = pixels[index] and 0x00ffffff
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}
