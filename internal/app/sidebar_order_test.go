package app

import (
	"encoding/json"
	"os"
	"path/filepath"
	"slices"
	"testing"

	"github.com/egoist/mygo/ui"
	"mygo-music/internal/music"
)

func dragSidebar(t *testing.T, tt *ui.Tester, from, to string, cancel bool) {
	t.Helper()
	src, ok := tt.Find(from)
	if !ok {
		t.Fatalf("missing drag source %s", from)
	}
	dst, ok := tt.Find(to)
	if !ok {
		t.Fatalf("missing drop target %s", to)
	}
	x, y := src.X+12, src.Y+src.H/2
	tt.Press(x, y)
	tt.Move(x+12, y+8)
	tt.Move(dst.X+12, dst.Y+dst.H/2)
	if cancel {
		tt.Key(0, ui.KeyEscape)
	}
	tt.Release(dst.X+12, dst.Y+dst.H/2)
	tt.Frame()
}

// Pointer drags reorder both ways without opening a playlist or changing the queue.
func TestSidebarPlaylistDrag(t *testing.T) {
	a := editTestApp()
	a.settings.path = filepath.Join(t.TempDir(), "settings.json")
	a.pl.Cue(a.playlists["10"].Tracks)
	before := a.route
	tt := ui.NewTester(a.view, 1200, 800)
	tt.Frame()
	dragSidebar(t, tt, "通勤", "睡前", false)
	if a.mine[0].ID != "11" || a.mine[1].ID != "10" {
		t.Fatalf("downward drag: %+v", a.mine)
	}
	first, _ := tt.Find("睡前")
	second, _ := tt.Find("通勤")
	if first.Y >= second.Y {
		t.Fatal("sidebar did not render the new order")
	}
	data, err := os.ReadFile(a.settings.path)
	if err != nil {
		t.Fatal(err)
	}
	restored := editTestApp()
	if err = json.Unmarshal(data, &restored.settings); err != nil {
		t.Fatal(err)
	}
	// Refreshed platform results retain the preference; new IDs follow it, removed IDs disappear.
	fresh := append(slices.Clone(restored.mine), music.Playlist{ID: "12", Name: "新歌"})
	ordered := restored.orderedSidebarPlaylists(restored.library, fresh, true)
	if ordered[0].ID != "11" || ordered[1].ID != "10" || ordered[2].ID != "12" {
		t.Fatalf("restored order: %+v", ordered)
	}
	ordered = restored.orderedSidebarPlaylists(restored.library, fresh[1:], true)
	if len(ordered) != 2 || ordered[0].ID != "11" || ordered[1].ID != "12" {
		t.Fatal("deleted playlist remained in the list")
	}
	restored.account = &music.Account{ID: "another"}
	if restored.orderedSidebarPlaylists(restored.library, fresh, true)[0].ID != "10" {
		t.Fatal("another account inherited ordering")
	}
	restored.account = a.account
	restored.library.id = "another-platform"
	if restored.orderedSidebarPlaylists(restored.library, fresh, true)[0].ID != "10" {
		t.Fatal("another platform inherited ordering")
	}
	dragSidebar(t, tt, "通勤", "睡前", false)
	if a.mine[0].ID != "10" {
		t.Fatal("upward drag failed")
	}
	dragSidebar(t, tt, "通勤", "睡前", true)
	dragSidebar(t, tt, "通勤", "飙升榜", false)
	if a.mine[0].ID != "10" || len(a.saved) != 1 || a.saved[0].ID != "20" {
		t.Fatal("cancelled or cross-section drag moved a playlist")
	}
	if a.route != before || len(a.pl.Queue()) != 2 || a.pl.Queue()[0].ID != "1" {
		t.Fatal("drag changed navigation or playback")
	}
}
