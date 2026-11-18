package app

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"

	"github.com/egoist/mygo/ui"
	"mygo-music/internal/i18n"
	"mygo-music/internal/player"
)

// The old switch migrates without re-enabling disabled normalization.
func TestNormalizationSettingMigration(t *testing.T) {
	for _, tc := range []struct {
		json string
		want player.NormalizationMode
	}{
		{`true`, player.NormalizationStandard},
		{`false`, player.NormalizationOff},
		{`"standard"`, player.NormalizationStandard},
		{`"dynamic"`, player.NormalizationDynamic},
		{`"off"`, player.NormalizationOff},
	} {
		s := defaultSettings()
		if err := json.Unmarshal([]byte(`{"normalize":`+tc.json+`}`), &s); err != nil {
			t.Fatal(err)
		}
		if s.Normalize != tc.want {
			t.Fatalf("%s: migrated to %s", tc.json, s.Normalize)
		}
		s.path = filepath.Join(t.TempDir(), "settings.json")
		if err := s.save(); err != nil {
			t.Fatal(err)
		}
		b, err := json.Marshal(s)
		if err != nil {
			t.Fatal(err)
		}
		var reloaded settings
		if err := json.Unmarshal(b, &reloaded); err != nil {
			t.Fatal(err)
		}
		if reloaded.Normalize != tc.want {
			t.Fatalf("%s did not survive a reload", tc.want)
		}
	}
}

// Selecting any loudness mode updates playback and persists the choice.
func TestNormalizationSettingsChoice(t *testing.T) {
	a := newTestApp()
	a.settings.path = filepath.Join(t.TempDir(), "settings.json")
	a.settingsPage = "playback"
	a.pl.Normalize = func() player.NormalizationMode { return a.settings.Normalize }
	tt := ui.NewTester(a.settingsView, 780, 640)
	settled(tt)
	for _, tc := range []struct {
		label string
		mode  player.NormalizationMode
	}{
		{"Dynamic", player.NormalizationDynamic}, {"Off", player.NormalizationOff}, {"Standard", player.NormalizationStandard},
	} {
		clickSettingRole(t, tt, ui.RolePopUpButton, "Even Out Loudness")
		if err := tt.Click(tc.label); err != nil {
			t.Fatal(err)
		}
		settled(tt)
		if a.settings.Normalize != tc.mode || a.pl.Normalize() != tc.mode {
			t.Fatalf("%s was not applied", tc.label)
		}
		var saved settings
		b, err := os.ReadFile(a.settings.path)
		if err != nil {
			t.Fatal(err)
		}
		if err := json.Unmarshal(b, &saved); err != nil {
			t.Fatal(err)
		}
		if saved.Normalize != tc.mode {
			t.Fatalf("%s was not persisted", tc.label)
		}
	}
}

// With MM_FRAMES set, saves the loudness selector and its menu in Chinese.
func TestNormalizationSettingsFrames(t *testing.T) {
	dir := framesDir(t)
	before := i18n.Language()
	i18n.SetLanguage(i18n.Chinese)
	defer i18n.SetLanguage(before)
	for _, dark := range []bool{false, true} {
		a := newTestApp()
		a.settingsPage = "playback"
		tt := ui.NewTester(a.settingsView, 780, 640)
		tt.SetDark(dark)
		settled(tt)
		name := "normalization-light"
		if dark {
			name = "normalization-dark"
		}
		saveFrame(t, dir, name+".png", tt.Image())
		clickSettingRole(t, tt, ui.RolePopUpButton, "音量均衡")
		settled(tt)
		saveFrame(t, dir, name+"-menu.png", tt.Image())
	}
}
