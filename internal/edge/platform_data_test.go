package edge

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestPlatformDataVector(t *testing.T) {
	envelope := Envelope{1, "AAECAwQFBgcICQoL", "1CiWbBgaQJw8-59H-Td2k9vAIU8_zVxQKtQ5h2qz8sxykNcXpd4_KjnyCXHP5GHzDDuqEw"}
	if got := encoding.EncodeToString(derive(testRecovery, "test-space", "platform-data")); got != "jMZa0IZgOPiLSkJz1EqCZDIcV_iuXtq_XuV857DCPm4" {
		t.Fatalf("unexpected key: %s", got)
	}
	var raw json.RawMessage
	if err := openData(testRecovery, "test-space", "soda", "profile", envelope, &raw); err != nil || string(raw) != `{"display_name":"Fixture"}` {
		t.Fatalf("vector: %s, %v", raw, err)
	}
	if err := openData(testRecovery, "test-space", "soda", "other", envelope, &raw); err == nil {
		t.Fatal("ciphertext replayed across keys")
	}
}

func TestPlatformProfileETagAndAccountGuards(t *testing.T) {
	const tag = `"AAECAwQFBgcICQoLDA0ODw"`
	var remote Envelope
	var existing, tombstone, unknown, conflict bool
	var writes int
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/v1/data/soda/profile" || r.Header.Get("Authorization") != "Bearer "+testRecovery {
			t.Error("unexpected request")
		}
		if r.Method == "GET" {
			w.Header().Set("Content-Type", "application/json")
			if unknown {
				w.WriteHeader(404)
				w.Write([]byte(`{"error":"unsupported"}`))
				return
			}
			if tombstone {
				w.Header().Set("ETag", tag)
				w.WriteHeader(410)
				w.Write([]byte(`{"error":"deleted"}`))
				return
			}
			if !existing {
				w.WriteHeader(404)
				w.Write([]byte(`{"error":"not_found"}`))
				return
			}
			w.Header().Set("ETag", tag)
			json.NewEncoder(w).Encode(remote)
			return
		}
		writes++
		if existing && r.Header.Get("If-Match") != tag || !existing && r.Header.Get("If-None-Match") != "*" {
			t.Error("missing precondition")
		}
		if conflict {
			w.WriteHeader(412)
			return
		}
		if err := json.NewDecoder(r.Body).Decode(&remote); err != nil {
			t.Error(err)
		}
		w.Header().Set("ETag", tag)
		if existing {
			w.WriteHeader(204)
		} else {
			w.WriteHeader(201)
		}
		existing = true
	}))
	defer server.Close()
	node, err := OpenSync(newClient(server.URL), t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	node.state.Grant = Grant{SpaceID: "test-space", Token: testRecovery, ExpiresAt: time.Now().Add(time.Hour).Unix()}
	node.state.Recovery = testRecovery
	syncProfile := func(profile Profile, publish bool) error {
		return node.SyncProfile(context.Background(), "soda", node.Ticket("soda"), profile, publish)
	}
	profile := Profile{AccountID: "first", Name: "Fixture"}
	unknown = true
	if err := syncProfile(profile, true); err == nil || writes != 0 {
		t.Fatalf("old node: %v, writes %d", err, writes)
	}
	unknown = false
	if err := syncProfile(profile, false); err != nil || node.state.Profiles["soda"] != profile || writes != 1 {
		t.Fatalf("create: %v, writes %d", err, writes)
	}
	updated := Profile{AccountID: "first", Name: "Phone"}
	if err := syncProfile(updated, true); err != nil || writes != 2 {
		t.Fatalf("update: %v, writes %d", err, writes)
	}
	if err := syncProfile(profile, false); err != nil || node.state.Profiles["soda"] != updated || writes != 2 {
		t.Fatalf("pull: %v, writes %d", err, writes)
	}
	if err := syncProfile(Profile{AccountID: "another", Name: "Demo"}, true); err == nil || writes != 2 {
		t.Fatalf("account isolation: %v, writes %d", err, writes)
	}
	conflict = true
	if err := syncProfile(profile, true); !dataStatus(err, 412, "") || writes != 3 || node.state.Profiles["soda"] != updated {
		t.Fatalf("conflict: %v, writes %d", err, writes)
	}
	tombstone = true
	if err := syncProfile(profile, true); !dataStatus(err, 410, "deleted") || writes != 3 {
		t.Fatalf("deleted: %v, writes %d", err, writes)
	}
	if err := node.Unbind(); err != nil {
		t.Fatal(err)
	}
	if err := syncProfile(profile, true); err != nil || writes != 3 {
		t.Fatalf("after unbind: %v, writes %d", err, writes)
	}
}
