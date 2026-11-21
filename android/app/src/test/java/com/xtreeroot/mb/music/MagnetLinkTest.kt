package com.xtreeroot.mb.music
import org.junit.Assert.*
import org.junit.Test
class MagnetLinkTest {
    @Test fun directLinkIsImportableWithoutBeingPresentInTheSearchIndex() {
        val link = "magnet:?xt=urn:btih:0123456789ABCDEF0123456789ABCDEF01234567&dn=My%20video&tr=udp%3A%2F%2Ftracker.example%3A80"
        val track = checkNotNull(TorrentSearchSource.directMagnet(link))
        assertEquals("My video", track.title)
        assertEquals(link, track.resourceUrl)
        assertEquals("other", track.resourceType)
        assertEquals("magnet:0123456789abcdef0123456789abcdef01234567", track.sourceTrackId)
    }
    @Test fun rejectsBrokenHashesAndAcceptsBase32AndV2Multihash() {
        for (link in listOf("magnet:?xt=urn:btih:30D1F776C01C1F67FA425D4FA683D0AFA21A1E4", "magnet:?xt=urn:btih:broken", "magnet:?xt=https://evil", "magnet:?xt=urn:btih:" + "a".repeat(40) + "\nextra", "magnet:?xt=%GG")) assertNull(TorrentSearchSource.directMagnet(link))
        assertNotNull(TorrentSearchSource.directMagnet("magnet:?xt=urn:btih:30D1F776C01C1F67FA425D4FA683D0AFA21AA1E4"))
        assertNotNull(TorrentSearchSource.directMagnet("magnet:?xt=urn:btih:" + "A".repeat(32)))
        assertNotNull(TorrentSearchSource.directMagnet("magnet:?xt=urn:btmh:1220" + "a".repeat(64)))
    }
}
