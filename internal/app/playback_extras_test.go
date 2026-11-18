package app

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
	"time"

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
