package player

import (
	"bytes"
	"errors"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"

	"mygo-music/internal/music"
)

func TestIndexedMediaReadsTailInsteadOfDownloadingPrefix(t *testing.T) {
	media := bytes.Repeat([]byte{0x5a}, 5*mediaBlock)
	copy(media[4:], "ftyp")
	media[4*mediaBlock+5] = 0x72
	var mu sync.Mutex
	var requests []string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		requests = append(requests, r.Header.Get("Range"))
		mu.Unlock()
		if r.Header.Get("Range") != "bytes=0-262143" && r.Header.Get("If-Range") != `"media-one"` {
			t.Error("missing If-Range validator")
		}
		w.Header().Set("ETag", `"media-one"`)
		http.ServeContent(w, r, "fixture.mp4", time.Time{}, bytes.NewReader(media))
	}))
	defer server.Close()
	dl := (*Cache)(nil).download("platform", "id", "high", &music.Stream{Type: "m4a", URL: server.URL})
	defer dl.Close()
	dl.waitFor(12)
	file := &downloadFile{dl: dl}
	dst := make([]byte, 1)
	for range 2 {
		if n, err := file.ReadAt(dst, 4*mediaBlock+5); n != 1 || err != nil || dst[0] != 0x72 {
			t.Fatalf("tail: %x, %v", dst, err)
		}
	}
	mu.Lock()
	defer mu.Unlock()
	if len(requests) != 2 || requests[0] != "bytes=0-262143" || requests[1] != "bytes=1048576-1310719" {
		t.Fatalf("downloaded unneeded bytes: %v", requests)
	}
}

func TestIndexedMediaDoesNotSpliceAChangedRepresentation(t *testing.T) {
	media := make([]byte, 2*mediaBlock)
	copy(media[4:], "ftyp")
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		tag := `"first"`
		if r.Header.Get("If-Range") != "" {
			tag = `"other"`
		}
		w.Header().Set("ETag", tag)
		http.ServeContent(w, r, "fixture.mp4", time.Time{}, bytes.NewReader(media))
	}))
	defer server.Close()
	dl := (*Cache)(nil).download("platform", "id", "high", &music.Stream{Type: "m4a", URL: server.URL})
	defer dl.Close()
	dl.waitFor(12)
	if n, err := (&downloadFile{dl: dl}).ReadAt(make([]byte, 128), mediaBlock); n != 0 || !errors.Is(err, errMediaChanged) {
		t.Fatalf("spliced %d bytes: %v", n, err)
	}
}
