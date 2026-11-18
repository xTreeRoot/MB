package app

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"image"
	"image/draw"
	"os"
	"testing"

	"github.com/egoist/mygo/ui"
	"mygo-music/internal/music"
)

func discoveryHome(a *app) *music.Browse {
	tracks := framesTracks(a)
	return &music.Browse{Sections: []music.Section{
		{Title: "For You", TrackLayout: music.TrackLayoutVideos, Tracks: tracks[:6], MorePage: "recommended"},
		{Title: "Music Chart", TrackLayout: music.TrackLayoutVideos, Tracks: tracks[2:8], MorePage: "chart"},
	}}
}

// Video cards are direct playback actions at every supported window width.
func TestDiscoveryCardsPlaySectionQueue(t *testing.T) {
	for _, width := range []int{880, 1180, 1500} {
		t.Run(fmt.Sprint(width), func(t *testing.T) {
			a := newTestApp()
			a.home = discoveryHome(a)
			tt := ui.NewTester(a.view, width, 900)
			tt.Frame()
			if err := tt.Click("Slow Tide"); err != nil {
				t.Fatal(err)
			}
			if cur := a.pl.Current(); cur == nil || cur.ID != "2" {
				t.Fatalf("clicked card selected %+v", cur)
			}
			if queue := a.pl.Queue(); len(queue) != 6 || queue[5].ID != "6" {
				t.Fatalf("wrong section queue: %+v", queue)
			}
		})
	}
}

func TestDiscoverySeeAll(t *testing.T) {
	a, ch, asked := browseApp()
	a.home = discoveryHome(a)
	tt := ui.NewTester(a.view, 1180, 780)
	tt.Frame()
	if err := tt.Click("See All"); err != nil {
		t.Fatal(err)
	}
	if got := wait(t, asked, "discovery page"); got != "recommended" {
		t.Fatalf("requested %q", got)
	}
	runPost(t, ch)
	tt.Frame()
	if a.route.page != pageBrowse || a.route.id != "recommended" {
		t.Fatalf("opened %+v", a.route)
	}
}

// Refresh does not start duplicate requests or discard the current page on a
// network failure, and another click can retry after the failed request ends.
func TestHomeRefreshKeepsPageAndRetries(t *testing.T) {
	a := newTestApp()
	ch := posts(a)
	a.home = discoveryHome(a)
	release := make(chan struct{})
	called := make(chan struct{}, 2)
	fake(a).home = func() (*music.Browse, error) {
		called <- struct{}{}
		<-release
		return nil, errors.New("network unavailable")
	}
	tt := ui.NewTester(a.view, 1180, 780)
	tt.Frame()
	if err := tt.Click(T("Refresh")); err != nil {
		t.Fatal(err)
	}
	wait(t, called, "refresh")
	if !a.homeLoading {
		t.Fatal("not loading")
	}
	tt.Frame()
	if n, ok := tt.AccessByRole(ui.RoleButton, T("Refresh")); !ok || !n.Disabled {
		t.Fatal("refresh is still enabled")
	}
	a.loadHome(a.library)
	if len(called) != 0 {
		t.Fatal("duplicate refresh")
	}
	close(release)
	runPost(t, ch)
	tt.Frame()
	if a.home == nil || a.homeErr == "" || a.homeLoading {
		t.Fatal("refresh discarded the page or hid the error")
	}
	fake(a).home = func() (*music.Browse, error) {
		return &music.Browse{Sections: []music.Section{{Title: "Updated", TrackLayout: music.TrackLayoutVideos, Tracks: []music.Track{song("new", "New video")}}}}, nil
	}
	if err := tt.Click(T("Refresh")); err != nil {
		t.Fatal(err)
	}
	runPost(t, ch)
	tt.Frame()
	if a.homeErr != "" || a.home.Sections[0].Title != "Updated" {
		t.Fatal("retry did not replace the page")
	}
}

// MM_FRAMES optionally uses the public discovery response from a live probe.
func TestDiscoveryHomeFrame(t *testing.T) {
	dir := framesDir(t)
	a := newTestApp()
	a.home = discoveryHome(a)
	if path := os.Getenv("MB_HOME_FIXTURE"); path != "" {
		data, err := os.ReadFile(path)
		if err != nil {
			t.Fatal(err)
		}
		if err = json.Unmarshal(data, &a.home); err != nil {
			t.Fatal(err)
		}
		// Fetch covers before rendering; the UI test never performs network IO.
		for _, sec := range a.home.Sections {
			for _, track := range sec.Tracks {
				data, err := music.Fetch(track.Album.ImageURL)
				if err != nil {
					continue
				}
				img, _, err := image.Decode(bytes.NewReader(data))
				if err == nil {
					rgba := image.NewRGBA(img.Bounds())
					draw.Draw(rgba, rgba.Bounds(), img, img.Bounds().Min, draw.Src)
					giveCover(a, track.Album.ImageURL, rgba)
				}
			}
		}
	}
	for _, width := range []int{880, 1180, 1500} {
		tt := ui.NewTester(a.view, width, 900)
		settled(tt)
		saveFrame(t, dir, fmt.Sprintf("home-discovery-%d.png", int(width)), tt.Image())
		if width == 1180 {
			tt.SetDark(true)
			settled(tt)
			saveFrame(t, dir, "home-discovery-dark.png", tt.Image())
		}
	}
}
