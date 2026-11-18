package app

import (
	"bytes"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/egoist/mygo"
	"github.com/egoist/mygo/audio"
	"github.com/egoist/mygo/ui"
	"github.com/egoist/mygo/ui/video"
	"mygo-music/internal/i18n"
	"mygo-music/internal/music"
)

var pumpMVRunLoop = func() {}

type mvSource struct {
	*fakeSource
	video func(string, string) (*music.VideoStream, error)
}

func (s *mvSource) Video(id, quality string) (*music.VideoStream, error) { return s.video(id, quality) }

// A late MV answer cannot replace a track or window that has moved on.
func TestMVRejectsStaleLoad(t *testing.T) {
	a := newTestApp()
	ch := posts(a)
	release := make(chan struct{})
	src := &mvSource{fakeSource: fake(a), video: func(string, string) (*music.VideoStream, error) {
		<-release
		return &music.VideoStream{URL: "https://media.example/video.mp4"}, nil
	}}
	src.caps["video"] = true
	a.library.src = src
	track := song("mv", "MV", "Artist")
	track.Source = a.library.id
	a.pl.Cue([]music.Track{track})
	if !a.canMV() {
		t.Fatal("test source does not provide MV playback")
	}
	a.mv.win = new(mygo.Window)
	a.loadMV("fake\x00mv")
	a.mv.release()
	a.mv.key = "fake\x00next"
	close(release)
	runPost(t, ch)
	if a.mv.video != nil || a.mv.proxy != nil || a.mv.key != "fake\x00next" {
		t.Fatal("an obsolete MV replaced current playback")
	}
}

// The video proxy forwards byte ranges and headers without exposing cookies.
func TestMVProxyRanges(t *testing.T) {
	media := []byte("0123456789abcdef")
	remote := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Referer") != "https://source.example/" || r.Header.Get("Cookie") != "memory-only" {
			t.Error("media headers were lost")
		}
		http.ServeContent(w, r, "video.mp4", time.Time{}, bytes.NewReader(media))
	}))
	defer remote.Close()
	proxy, err := newMVProxy(&music.VideoStream{URL: remote.URL, Headers: map[string]string{"Referer": "https://source.example/", "Cookie": "memory-only"}})
	if err != nil {
		t.Fatal(err)
	}
	defer proxy.Close()
	req, _ := http.NewRequest("GET", proxy.url, nil)
	req.Header.Set("Range", "bytes=4-8")
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	b, _ := io.ReadAll(res.Body)
	res.Body.Close()
	if res.StatusCode != 206 || string(b) != "45678" || res.Header.Get("Content-Range") != "bytes 4-8/16" {
		t.Fatalf("response %d: %q, range %q", res.StatusCode, b, res.Header.Get("Content-Range"))
	}
	if strings.Contains(proxy.url, "memory-only") || res.Header.Get("Set-Cookie") != "" {
		t.Fatal("media credentials escaped")
	}
	u, _ := url.Parse(proxy.url)
	res, err = http.Get("http://" + u.Host + "/video.mp4")
	if err != nil {
		t.Fatal(err)
	}
	res.Body.Close()
	if res.StatusCode != 404 {
		t.Fatal("unguessable media path was not enforced")
	}
}

// Closing an MV cancels pending media downloads instead of leaking a transfer.
func TestMVProxyCloseCancelsDownload(t *testing.T) {
	started, cancelled := make(chan struct{}), make(chan struct{})
	remote := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		close(started)
		<-r.Context().Done()
		close(cancelled)
	}))
	defer remote.Close()
	proxy, err := newMVProxy(&music.VideoStream{URL: remote.URL})
	if err != nil {
		t.Fatal(err)
	}
	defer proxy.Close()
	finished := make(chan struct{})
	go func() {
		res, _ := http.Get(proxy.url)
		if res != nil {
			res.Body.Close()
		}
		close(finished)
	}()
	select {
	case <-started:
	case <-time.After(time.Second):
		t.Fatal("download did not begin")
	}
	proxy.Close()
	select {
	case <-cancelled:
	case <-time.After(time.Second):
		t.Fatal("download survived closing the MV")
	}
	<-finished
}

// Video drift is corrected without seeking repeatedly while buffering.
func TestMVSync(t *testing.T) {
	for _, tc := range []struct {
		delta                    time.Duration
		playing, buffering, seek bool
		rate                     float64
	}{
		{0, true, false, false, 1}, {100 * time.Millisecond, true, false, false, 1.03},
		{-100 * time.Millisecond, true, false, false, 0.97}, {2 * time.Second, true, false, true, 1},
		{2 * time.Second, true, true, false, 1}, {0, false, false, false, 1},
		{2 * time.Second, false, false, true, 1},
	} {
		seek, rate := mvSync(5*time.Second+tc.delta, 5*time.Second, tc.playing, tc.buffering)
		if seek != tc.seek || rate != tc.rate {
			t.Errorf("%+v: seek %v, rate %v", tc, seek, rate)
		}
	}
}

// MV transport and progress controls operate the shared audio player.
func TestMVPlaybackControls(t *testing.T) {
	a := newTestApp()
	a.pl.Cue([]music.Track{song("mv", "MV", "Artist")})
	out := audio.NewPlayer(&silence{})
	out.SetVolume(0)
	out.Play()
	a.pl.Attach(out)
	defer out.Close()
	tt := ui.NewTester(a.mvView, 640, 360)
	settled(tt)
	if err := tt.Click("Pause"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	if a.pl.Playing() {
		t.Fatal("MV pause did not pause the shared player")
	}
	if err := tt.Click("Play"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	if !a.pl.Playing() {
		t.Fatal("MV play did not resume the shared player")
	}
	tt.Key(0, ui.KeySpace)
	tt.Frame()
	if a.pl.Playing() {
		t.Fatal("Space did not pause from the MV")
	}
	progress, ok := tt.Find("Progress")
	if !ok {
		t.Fatal("missing progress control")
	}
	tt.ClickAt(progress.X+progress.W/2, progress.Y+progress.H/2)
	tt.Frame()
	if a.pl.Position() < 90*time.Second || a.pl.Position() > 110*time.Second {
		t.Fatalf("MV seek left audio at %v", a.pl.Position())
	}
}

// With MM_FRAMES set, renders real decoded video under the MV controls.
func TestMVFrames(t *testing.T) {
	dir := framesDir(t)
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
	defer proxy.Close()
	p, err := video.OpenURL(proxy.url)
	if err != nil {
		t.Fatal(err)
	}
	defer p.Close()
	p.SetMuted(true)
	a := newTestApp()
	a.pl.Cue([]music.Track{song("mv", "Northern Lights", "Polar Night")})
	a.mv.video, a.mv.pinned = p, true
	before := i18n.Language()
	i18n.SetLanguage(i18n.Chinese)
	defer i18n.SetLanguage(before)
	tt := ui.NewTester(a.mvView, 640, 360)
	for deadline := time.Now().Add(5 * time.Second); !p.Ready(); {
		pumpMVRunLoop()
		tt.Frame()
		if err := p.Err(); err != nil {
			t.Fatal(err)
		}
		if time.Now().After(deadline) {
			t.Fatal("native video did not decode its first picture")
		}
		time.Sleep(10 * time.Millisecond)
	}
	settled(tt)
	p.Pause()
	saveFrame(t, dir, "mv-player.png", tt.Image())
}
