package player

import (
	"testing"
	"time"

	"mygo-music/internal/music"
)

// UI effects can follow explicit selections without confusing restore, retry,
// pause, seek or automatic queue advancement with a new content click.
func TestPickedOnlyForContentSelection(t *testing.T) {
	p := New(func(func()) {})
	q := []music.Track{{ID: "a", Source: "test"}, {ID: "b", Source: "test"}}
	var picks []string
	p.Picked = func() { picks = append(picks, p.Current().ID) }
	p.PlayQueue(q, 0)
	p.PlayTrack(q, 1)
	p.PlayShuffled(q)
	p.Jump(0)
	if len(picks) != 4 || picks[0] != "a" || picks[1] != "b" || picks[3] != "a" {
		t.Fatalf("explicit selections: %v", picks)
	}
	p.Next()
	p.Prev()
	p.TogglePlay()
	p.Seek(time.Second)
	p.Restore(Session{Queue: q, Order: []int{0, 1}, At: 0, Position: 9000})
	p.TogglePlay() // resumes/restreams the restored item
	p.PlayQueue(nil, 0)
	p.Jump(99)
	if len(picks) != 4 {
		t.Fatalf("transport or restore masqueraded as selection: %v", picks)
	}
}
