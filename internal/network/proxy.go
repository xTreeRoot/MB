// Package network applies the listener's domain allowlist to the system proxy.
package network

import (
	"fmt"
	"net/http"
	"net/url"
	"regexp"
	"slices"
	"strings"
	"sync/atomic"

	"github.com/egoist/mygo/netproxy"
)

var domain = regexp.MustCompile(`^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$`)

type hostList struct{ hosts []string }

// Policy can be changed while requests are in flight. Only subsequent requests
// use the new list; loopback requests always stay on this computer.
type Policy struct{ allow atomic.Pointer[hostList] }

var Default Policy

// ParseHosts accepts one domain per line, with *.domain for subdomains.
// A global wildcard, URL, port or credentials are not domain allowlist entries.
func ParseHosts(input string) ([]string, error) {
	hosts := make([]string, 0)
	for _, value := range strings.Fields(input) {
		host := strings.TrimSuffix(strings.ToLower(value), ".")
		bare := strings.TrimPrefix(host, "*.")
		if len(bare) > 253 || !domain.MatchString(bare) {
			return nil, fmt.Errorf("invalid proxy domain: %s", value)
		}
		if !slices.Contains(hosts, host) {
			hosts = append(hosts, host)
		}
	}
	return hosts, nil
}

// SetHosts replaces the allowlist after validating it. An empty list is direct.
func (p *Policy) SetHosts(hosts []string) error {
	clean, err := ParseHosts(strings.Join(hosts, "\n"))
	if err != nil {
		return err
	}
	p.allow.Store(&hostList{hosts: clean})
	return nil
}

// Proxy asks the system only for an allowlisted destination. Other requests
// stay direct even when a process-wide proxy environment variable is present.
func (p *Policy) Proxy(req *http.Request) (*url.URL, error) {
	return p.proxy(req, netproxy.Proxy)
}

func (p *Policy) proxy(req *http.Request, system func(*http.Request) (*url.URL, error)) (*url.URL, error) {
	list := p.allow.Load()
	if list == nil {
		return nil, nil
	}
	host := strings.TrimSuffix(strings.ToLower(req.URL.Hostname()), ".")
	for _, entry := range list.hosts {
		matches := host == entry
		if strings.HasPrefix(entry, "*.") {
			matches = strings.HasSuffix(host, entry[1:])
		}
		if matches {
			return system(req)
		}
	}
	return nil, nil
}

// Transport preserves Go's connection pooling and checks the allowlist again
// on each request, including redirects and requests to media hosts.
func Transport() *http.Transport {
	transport := http.DefaultTransport.(*http.Transport).Clone()
	transport.Proxy = Default.Proxy
	return transport
}
