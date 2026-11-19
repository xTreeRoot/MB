package edge

import (
	"bytes"
	"crypto/aes"
	"crypto/cipher"
	"crypto/hkdf"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/json"
	"errors"
	"fmt"
)

type Session struct {
	AccountID string          `json:"account_id"`
	Label     string          `json:"account_label,omitempty"`
	Data      json.RawMessage `json:"session"`
}

func randBytes(size int) []byte {
	data := make([]byte, size)
	rand.Read(data)
	return data
}

func derive(recovery, space, purpose string) []byte {
	key, _ := encoding.DecodeString(recovery)
	derived, _ := hkdf.Key(sha256.New, key, []byte("mb-sync-v1\n"+space), purpose, 32)
	return derived
}

func accountRef(recovery, space, plugin, account string) string {
	mac := hmac.New(sha256.New, derive(recovery, space, "account-ref"))
	mac.Write([]byte(plugin + "\n" + account))
	return encoding.EncodeToString(mac.Sum(nil))
}

func sessionCipher(recovery, space string) cipher.AEAD {
	block, _ := aes.NewCipher(derive(recovery, space, "session"))
	gcm, _ := cipher.NewGCM(block)
	return gcm
}

func associatedData(space, plugin, ref string, format int) []byte {
	return fmt.Appendf(nil, "mb-session-v1\n%s\n%s\n%s\n%d", space, plugin, ref, format)
}

func validSession(session Session) bool {
	data := bytes.TrimSpace(session.Data)
	return session.AccountID != "" && len(data) > 0 && data[0] == '{' && json.Valid(data)
}

func seal(recovery, space, plugin string, format int, session Session) (Envelope, string, error) {
	var result Envelope
	if !validBase64(recovery, 32) || !validSession(session) || format < 1 || format > 65535 {
		return result, "", errors.New("invalid MB session export")
	}
	data, err := json.Marshal(session)
	if err != nil {
		return result, "", err
	}
	ref := accountRef(recovery, space, plugin, session.AccountID)
	nonce := randBytes(12)
	data = sessionCipher(recovery, space).Seal(nil, nonce, data, associatedData(space, plugin, ref, format))
	result = Envelope{Format: format, Nonce: encoding.EncodeToString(nonce), Ciphertext: encoding.EncodeToString(data)}
	body, _ := json.Marshal(result)
	if len(body) > envelopeLimit {
		return Envelope{}, "", errors.New("MB session exceeds size limit")
	}
	return result, ref, nil
}

func openSession(recovery, space, plugin, ref string, envelope Envelope) (Session, error) {
	var session Session
	if !validBase64(recovery, 32) || !validBase64(envelope.Nonce, 12) || len(envelope.Ciphertext) > envelopeLimit || envelope.Format < 1 || envelope.Format > 65535 {
		return session, errors.New("invalid MB session envelope")
	}
	nonce, _ := encoding.DecodeString(envelope.Nonce)
	data, err := encoding.DecodeString(envelope.Ciphertext)
	if err != nil || encoding.EncodeToString(data) != envelope.Ciphertext {
		return session, errors.New("invalid MB session ciphertext")
	}
	plain, err := sessionCipher(recovery, space).Open(nil, nonce, data, associatedData(space, plugin, ref, envelope.Format))
	if err != nil || json.Unmarshal(plain, &session) != nil || !validSession(session) || accountRef(recovery, space, plugin, session.AccountID) != ref {
		return Session{}, errors.New("cannot authenticate MB session")
	}
	return session, nil
}
