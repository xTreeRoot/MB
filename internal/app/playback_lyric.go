package app

import (
	"mygo-music/internal/lyrics"
	"strings"
	"time"
)

// currentLyric follows seeks and honors subtitle end times in instrumental gaps.
func currentLyric(lines []lyrics.Line, pos time.Duration) string {
	i := lyrics.Index(lines, pos)
	if i < 0 || lines[i].End > 0 && pos >= lines[i].End {
		return ""
	}
	return strings.Join(strings.Fields(lines[i].Text), " ")
}
