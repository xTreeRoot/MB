package app

import (
	"github.com/egoist/mygo/ui"
	"mygo-music/internal/music"
)

const (
	nowPlayingCover = "cover"
	nowPlayingVideo = "video"
)

// syncInlineMV owns the video only while the expanded player's video view is visible.
// The stored choice belongs to the listener, so changing tracks never resets it.
func (a *app) syncInlineMV() {
	inline := a.mv.win == nil && a.nowPlaying && a.settings.NowPlayingMedia != nowPlayingCover && a.canMV() && a.npPane != paneStage && a.npPane != paneWall
	if inline == a.mv.inline {
		return
	}
	a.mv.inline = inline
	if inline {
		a.syncMV()
	} else if a.mv.win == nil {
		a.mv.release()
		a.mv.key = ""
	}
}

func (a *app) chooseNowPlayingMedia(mode string) {
	a.settings.NowPlayingMedia = mode
	a.syncInlineMV()
	a.changed()
}

// npMedia shares the audio playhead with a muted native video and keeps the cover as its fallback.
func (a *app) npMedia(c *ui.Context, track *music.Track, art *artwork, size float32) {
	if !a.canMV() {
		a.npCover(c, track, art, size)
		return
	}
	showVideo := a.settings.NowPlayingMedia != nowPlayingCover
	white := ui.RGB(255, 255, 255)
	box := ui.Box(c.Key("now-playing-media")).Size(size, size)
	if showVideo && a.mv.win == nil {
		box.Radius(14).Clip().Background(ui.RGB(20, 20, 23))
	}
	box.Children(func() {
		if !showVideo || a.mv.win != nil {
			a.npCover(c, track, art, size)
		} else {
			m := &a.mv
			if m.video != nil {
				ui.Video(c.Key("inline-video"), m.video).Fill().Fit(ui.Contain).Label(T("Music Video"))
			}
			if m.loading || m.video != nil && m.video.Err() == nil && (!m.video.Ready() || m.video.Buffering()) {
				ui.Box(c).Absolute().Fill().Center().Children(func() { ui.Spinner(c) })
			}
			if m.err != "" || m.video != nil && m.video.Err() != nil {
				ui.Column(c).Absolute().Fill().Padding(18).Center().Gap(10).Children(func() {
					ui.Text(c, T("Couldn't load the MV. Try again.")).TextColor(white).FontSize(12).TextAlign(ui.Center)
					ui.Button(c, T("Retry")).OnClick(func() { a.loadMV(a.mv.key) })
				})
			}
		}
		ui.Row(c.Key("media-modes")).Absolute().Top(10).Right(10).Padding(3).Gap(2).Radius(9).Background(ui.RGBA(0, 0, 0, 155)).Children(func() {
			for _, mode := range []struct{ value, label string }{{nowPlayingCover, T("Cover")}, {nowPlayingVideo, T("Video")}} {
				active := mode.value == nowPlayingVideo && showVideo || mode.value == nowPlayingCover && !showVideo
				button := ui.ButtonBase(c.Key(mode.value)).Height(25).PaddingX(10).Radius(6).Label(mode.label).
					OnClick(func() { a.chooseNowPlayingMedia(mode.value) })
				if active {
					button.Background(white.Alpha(0.2))
				}
				button.Children(func() { ui.Text(c, mode.label).FontSize(11).FontWeight(600).TextColor(white) })
			}
		})
	})
}
