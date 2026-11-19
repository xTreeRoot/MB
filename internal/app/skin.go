package app

import (
	"bytes"
	"errors"
	"image"
	"io"
	"io/fs"
	"os"
	"path/filepath"

	"github.com/egoist/mygo"
	"github.com/egoist/mygo/ui"
)

const skinFile = "background-skin.jpg"

func skinPath(settingsPath string) string {
	return filepath.Join(filepath.Dir(settingsPath), skinFile)
}

func importSkin(source, destination string) (*ui.Bitmap, error) {
	const maxBytes = 32 << 20
	const maxPixels = 50_000_000
	file, err := os.Open(source)
	if err != nil {
		return nil, err
	}
	defer file.Close()
	data, err := io.ReadAll(io.LimitReader(file, maxBytes+1))
	if err != nil {
		return nil, err
	}
	if len(data) > maxBytes {
		return nil, errors.New("skin file too large")
	}
	data = stillCover(data)
	config, _, err := image.DecodeConfig(bytes.NewReader(data))
	if err != nil {
		return nil, err
	}
	if config.Width <= 0 || config.Height <= 0 || config.Width > maxPixels/config.Height {
		return nil, errors.New("skin dimensions too large")
	}
	img, _, err := image.Decode(bytes.NewReader(data))
	if err != nil {
		return nil, err
	}
	_, encoded, err := shrinkArt(img, min(2560, max(config.Width, config.Height)))
	if err != nil {
		return nil, err
	}
	bmp, err := ui.DecodeBitmap(encoded)
	if err != nil {
		return nil, err
	}
	dir := filepath.Dir(destination)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return nil, err
	}
	tmp, err := os.CreateTemp(dir, ".background-*.jpg")
	if err != nil {
		return nil, err
	}
	defer os.Remove(tmp.Name())
	_, writeErr := tmp.Write(encoded)
	if err := errors.Join(writeErr, tmp.Close()); err != nil {
		return nil, err
	}
	if err := os.Rename(tmp.Name(), destination); err != nil {
		return nil, err
	}
	return bmp, nil
}

func (a *app) restoreSkin() {
	if a.settings.path == "" {
		return
	}
	destination := skinPath(a.settings.path)
	a.skinBusy = true
	go func() {
		data, err := os.ReadFile(destination)
		var bmp *ui.Bitmap
		if err == nil {
			bmp, err = ui.DecodeBitmap(data)
		}
		a.post(func() {
			a.skin, a.skinBusy = bmp, false
			a.skinError = err != nil && !errors.Is(err, fs.ErrNotExist)
		})
	}()
}

var pickSkin = func(parent *mygo.Window) ([]string, error) {
	return mygo.Dialog.Open(mygo.OpenDialogOptions{
		Parent:  parent,
		Title:   T("Choose Background Image…"),
		Filters: []mygo.FileFilter{{Name: T("Images"), Extensions: []string{"jpg", "jpeg", "png", "webp"}}},
	})
}

func (a *app) chooseSkin() {
	if a.skinBusy || a.settings.path == "" {
		return
	}
	a.skinBusy, a.skinError = true, false
	parent, destination := a.settingsWin, skinPath(a.settings.path)
	go func() {
		paths, err := pickSkin(parent)
		var bmp *ui.Bitmap
		if err == nil && len(paths) > 0 {
			bmp, err = importSkin(paths[0], destination)
		}
		a.post(func() {
			if bmp != nil {
				a.skin = bmp
			}
			a.skinBusy, a.skinError = false, err != nil
		})
	}()
}

func (a *app) clearSkin() {
	if a.skinBusy || a.settings.path == "" {
		return
	}
	a.skinBusy = true
	destination := skinPath(a.settings.path)
	go func() {
		err := os.Remove(destination)
		a.post(func() {
			ok := err == nil || errors.Is(err, fs.ErrNotExist)
			if ok {
				a.skin = nil
			}
			a.skinBusy, a.skinError = false, !ok
		})
	}()
}

func (a *app) skinBackdrop(c *ui.Context) {
	opacity := float32(0.22)
	if a.pal.dark {
		opacity = 0.35
	}
	ui.Box(c.Key("background-skin")).Absolute().Top(0).Left(0).Right(0).Bottom(0).ZIndex(-1).Background(a.pal.bg).
		AccessHidden(true).IgnoreInput(true).Children(func() {
		artImage(c, a.skin, 0, opacity)
	})
}
