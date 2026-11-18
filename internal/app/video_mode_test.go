package app

import (
	"bytes"
	"encoding/json"
	"errors"
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

// Switching changes the view without restarting audio. A pending answer must
// not bring video back after switching away; browsing remains possible.
func TestVideoModeSwitchAndBrowse(t *testing.T) {
	a := newTestApp()
	a.settings.path = filepath.Join(t.TempDir(), "settings.json")
	ch := posts(a)
	requests := make(chan string, 8)
	release := make(chan struct{})
	src := &mvSource{fakeSource: fake(a), video: func(id, quality string) (*music.VideoStream, error) {
		requests <- id
		<-release
		return nil, errors.New("test video")
	}}
	a.library.src = src
	src.caps["video"] = true
	a.pl.Cue([]music.Track{videoSong("1", "First")})
	a.pl.Hold(42 * time.Second)
	a.pl.Changed = a.syncPlaybackExtras
	tt := ui.NewTester(a.view, 1180, 780)
	settled(tt)
	current, position := a.pl.Current(), a.pl.Position()
	if err := tt.Click("Video Mode"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	if id := wait(t, requests, "video"); id != "1" {
		t.Fatal(id)
	}
	if a.route.page != pageVideo || !a.mv.inline || a.pl.Current() != current || a.pl.Position() != position || !a.pl.Playing() {
		t.Fatal("video mode changed audio playback or did not show the video page")
	}
	if err := tt.Click("Music Mode"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	close(release)
	runPost(t, ch)
	if a.route.page != pageHome || a.mv.inline || a.mv.loading || a.mv.video != nil || a.mv.err != "" {
		t.Fatal("music mode retained a video request")
	}
	if err := tt.Click("Video Mode"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	wait(t, requests, "reopened video")
	until(t, ch, func() bool { return !a.mv.loading })
	if err := tt.Click("Home"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	a.syncPlaybackExtras()
	tt.Frame()
	if a.route.page != pageHome || a.mv.inline {
		t.Fatal("playback pulled the listener away from browsing")
	}
	a.pl.Cue([]music.Track{videoSong("2", "Second")})
	a.pl.Changed()
	tt.Frame()
	if id := wait(t, requests, "next selected video"); id != "2" {
		t.Fatal(id)
	}
	until(t, ch, func() bool { return !a.mv.loading })
	if a.route.page != pageVideo || a.mv.key != "fake\x002" || a.mv.err == "" {
		t.Fatal("new selection did not open video or report its error")
	}
	data, err := os.ReadFile(a.settings.path)
	if err != nil {
		t.Fatal(err)
	}
	var saved settings
	if err = json.Unmarshal(data, &saved); err != nil || saved.PlaybackMode != playbackVideo {
		t.Fatal("playback mode was not saved", err)
	}
	a.choosePlaybackMode(playbackMusic)
}

// The mode is usable before selecting a track, and sources without video give
// a clear state without attempting to execute tools or resolve a video.
func TestVideoModeWithoutVideoSource(t *testing.T) {
	a := newTestApp()
	tt := ui.NewTester(a.view, 880, 580)
	settled(tt)
	if err := tt.Click("Video Mode"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	if !tt.HasText("Choose a track to watch from Home or your library.") {
		t.Fatal("missing empty video state")
	}
	a.pl.Cue([]music.Track{videoSong("audio", "Audio only")})
	tt.Frame()
	if !tt.HasText("This source does not provide video playback.") || a.mv.loading || a.mv.inline {
		t.Fatal("audio-only source started a video request")
	}
	if err := tt.Click("Music Mode"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	if a.route.page != pageHome {
		t.Fatal("could not leave unsupported video")
	}
}

// Exercise the actual native decoder through the new mode, including pause,
// seeking, switching away, and several window sizes and appearances.
func TestVideoModeNativePlayback(t *testing.T) {
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
	a.library.src = &mvSource{fakeSource: fake(a), video: func(string, string) (*music.VideoStream, error) {
		return &music.VideoStream{URL: remote.URL}, nil
	}}
	a.library.src.(*mvSource).caps["video"] = true
	a.pl.Cue([]music.Track{videoSong("1", "Video playback")})
	a.pl.Changed = a.syncPlaybackExtras
	tt := ui.NewTester(a.view, 1180, 780)
	settled(tt)
	if err := tt.Click("Video Mode"); err != nil {
		t.Fatal(err)
	}
	until(t, ch, func() bool { return !a.mv.loading })
	p := a.mv.video
	if p == nil {
		t.Fatal("mode did not open native video", a.mv.err)
	}
	defer func() { a.quitting = true; a.mv.release() }()
	deadline := time.Now().Add(8 * time.Second)
	for !p.Ready() {
		pumpMVRunLoop()
		tt.Frame()
		if p.Err() != nil {
			t.Fatal(p.Err())
		}
		if time.Now().After(deadline) {
			t.Fatal("video mode did not decode")
		}
		time.Sleep(10 * time.Millisecond)
	}
	// The fixture lasts 1.2 seconds; keep it alive while slower instrumented
	// UI builds exercise controls against the much longer silent audio.
	p.SetLoop(true)
	out := audio.NewPlayer(&silence{})
	out.SetVolume(0)
	out.Play()
	a.pl.Attach(out)
	defer out.Close()
	a.syncPlaybackExtras()
	if !p.Muted() || !p.Playing() {
		t.Fatal("video mode did not follow audio play")
	}
	if err := tt.Click("Play/Pause"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	if p.Playing() || a.pl.Playing() {
		t.Fatal("player bar did not pause both audio and video")
	}
	a.mv.lastSeek = time.Time{}
	// Keep the target inside this short fixture. Seeking past its end can
	// legitimately end the native video while the longer silent audio resumes.
	target := p.Duration() / 2
	a.pl.Seek(target)
	until(t, ch, func() bool { return absDuration(out.Position()-target) < 100*time.Millisecond })
	deadline = time.Now().Add(4 * time.Second)
	for absDuration(p.Position()-a.pl.Position()) > 100*time.Millisecond {
		pumpMVRunLoop()
		tt.Frame()
		if p.Err() != nil {
			t.Fatal(p.Err())
		}
		if time.Now().After(deadline) {
			t.Fatalf("video mode seek %v, audio %v", p.Position(), a.pl.Position())
		}
		time.Sleep(10 * time.Millisecond)
	}
	if err := tt.Click("Play/Pause"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	if !p.Playing() || !a.pl.Playing() {
		t.Fatalf("resume: audio playing=%v loading=%v position=%v; video playing=%v ready=%v buffering=%v position=%v err=%v", a.pl.Playing(), a.pl.Loading(), a.pl.Position(), p.Playing(), p.Ready(), p.Buffering(), p.Position(), p.Err())
	}
	a.pl.TogglePlay()
	if err := tt.Click("Open Now Playing"); err != nil {
		t.Fatal(err)
	}
	settled(tt)
	if a.mv.video != p || !a.mv.inline {
		t.Fatal("expanded player replaced the active decoder")
	}
	tt.Key(0, ui.KeyEscape)
	settled(tt)
	tt.Frame()
	if a.mv.video != p || !a.mv.inline || !a.videoPageVisible() {
		t.Fatal("closing the expanded player lost the main video")
	}
	old := i18n.Language()
	i18n.SetLanguage(i18n.Chinese)
	defer i18n.SetLanguage(old)
	for _, width := range []int{880, 1180, 1500} {
		for _, dark := range []bool{false, true} {
			tt.SetSize(width, 780)
			tt.SetDark(dark)
			settled(tt)
			if r, ok := tt.Find(T("Video Playback")); !ok || r.W < 500 || r.H < 300 {
				t.Fatalf("video is missing or too small at %d: %+v", width, r)
			}
			if dir := os.Getenv("MM_FRAMES"); dir != "" {
				saveFrame(t, dir, fmt.Sprintf("video-mode-%d-dark-%t.png", width, dark), tt.Image())
			}
		}
	}
	if err := tt.Click(T("Music Mode")); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	if a.mv.video != nil || a.mv.inline || a.route.page != pageHome {
		t.Fatal("switching back did not release the video")
	}
}
