package app

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/egoist/mygo/ui"
	"mygo-music/internal/music"
)

// Cover/video clicks persist across tracks and a late video response cannot undo a switch.
func TestNowPlayingMediaChoice(t *testing.T) {
	a := newTestApp()
	a.settings.path = filepath.Join(t.TempDir(), "settings.json")
	calls := make(chan string, 8)
	a.library.src = &mvSource{fakeSource: fake(a), video: func(id, quality string) (*music.VideoStream, error) {
		calls <- id
		return nil, errors.New("test video")
	}}
	a.library.src.(*mvSource).caps["video"] = true
	ch := posts(a)
	a.pl.Changed = a.syncMV
	a.pl.Cue([]music.Track{videoSong("1", "First"), videoSong("2", "Second")})
	a.pl.SetLyrics(nil)
	a.nowPlaying = true
	tt := ui.NewTester(a.view, 1200, 800)
	tt.Frame()
	tt.Advance(time.Second)
	tt.Frame()
	if got := wait(t, calls, "initial video"); got != "1" {
		t.Fatal(got)
	}
	if err := tt.Click("Cover"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	runPost(t, ch)
	if a.mv.inline || a.mv.video != nil || a.mv.loading || a.settings.NowPlayingMedia != nowPlayingCover {
		t.Fatal("cover switch retained the video job")
	}
	a.pl.Cue([]music.Track{videoSong("2", "Second")})
	a.pl.Changed()
	tt.Frame()
	if a.settings.NowPlayingMedia != nowPlayingCover || a.mv.inline {
		t.Fatal("next track reset the cover preference")
	}
	if err := tt.Click("Video"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	if got := wait(t, calls, "next track video"); got != "2" {
		t.Fatal(got)
	}
	until(t, ch, func() bool { return !a.mv.loading })
	if !a.mv.inline || a.mv.err == "" {
		t.Fatal("video failure did not leave cover switching usable")
	}
	a.pl.Cue([]music.Track{videoSong("3", "Third")})
	a.pl.Changed()
	tt.Frame()
	if got := wait(t, calls, "changed video track"); got != "3" {
		t.Fatal(got)
	}
	until(t, ch, func() bool { return !a.mv.loading })
	if a.settings.NowPlayingMedia != nowPlayingVideo || a.mv.key != "fake\x003" {
		t.Fatal("next track reset video choice or retained old video")
	}
	data, err := os.ReadFile(a.settings.path)
	if err != nil {
		t.Fatal(err)
	}
	var restored settings
	if err = json.Unmarshal(data, &restored); err != nil {
		t.Fatal(err)
	}
	if restored.NowPlayingMedia != nowPlayingVideo {
		t.Fatal("video choice was not saved")
	}
	if err = tt.Click("Cover"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	a.nowPlaying = false
	tt.Frame()
	a.nowPlaying = true
	tt.Frame()
	if a.mv.inline {
		t.Fatal("reopening reset cover choice")
	}
}

func videoSong(id, name string) music.Track { tr := song(id, name); tr.Source = "fake"; return tr }
