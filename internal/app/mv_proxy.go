package app

import (
	"crypto/rand"
	"errors"
	"io"
	"net"
	"net/http"
	"net/url"
	"time"

	"mygo-music/internal/music"
)

// mvProxy keeps request headers in Go and supports the media framework's range
// requests. Each video gets a private loopback URL that dies with its player.
type mvProxy struct {
	server *http.Server
	url    string
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
		for _, key := range []string{"Range", "If-Range"} {
			if value := r.Header.Get(key); value != "" {
				req.Header.Set(key, value)
			}
		}
		res, err := client.Do(req)
		if err != nil {
			http.Error(w, "Could not load video", http.StatusBadGateway)
			return
		}
		defer res.Body.Close()
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
			_, _ = io.Copy(w, res.Body)
		}
	})
	p := &mvProxy{server: server, url: "http://" + listener.Addr().String() + path}
	go server.Serve(listener)
	return p, nil
}

func (p *mvProxy) Close() {
	if p != nil {
		_ = p.server.Close()
	}
}
