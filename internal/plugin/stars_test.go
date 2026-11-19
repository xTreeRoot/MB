package plugin

import (
	"os"
	"slices"
	"strings"
	"testing"
)

func TestStarsRepositoriesIgnoresOtherPageLinksAndHost(t *testing.T) {
	markup := `<h2 class="h3"><a href="/someone/outside">Outside</a></h2>
<div id="user-list-repositories">
  <h2 class="other h3"><a href="/xTreeRoot/mygo-music-soda"><span>owner / </span>soda</a></h2>
  <h2 class="h3"><a href="/xtreeroot/MYGO-MUSIC-SODA">Duplicate</a></h2>
  <h2 class="h3"><a href="/XTreeroot/mb">Host app</a></h2>
  <h2 class="h3"><a href="/XTReeRoot/MB-PLUGIN-TEMPLATE">开发模板</a></h2>
  <h2 class="h3"><a href="/other/mb-plugin-template">Same name, different owner</a></h2>
  <h2 class="h3"><a href="/xTreeRoot/mb-plugin-template-extra">Distinct plugin</a></h2>
  <p><a href="/owner/not-a-heading">Mention</a></p>
  <h2 class="h3"><a href="//other.test/foo">External</a></h2>
  <h2 class="h3"><a href="/xTreeRoot/mygo-ximalaya">Ximalaya</a></h2>
</div><h2 class="h3"><a href="/xTreeRoot/other">Other</a></h2>`
	want := []string{"xTreeRoot/mygo-music-soda", "other/mb-plugin-template", "xTreeRoot/mb-plugin-template-extra", "xTreeRoot/mygo-ximalaya"}
	if page := os.Getenv("MB_STARS_HTML_FIXTURE"); page != "" {
		bytes, err := os.ReadFile(page)
		if err != nil {
			t.Fatal(err)
		}
		markup = string(bytes)
		want = []string{"xTreeRoot/mygo-music-soda", "xTreeRoot/mygo-ximalaya", "xTreeRoot/mygo-music-youtube-music", "xTreeRoot/mygo-music-netease"}
	}
	repos, err := parseStarsRepositories(strings.NewReader(markup))
	if err != nil || !slices.Equal(repos, want) {
		t.Fatalf("list parsed as %v, err %v", repos, err)
	}
	if _, err := parseStarsRepositories(strings.NewReader(`<h1>Sign in</h1>`)); err == nil {
		t.Fatal("a non-list page reported success")
	}
}

func TestStarsURLStaysOnPublicGitHubLists(t *testing.T) {
	address, err := starsListURL(" https://github.com/stars/xTreeRoot/lists/fork-%E5%A4%9A%E5%B9%B3%E5%8F%B0/?tab=ignored#details ")
	if err != nil || address.String() != "https://github.com/stars/xTreeRoot/lists/fork-%E5%A4%9A%E5%B9%B3%E5%8F%B0" {
		t.Fatalf("address %v, error %v", address, err)
	}
	for _, input := range []string{"https://github.com@other.test/stars/a/lists/b", "https://github.com/a/b", "http://github.com/stars/a/lists/b", "https://github.com/stars/a/lists/%2E%2E"} {
		if _, err := starsListURL(input); err == nil {
			t.Fatalf("accepted %q", input)
		}
	}
}
