package player

import (
	"errors"
	"net/http"
	"net/http/httptest"
	"strconv"
	"sync/atomic"
	"testing"
	"time"

	"mygo-music/internal/music"
)

// An old decoder's late failure must not stop the next song.
func TestStaleStreamFailureIgnored(t *testing.T) {
	p, ch, _, q := servedQueue(t, "a", "b")
	for i := range q {
		q[i].Duration = 5000
	}
	p.PlayTrack(q, 0)
	settle(t, ch, p)
	failed := p.watch.failed
	p.Jump(1)
	failed(errors.New("old connection failed"))
	settle(t, ch, p)
	for len(ch) > 0 {
		next(t, ch)
	}
	if p.Current().ID != "b" || p.Err() != "" || !p.Ready() || !p.Playing() {
		t.Fatalf("stale failure affected current audio: track=%s error=%q ready=%v playing=%v", p.Current().ID, p.Err(), p.Ready(), p.Playing())
	}
}

// A restored queue keeps its playhead without opening an expiring network
// stream while paused. Play obtains fresh media at that playhead.
func TestRestoreWaitsForPlay(t *testing.T) {
	p, ch, src, q := servedQueue(t, "a")
	q[0].Duration = 5000
	p.Restore(Session{Queue: q, Order: []int{0}, At: 0, Position: 2500})
	if p.Playing() || p.Loading() || p.Ready() || p.Position() != 2500*time.Millisecond {
		t.Fatalf("restored: playing=%v loading=%v ready=%v position=%v", p.Playing(), p.Loading(), p.Ready(), p.Position())
	}
	if asked := src.count(src.asked, "a"); asked != 0 {
		t.Fatalf("opened a network stream %d times while paused", asked)
	}
	p.Seek(3 * time.Second)
	p.TogglePlay()
	settle(t, ch, p)
	if !p.Ready() || !p.Playing() || p.Current().ID != "a" || p.Position() < 3*time.Second {
		t.Fatalf("resume: ready=%v playing=%v position=%v error=%q", p.Ready(), p.Playing(), p.Position(), p.Err())
	}
	if asked := src.count(src.asked, "a"); asked != 1 {
		t.Fatalf("asked for the resumed stream %d times", asked)
	}
}

// A connection lost after the decoder opened must stop at the same track and
// report failure, so Play can obtain a new stream instead of reusing dead audio.
func TestBrokenStreamCanResume(t *testing.T) {
	b := wavFile(1, 2, 16, 44100, make([]byte, 44100*4*10))
	release := make(chan struct{})
	var requests atomic.Int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if requests.Add(1) == 1 {
			w.Header().Set("Content-Length", strconv.Itoa(len(b)))
			w.Write(b[:1000])
			w.(http.Flusher).Flush()
			select {
			case <-release:
			case <-r.Context().Done():
			}
			return // an incomplete body fails after the decoder has opened
		}
		w.Write(b)
	}))
	defer srv.Close()
	defer func() {
		select {
		case <-release:
		default:
			close(release)
		}
	}()
	ch := make(chan func(), 64)
	p := New(func(fn func()) { ch <- fn })
	p.Source = func(string) music.Source { return onServer{url: srv.URL} }
	p.SetVolume(0)
	defer p.stop()
	p.Restore(Session{Queue: []music.Track{{ID: "a", Source: "s", Duration: 10000}}, Order: []int{0}, At: 0, Position: 2000})
	p.TogglePlay()
	settle(t, ch, p)
	if !p.Ready() {
		t.Fatal("the first stream never opened")
	}
	close(release)
	eventually(t, "broken audio remained ready without an error", func() bool {
		select {
		case fn := <-ch:
			fn()
		default:
		}
		return p.Err() != ""
	})
	if p.Ready() || p.Playing() || p.Current().ID != "a" || p.Position() < 2*time.Second {
		t.Fatalf("failed: ready=%v playing=%v track=%s position=%v", p.Ready(), p.Playing(), p.Current().ID, p.Position())
	}
	from := p.Position()
	p.TogglePlay()
	settle(t, ch, p)
	if !p.Ready() || !p.Playing() || p.Err() != "" || p.Position() < from || requests.Load() != 2 {
		t.Fatalf("retry: ready=%v playing=%v position=%v error=%q requests=%d", p.Ready(), p.Playing(), p.Position(), p.Err(), requests.Load())
	}
}
