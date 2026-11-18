package app

import "github.com/egoist/mygo/ui"

type playbackMode string

const (
	playbackMusic playbackMode = "music"
	playbackVideo playbackMode = "video"
)

// playbackPicked responds only to an explicit content/queue selection. Changing
// the preference, restoring playback or updating transport never opens a view.
func (a *app) playbackPicked() {
	if !a.quitting && a.pl.Current() != nil && a.settings.PlaybackMode == playbackVideo {
		a.requestVideoWindow()
	}
}

func (a *app) requestVideoWindow() {
	if a.mv.openPending {
		return
	}
	a.mv.openPending = true
	gen := a.mv.openGen
	a.post(func() {
		if gen != a.mv.openGen {
			return
		}
		a.mv.openPending = false
		if !a.quitting && a.pl.Current() != nil && a.settings.PlaybackMode == playbackVideo {
			a.openMVWindow(true)
		}
	})
}

func (a *app) choosePlaybackMode(mode playbackMode) {
	a.settings.PlaybackMode = mode
	// Invalidate already queued opens, including video -> music -> video
	// before the next event-loop turn. A fresh selection is required.
	a.mv.openGen++
	a.mv.openPending = false
	if mode == playbackMusic {
		a.settings.NowPlayingMedia = nowPlayingCover
		if win := a.mv.win; win != nil {
			// Native window destruction must happen after the current build.
			a.post(func() {
				if a.mv.win == win {
					win.Close()
				}
			})
		} else {
			a.syncInlineMV()
		}
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
