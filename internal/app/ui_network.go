package app

import (
	"strings"

	"github.com/egoist/mygo/ui"

	"mygo-music/internal/network"
)

// networkSettings edits the domain allowlist without changing the OS proxy.
func (a *app) networkSettings(c *ui.Context, p palette, block settingsBlock) {
	block(T("System Proxy Allowlist"), func() {
		if a.proxyDraft == nil {
			value := strings.Join(a.settings.SystemProxyHosts, "\n")
			a.proxyDraft = &value
		}
		ui.Column(c).Padding(16).Gap(12).Children(func() {
			ui.Text(c, T("System Proxy Allowlist")).FontSize(13.5).FontWeight(500)
			ui.Text(c, T("Only these domains use the system proxy. All other requests connect directly. One domain per line; *.example.com includes subdomains.")).FontSize(12).TextColor(p.muted).LineHeight(1.4)
			ui.TextArea(c.Key("proxy-hosts"), a.proxyDraft).Label(T("Proxy Domains")).Height(160)
			if a.proxyErr != "" {
				ui.Text(c, a.proxyErr).FontSize(12).TextColor(p.accent)
			}
			ui.Row(c).Justify(ui.End).Children(func() {
				ui.PrimaryButton(c.Key("save-proxy"), T("Save")).Disabled(*a.proxyDraft == strings.Join(a.settings.SystemProxyHosts, "\n")).OnClick(func() { a.saveProxyHosts() })
			})
		})
	})
}

func (a *app) saveProxyHosts() {
	hosts, err := network.ParseHosts(*a.proxyDraft)
	if err != nil {
		a.proxyErr = T("Enter domains such as music.youtube.com or *.youtube.com; URLs and a global wildcard are not allowed.")
		return
	}
	// Validated above; publish before saving so new requests use it immediately.
	network.Default.SetHosts(hosts)
	a.settings.SystemProxyHosts = hosts
	*a.proxyDraft = strings.Join(hosts, "\n")
	a.proxyErr = ""
	a.changed()
}
