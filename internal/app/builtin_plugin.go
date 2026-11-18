package app

import (
	"embed"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"slices"

	"mygo-music/internal/plugin"
)

// bundledPlatforms embeds independent source strategies; each directory declares its own manifest.
//
//go:embed builtin/bilibili/plugin.json builtin/bilibili/index.js builtin/bilibili/locales/zh-CN.json
var bundledPlatforms embed.FS

// loadBundledPlatform installs one platform's assets and loads the common Source contract.
func loadBundledPlatform(id, directory, dataDirectory string) (*plugin.Plugin, error) {
	if err := os.MkdirAll(directory, 0o700); err != nil {
		return nil, err
	}
	source, err := fs.Sub(bundledPlatforms, "builtin/"+id)
	if err != nil {
		return nil, err
	}
	err = fs.WalkDir(source, ".", func(name string, entry fs.DirEntry, walkErr error) error {
		if walkErr != nil {
			return walkErr
		}
		if entry.IsDir() {
			return nil
		}
		contents, err := fs.ReadFile(source, name)
		if err != nil {
			return fmt.Errorf("read bundled %s %s: %w", id, name, err)
		}
		target := filepath.Join(directory, filepath.FromSlash(name))
		if err := os.MkdirAll(filepath.Dir(target), 0o700); err != nil {
			return err
		}
		if err := os.WriteFile(target, contents, 0o600); err != nil {
			return fmt.Errorf("install bundled %s %s: %w", id, name, err)
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	return plugin.Load(directory, dataDirectory)
}

// loadBundledPlugins registers independent platform directories without platform switches in the player.
func loadBundledPlugins(existing []*plugin.Plugin, directory, dataDirectory string) ([]*plugin.Plugin, error) {
	entries, err := fs.ReadDir(bundledPlatforms, "builtin")
	if err != nil {
		return nil, err
	}
	var sources []*plugin.Plugin
	for _, entry := range entries {
		if !entry.IsDir() || slices.ContainsFunc(existing, func(p *plugin.Plugin) bool { return p.Info().ID == entry.Name() }) {
			continue
		}
		source, err := loadBundledPlatform(entry.Name(), filepath.Join(directory, entry.Name()), dataDirectory)
		if err != nil {
			return nil, err
		}
		sources = append(sources, source)
	}
	return sources, nil
}
