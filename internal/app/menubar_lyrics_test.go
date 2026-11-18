package app

import (
	"strings"
	"testing"
	"time"
	"unicode/utf8"

	"github.com/egoist/mygo/audio"

	"mygo-music/internal/lyrics"
	"mygo-music/internal/music"
)

// Long lines advance through their tail and wrap, preserving a bounded UTF-8 title.
func TestMenuBarMarquee(t *testing.T) {
	full := "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
	for _, tc := range []struct {
		at   time.Duration
		want string
	}{
		{-time.Second, full[:18]},
		{0, full[:18]},
		{menuBarScrollHold, full[:18]},
		{menuBarScrollHold + menuBarScrollStep, full[1:19]},
		{menuBarScrollHold + 18*menuBarScrollStep, full[18:36]},
		{menuBarScrollHold + 36*menuBarScrollStep, menuBarScrollGap + full[:16]},
		{menuBarScrollHold + 38*menuBarScrollStep, full[:18]},
	} {
		if got := menuBarLine(full, tc.at); got != tc.want {
			t.Errorf("at %v: %q, want %q", tc.at, got, tc.want)
		}
	}
	chinese := strings.Repeat("歌词滚动🎵", 10)
	seen := make(map[string]bool)
	for step := range 100 {
		got := menuBarLine(chinese, time.Duration(step)*menuBarScrollStep)
		if !utf8.ValidString(got) || utf8.RuneCountInString(got) != menuBarCharacters {
			t.Fatalf("broken or unbounded title: %q", got)
		}
		seen[got] = true
	}
	if len(seen) < 5 {
		t.Fatal("long Chinese lyrics stayed truncated instead of scrolling")
	}
}

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
	first, tip := a.menuBarText()
	if first != menuBarLine(full, 0) || !strings.Contains(tip, full) {
		t.Fatal("first subtitle or full tooltip was lost")
	}
	a.pl.Hold(3 * time.Second)
	moved, _ := a.menuBarText()
	if moved == first {
		t.Fatal("long subtitle did not scroll")
	}
	// The same playhead produces the same output when playback is paused.
	frozen, _ := a.menuBarText()
	if frozen != moved {
		t.Fatal("marquee depends on a wall clock")
	}
	a.pl.Hold(time.Second)
	back, _ := a.menuBarText()
	if back != first {
		t.Fatal("seek backwards did not restore the corresponding lyric window")
	}
	a.pl.Hold(12 * time.Second)
	next, _ := a.menuBarText()
	if next != first {
		t.Fatal("identical next subtitle did not start from its beginning")
	}
	a.pl.Hold(11 * time.Second)
	gap, _ := a.menuBarText()
	if gap != "First Track" {
		t.Fatalf("subtitle gap retained an expired line: %q", gap)
	}
	a.pl.Cue([]music.Track{song("2", "Second Track", "Another Artist")})
	a.pl.SetLyrics(nil)
	title, tip := a.menuBarText()
	if title != "Second Track" || strings.Contains(tip, full) {
		t.Fatal("track switch retained stale lyrics")
	}
	a.settings.MenuBarLyrics = false
	if title, _ = a.menuBarText(); title != "" {
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
	before, _ := a.menuBarText()
	if a.pl.Playing() || before != menuBarLine(full, 2*time.Second) {
		t.Fatal("paused lyrics did not follow the audio position")
	}
	time.Sleep(30 * time.Millisecond)
	after, _ := a.menuBarText()
	if after != before {
		t.Fatal("paused lyrics kept scrolling")
	}
	a.pl.Seek(time.Second)
	back, _ := a.menuBarText()
	if back != menuBarLine(full, 0) || back == before {
		t.Fatal("paused seek did not reset the lyric window")
	}
}
