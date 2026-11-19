package app

import (
	"crypto/rand"
	"errors"
	"io"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync/atomic"
	"time"

	"mygo-music/internal/music"
)

// mvProxy keeps request headers in Go and supports the media framework's range
// requests. Each video gets a private loopback URL that dies with its player.
type mvProxy struct {
	server      *http.Server
	url         string
	refreshable atomic.Bool
}

func videoRangeMatches(requestRange string, response *http.Response) bool {
	if !strings.HasPrefix(requestRange, "bytes=") {
		return false
	}
	wanted := strings.Split(strings.TrimPrefix(requestRange, "bytes="), "-")
	parts := strings.Fields(response.Header.Get("Content-Range"))
	if len(wanted) != 2 || len(parts) != 2 || parts[0] != "bytes" {
		return false
	}
	span, length, ok := strings.Cut(parts[1], "/")
	first, last, split := strings.Cut(span, "-")
	start, firstErr := strconv.ParseInt(first, 10, 64)
	end, lastErr := strconv.ParseInt(last, 10, 64)
	size, sizeErr := strconv.ParseInt(length, 10, 64)
	encoding := response.Header.Get("Content-Encoding")
	if !ok || !split || firstErr != nil || lastErr != nil || sizeErr != nil || start < 0 || end < start || end >= size ||
		response.ContentLength >= 0 && response.ContentLength != end-start+1 || encoding != "" && encoding != "identity" {
		return false
	}
	if wanted[0] == "" {
		suffix, err := strconv.ParseInt(wanted[1], 10, 64)
		return err == nil && suffix > 0 && start == max(0, size-suffix) && end == size-1
	}
	wantedStart, err := strconv.ParseInt(wanted[0], 10, 64)
	if err != nil || wantedStart != start {
		return false
	}
	if wanted[1] == "" {
		return end == size-1
	}
	wantedEnd, err := strconv.ParseInt(wanted[1], 10, 64)
	return err == nil && end == min(wantedEnd, size-1)
}

func newMVProxy(stream *music.VideoStream) (*mvProxy, error) {
	if stream == nil {
		return nil, errors.New("mv: no video stream")
	}
	u, err := url.Parse(stream.URL)
	if err != nil || u.Host == "" || u.Scheme != "https" && u.Scheme != "http" {
		return nil, errors.New("mv: invalid video URL")
	}
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return nil, err
	}
	path := "/" + rand.Text() + "/video.mp4"
	client := music.HeadersStay(http.DefaultClient, u.Hostname(), stream.Headers)
	server := &http.Server{ReadHeaderTimeout: 5 * time.Second}
	p := &mvProxy{server: server, url: "http://" + listener.Addr().String() + path}
	server.Handler = http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != path {
			http.NotFound(w, r)
			return
		}
		if r.Method != http.MethodGet && r.Method != http.MethodHead {
			w.WriteHeader(http.StatusMethodNotAllowed)
			return
		}
		req, err := http.NewRequestWithContext(r.Context(), r.Method, stream.URL, nil)
		if err != nil {
			http.Error(w, "Invalid media request", http.StatusBadGateway)
			return
		}
		req.Header.Set("User-Agent", music.Agent)
		for key, value := range stream.Headers {
			req.Header.Set(key, value)
		}
		req.Header.Set("Accept-Encoding", "identity")
		for _, key := range []string{"Range", "If-Range"} {
			if value := r.Header.Get(key); value != "" {
				req.Header.Set(key, value)
			}
		}
		res, err := client.Do(req)
		if err != nil {
			if r.Context().Err() == nil {
				p.refreshable.Store(true)
			}
			http.Error(w, "Could not load video", http.StatusBadGateway)
			return
		}
		defer res.Body.Close()
		if res.StatusCode == 401 || res.StatusCode == 410 || res.StatusCode >= 500 {
			p.refreshable.Store(true)
		}
		if res.StatusCode == 206 && !videoRangeMatches(r.Header.Get("Range"), res) {
			p.refreshable.Store(true)
			http.Error(w, "Invalid media range", http.StatusBadGateway)
			return
		}
		for _, key := range []string{"Content-Type", "Content-Length", "Content-Range", "Accept-Ranges", "ETag", "Last-Modified"} {
			if value := res.Header.Get(key); value != "" {
				w.Header().Set(key, value)
			}
		}
		if w.Header().Get("Content-Type") == "" {
			w.Header().Set("Content-Type", "video/mp4")
		}
		w.Header().Set("Cache-Control", "no-store")
		w.WriteHeader(res.StatusCode)
		if r.Method == http.MethodGet {
			if _, err := io.Copy(w, res.Body); err != nil && r.Context().Err() == nil {
				p.refreshable.Store(true)
			}
		}
	})
	go server.Serve(listener)
	return p, nil
}

func (p *mvProxy) Close() {
	if p != nil {
		_ = p.server.Close()
	}
}
