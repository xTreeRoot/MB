package app

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"slices"
	"testing"

	"github.com/egoist/mygo"
	"github.com/egoist/mygo/ui"
	"mygo-music/internal/plugin"
)

func TestStarsImportReviewsBeforeInstallingAndKeepsPluginData(t *testing.T) {
	mygo.App.SetPath(mygo.PathUserData, t.TempDir())
	t.Cleanup(func() { mygo.App.SetPath(mygo.PathUserData, "") })
	directory := t.TempDir()
	write := func(path, contents string) {
		t.Helper()
		if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(path, []byte(contents), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	write(filepath.Join(directory, "plugin.json"), `{"id":"stars-fixture","name":"Stars Fixture","permissions":{"network":["fixture.test"]}}`)
	write(filepath.Join(directory, "index.js"), "// new code")
	old := filepath.Join(pluginsDir(), "stars-fixture")
	write(filepath.Join(old, ".origin.json"), `{"repo":"owner/fixture","version":"old"}`)
	write(filepath.Join(old, "index.js"), "// old code")
	data := userDataDir("plugin-data/stars-fixture/account-state")
	write(data, "synthetic account state")
	wasList, wasFetch := fetchStarsRepositories, fetchPlugin
	t.Cleanup(func() { fetchStarsRepositories, fetchPlugin = wasList, wasFetch })
	fetchStarsRepositories = func(_ context.Context, _ string) ([]string, error) {
		return []string{"owner/offline", "Owner/Fixture"}, nil
	}
	fetchPlugin = func(repo, target string) (*plugin.Pending, error) {
		if repo == "owner/offline" {
			return nil, errors.New("fixture offline")
		}
		pending, err := plugin.Open(directory, target)
		if err == nil {
			pending.Repo = repo
		}
		return pending, err
	}
	a := newTestApp()
	ch := posts(a)
	a.settingsPage = "plugins"
	view := ui.NewTester(a.settingsView, 780, 800)
	click := func(label string) {
		t.Helper()
		if err := view.Click(label); err != nil {
			t.Fatalf("%v; shows %q", err, view.Texts())
		}
		view.Frame()
	}
	view.Frame()
	click("Import / Replace Plugins")
	click("GitHub Stars list URL")
	view.Type("https://github.com/stars/owner/lists/fixture")
	view.Frame()
	click("Read List")
	runPost(t, ch)
	view.Frame()
	if bytes, _ := os.ReadFile(filepath.Join(old, "index.js")); string(bytes) != "// old code" {
		t.Fatal("preview changed installed code")
	}
	if _, ok := view.Find("fixture offline"); !ok {
		t.Fatal("partial failure not shown")
	}
	if path := os.Getenv("MM_FRAMES"); path != "" {
		saveFrame(t, path, "plugins-stars-review.png", view.Image())
	}
	click("Confirm Import / Replace")
	runPost(t, ch)
	view.Frame()
	if bytes, _ := os.ReadFile(filepath.Join(old, "index.js")); string(bytes) != "// new code" {
		t.Fatalf("replacement not installed: %q", bytes)
	}
	if bytes, _ := os.ReadFile(data); string(bytes) != "synthetic account state" {
		t.Fatalf("account state changed: %q", bytes)
	}
	if !slices.ContainsFunc(a.allLibs, func(lib *library) bool { return lib.id == "stars-fixture" }) {
		t.Fatal("library not refreshed after install")
	}
}
