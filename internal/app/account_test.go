package app

import (
	"image"
	"image/draw"
	"os"
	"sync/atomic"
	"testing"
	"time"

	"github.com/egoist/mygo/ui"

	"mygo-music/internal/music"
)

// Old authenticated page errors must not reopen sign-in over the guest home after signing out.
func TestAccountChangeRejectsOldPageErrors(t *testing.T) {
	a := newTestApp()
	ch := posts(a)
	started, release := make(chan string, 3), make(chan struct{})
	fake(a).home = func() (*music.Browse, error) {
		started <- "home"
		<-release
		return nil, music.ErrSignIn
	}
	fake(a).playlists = func() ([]music.Playlist, error) {
		started <- "playlists"
		<-release
		return nil, music.ErrSignIn
	}
	fake(a).browse = func(string) (*music.Browse, error) {
		started <- "browse"
		<-release
		return nil, music.ErrSignIn
	}
	a.loadHome(a.library)
	a.loadMine(a.library)
	a.loadBrowse(a.library, "personal", false)
	for range 3 {
		wait(t, started, "an authenticated page request")
	}
	a.library.accountGen++
	close(release)
	for range 3 {
		runPost(t, ch)
	}
	if a.signIn.open || a.needsSignIn || a.homeErr != "" {
		t.Fatal("an obsolete page error interrupted the guest session")
	}
}

// Signing out waits for the source before reloading, and rejects an older account answer that used to restore the login.
func TestSignOutReloadsAsGuest(t *testing.T) {
	a := newTestApp()
	ch := posts(a)
	a.account = &music.Account{ID: "old", Name: "Listener"}
	a.mine = []music.Playlist{{ID: "private", Name: "Private"}}
	accountStarted, finishAccount := make(chan struct{}), make(chan struct{})
	signOutStarted, finishSignOut := make(chan struct{}), make(chan struct{})
	var signedOut atomic.Bool
	fake(a).account = func() (*music.Account, error) {
		close(accountStarted)
		<-finishAccount
		return &music.Account{ID: "late", Name: "Late account"}, nil
	}
	fake(a).signOut = func() error {
		close(signOutStarted)
		<-finishSignOut
		signedOut.Store(true)
		return nil
	}
	fake(a).home = func() (*music.Browse, error) {
		if !signedOut.Load() {
			t.Error("home reloaded before the source stopped using its credentials")
		}
		return &music.Browse{Sections: []music.Section{{Title: "Public picks", Tracks: []music.Track{song("public", "Public video")}}}}, nil
	}
	a.loadAccount(a.library)
	wait(t, accountStarted, "the pending account request")
	tt := ui.NewTester(a.view, 1000, 700)
	settled(tt)
	if err := tt.Click("Listener"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	if err := tt.Click("Sign Out"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	wait(t, signOutStarted, "signing out")
	close(finishAccount)
	runPost(t, ch)
	if a.account != nil || len(a.mine) != 0 || len(a.saved) != 0 {
		t.Fatal("sign-out kept personal data or accepted a stale account")
	}
	close(finishSignOut)
	runPost(t, ch)
	until(t, ch, func() bool { return a.home != nil })
	if a.signIn.open || a.homeErr != "" || a.route.page != pageHome {
		t.Fatalf("guest home did not load: route %+v, error %q, sign-in %v", a.route, a.homeErr, a.signIn.open)
	}
	settled(tt)
	if dir := os.Getenv("MM_FRAMES"); dir != "" {
		saveFrame(t, dir, "guest-home.png", tt.Image())
	}
	if err := tt.Click("Public picks"); err != nil {
		t.Fatal(err)
	}
	if a.route.page != pageSection || a.signIn.open {
		t.Fatal("a guest could not open the public recommendations")
	}
}

// Clicking the account row drops its menu, which takes the focus so the
// arrows walk its items, and Escape or a second click puts it away.
func TestAccountMenu(t *testing.T) {
	a := newTestApp()
	a.account = &music.Account{ID: "1", Name: "小明"}
	tt := ui.NewTester(a.view, 1000, 700)
	tt.Frame()

	if err := tt.Click("小明"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	if !a.accountOpen || !tt.HasText("Sign Out") {
		t.Fatalf("menu not open: %v", a.accountOpen)
	}
	if !tt.Focused("Settings…") {
		t.Fatal("the first item has no focus")
	}
	tt.Key(0, ui.KeyDown)
	tt.Frame()
	if !tt.Focused("Sign Out") {
		t.Fatal("Down did not move to signing out")
	}
	tt.Key(0, ui.KeyEscape)
	tt.Frame()
	if a.accountOpen {
		t.Fatal("Escape left the menu open")
	}

	tt.Click("小明")
	tt.Frame()
	tt.Click("小明")
	tt.Frame()
	if a.accountOpen {
		t.Fatal("a second click left the menu open")
	}
}

// My Page in the account menu opens the listener's own page at the source,
// and loads it anew each time, as what they follow changes on other pages.
func TestAccountMyPage(t *testing.T) {
	a := newTestApp()
	ch := posts(a)
	a.account = &music.Account{ID: "1", Name: "小明", Page: "user:1"}
	asked := make(chan string, 4)
	fake(a).browse = func(page string) (*music.Browse, error) {
		asked <- page
		return &music.Browse{Title: "小明", ImageURL: "https://example.com/a.jpg", Description: "3 关注 · 5 粉丝\n你好",
			Sections: []music.Section{{Title: "创建的歌单", Empty: "暂无公开歌单"}}}, nil
	}
	tt := ui.NewTester(a.view, 1000, 700)
	tt.Frame()

	tt.Click("小明")
	tt.Frame()
	if !tt.Focused("My Page") {
		t.Fatal("My Page isn't first in the menu")
	}
	if err := tt.Click("My Page"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	if want := (route{page: pageBrowse, id: "user:1", src: "fake"}); a.route != want || a.accountOpen {
		t.Fatalf("route %+v, menu open %v", a.route, a.accountOpen)
	}
	if got := wait(t, asked, "the page"); got != "user:1" {
		t.Fatalf("asked for %q", got)
	}
	runPost(t, ch)
	tt.Frame()

	a.open(route{page: pageHome})
	tt.Frame()
	tt.Click("小明")
	tt.Frame()
	tt.Click("My Page")
	wait(t, asked, "the page again")
}

// With MM_FRAMES set, saves the foot of the sidebar with the account menu
// open, light and dark.
func TestAccountMenuFrames(t *testing.T) {
	dir := os.Getenv("MM_FRAMES")
	if dir == "" {
		t.Skip()
	}
	for _, dark := range []bool{false, true} {
		a := newTestApp()
		a.account = &music.Account{ID: "1", Name: "小明"}
		tt := ui.NewTester(a.view, 1000, 600)
		tt.SetDark(dark)
		tt.Frame()
		tt.Click("小明")
		tt.Advance(time.Second)
		tt.Frame()
		img := tt.Image()
		s := float32(img.Bounds().Dx()) / 1000
		crop := image.Rect(0, int(400*s), int((sidebarWidth+20)*s), img.Bounds().Dy())
		out := image.NewRGBA(image.Rect(0, 0, crop.Dx(), crop.Dy()))
		draw.Draw(out, out.Bounds(), img, crop.Min, draw.Src)
		name := "account-light.png"
		if dark {
			name = "account-dark.png"
		}
		saveFrame(t, dir, name, out)
	}
}
