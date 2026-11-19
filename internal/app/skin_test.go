package app

import (
	"image"
	"image/color"
	"image/draw"
	"image/png"
	"os"
	"path/filepath"
	"testing"

	"github.com/egoist/mygo"
	"github.com/egoist/mygo/ui"
)

func TestBackgroundSkinSurvivesInvalidImportAndReopen(t *testing.T) {
	directory := t.TempDir()
	input := filepath.Join(directory, "background.png")
	file, err := os.Create(input)
	if err != nil {
		t.Fatal(err)
	}
	canvas := image.NewRGBA(image.Rect(0, 0, 200, 130))
	draw.Draw(canvas, canvas.Bounds(), image.NewUniform(color.RGBA{R: 70, G: 104, B: 185, A: 255}), image.Point{}, draw.Src)
	if err := png.Encode(file, canvas); err != nil {
		file.Close()
		t.Fatal(err)
	}
	if err := file.Close(); err != nil {
		t.Fatal(err)
	}
	originalPick := pickSkin
	t.Cleanup(func() { pickSkin = originalPick })
	pickSkin = func(*mygo.Window) ([]string, error) { return []string{input}, nil }
	a := newTestApp()
	a.settings.path = filepath.Join(directory, "settings.json")
	callbacks := posts(a)
	a.chooseSkin()
	runPost(t, callbacks)
	if a.skin == nil || a.skinError {
		t.Fatal("valid skin did not load")
	}
	imported := a.skin
	if err := os.WriteFile(input, []byte("not an image"), 0o600); err != nil {
		t.Fatal(err)
	}
	a.chooseSkin()
	runPost(t, callbacks)
	if a.skin != imported || !a.skinError {
		t.Fatal("invalid input replaced the current skin or did not report failure")
	}
	reopened := newTestApp()
	reopened.settings.path = a.settings.path
	reopenedCallbacks := posts(reopened)
	reopened.restoreSkin()
	runPost(t, reopenedCallbacks)
	if reopened.skin == nil || reopened.skinError {
		t.Fatal("reopen did not restore the valid skin")
	}
	view := ui.NewTester(reopened.view, 1200, 800)
	view.Frame()
	if err := view.Click(T("Video Mode")); err != nil {
		t.Fatal(err)
	}
	if reopened.settings.PlaybackMode != playbackVideo {
		t.Fatal("skin intercepted the mode switch")
	}
	if directory := os.Getenv("MM_FRAMES"); directory != "" {
		saveFrame(t, directory, "desktop-background-skin.png", view.Image())
	}
	reopened.clearSkin()
	runPost(t, reopenedCallbacks)
	reopened.restoreSkin()
	runPost(t, reopenedCallbacks)
	if reopened.skin != nil || reopened.skinError {
		t.Fatal("cleared skin returned after reloading")
	}
}
