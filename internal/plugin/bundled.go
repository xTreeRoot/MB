package plugin

import (
	"encoding/json"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
)

// LoadBundled installs and compiles plugins from a filesystem with one plugin
// per directory. Explicitly installed plugins take precedence by manifest ID;
// each source keeps its data in dataDirectory/<id> across bundled upgrades.
func LoadBundled(assets fs.FS, existing []*Plugin, directory, dataDirectory string) ([]*Plugin, error) {
	entries, err := fs.ReadDir(assets, ".")
	if err != nil {
		return nil, err
	}
	installed := make(map[string]bool, len(existing))
	for _, p := range existing {
		installed[p.Info().ID] = true
	}
	var sources []*Plugin
	seen := map[string]bool{}
	for _, entry := range entries {
		if !entry.IsDir() {
			continue
		}
		source, err := fs.Sub(assets, entry.Name())
		if err != nil {
			return nil, err
		}
		b, err := fs.ReadFile(source, "plugin.json")
		if err != nil {
			return nil, fmt.Errorf("bundled %s: %w", entry.Name(), err)
		}
		var manifest Manifest
		if err := json.Unmarshal(b, &manifest); err != nil {
			return nil, fmt.Errorf("bundled %s: %w", entry.Name(), err)
		}
		if !pluginName.MatchString(manifest.ID) || seen[manifest.ID] {
			return nil, fmt.Errorf("bundled %s: invalid or duplicate plugin id %q", entry.Name(), manifest.ID)
		}
		seen[manifest.ID] = true
		if installed[manifest.ID] {
			continue
		}
		p, err := loadBundledPlugin(source, filepath.Join(directory, manifest.ID), dataDirectory)
		if err != nil {
			return nil, err
		}
		sources = append(sources, p)
	}
	return sources, nil
}

// loadBundledPlugin refreshes runtime assets without touching plugin data.
func loadBundledPlugin(source fs.FS, directory, dataDirectory string) (*Plugin, error) {
	if err := os.MkdirAll(directory, 0o700); err != nil {
		return nil, err
	}
	err := fs.WalkDir(source, ".", func(name string, entry fs.DirEntry, walkErr error) error {
		if walkErr != nil {
			return walkErr
		}
		if entry.IsDir() {
			return nil
		}
		contents, err := fs.ReadFile(source, name)
		if err != nil {
			return fmt.Errorf("read bundled %s: %w", name, err)
		}
		target := filepath.Join(directory, filepath.FromSlash(name))
		if err := os.MkdirAll(filepath.Dir(target), 0o700); err != nil {
			return err
		}
		if err := os.WriteFile(target, contents, 0o600); err != nil {
			return fmt.Errorf("install bundled %s: %w", name, err)
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	p, err := Load(directory, dataDirectory)
	if err == nil {
		p.bundled = true
	}
	return p, err
}
