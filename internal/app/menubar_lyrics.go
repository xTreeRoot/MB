package app

import (
	"time"

	"github.com/egoist/mygo"
	"mygo-music/internal/lyrics"
)

const (
	menuBarCharacters = 18
	menuBarScrollHold = time.Second
	menuBarScrollStep = 250 * time.Millisecond
	menuBarScrollGap  = "　　"
)

// menuBarLine scrolls a bounded window of the complete line using audio time.
// Seeking is deterministic and pausing freezes the display without another clock.
func menuBarLine(text string, elapsed time.Duration) string {
	runes := []rune(text)
	if len(runes) <= menuBarCharacters {
		return text
	}
	runes = append(runes, []rune(menuBarScrollGap)...)
	cycle := menuBarScrollHold + time.Duration(len(runes))*menuBarScrollStep
	phase := max(elapsed, 0) % cycle
	offset := 0
	if phase > menuBarScrollHold {
		offset = int((phase - menuBarScrollHold) / menuBarScrollStep)
	}
	visible := make([]rune, menuBarCharacters)
	for i := range visible {
		visible[i] = runes[(offset+i)%len(runes)]
	}
	return string(visible)
}

// menuBarText is independent of visible windows and uses each subtitle's own
// start time, so identical consecutive lines still begin at their first word.
func (a *app) menuBarText() (text, tip string) {
	tip = mygo.App.Name()
	if !a.settings.MenuBarLyrics || a.pl == nil {
		return
	}
	track := a.pl.Current()
	if track == nil {
		return
	}
	position := a.pl.Position()
	lines, _ := a.pl.Lyrics()
	full := currentLyric(lines, position)
	elapsed := position
	if full == "" {
		full = track.Name
	} else {
		elapsed -= lines[lyrics.Index(lines, position)].At
	}
	return menuBarLine(full, elapsed), full + "\n" + track.Name + " · " + track.ArtistNames()
}
