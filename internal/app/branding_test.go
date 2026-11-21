package app

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/egoist/mygo"
)

func TestBrandRenamePreservesExistingData(t *testing.T) {
	for _, suffix := range []string{"", " Dev"} {
		t.Run("山海"+suffix, func(t *testing.T) {
			dataRoot, cacheRoot := t.TempDir(), t.TempDir()
			legacy := filepath.Join(dataRoot, "MB"+suffix)
			pluginPath := filepath.Join(legacy, "plugin-data", "example", "storage.json")
			if err := os.MkdirAll(filepath.Dir(pluginPath), 0o755); err != nil {
				t.Fatal(err)
			}
			original := []byte(`{"enabled":true,"account":"preserved-test-account"}`)
			if err := os.WriteFile(pluginPath, original, 0o600); err != nil {
				t.Fatal(err)
			}
			application := new(mygo.Application)
			application.SetName("山海" + suffix)
			if err := useLegacyDesktopStorage(application, dataRoot, cacheRoot); err != nil {
				t.Fatal(err)
			}
			data, err := application.Path(mygo.PathUserData)
			if err != nil {
				t.Fatal(err)
			}
			stored, err := os.ReadFile(filepath.Join(data, "plugin-data", "example", "storage.json"))
			if err != nil || string(stored) != string(original) {
				t.Fatalf("renaming lost existing plugin data: %q, %v", stored, err)
			}
			cache, err := application.Path(mygo.PathCache)
			if err != nil {
				t.Fatal(err)
			}
			if cache != filepath.Join(cacheRoot, "MB"+suffix) {
				t.Fatalf("legacy cache was not reused: %q", cache)
			}
			if _, err := os.Stat(cache); err != nil {
				t.Fatalf("cache directory is unavailable: %v", err)
			}
		})
	}
}

func TestBrandRenameReportsUnavailableStorage(t *testing.T) {
	blocked := filepath.Join(t.TempDir(), "not-a-directory")
	if err := os.WriteFile(blocked, []byte("existing file"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := useLegacyDesktopStorage(new(mygo.Application), blocked, t.TempDir()); err == nil {
		t.Fatal("unavailable storage must not silently open an empty library")
	}
}
