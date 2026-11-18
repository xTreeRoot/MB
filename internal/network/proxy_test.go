package network

import (
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
)

// Proxy decisions follow destination boundaries and change without restarting.
func TestAllowlistRouting(t *testing.T) {
	var policy Policy
	if err := policy.SetHosts([]string{"*.youtube.com", "api.betterlyrics.org"}); err != nil {
		t.Fatal(err)
	}
	proxy := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { io.WriteString(w, "proxy") }))
	defer proxy.Close()
	direct := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { io.WriteString(w, "direct") }))
	defer direct.Close()
	proxyURL, _ := url.Parse(proxy.URL)
	calls := 0
	transport := http.DefaultTransport.(*http.Transport).Clone()
	defer transport.CloseIdleConnections()
	transport.Proxy = func(r *http.Request) (*url.URL, error) {
		return policy.proxy(r, func(*http.Request) (*url.URL, error) { calls++; return proxyURL, nil })
	}
	client := &http.Client{Transport: transport}
	for _, tc := range []struct{ address, want string }{{"http://music.youtube.com/test", "proxy"}, {direct.URL, "direct"}} {
		res, err := client.Get(tc.address)
		if err != nil {
			t.Fatal(err)
		}
		got, _ := io.ReadAll(res.Body)
		res.Body.Close()
		if string(got) != tc.want {
			t.Fatalf("%s: %s", tc.address, got)
		}
	}
	for _, host := range []string{"youtube.com.evil.test", "fakeyoutube.com", "api.betterlyrics.org.evil.test", "api.bilibili.com", "youtube.com"} {
		req, _ := http.NewRequest("GET", "https://"+host, nil)
		if got, err := transport.Proxy(req); err != nil || got != nil {
			t.Errorf("unlisted %s selected a proxy", host)
		}
	}
	if calls != 1 {
		t.Fatalf("system resolver called for %d requests", calls)
	}
	if err := policy.SetHosts(nil); err != nil {
		t.Fatal(err)
	}
	req, _ := http.NewRequest("GET", "https://music.youtube.com", nil)
	if got, _ := transport.Proxy(req); got != nil {
		t.Fatal("cleared list still proxies")
	}
}

func TestAllowlistValidation(t *testing.T) {
	for _, input := range []string{"*", "https://youtube.com", "user:pass@youtube.com", "youtube.com:443", "foo.*.com", "-bad.com", "youtube..com"} {
		if _, err := ParseHosts(input); err == nil {
			t.Errorf("accepted %q", input)
		}
	}
	got, err := ParseHosts(" MUSIC.YouTube.com.\n*.ytimg.com\nmusic.youtube.com ")
	if err != nil || strings.Join(got, ",") != "music.youtube.com,*.ytimg.com" {
		t.Fatalf("parsed %v: %v", got, err)
	}
}
