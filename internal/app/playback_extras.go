package app

import (
	"runtime"
	"time"

	"github.com/egoist/mygo"
)

func menuBarLine(text string) string {
	runes := []rune(text)
	if len(runes) > 18 {
		return string(runes[:17]) + "…"
	}
	return text
}

func (a *app) updateTrayLyric() {
	if runtime.GOOS != "darwin" || a.tray == nil {
		return
	}
	text, tip := "", mygo.App.Name()
	if track := a.pl.Current(); a.settings.MenuBarLyrics && track != nil {
		lines, _ := a.pl.Lyrics()
		text = currentLyric(lines, a.pl.Position())
		if text == "" {
			text = track.Name
		}
		tip = text + "\n" + track.Name + " · " + track.ArtistNames()
		text = menuBarLine(text)
	}
	if text != a.trayLyric {
		a.tray.SetTitle(text)
		a.trayLyric = text
	}
	if tip != a.trayTip {
		a.tray.SetToolTip(tip)
		a.trayTip = tip
	}
}

// syncPlaybackExtras runs independently of visible views, so lyrics keep moving
// with the main window hidden. It polls only while there is something to update.
func (a *app) syncPlaybackExtras() {
	if a.quitting || a.pl == nil {
		return
	}
	a.updateTrayLyric()
	a.syncMV()
	mvActive := a.mv.win != nil && (a.pl.Playing() || a.mv.loading || a.mv.video != nil && (!a.mv.video.Ready() || a.mv.video.Buffering()))
	needed := mvActive || runtime.GOOS == "darwin" && a.tray != nil && a.settings.MenuBarLyrics && a.pl.Playing()
	if !needed || a.extrasTick {
		return
	}
	a.extrasTick = true
	time.AfterFunc(200*time.Millisecond, func() {
		mygo.RunOnMain(func() {
			a.extrasTick = false
			a.syncPlaybackExtras()
		})
	})
}
