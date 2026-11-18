package plugin

import "testing"

// An optional video export receives track/quality and returns in-memory headers.
func TestVideoCapability(t *testing.T) {
	p := load(t, `{"id":"mv"}`, `export function video({id, quality}) {
  return {url: "https://media.example/" + id + ".mp4?q=" + quality, headers: {Referer: "https://source.example/"}};
}`)
	if !p.Can("video") {
		t.Fatal("video capability was not discovered")
	}
	stream, err := p.Video("track", "high")
	if err != nil {
		t.Fatal(err)
	}
	if stream.URL != "https://media.example/track.mp4?q=high" || stream.Headers["Referer"] != "https://source.example/" {
		t.Fatalf("video response %+v", stream)
	}
	without := load(t, `{"id":"audio"}`, `export function stream() { return {url:"https://media.example/audio.mp3"}; }`)
	if without.Can("video") {
		t.Fatal("an audio-only plugin advertised video")
	}
	if _, err := without.Video("track", "high"); err == nil {
		t.Fatal("missing video export succeeded")
	}
}
