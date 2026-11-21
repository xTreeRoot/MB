// Command icon exports the selected Shanhai artwork to the app and website icons.
// Run from the repository root on a Mac with Xcode 26 or later:
//
//	go run ./icon
//
// icon/shanhai.png is the full-bleed, opaque 1024-pixel master. Keep the
// Icon Composer documents pointed at shanhai.png when replacing the artwork.
package main

import (
	"bytes"
	"encoding/base64"
	"fmt"
	"image"
	"image/color"
	"image/draw"
	"image/png"
	"log"
	"os"
	"os/exec"
	"path/filepath"

	xdraw "golang.org/x/image/draw"
)

const ictool = "/Applications/Xcode.app/Contents/Applications/Icon Composer.app/Contents/Executables/ictool"

func main() {
	file, err := os.Open("icon/shanhai.png")
	if err != nil {
		log.Fatal(err)
	}
	source, err := png.Decode(file)
	file.Close()
	if err != nil {
		log.Fatal(err)
	}
	if source.Bounds().Dx() != 1024 || source.Bounds().Dy() != 1024 {
		log.Fatal("icon/shanhai.png must be 1024 x 1024")
	}
	for _, target := range []struct {
		path string
		size int
	}{
		{"icon/AppIcon.icon/Assets/shanhai.png", 1024},
		{"icon/AppIcon-dev.icon/Assets/shanhai.png", 1024},
		{"ios/MBiOS/Assets.xcassets/AppIcon.appiconset/AppIcon.png", 1024},
		{"android/app/src/main/res/drawable-nodpi/mb_icon.png", 192},
		{"website/public/icon.png", 1024},
	} {
		if err := writeIcon(source, target.path, target.size); err != nil {
			log.Fatal(err)
		}
	}
	for _, target := range []struct{ document, output string }{
		{"icon/AppIcon.icon", "resources/icon.png"},
		{"icon/AppIcon-dev.icon", "resources/icon-dev.png"},
	} {
		if err := render(target.document, target.output); err != nil {
			log.Fatal(err)
		}
	}
	var favicon bytes.Buffer
	if err := png.Encode(&favicon, resize(source, 128)); err != nil {
		log.Fatal(err)
	}
	svg := fmt.Sprintf(`<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 1024"><defs><clipPath id="shape"><rect width="1024" height="1024" rx="230"/></clipPath></defs><image width="1024" height="1024" clip-path="url(#shape)" href="data:image/png;base64,%s"/></svg>`+"\n", base64.StdEncoding.EncodeToString(favicon.Bytes()))
	if err := os.WriteFile("website/public/favicon.svg", []byte(svg), 0o644); err != nil {
		log.Fatal(err)
	}
}

func resize(source image.Image, size int) image.Image {
	if source.Bounds().Dx() == size && source.Bounds().Dy() == size {
		return source
	}
	target := image.NewRGBA(image.Rect(0, 0, size, size))
	xdraw.CatmullRom.Scale(target, target.Bounds(), source, source.Bounds(), xdraw.Src, nil)
	return target
}

func writeIcon(source image.Image, path string, size int) error {
	file, err := os.Create(path)
	if err != nil {
		return err
	}
	if err := png.Encode(file, resize(source, size)); err != nil {
		file.Close()
		return err
	}
	return file.Close()
}

// render has ictool draw document as macOS does, and lays it on the
// 1024-pixel grid of macOS icons at output: an 824-pixel body, centered,
// over a soft shadow.
func render(document, output string) error {
	const canvas, body = 1024, 824
	dir, err := os.MkdirTemp("", "icon")
	if err != nil {
		return err
	}
	defer os.RemoveAll(dir)
	drawn := filepath.Join(dir, "icon.png")
	size := fmt.Sprint(body)
	if out, err := exec.Command(ictool, document, "--export-image", "--output-file", drawn,
		"--platform", "macOS", "--rendition", "Default", "--width", size, "--height", size, "--scale", "1").CombinedOutput(); err != nil {
		return fmt.Errorf("ictool (it comes with Xcode 26 or later): %v\n%s", err, out)
	}
	// ictool draws in Display P3, and the PNG this writes carries no
	// profile, which readers take as sRGB: convert, or the reds go dull.
	if out, err := exec.Command("sips", "--matchTo", "/System/Library/ColorSync/Profiles/sRGB Profile.icc", drawn).CombinedOutput(); err != nil {
		return fmt.Errorf("sips: %v\n%s", err, out)
	}
	f, err := os.Open(drawn)
	if err != nil {
		return err
	}
	defer f.Close()
	src, err := png.Decode(f)
	if err != nil {
		return err
	}

	at := image.Pt((canvas-body)/2, (canvas-body)/2)
	dst := image.NewNRGBA(image.Rect(0, 0, canvas, canvas))
	shadow := image.NewAlpha(dst.Bounds())
	draw.Draw(shadow, src.Bounds().Add(at).Add(image.Pt(0, 12)), src, src.Bounds().Min, draw.Src)
	blur(shadow, 10)
	draw.DrawMask(dst, dst.Bounds(), image.NewUniform(color.NRGBA{A: 72}), image.Point{}, shadow, image.Point{}, draw.Over)
	draw.Draw(dst, src.Bounds().Add(at), src, src.Bounds().Min, draw.Over)

	out, err := os.Create(output)
	if err != nil {
		return err
	}
	if err := png.Encode(out, dst); err != nil {
		out.Close()
		return err
	}
	return out.Close()
}

// blur approximates a Gaussian blur of the given radius with three box
// blurs in each direction.
func blur(m *image.Alpha, radius int) {
	w, h := m.Rect.Dx(), m.Rect.Dy()
	buf := make([]uint8, len(m.Pix))
	pass := func(src, dst []uint8, count, length, stride, step int) {
		for line := range count {
			base := line * stride
			sum := 0
			for i := -radius; i <= radius; i++ {
				sum += int(src[base+min(max(i, 0), length-1)*step])
			}
			for i := range length {
				dst[base+i*step] = uint8(sum / (2*radius + 1))
				sum += int(src[base+min(i+radius+1, length-1)*step]) - int(src[base+max(i-radius, 0)*step])
			}
		}
	}
	for range 3 {
		pass(m.Pix, buf, h, w, m.Stride, 1)
		pass(buf, m.Pix, w, h, 1, m.Stride)
	}
}
