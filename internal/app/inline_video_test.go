package app

import (
	"bytes"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"testing"
	"time"

	"github.com/egoist/mygo/audio"
	"github.com/egoist/mygo/ui"
	"github.com/egoist/mygo/ui/video"
	"mygo-music/internal/i18n"
	"mygo-music/internal/lyrics"
	"mygo-music/internal/music"
)

// The native decoder displays inline and follows pause, resume and audio seeks.
func TestInlineVideoPlayback(t *testing.T) {
	data, err := os.ReadFile("testdata/mv.mp4")
	if err != nil {
		t.Fatal(err)
	}
	remote := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.ServeContent(w, r, "mv.mp4", time.Time{}, bytes.NewReader(data))
	}))
	defer remote.Close()
	proxy, err := newMVProxy(&music.VideoStream{URL: remote.URL})
	if err != nil {
		t.Fatal(err)
	}
	p, err := video.OpenURL(proxy.url)
	if err != nil {
		proxy.Close()
		t.Fatal(err)
	}
	p.SetMuted(true)
	p.Pause()
	a := newTestApp()
	a.library.src = &mvSource{fakeSource: fake(a), video: func(string, string) (*music.VideoStream, error) { return nil, errors.New("unexpected video load") }}
	a.library.src.(*mvSource).caps["video"] = true
	tracks := framesTracks(a)
	a.pl.Cue(tracks)
	a.pl.SetLyrics(lyrics.Build("[00:00.00]The sky goes green at the edges\n[00:02.00]And everyone stops to look", ""))
	a.nowPlaying, a.npPane = true, paneLyrics
	a.mv.video, a.mv.proxy, a.mv.inline, a.mv.key = p, proxy, true, "fake\x00"+tracks[0].ID
	a.pl.Changed = a.syncMV
	defer func() { a.quitting = true; a.mv.release() }()
	old := i18n.Language()
	i18n.SetLanguage(i18n.Chinese)
	defer i18n.SetLanguage(old)
	tt := ui.NewTester(a.view, 1180, 780)
	tt.SetDark(true)
	deadline := time.Now().Add(8 * time.Second)
	for !p.Ready() {
		pumpMVRunLoop()
		tt.Frame()
		if p.Err() != nil {
			t.Fatal(p.Err())
		}
		if time.Now().After(deadline) {
			t.Fatal("inline video did not decode")
		}
		time.Sleep(10 * time.Millisecond)
	}
	out := audio.NewPlayer(&silence{})
	out.SetVolume(0)
	out.Play()
	a.pl.Attach(out)
	defer out.Close()
	a.syncMV()
	if !p.Muted() || !p.Playing() {
		t.Fatal("inline video did not follow audio play")
	}
	a.pl.TogglePlay()
	a.syncMV()
	if p.Playing() {
		t.Fatal("inline video kept playing after audio paused")
	}
	out.Pause()
	a.mv.lastSeek = time.Time{}
	a.pl.Seek(1500 * time.Millisecond)
	a.syncMV()
	deadline = time.Now().Add(4 * time.Second)
	for absDuration(p.Position()-a.pl.Position()) > 300*time.Millisecond {
		pumpMVRunLoop()
		tt.Frame()
		if p.Err() != nil {
			t.Fatal(p.Err())
		}
		if time.Now().After(deadline) {
			t.Fatalf("video seek %v, audio %v", p.Position(), a.pl.Position())
		}
		time.Sleep(10 * time.Millisecond)
	}
	a.pl.TogglePlay()
	a.syncMV()
	if !p.Playing() {
		t.Fatal("inline video did not resume")
	}
	a.pl.TogglePlay()
	a.syncMV()
	settled(tt)
	if dir := os.Getenv("MM_FRAMES"); dir != "" {
		saveFrame(t, dir, "now-playing-video.png", tt.Image())
	}
	if err = tt.Click("封面"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	if a.mv.video != nil || a.mv.inline {
		t.Fatal("cover did not release native video")
	}
	if dir := os.Getenv("MM_FRAMES"); dir != "" {
		saveFrame(t, dir, "now-playing-cover.png", tt.Image())
	}
}

func absDuration(d time.Duration) time.Duration {
	if d < 0 {
		return -d
	}
	return d
}
