package plugin

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"

	"golang.org/x/net/html"
)

func starsListURL(input string) (*url.URL, error) {
	address, err := url.Parse(strings.TrimSpace(input))
	if err == nil && address.Scheme == "https" && strings.EqualFold(address.Host, "github.com") && address.User == nil {
		parts := strings.Split(strings.TrimSuffix(address.Path, "/"), "/")
		if len(parts) == 5 && parts[0] == "" && parts[1] == "stars" && parts[3] == "lists" && parts[4] != "" &&
			parts[4] != "." && parts[4] != ".." && !strings.Contains(parts[4], "\\") && repoName.MatchString(parts[2]+"/list") && !strings.Contains(parts[2], "..") {
			return &url.URL{Scheme: "https", Host: "github.com", Path: strings.Join(parts, "/")}, nil
		}
	}
	return nil, errors.New("not a public GitHub Stars list URL")
}

func StarsRepositories(ctx context.Context, input string) ([]string, error) {
	address, err := starsListURL(input)
	if err != nil {
		return nil, err
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, address.String(), nil)
	if err != nil {
		return nil, err
	}
	request.Header.Set("Accept", "text/html")
	client := http.Client{Timeout: 25 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	response, err := client.Do(request)
	if err != nil {
		return nil, err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("GitHub Stars list: HTTP %d", response.StatusCode)
	}
	const maxPage = 4 << 20
	page, err := io.ReadAll(io.LimitReader(response.Body, maxPage+1))
	if err != nil {
		return nil, err
	}
	if len(page) > maxPage {
		return nil, errors.New("GitHub Stars list page is too large")
	}
	return parseStarsRepositories(bytes.NewReader(page))
}

func parseStarsRepositories(reader io.Reader) ([]string, error) {
	doc, err := html.Parse(reader)
	if err != nil {
		return nil, err
	}
	var list *html.Node
	for node := range doc.Descendants() {
		if node.Type == html.ElementNode && node.Data == "div" && htmlAttr(node, "id") == "user-list-repositories" {
			list = node
			break
		}
	}
	if list == nil {
		return nil, errors.New("GitHub Stars repository list not found")
	}
	var repos []string
	seen := map[string]bool{"xtreeroot/mb": true, "xtreeroot/mb-plugin-template": true}
	for node := range list.Descendants() {
		if node.Type != html.ElementNode || node.Data != "a" || node.Parent.Data != "h2" ||
			!strings.Contains(" "+strings.Join(strings.Fields(htmlAttr(node.Parent, "class")), " ")+" ", " h3 ") {
			continue
		}
		path := htmlAttr(node, "href")
		repo := strings.TrimPrefix(path, "/")
		if repo == path || !repoName.MatchString(repo) || strings.Contains(repo, "..") {
			continue
		}
		key := strings.ToLower(repo)
		if !seen[key] {
			seen[key] = true
			repos = append(repos, repo)
		}
	}
	return repos, nil
}

func htmlAttr(node *html.Node, key string) string {
	for _, attr := range node.Attr {
		if attr.Key == key {
			return attr.Val
		}
	}
	return ""
}
