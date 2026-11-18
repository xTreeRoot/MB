package app

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"sync/atomic"
	"testing"
	"time"

	"github.com/egoist/mygo/audio"
	"github.com/egoist/mygo/ui"

	"mygo-music/internal/music"
	"mygo-music/internal/player"
)

// Startup lyrics use the saved source and playhead. If startup metadata fails,
// the player bar's Play reloads it with fresh audio without selecting a song.
func TestRestoreSessionLyricsResume(t *testing.T) {
	for _, failFirst := range []bool{false, true} {
		t.Run(map[bool]string{false: "loaded", true: "retry"}[failFirst], func(t *testing.T) {
			a := newTestApp()
			ch := posts(a)
			a.pl = player.New(a.post)
			a.pl.Source = a.sourceByID
			a.pl.SetVolume(0)
			media, err := filepath.Abs("../player/testdata/seek.mp3")
			if err != nil {
				t.Fatal(err)
			}
			var streams, lyricCalls atomic.Int32
			fake(a).stream = func(id, _ string) (*music.Stream, error) {
				streams.Add(1)
				if id != "saved" {
					return nil, errors.New("wrong track")
				}
				return &music.Stream{URL: music.FileURL(media), Type: "mp3"}, nil
			}
			fake(a).lyrics = func(id string) (*music.Lyrics, error) {
				call := lyricCalls.Add(1)
				if id != "saved" {
					return nil, errors.New("wrong track")
				}
				if failFirst && call == 1 {
					return nil, errors.New("startup connection failed")
				}
				return &music.Lyrics{Lines: []music.LyricLine{
					{Start: 0, End: 1000, Text: "开头"},
					{Start: 1000, End: 4000, Text: "恢复位置的字幕"},
				}}, nil
			}
			track := music.Track{ID: "saved", Source: "fake", Name: "Saved Track", Duration: 5000}
			keptSession(t, player.Session{Queue: []music.Track{track}, Order: []int{0}, At: 0, Position: 2000})
			a.restoreSession()
			until(t, ch, func() bool { _, loaded := a.pl.Lyrics(); return loaded })
			want := "恢复位置的字幕"
			if failFirst {
				want = track.Name
			}
			if got := a.menuBarContent(); got.text != want || a.pl.Playing() || streams.Load() != 0 {
				t.Fatalf("startup: text=%q playing=%v streams=%d", got.text, a.pl.Playing(), streams.Load())
			}
			tt := ui.NewTester(a.view, 1180, 780)
			settled(tt)
			if dir := os.Getenv("MM_FRAMES"); dir != "" && !failFirst {
				saveFrame(t, dir, "restored-paused.png", tt.Image())
			}
			if err := tt.Click("Play/Pause"); err != nil {
				t.Fatal(err)
			}
			until(t, ch, func() bool { _, loaded := a.pl.Lyrics(); return loaded && a.pl.Ready() })
			tt.Frame()
			if got := a.menuBarContent(); got.text != "恢复位置的字幕" || !a.pl.Playing() || a.pl.Position() < 2*time.Second || streams.Load() != 1 {
				t.Fatalf("resume: text=%q position=%v playing=%v streams=%d", got.text, a.pl.Position(), a.pl.Playing(), streams.Load())
			}
			a.pl.TogglePlay()
		})
	}
}

// The session is kept a while after playback changes and every while as
// it plays, and paused nothing more is due. It was kept by a ticker that
// woke the app, and redrew its window, every half minute even at rest.
func TestSessionRestsWhilePaused(t *testing.T) {
	var due []func()
	defer func(after func(time.Duration, func())) { sessionAfter = after }(sessionAfter)
	sessionAfter = func(_ time.Duration, fn func()) { due = append(due, fn) }

	a := newTestApp()
	a.pl.Changed = a.keepSession
	a.pl.Cue([]music.Track{song("7", "宝船")})
	out := audio.NewPlayer(&silence{})
	out.SetVolume(0)
	out.Play()
	a.pl.Attach(out)
	defer out.Close()

	a.pl.TogglePlay()
	a.pl.TogglePlay()
	if len(due) != 1 {
		t.Fatalf("%d saves due after pausing and playing", len(due))
	}
	due[0]()
	if len(due) != 2 {
		t.Fatalf("playing, %d saves due after one, want another", len(due)-1)
	}

	a.pl.TogglePlay()
	due[1]()
	if len(due) != 2 {
		t.Errorf("paused, %d more saves due", len(due)-2)
	}
}

// keptSession keeps a session as the app would have on quitting, and takes it
// away with the test.
func keptSession(t *testing.T, s player.Session) {
	t.Helper()
	path, err := sessionPath()
	if err != nil {
		t.Fatal(err)
	}
	b, err := json.Marshal(s)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, b, 0o600); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { os.Remove(path) })
}

// Started again, the app picks up the station it played, paused where it was,
// and the station's source goes on feeding it.
func TestRestoreSessionReattachesRadio(t *testing.T) {
	asked := make(chan string, 4)
	a := newTestApp()
	ch := posts(a)
	fake(a).stationNext = func(id string) ([]music.Track, error) {
		asked <- id
		tr := song("3", "三")
		tr.Source = "fake"
		return []music.Track{tr}, nil
	}
	name := radioName("fake", &music.Station{ID: "st", Name: "Night Drive"})
	tr := song("1", "一")
	tr.Source = "fake"
	keptSession(t, player.Session{Queue: []music.Track{tr}, Order: []int{0}, At: 0, Position: 5000, Radio: name})

	a.restoreSession()
	if a.pl.Radio() != name || a.pl.Playing() {
		t.Fatalf("radio %q, playing %v", a.pl.Radio(), a.pl.Playing())
	}
	if id := wait(t, asked, "the station's next songs"); id != "st" {
		t.Fatalf("asked station %q", id)
	}
	until(t, ch, func() bool { return len(a.pl.Queue()) == 2 })
	if radioLabel(a.pl.Radio()) != "Night Drive" {
		t.Errorf("the radio is called %q", radioLabel(a.pl.Radio()))
	}
}

// A session whose radio's source went away picks up its songs without a crash,
// the radio adding nothing more.
func TestRestoreSessionOfGoneSource(t *testing.T) {
	a := newTestApp()
	tr := song("1", "一")
	tr.Source = "gone"
	keptSession(t, player.Session{Queue: []music.Track{tr}, Order: []int{0}, At: 0, Radio: radioName("gone", &music.Station{ID: "st", Name: "Night Drive"})})

	a.restoreSession()
	if cur := a.pl.Current(); cur == nil || cur.ID != "1" || len(a.pl.Queue()) != 1 {
		t.Errorf("playing %+v of %d", cur, len(a.pl.Queue()))
	}
}
