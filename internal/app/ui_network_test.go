package app

import (
	"encoding/json"
	"os"
	"path/filepath"
	"slices"
	"testing"

	"github.com/egoist/mygo/ui"
	"mygo-music/internal/network"
)

// Saving changes the active policy and persists it; invalid text keeps the old
// policy, while an explicitly empty list stays empty across settings reloads.
func TestNetworkAllowlistSettings(t *testing.T) {
	a := newTestApp()
	a.settings.path = filepath.Join(t.TempDir(), "settings.json")
	a.settingsPage = "network"
	t.Cleanup(func() { network.Default.SetHosts(nil) })
	tt := ui.NewTester(a.settingsView, 780, 640)
	tt.Frame()
	if dir := os.Getenv("MM_FRAMES"); dir != "" {
		saveFrame(t, dir, "network-allowlist-default.png", tt.Image())
	}
	edit := func(value string) {
		t.Helper()
		if err := tt.Click(T("Proxy Domains")); err != nil {
			t.Fatal(err)
		}
		tt.Key(ui.Cmd, ui.KeyA)
		tt.Type(value)
	}
	save := func() {
		t.Helper()
		if err := tt.Click(T("Save")); err != nil {
			t.Fatal(err)
		}
		tt.Frame()
	}
	edit("music.youtube.com\n*.ytimg.com")
	save()
	if !slices.Equal(a.settings.SystemProxyHosts, []string{"music.youtube.com", "*.ytimg.com"}) {
		t.Fatalf("saved hosts %v", a.settings.SystemProxyHosts)
	}
	edit("*")
	save()
	if a.proxyErr == "" || len(a.settings.SystemProxyHosts) != 2 {
		t.Fatal("invalid wildcard replaced the saved allowlist")
	}
	edit("")
	save()
	data, err := os.ReadFile(a.settings.path)
	if err != nil {
		t.Fatal(err)
	}
	reloaded := defaultSettings()
	if err = json.Unmarshal(data, &reloaded); err != nil {
		t.Fatal(err)
	}
	if len(reloaded.SystemProxyHosts) != 0 {
		t.Fatal("empty list restored defaults")
	}
	if dir := os.Getenv("MM_FRAMES"); dir != "" {
		saveFrame(t, dir, "network-allowlist.png", tt.Image())
	}
}
