package app

import (
	"runtime"
	"time"

	"github.com/egoist/mygo"
)

func (a *app) updateTrayLyric() {
	if runtime.GOOS != "darwin" || a.tray == nil {
		return
	}
	text, tip := a.menuBarText()
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
	needed := runtime.GOOS == "darwin" && a.tray != nil && a.settings.MenuBarLyrics && a.pl.Playing()
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
