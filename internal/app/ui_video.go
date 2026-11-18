package app

import "github.com/egoist/mygo/ui"

type playbackMode string

const (
	playbackMusic playbackMode = "music"
	playbackVideo playbackMode = "video"
)

// syncPlaybackMode opens only a newly selected track. Navigating away can then
// browse without a playback tick pulling the listener back into the video.
func (a *app) syncPlaybackMode() {
	track := a.pl.Current()
	if track == a.modeTrack {
		return
	}
	a.modeTrack = track
	if !a.quitting && track != nil && a.settings.PlaybackMode == playbackVideo {
		a.open(route{page: pageVideo, src: track.Source})
	}
}

func (a *app) choosePlaybackMode(mode playbackMode) {
	a.settings.PlaybackMode = mode
	a.modeTrack = a.pl.Current()
	if mode == playbackVideo {
		a.open(route{page: pageVideo})
	} else if a.route.page == pageVideo {
		for a.route.page == pageVideo && len(a.history) > 0 {
			a.back()
		}
		if a.route.page == pageVideo {
			a.open(route{page: pageHome})
		}
		// Leaving this mode must not leave a forward link to its video page.
		a.forward = nil
	}
	a.syncInlineMV()
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

// A native decoder can appear in only one view. While the expanded player is
// animating, it owns the picture until its layer has fully disappeared.
func (a *app) videoPageVisible() bool {
	return a.route.page == pageVideo && !a.nowPlaying && a.npT <= 0
}

func (a *app) videoPage(c *ui.Context) {
	top, bottom := a.insets(c)
	ui.Column(c.Key("video-page")).Absolute().Top(top + 12).Bottom(bottom + 16).Left(24).Right(24).Gap(14).Children(func() {
		ui.Box(c.Key("screen")).Grow(1).AlignSelf(ui.Stretch).MinHeight(0).Radius(12).Clip().Background(ui.RGB(0, 0, 0)).Children(func() {
			a.videoScreen(c)
		})
		if track := a.pl.Current(); track != nil {
			ui.Column(c).Gap(4).Children(func() {
				ui.Text(c, track.Name).FontSize(18).FontWeight(650).SingleLine().TextColor(a.pal.text)
				if artists := artistNames(track.Artists); artists != "" {
					ui.Text(c, artists).FontSize(13).SingleLine().TextColor(a.pal.muted)
				}
			})
		}
	})
}

func (a *app) videoScreen(c *ui.Context) {
	m := &a.mv
	message := ""
	switch {
	case a.pl.Current() == nil:
		message = T("Choose a track to watch from Home or your library.")
	case !a.canMV():
		message = T("This source does not provide video playback.")
	case m.win != nil:
		message = T("The video is playing in the mini player.")
	case !a.videoPageVisible():
		return
	case m.err != "" || m.video != nil && m.video.Err() != nil:
		message = T("Couldn't load the video. Try again.")
	default:
		if m.video != nil {
			ui.Video(c.Key("main-video"), m.video).Fill().Fit(ui.Contain).Label(T("Video Playback"))
		}
		if m.loading || m.video == nil || !m.video.Ready() || m.video.Buffering() {
			ui.Box(c).Absolute().Fill().Center().Children(func() { ui.Spinner(c) })
		}
		return
	}
	ui.Column(c.Key("video-state")).Fill().Padding(24).Center().Gap(14).Children(func() {
		ui.Text(c, message).TextColor(white.Alpha(0.8)).FontSize(14).TextAlign(ui.Center)
		if m.win != nil && a.canMV() {
			ui.Button(c, T("Play Video Here")).OnClick(func() {
				if m.win != nil {
					m.win.Close()
				}
			})
		} else if a.canMV() {
			ui.Button(c, T("Retry")).OnClick(func() { a.loadMV(m.key) })
		}
	})
}
