package app

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/egoist/mygo/audio"
	"github.com/egoist/mygo/ui"
	"mygo-music/internal/i18n"
	"mygo-music/internal/music"
	"mygo-music/internal/player"
)

// Selecting video mode persists the preference without changing the main
// window's route, audio track or playhead. Native creation is a queued action.
func TestVideoModeSwitchAndBrowse(t *testing.T) {
	a := newTestApp()
	a.settings.path = filepath.Join(t.TempDir(), "settings.json")
	ch := posts(a)
	a.pl.Cue([]music.Track{videoSong("1", "First")})
	a.pl.Hold(42 * time.Second)
	current, position := a.pl.Current(), a.pl.Position()
	tt := ui.NewTester(a.view, 1180, 780)
	settled(tt)
	if err := tt.Click("Video Mode"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	if a.route.page != pageHome || !a.mv.openPending || a.pl.Current() != current || a.pl.Position() != position || !a.pl.Playing() {
		t.Fatal("video mode navigated or restarted audio, or created its window during render")
	}
	if err := tt.Click("Music Mode"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	until(t, ch, func() bool { return !a.mv.openPending })
	if a.mv.openPending || a.mv.loading || a.mv.video != nil {
		t.Fatal("cancelled open survived switching away")
	}
	if err := tt.Click("Video Mode"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	until(t, ch, func() bool { return !a.mv.openPending }) // no native main window in this headless test
	a.pl.Cue([]music.Track{videoSong("2", "Second")})
	a.syncPlaybackMode()
	tt.Frame()
	if a.route.page != pageHome || !a.mv.openPending {
		t.Fatal("new selection took over the browsing window")
	}
	data, err := os.ReadFile(a.settings.path)
	if err != nil {
		t.Fatal(err)
	}
	var saved settings
	if err = json.Unmarshal(data, &saved); err != nil || saved.PlaybackMode != playbackVideo {
		t.Fatal("mode was not saved", err)
	}
	a.choosePlaybackMode(playbackMusic)
	until(t, ch, func() bool { return !a.mv.openPending })
}

func TestVideoWindowEmptyAndUnsupported(t *testing.T) {
	a := newTestApp()
	a.mv.large, a.mv.inline = true, true
	a.loadMV("")
	tt := ui.NewTester(a.videoWindowView, 880, 580)
	settled(tt)
	if !tt.HasText("Choose a track to watch from Home or your library.") {
		t.Fatal("missing empty state")
	}
	a.pl.Cue([]music.Track{videoSong("audio", "Audio only")})
	a.loadMV("fake\x00audio")
	tt.Frame()
	if !tt.HasText("This source does not provide video playback.") || a.mv.loading {
		t.Fatal("unsupported source started loading")
	}
}

// The window's view exercises native decoding, shared audio controls, seeking,
// description/comments switching and independent narrow/wide layouts.
func TestVideoWindowNativePlayback(t *testing.T) {
	data, err := os.ReadFile("testdata/mv.mp4")
	if err != nil {
		t.Fatal(err)
	}
	remote := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.ServeContent(w, r, "mv.mp4", time.Time{}, bytes.NewReader(data))
	}))
	defer remote.Close()
	a := newTestApp()
	ch := posts(a)
	a.pl = player.New(a.post)
	a.pl.Source = a.sourceByID
	src := &mvSource{fakeSource: fake(a), video: func(string, string) (*music.VideoStream, error) { return &music.VideoStream{URL: remote.URL}, nil }}
	src.caps["video"] = true
	a.library.src = src
	a.pl.Cue([]music.Track{videoSong("1", "Video playback")})
	a.pl.Current().Description = "A separate playback window keeps the main library available."
	a.pl.Changed = a.syncMV
	a.mv.large, a.mv.inline = true, true
	a.loadMV("fake\x001")
	until(t, ch, func() bool { return !a.mv.loading })
	p := a.mv.video
	if p == nil {
		t.Fatal(a.mv.err)
	}
	defer func() { a.quitting = true; a.mv.release() }()
	tt := ui.NewTester(a.videoWindowView, 1180, 740)
	deadline := time.Now().Add(8 * time.Second)
	for !p.Ready() {
		pumpMVRunLoop()
		tt.Frame()
		if p.Err() != nil {
			t.Fatal(p.Err())
		}
		if time.Now().After(deadline) {
			t.Fatal("native picture did not decode")
		}
		time.Sleep(10 * time.Millisecond)
	}
	p.SetLoop(true) // short fixture only
	out := audio.NewPlayer(&silence{})
	out.SetVolume(0)
	out.Play()
	a.pl.Attach(out)
	defer out.Close()
	a.syncMV()
	tt.Move(400, 380)
	settled(tt)
	if !p.Muted() || !p.Playing() {
		t.Fatal("picture did not follow audio")
	}
	if err = tt.Click("Pause"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	if p.Playing() || a.pl.Playing() {
		t.Fatal("pause did not stop both")
	}
	a.mv.lastSeek = time.Time{}
	target := p.Duration() / 2
	a.pl.Seek(target)
	until(t, ch, func() bool { return absDuration(out.Position()-target) < 100*time.Millisecond })
	deadline = time.Now().Add(4 * time.Second)
	for absDuration(p.Position()-a.pl.Position()) > 100*time.Millisecond {
		pumpMVRunLoop()
		tt.Frame()
		if time.Now().After(deadline) {
			t.Fatalf("seek: video %v audio %v", p.Position(), a.pl.Position())
		}
		time.Sleep(10 * time.Millisecond)
	}
	if err = tt.Click("Play"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	if !p.Playing() || !a.pl.Playing() {
		t.Fatal("resume did not start both")
	}
	tt.Key(0, ui.KeySpace)
	tt.Frame()
	if p.Playing() || a.pl.Playing() {
		t.Fatal("Space did not pause both")
	}
	if err = tt.Click("Comments"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	until(t, ch, func() bool { return a.comments.loaded })
	tt.Frame()
	if !tt.HasText("No comments yet") {
		t.Fatal("comments did not load")
	}
	if err = tt.Click("About"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	if !tt.HasText(a.pl.Current().Description) {
		t.Fatal("description did not return")
	}
	old := i18n.Language()
	i18n.SetLanguage(i18n.Chinese)
	defer i18n.SetLanguage(old)
	for _, width := range []int{880, 1180, 1500} {
		tt.SetSize(width, 740)
		settled(tt)
		if r, ok := tt.Find(T("Music Video")); !ok || r.W < 500 || r.H < 300 {
			t.Fatalf("missing video at %d: %+v", width, r)
		}
		if dir := os.Getenv("MM_FRAMES"); dir != "" {
			saveFrame(t, dir, fmt.Sprintf("video-window-%d.png", width), tt.Image())
		}
	}
}
