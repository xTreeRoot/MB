package app

import (
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	video "github.com/egoist/mygo/mbvideo"
	"github.com/egoist/mygo/ui"
	"mygo-music/internal/music"
)

// Metadata has no bytes until cancelled. Constructing, drawing, handling input
// and closing must all finish while the network request is still outstanding.
func TestVideoLoadingDoesNotBlockUI(t *testing.T) {
	started := make(chan struct{}, 8)
	remote := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		select {
		case started <- struct{}{}:
		default:
		}
		<-r.Context().Done()
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
	p.SetMuted(true)
	a := newTestApp()
	a.mv.video, a.mv.loading, a.mv.proxy = p, true, proxy
	defer a.mv.release()
	startedAt := time.Now()
	tt := ui.NewTester(a.mvView, 640, 360)
	if elapsed := time.Since(startedAt); elapsed > 500*time.Millisecond {
		t.Fatalf("render blocked on metadata for %v", elapsed)
	}
	wait(t, started, "metadata download")
	if !tt.HasText("Loading…") {
		t.Fatal("missing loading feedback")
	}
	// Input must still be handled while metadata is unavailable.
	if err = tt.Click("Keep MV on Top"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	if !a.mv.pinned {
		t.Fatal("loading view did not handle input")
	}
	startedAt = time.Now()
	a.mv.release()
	if elapsed := time.Since(startedAt); elapsed > 500*time.Millisecond {
		t.Fatalf("close blocked on metadata for %v", elapsed)
	}
	tt.Frame()
}
