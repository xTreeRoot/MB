package com.xtreeroot.mb.playback

import com.xtreeroot.mb.music.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class QueueSnapshotTest {
    @Test
    fun restoresTrackIdentityOrderPositionAndModesWithoutStreamUrls() {
        val track = Track("bilibili", "BVtrack", "歌", "歌手", "https://cover.example/1.jpg", 5_000)
        val original = QueueSnapshot(listOf(track, track.copy(sourceId = "plugin")), 1, 3210, 2, true, listOf(1, 0))
        val encoded = original.encode()
        assertEquals(original, QueueSnapshot.decode(encoded))
        assertFalse(encoded.contains("mediaUrl"))
    }

    @Test
    fun corruptSnapshotsDoNotRestoreAnInvalidSelection() {
        assertNull(QueueSnapshot.decode("{"))
        assertNull(QueueSnapshot.decode("""{"tracks":[]}"""))
        val restored = QueueSnapshot.decode("""{"tracks":[{"source":"bilibili","track":"BVtrack","title":"歌"}],
            "index":20,"position":-1,"repeat":10,"shuffleOrder":[3]}""")
        assertEquals(0, restored?.index)
        assertEquals(0L, restored?.positionMs)
        assertNull(restored?.shuffleOrder)
    }

    @Test fun portableQueueRestoresOnlyTheStationForItsSelectedSource() {
        val station = PlaybackStation("radio", "daily", "Daily mix")
        val tracks = listOf(Track("radio", "first", "Track 1", ""), Track("other", "second", "Track 2", ""))
        val snapshot = QueueSnapshot(tracks, 0, 3210, 0, false, station = station)
        assertEquals(station, QueueSnapshot.decode(snapshot.forPortableStorage().encode())?.station)
        assertNull(QueueSnapshot.decode(snapshot.copy(index = 1).encode())?.station)
    }
}
