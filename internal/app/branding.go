package app

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"github.com/egoist/mygo"
)

// preserveDesktopStorage keeps the pre-rename library, sessions and plugin data.
func preserveDesktopStorage(application *mygo.Application) error {
	dataRoot, err := os.UserConfigDir()
	if err != nil {
		return err
	}
	cacheRoot, err := os.UserCacheDir()
	if err != nil {
		return err
	}
	return useLegacyDesktopStorage(application, dataRoot, cacheRoot)
}

func useLegacyDesktopStorage(application *mygo.Application, dataRoot, cacheRoot string) error {
	suffix := ""
	if strings.HasSuffix(application.Name(), " Dev") {
		suffix = " Dev"
	}
	for _, directory := range []struct {
		kind mygo.PathName
		root string
	}{
		{mygo.PathUserData, dataRoot},
		{mygo.PathCache, cacheRoot},
	} {
		path := filepath.Join(directory.root, "MB"+suffix)
		if err := os.MkdirAll(path, 0o755); err != nil {
			return fmt.Errorf("open existing application storage: %w", err)
		}
		application.SetPath(directory.kind, path)
	}
	return nil
}
