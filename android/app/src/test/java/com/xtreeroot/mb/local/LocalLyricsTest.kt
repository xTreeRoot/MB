package com.xtreeroot.mb.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalLyricsTest {
    private val lyric = "[00:01.25]第一行\n[00:03.00]第二行"

    @Test fun readsTimedUtf8AndUtf16ButRejectsUntimedOrOversizedSidecars() {
        assertEquals(lyric, decodeLrcDocument(("\uFEFF" + lyric).encodeToByteArray()))
        assertEquals(lyric, decodeLrcDocument(lyric.toByteArray(Charsets.UTF_16)))
        assertEquals(1_250L, parseLrc(checkNotNull(decodeLrcDocument(lyric.encodeToByteArray()))).first().startMs)
        assertNull(decodeLrcDocument("not a timed lyric".encodeToByteArray()))
        assertNull(decodeLrcDocument(lyric.encodeToByteArray().copyOf(maximumLyricBytes + 1)))
    }

    @Test fun decodesId3WithoutIncludingLanguageOrDescription() {
        val utf8 = byteArrayOf(3) + "zhoPreferred\u0000".encodeToByteArray() + lyric.encodeToByteArray()
        val latin = byteArrayOf(0) + "eng\u0000[00:01.00]café".toByteArray(Charsets.ISO_8859_1)
        assertEquals(lyric, decodeId3Lyrics(utf8))
        assertEquals("[00:01.00]café", decodeId3Lyrics(latin))
        assertNull(decodeId3Lyrics(byteArrayOf(3) + "engunterminated".encodeToByteArray()))
    }

    @Test fun usesAlignedUtf16DelimitersAndHonorsByteOrder() {
        val language = "eng".encodeToByteArray()
        val littleEndian = byteArrayOf(1) + language + "\uFEFFNotes\u0000".toByteArray(Charsets.UTF_16LE) + lyric.toByteArray(Charsets.UTF_16LE)
        val bigEndian = byteArrayOf(2) + language + "Notes\u0000$lyric".toByteArray(Charsets.UTF_16BE)
        assertEquals(lyric, decodeId3Lyrics(littleEndian))
        assertEquals(lyric, decodeId3Lyrics(bigEndian))
    }
}
