package app

import (
	"slices"
	"strconv"

	"github.com/egoist/mygo/ui"
	"mygo-music/internal/music"
)

// Sidebar ordering is a local preference; it never edits a platform's favorite folders.
type sidebarPlaylistKey struct {
	mine bool
	id   string
}

type sidebarPlaylistDrag struct {
	library *library
	account string
	mine    bool
	id      string
}

func sidebarAccount(lib *library) string {
	if lib.account != nil {
		return lib.account.ID
	}
	return ""
}

func sidebarOrderKey(lib *library, mine bool) string {
	return lib.id + "\x00" + sidebarAccount(lib) + "\x00" + strconv.FormatBool(mine)
}

// orderedSidebarPlaylists retains saved positions across refreshes and app restarts.
// Missing folders disappear naturally and newly created folders follow the saved ones.
func (a *app) orderedSidebarPlaylists(lib *library, lists []music.Playlist, mine bool) []music.Playlist {
	order := a.settings.PlaylistOrders[sidebarOrderKey(lib, mine)]
	if len(order) == 0 {
		return lists
	}
	positions := make(map[string]int, len(order))
	for i, id := range order {
		positions[id] = i
	}
	rank := func(id string) int {
		if i, ok := positions[id]; ok {
			return i
		}
		return len(order)
	}
	result := slices.Clone(lists)
	slices.SortStableFunc(result, func(left, right music.Playlist) int { return rank(left.ID) - rank(right.ID) })
	return result
}

func (a *app) bindSidebarPlaylistDrag(row ui.Element, lib *library, id string, mine bool) {
	row.Drag(sidebarPlaylistDrag{lib, sidebarAccount(lib), mine, id})
	if dragged, ok := ui.DragOver[sidebarPlaylistDrag](row); ok && sidebarDragMatches(dragged, lib, mine) && dragged.id != id {
		row.Background(a.pal.accent.Alpha(0.18))
	}
	if dragged, ok := ui.Drop[sidebarPlaylistDrag](row); ok {
		a.moveSidebarPlaylist(dragged, lib, id, mine)
	}
}

func sidebarDragMatches(dragged sidebarPlaylistDrag, lib *library, mine bool) bool {
	return dragged.library == lib && dragged.account == sidebarAccount(lib) && dragged.mine == mine
}

// moveSidebarPlaylist inserts the dragged folder at its target without navigating or touching playback.
func (a *app) moveSidebarPlaylist(dragged sidebarPlaylistDrag, lib *library, target string, mine bool) {
	if !sidebarDragMatches(dragged, lib, mine) || dragged.id == target {
		return
	}
	lists := lib.saved
	if mine {
		lists = lib.mine
	}
	lists = a.orderedSidebarPlaylists(lib, lists, mine)
	from := slices.IndexFunc(lists, func(pl music.Playlist) bool { return pl.ID == dragged.id })
	to := slices.IndexFunc(lists, func(pl music.Playlist) bool { return pl.ID == target })
	if from < 0 || to < 0 {
		return
	}
	result := slices.Clone(lists)
	moved := result[from]
	result = slices.Delete(result, from, from+1)
	result = slices.Insert(result, to, moved)
	if mine {
		lib.mine = result
	} else {
		lib.saved = result
	}
	order := make([]string, len(result))
	for i, pl := range result {
		order[i] = pl.ID
	}
	if a.settings.PlaylistOrders == nil {
		a.settings.PlaylistOrders = make(map[string][]string)
	}
	a.settings.PlaylistOrders[sidebarOrderKey(lib, mine)] = order
	a.changed()
}
