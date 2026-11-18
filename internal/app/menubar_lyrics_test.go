package app

import (
	"strings"
	"testing"
	"time"

	"github.com/egoist/mygo/audio"

	"mygo-music/internal/lyrics"
	"mygo-music/internal/music"
)

// The menu bar follows subtitles, seeks and tracks with no main window present.
func TestMenuBarLyricsFollowPlayback(t *testing.T) {
	a := newTestApp()
	a.settings.MenuBarLyrics = true
	a.pl.Cue([]music.Track{song("1", "First Track", "Artist")})
	full := "第一句歌词很长需要在状态栏里滚动显示而不是直接截断丢掉后半句"
	lines := []lyrics.Line{
		{At: time.Second, End: 10 * time.Second, Text: full},
		{At: 12 * time.Second, End: 20 * time.Second, Text: full},
	}
	a.pl.SetLyrics(lines)
	a.pl.Hold(time.Second)
	first := a.menuBarContent()
	if first.text != full || first.elapsed != 0 || !strings.Contains(first.tip, full) {
		t.Fatal("first subtitle or full tooltip was lost")
	}
	a.pl.Hold(3 * time.Second)
	moved := a.menuBarContent()
	if moved.elapsed != 2*time.Second {
		t.Fatal("long subtitle did not scroll")
	}
	// The same playhead produces the same output when playback is paused.
	frozen := a.menuBarContent()
	if frozen != moved {
		t.Fatal("marquee depends on a wall clock")
	}
	a.pl.Hold(time.Second)
	back := a.menuBarContent()
	if back != first {
		t.Fatal("seek backwards did not restore the corresponding lyric window")
	}
	a.pl.Hold(12 * time.Second)
	next := a.menuBarContent()
	if next != first {
		t.Fatal("identical next subtitle did not start from its beginning")
	}
	a.pl.Hold(11 * time.Second)
	gap := a.menuBarContent()
	if gap.text != "First Track" {
		t.Fatalf("subtitle gap retained an expired line: %q", gap.text)
	}
	a.pl.Cue([]music.Track{song("2", "Second Track", "Another Artist")})
	a.pl.SetLyrics(nil)
	title := a.menuBarContent()
	if title.text != "Second Track" || strings.Contains(title.tip, full) {
		t.Fatal("track switch retained stale lyrics")
	}
	a.settings.MenuBarLyrics = false
	if title = a.menuBarContent(); title.text != "" {
		t.Fatal("disabling menu bar lyrics kept its title")
	}
}

// Paused audio freezes the marquee; seeking updates it before playback resumes.
func TestMenuBarLyricsPauseAndSeek(t *testing.T) {
	a := newTestApp()
	a.settings.MenuBarLyrics = true
	a.pl.Cue([]music.Track{song("1", "Track")})
	full := "这是一句需要横向滚动才能完整看见的菜单栏歌词暂停后必须停止移动"
	a.pl.SetLyrics([]lyrics.Line{{At: time.Second, Text: full}})
	out := audio.NewPlayer(&silence{})
	defer out.Close()
	out.SetVolume(0)
	a.pl.Attach(out)
	a.pl.TogglePlay()
	out.Pause()
	a.pl.Seek(3 * time.Second)
	before := a.menuBarContent()
	if a.pl.Playing() || before.elapsed != 2*time.Second {
		t.Fatal("paused lyrics did not follow the audio position")
	}
	time.Sleep(30 * time.Millisecond)
	after := a.menuBarContent()
	if after != before {
		t.Fatal("paused lyrics kept scrolling")
	}
	a.pl.Seek(time.Second)
	back := a.menuBarContent()
	if back.elapsed != 0 || back == before {
		t.Fatal("paused seek did not reset the lyric window")
	}
}
