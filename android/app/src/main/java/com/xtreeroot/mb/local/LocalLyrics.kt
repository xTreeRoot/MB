package com.xtreeroot.mb.local

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset

internal const val maximumLyricBytes = 2 * 1024 * 1024

internal fun decodeLrcDocument(bytes: ByteArray): String? {
    if (bytes.isEmpty() || bytes.size > maximumLyricBytes) return null
    return (decodeText(bytes, Charsets.UTF_8) ?: decodeText(bytes, Charsets.UTF_16))
        ?.removePrefix("\uFEFF")?.takeIf { parseLrc(it).isNotEmpty() }
}

internal fun decodeId3Lyrics(data: ByteArray): String? {
    if (data.size !in 6..maximumLyricBytes) return null
    val encoding = data.first().toInt()
    val width = if (encoding == 1 || encoding == 2) 2 else 1
    val delimiter = (4..data.size - width step width).firstOrNull { position ->
        data[position] == 0.toByte() && (width == 1 || data[position + 1] == 0.toByte())
    } ?: return null
    val start = delimiter + width
    if (start >= data.size) return null
    fun littleEndianBom(position: Int) = position + 1 < data.size && data[position] == 0xFF.toByte() && data[position + 1] == 0xFE.toByte()
    val charset = when (encoding) {
        0 -> Charsets.ISO_8859_1
        1 -> {
            val bigEndianBom = data[start] == 0xFE.toByte() && start + 1 < data.size && data[start + 1] == 0xFF.toByte()
            if (littleEndianBom(start) || (!bigEndianBom && littleEndianBom(4))) Charsets.UTF_16LE else Charsets.UTF_16BE
        }
        2 -> Charsets.UTF_16BE
        3 -> Charsets.UTF_8
        else -> return null
    }
    return decodeText(data.copyOfRange(start, data.size), charset)?.trimEnd('\u0000')?.removePrefix("\uFEFF")
}

private fun decodeText(bytes: ByteArray, charset: Charset): String? = try {
    charset.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
} catch (_: CharacterCodingException) { null }
