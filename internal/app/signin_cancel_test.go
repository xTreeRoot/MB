package app

import (
	"testing"
	"time"

	"github.com/egoist/mygo/ui"
	"mygo-music/internal/music"
)

type cancelableSource struct {
	*fakeSource
	cancel func()
}

func (source *cancelableSource) CancelSignIn() { source.cancel() }

// Clicking Cancel must stop a source's QR sign-in and ignore an account arriving after cancellation.
func TestSignInCancelStopsSource(t *testing.T) {
	a := newTestApp()
	ch := posts(a)
	started := make(chan struct{})
	release := make(chan struct{})
	cancelled := false
	source := &cancelableSource{
		fakeSource: newFakeSource("qr"),
		cancel:     func() { cancelled = true; close(release) },
	}
	source.signIn = func() (*music.Account, error) {
		close(started)
		<-release
		return &music.Account{ID: "late", Name: "Late account"}, nil
	}
	lib := addFake(a, source)
	a.promptSignIn(lib)
	a.startSignIn()
	select {
	case <-started:
	case <-time.After(time.Second):
		t.Fatal("sign-in did not start")
	}
	tt := ui.NewTester(func(c *ui.Context) { a.signInDialog(c, a.pal, false) }, 800, 600)
	settled(tt)
	if err := tt.Click(T("Cancel")); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	if !cancelled || a.signIn.open || a.signIn.waiting {
		t.Fatalf("cancelled %v; dialog %+v", cancelled, a.signIn)
	}
	runPost(t, ch)
	if lib.account != nil {
		t.Fatal("cancelled sign-in installed its late account")
	}
}

// With MM_FRAMES set, render the actual native sign-in dialog containing Bilibili's QR payload.
func TestBilibiliQRDialogFrame(t *testing.T) {
	dir := framesDir(t)
	a := newTestApp()
	lib := a.library
	fake(a).info.Name = "哔哩哔哩"
	a.signIn = signInDialog{open: true, waiting: true, lib: lib}
	a.signInPrompted(music.SignInPrompt{
		QR:   "https://account.bilibili.com/h5/account-h5/auth/scan-web?qrcode_key=test",
		Text: "使用哔哩哔哩 App 扫描二维码。",
	})
	tt := ui.NewTester(func(c *ui.Context) { a.signInDialog(c, a.pal, false) }, 800, 600)
	a.pal = paletteFor(ui.LightTheme())
	settled(tt)
	tt.Advance(time.Second)
	tt.Frame()
	saveFrame(t, dir, "bilibili-qr-login.png", tt.Image())
}
