package app

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
	"unicode/utf8"

	"mygo-music/internal/lyrics"
)

// Menu bar and MV lyrics follow the playhead backwards and clear subtitle gaps.
func TestCurrentLyricFollowsSeek(t *testing.T) {
	lines := []lyrics.Line{
		{At: time.Second, End: 3 * time.Second, Text: " first\nline "},
		{At: 5 * time.Second, Text: "second line"},
	}
	for _, tc := range []struct {
		at   time.Duration
		want string
	}{
		{0, ""}, {time.Second, "first line"}, {2 * time.Second, "first line"},
		{3 * time.Second, ""}, {6 * time.Second, "second line"}, {2 * time.Second, "first line"},
	} {
		if got := currentLyric(lines, tc.at); got != tc.want {
			t.Errorf("at %v: %q, want %q", tc.at, got, tc.want)
		}
	}
}

// The first marquee frame stays bounded without splitting a UTF-8 character.
func TestMenuBarLineIsBounded(t *testing.T) {
	text := strings.Repeat("中文🎵", 20)
	got := menuBarLine(text, 0)
	if !utf8.ValidString(got) || utf8.RuneCountInString(got) != 18 {
		t.Fatalf("invalid marquee lyric: %q", got)
	}
	if menuBarLine("short line", time.Hour) != "short line" {
		t.Fatal("short lyric was changed")
	}
}

// Disabling menu bar lyrics survives saving and reloading the settings.
func TestMenuBarLyricsSetting(t *testing.T) {
	s := defaultSettings()
	s.path = filepath.Join(t.TempDir(), "settings.json")
	s.MenuBarLyrics = false
	if err := s.save(); err != nil {
		t.Fatal(err)
	}
	b, err := os.ReadFile(s.path)
	if err != nil {
		t.Fatal(err)
	}
	reloaded := defaultSettings()
	if err := json.Unmarshal(b, &reloaded); err != nil {
		t.Fatal(err)
	}
	if reloaded.MenuBarLyrics {
		t.Fatal("lyrics were re-enabled after reloading")
	}
}
