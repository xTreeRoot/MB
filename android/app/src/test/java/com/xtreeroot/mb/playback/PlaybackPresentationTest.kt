package com.xtreeroot.mb.playback

import com.xtreeroot.mb.preferences.PlaybackMode
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackPresentationTest {
    @Test fun restoredOrTransportedTrackDoesNotApplySavedVideoPreferenceUntilContentSelection() {
        val restored = PlaybackPresentation().update(supportsVideo = true)
        assertEquals(PlaybackMode.Music, restored.mode)
        assertEquals(0L, restored.opening)
        val explicit = restored.update(supportsVideo = true, contentPreference = PlaybackMode.Video)
        assertEquals(PlaybackMode.Video, explicit.mode)
        assertEquals(1L, explicit.opening)
        assertEquals(explicit, explicit.update(supportsVideo = true))
        assertEquals(2L, explicit.update(supportsVideo = true, contentPreference = PlaybackMode.Video).opening)
    }

    @Test fun playbackOfALocalTrackForcesMusicWithoutEatingTheSavedVideoPreference() {
        val video = PlaybackPresentation().update(supportsVideo = true, contentPreference = PlaybackMode.Video)
        val local = video.update(supportsVideo = false, contentPreference = PlaybackMode.Video)
        assertEquals(PlaybackMode.Music, local.mode)
        assertEquals(video.opening, local.opening)
        assertEquals(local, local.update(supportsVideo = true))
        assertEquals(PlaybackMode.Video, local.update(supportsVideo = true, contentPreference = PlaybackMode.Video).mode)
    }
}
