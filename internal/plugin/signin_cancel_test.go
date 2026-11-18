package plugin

import (
	"strings"
	"testing"
	"time"

	"mygo-music/internal/music"
)

// Any plugin can export cancellation; no bundled platform or platform-specific
// login code is required to stop an outstanding signIn promise.
func TestPluginSignInCancellation(t *testing.T) {
	p := load(t, `{"id":"generic-login"}`, `
let rejectSignIn;
export function signIn() {
  return new Promise((resolve, reject) => {
    rejectSignIn = reject;
    host.signInPrompt({text: "Waiting"});
  });
}
export function cancelSignIn() {
  if (rejectSignIn) {
    rejectSignIn(new Error("Cancelled"));
    rejectSignIn = null;
  }
}`)
	// Cancelling before the runtime starts must neither start it nor fail.
	p.CancelSignIn()
	prompted := make(chan struct{}, 1)
	p.OnSignInPrompt(func(music.SignInPrompt) { prompted <- struct{}{} })
	finished := make(chan error, 1)
	go func() { _, err := p.SignIn(); finished <- err }()
	select {
	case <-prompted:
	case <-time.After(3 * time.Second):
		t.Fatal("plugin sign-in did not start")
	}
	p.CancelSignIn()
	select {
	case err := <-finished:
		if err == nil || !strings.Contains(err.Error(), "Cancelled") {
			t.Fatalf("sign-in did not reject after cancellation: %v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("plugin cancellation left sign-in waiting")
	}
	without := load(t, plain, `export function account() { return null; }`)
	without.CancelSignIn()
	if account, err := without.Account(); err != nil || account != nil {
		t.Fatalf("cancellation affected a plugin without the export: %v, %v", account, err)
	}
}
