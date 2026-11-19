package app

import (
	"os"
	"strings"
	"testing"

	"github.com/egoist/mygo/ui"
	"mygo-music/internal/edge"
)

func TestEdgeSettingsRequiresAUserAddressAndRejectsHTTP(t *testing.T) {
	a := newTestApp()
	a.edge.nodes = edge.NewNodes(t.TempDir())
	a.settingsPage = "edge"
	tester := ui.NewTester(a.settingsView, 780, 640)
	tester.Frame()
	if a.edge.address != "" || a.edge.password != "" {
		t.Fatal("node settings unexpectedly prefilled")
	}
	if dir := os.Getenv("MM_FRAMES"); dir != "" {
		saveFrame(t, dir, "desktop-node-settings.png", tester.Image())
	}
	if err := tester.Click(T("Server Domain or HTTPS Address")); err != nil {
		t.Fatal(err)
	}
	tester.Type("http://node.example")
	if err := tester.Click(T("Password")); err != nil {
		t.Fatal(err)
	}
	tester.Type("synthetic fixture")
	if err := tester.Click(T("Connect to Node") + " MB Server"); err != nil {
		t.Fatal(err)
	}
	if a.edge.busy || !strings.Contains(a.edge.message, "HTTPS") || a.edge.service != nil {
		t.Fatal("invalid HTTP node reached the connection flow")
	}
}
