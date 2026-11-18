package plugin

import (
	"os"
	"path/filepath"
	"testing"
	"testing/fstest"
)

// Bundled assets use their manifest IDs, the same host and the same persisted
// data as externally installed plugins, including after a bundled upgrade.
func TestBundledPluginsRefreshAssetsAndKeepData(t *testing.T) {
	assets := fstest.MapFS{
		"catalog/plugin.json":        {Data: []byte(`{"id":"catalog","main":"index.js"}`)},
		"catalog/index.js":           {Data: []byte(`export function suggest() { return [host.storage.get("kept") || "first"]; }`)},
		"catalog/locales/zh-CN.json": {Data: []byte(`{"Catalog":"目录"}`)},
		"radio/plugin.json":          {Data: []byte(`{"id":"station","main":"index.js"}`)},
		"radio/index.js":             {Data: []byte(`export function suggest() { return ["radio"]; }`)},
	}
	directory, data := t.TempDir(), t.TempDir()
	sources, err := LoadBundled(assets, nil, directory, data)
	if err != nil {
		t.Fatal(err)
	}
	if len(sources) != 2 || sources[0].Info().ID != "catalog" || sources[1].Info().ID != "station" {
		t.Fatalf("did not discover plugins by manifest ID: %v", sources)
	}
	for _, source := range sources {
		source.Close()
	}
	if _, err := os.Stat(filepath.Join(directory, "station", "index.js")); err != nil {
		t.Fatal(err)
	}
	locale := filepath.Join(directory, "catalog", "locales", "zh-CN.json")
	if b, err := os.ReadFile(locale); err != nil || string(b) != `{"Catalog":"目录"}` {
		t.Fatalf("nested asset: %s, %v", b, err)
	}
	if err := os.MkdirAll(filepath.Join(data, "catalog"), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(data, "catalog", "storage.json"), []byte(`{"kept":"session-survived"}`), 0o600); err != nil {
		t.Fatal(err)
	}
	assets["catalog/index.js"].Data = []byte(`export function suggest() { return [host.storage.get("kept"), "updated"]; }`)
	sources, err = LoadBundled(assets, nil, directory, data)
	if err != nil {
		t.Fatal(err)
	}
	for _, source := range sources {
		defer source.Close()
	}
	got, err := sources[0].Suggest("")
	if err != nil || len(got) != 2 || got[0] != "session-survived" || got[1] != "updated" {
		t.Fatalf("upgrade lost data or loaded stale code: %v, %v", got, err)
	}
}

// An installed plugin overrides a bundled one by ID, even if their directory
// names differ; its code and data must not be replaced with the shipped copy.
func TestBundledPluginInstalledOverride(t *testing.T) {
	existing := load(t, `{"id":"custom"}`, `export function suggest() { return ["installed"]; }`)
	assets := fstest.MapFS{
		"shipped/plugin.json": {Data: []byte(`{"id":"custom"}`)},
		"shipped/index.js":    {Data: []byte(`export function suggest() { return ["bundled"]; }`)},
	}
	dir := t.TempDir()
	sources, err := LoadBundled(assets, []*Plugin{existing}, dir, t.TempDir())
	if err != nil || len(sources) != 0 {
		t.Fatalf("loaded an overridden bundled plugin: %v, %v", sources, err)
	}
	entries, err := os.ReadDir(dir)
	if err != nil || len(entries) != 0 {
		t.Fatalf("wrote overridden assets: %v, %v", entries, err)
	}
	got, err := existing.Suggest("")
	if err != nil || len(got) != 1 || got[0] != "installed" {
		t.Fatalf("changed installed source: %v, %v", got, err)
	}
}

// Reject IDs which could overwrite another folder, and duplicate bundled IDs
// which would share one runtime directory and storage namespace.
func TestBundledPluginInvalidIDs(t *testing.T) {
	for _, manifest := range []string{`{"id":"../outside"}`, `{"id":""}`, `{`} {
		assets := fstest.MapFS{"one/plugin.json": {Data: []byte(manifest)}}
		if _, err := LoadBundled(assets, nil, t.TempDir(), t.TempDir()); err == nil {
			t.Fatalf("loaded invalid manifest: %s", manifest)
		}
	}
	assets := fstest.MapFS{
		"one/plugin.json": {Data: []byte(`{"id":"same"}`)},
		"one/index.js":    {Data: []byte(`export function suggest() { return []; }`)},
		"two/plugin.json": {Data: []byte(`{"id":"same"}`)},
		"two/index.js":    {Data: []byte(`export function suggest() { return []; }`)},
	}
	if _, err := LoadBundled(assets, nil, t.TempDir(), t.TempDir()); err == nil {
		t.Fatal("loaded duplicate bundled IDs")
	}
}
