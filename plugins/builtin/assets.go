// Package builtin contains the plugins shipped with MB. It only embeds plugin
// assets; loading and playback belong to the player and its plugin host.
package builtin

import "embed"

// Assets holds standard plugins, one manifest, entry point and locales per
// directory. Development metadata and tests do not ship as runtime assets.
//
//go:embed */plugin.json */index.js */locales/*.json
var Assets embed.FS
