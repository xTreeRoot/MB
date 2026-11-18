package app

import "github.com/egoist/mygo/ui"

type playbackMode string

const (
	playbackMusic playbackMode = "music"
	playbackVideo playbackMode = "video"
)

// Playback changes request a window outside the current render. Browsing the
// main window never navigates away from its page when a new video is selected.
func (a *app) syncPlaybackMode() {
	track := a.pl.Current()
	if track == a.modeTrack {
		return
	}
	a.modeTrack = track
	if !a.quitting && track != nil && a.settings.PlaybackMode == playbackVideo {
		a.requestVideoWindow()
	}
}

func (a *app) requestVideoWindow() {
	if a.mv.openPending {
		return
	}
	a.mv.openPending = true
	a.post(func() {
		a.mv.openPending = false
		if !a.quitting && a.settings.PlaybackMode == playbackVideo {
			a.openMVWindow(true)
		}
	})
}

func (a *app) choosePlaybackMode(mode playbackMode) {
	a.settings.PlaybackMode = mode
	a.modeTrack = a.pl.Current()
	if mode == playbackVideo {
		a.requestVideoWindow()
	} else if a.mv.large && a.mv.win != nil {
		// Closing a native window during the segmented control's build would
		// reenter native drawing. Update queues the close after that frame.
		a.post(func() {
			if a.settings.PlaybackMode != playbackVideo && a.mv.large && a.mv.win != nil {
				a.mv.win.Close()
			}
		})
	}
	a.changed()
}

func (a *app) playbackModeSwitch(c *ui.Context) {
	selected := 0
	if a.settings.PlaybackMode == playbackVideo {
		selected = 1
	}
	if segmented(c.Key("playback-mode"), &selected, T("Music Mode"), T("Video Mode")) {
		mode := playbackMusic
		if selected == 1 {
			mode = playbackVideo
		}
		a.choosePlaybackMode(mode)
	}
}
