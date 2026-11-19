package edge

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"net/http"
	"os"
	"path/filepath"
	"sync"
	"time"
)

type KeyChanged struct{ Key string }

func (*KeyChanged) Error() string {
	return "The node signing key changed. Confirm the new key before connecting."
}

type Node struct {
	Address string
	Sync    *Sync
	dir     string
}

func (node *Node) Catalog() *Catalog {
	node.Sync.mu.Lock()
	defer node.Sync.mu.Unlock()
	state := node.Sync.state
	if state.KeyMismatch || state.Grant.Token == "" || !validBase64(state.Key, 32) {
		return nil
	}
	return newCatalog(node.Sync.client, node.dir, state.Key)
}

type Nodes struct {
	dir        string
	mu         sync.Mutex
	retry      map[string]time.Time
	makeClient func(string) *Client
}

func NewNodes(directory string) *Nodes {
	return &Nodes{dir: directory, retry: map[string]time.Time{}, makeClient: newClient}
}

func (nodes *Nodes) open(address string) (*Node, error) {
	digest := sha256.Sum256([]byte(address))
	dir := filepath.Join(nodes.dir, hex.EncodeToString(digest[:]))
	service, err := OpenSync(nodes.makeClient(address), dir)
	if err != nil {
		return nil, err
	}
	return &Node{Address: address, Sync: service, dir: dir}, nil
}

func (nodes *Nodes) Resume() (*Node, error) {
	data, err := os.ReadFile(filepath.Join(nodes.dir, "active"))
	if errors.Is(err, os.ErrNotExist) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	address, err := NormalizeAddress(string(data))
	if err != nil {
		return nil, err
	}
	return nodes.open(address)
}

func (nodes *Nodes) Join(ctx context.Context, current *Node, address, password, approved string) (*Node, error) {
	nodes.mu.Lock()
	defer nodes.mu.Unlock()
	address, err := NormalizeAddress(address)
	if err != nil {
		return nil, err
	}
	if password == "" {
		return nil, errors.New("Enter the node password")
	}
	if wait := time.Until(nodes.retry[address]); wait > 0 {
		return nil, &StatusError{Code: http.StatusTooManyRequests, RetryAfter: wait}
	}
	node := current
	if node == nil || node.Address != address {
		node, err = nodes.open(address)
		if err != nil {
			return nil, err
		}
	}
	grant, err := node.Sync.client.join(ctx, password)
	if status, ok := errors.AsType[*StatusError](err); ok && status.Code == http.StatusTooManyRequests {
		nodes.retry[address] = time.Now().Add(max(status.RetryAfter, time.Second))
	}
	if err != nil {
		return nil, err
	}
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	if err := node.Sync.setGrant(grant, approved); err != nil {
		return nil, err
	}
	if err := writePrivate(filepath.Join(nodes.dir, "active"), []byte(address)); err != nil {
		return nil, err
	}
	if current != nil && current != node {
		current.Sync.suspend()
	}
	return node, nil
}
