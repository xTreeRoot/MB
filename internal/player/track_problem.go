package player

import (
	"errors"
	"net"

	"mygo-music/internal/i18n"
	"mygo-music/internal/music"
)

type problemKey struct{ source, id string }

func classifyTrackProblem(err error) music.TrackProblem {
	var network net.Error
	var operation *net.OpError
	var dns *net.DNSError
	switch {
	case errors.Is(err, music.ErrVIP):
		return music.TrackVIP
	case errors.Is(err, music.ErrNoStream), errors.Is(err, errNoSource):
		return music.TrackNoSource
	case errors.Is(err, music.ErrSignIn):
		return music.TrackSignIn
	case errors.Is(err, music.ErrUnavailable):
		return music.TrackUnavailable
	case errors.Is(err, music.ErrNetwork), errors.As(err, &operation), errors.As(err, &dns), errors.As(err, &network) && network.Timeout():
		return music.TrackNetwork
	default:
		return music.TrackUnknown
	}
}

func (p *Player) rememberTrackProblem(track *music.Track, err error) {
	if track == nil {
		return
	}
	key := problemKey{track.Source, track.ID}
	if err == nil {
		delete(p.trackProblems, key)
		return
	}
	if p.trackProblems == nil {
		p.trackProblems = map[problemKey]music.TrackProblem{}
	}
	p.trackProblems[key] = classifyTrackProblem(err)
}

func (p *Player) TrackProblem(track *music.Track) music.TrackProblem {
	if reason := p.trackProblems[problemKey{track.Source, track.ID}]; reason != "" {
		return reason
	}
	switch track.UnavailableReason {
	case music.TrackVIP, music.TrackNoSource, music.TrackSignIn, music.TrackUnavailable:
		return track.UnavailableReason
	}
	if track.Unavailable {
		return music.TrackUnavailable
	}
	return ""
}

func TrackProblemText(reason music.TrackProblem) string {
	switch reason {
	case music.TrackVIP:
		return i18n.T("VIP required")
	case music.TrackNoSource:
		return i18n.T("Audio source unavailable")
	case music.TrackSignIn:
		return i18n.T("Sign in to play this song")
	case music.TrackUnavailable:
		return i18n.T("Temporarily unplayable")
	case music.TrackNetwork:
		return i18n.T("Network error, retry")
	case music.TrackUnknown:
		return i18n.T("Playback failed for an unknown reason")
	default:
		return ""
	}
}
