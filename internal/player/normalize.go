package player

import (
	"encoding/json"
	"fmt"
	"io"
	"math"
	"sync/atomic"

	"github.com/egoist/mygo/audio"
)

// NormalizationMode chooses a fixed gain for a song, adaptive gain, or bypass.
type NormalizationMode string

const (
	NormalizationStandard NormalizationMode = "standard"
	NormalizationDynamic  NormalizationMode = "dynamic"
	NormalizationOff      NormalizationMode = "off"
)

// UnmarshalJSON also accepts the on/off choice saved by earlier versions.
func (m *NormalizationMode) UnmarshalJSON(b []byte) error {
	var value any
	if err := json.Unmarshal(b, &value); err != nil {
		return err
	}
	switch value := value.(type) {
	case bool:
		*m = NormalizationOff
		if value {
			*m = NormalizationStandard
		}
		return nil
	case string:
		switch mode := NormalizationMode(value); mode {
		case NormalizationStandard, NormalizationDynamic, NormalizationOff:
			*m = mode
			return nil
		}
	}
	return fmt.Errorf("player: invalid loudness mode %s", b)
}

const (
	normTarget  = 0.126 // about -18 dBFS RMS
	normGate    = 0.003 // do not lift silence or very quiet background noise
	normCeiling = 0.98
	normMaxGain = 4.0 // at most 12 dB of amplification
)

// normalizer links the channels so gain changes preserve the stereo image.
// Only mode crosses threads; the audio decoder owns the envelope and gain.
type normalizer struct {
	stream
	mode                             atomic.Int32
	last                             bool
	power, gain, limit               float64
	meter, attack, release, recovery float64
}

func newNormalizer(s stream) *normalizer {
	rate := float64(s.SampleRate())
	coefficient := func(seconds float64) float64 { return 1 - math.Exp(-1/(rate*seconds)) }
	return &normalizer{stream: s, gain: 1, limit: 1,
		meter: coefficient(0.4), attack: coefficient(0.03),
		release: coefficient(1), recovery: coefficient(0.08)}
}

func (n *normalizer) setMode(mode NormalizationMode) {
	var dynamic int32
	if mode == NormalizationDynamic {
		dynamic = 1
	}
	n.mode.Store(dynamic)
}

func (n *normalizer) reset() { n.power, n.gain, n.limit = 0, 1, 1 }

func (n *normalizer) SetPosition(frame int64) error {
	if err := n.stream.SetPosition(frame); err != nil {
		return err
	}
	n.reset()
	return nil
}

func (n *normalizer) Read(dst []float32) (int, error) {
	count, err := n.stream.Read(dst)
	dynamic := n.mode.Load() != 0
	if dynamic != n.last {
		n.reset()
		n.last = dynamic
	}
	if !dynamic {
		return count, err
	}
	ch := n.Channels()
	for i := 0; i+ch <= count; i += ch {
		var energy, peak float64
		for _, sample := range dst[i : i+ch] {
			v := float64(sample)
			energy += v * v
			peak = max(peak, math.Abs(v))
		}
		n.power += (energy/float64(ch) - n.power) * n.meter
		target := 1.0
		if n.power >= normGate*normGate {
			target = min(normMaxGain, max(1/normMaxGain, normTarget/math.Sqrt(n.power)))
		}
		speed := n.release
		if target < n.gain {
			speed = n.attack
		}
		n.gain += (target - n.gain) * speed
		// Peaks reduce gain at once; recovering slowly avoids repeated clipping.
		n.limit += (1 - n.limit) * n.recovery
		if peak*n.gain > normCeiling {
			n.limit = min(n.limit, normCeiling/(peak*n.gain))
		}
		gain := float32(n.gain * n.limit)
		for j := i; j < i+ch; j++ {
			dst[j] *= gain
		}
	}
	return count, err
}

// measuredGain measures the complete song without changing its samples. Quiet
// blocks do not drag the average down; a single gain preserves song dynamics.
func measuredGain(d audio.Decoder) (float64, error) {
	ch := d.Channels()
	blockFrames := max(d.SampleRate()*2/5, 1)
	buf := make([]float32, 4096*ch)
	var energy, total, peak float64
	var frames, kept int64
	addBlock := func() {
		if frames > 0 && energy/float64(frames) >= normGate*normGate {
			total += energy
			kept += frames
		}
		energy, frames = 0, 0
	}
	for {
		count, err := d.Read(buf)
		for i := 0; i+ch <= count; i += ch {
			for _, sample := range buf[i : i+ch] {
				v := float64(sample)
				energy += v * v / float64(ch)
				peak = max(peak, math.Abs(v))
			}
			frames++
			if frames == int64(blockFrames) {
				addBlock()
			}
		}
		if err != nil {
			if err != io.EOF {
				return 1, err
			}
			break
		}
		if count == 0 {
			return 1, io.ErrNoProgress
		}
	}
	addBlock()
	if kept == 0 || peak == 0 {
		return 1, nil
	}
	gain := min(normMaxGain, max(1/normMaxGain, normTarget/math.Sqrt(total/float64(kept))))
	return min(gain, normCeiling/peak), nil
}

// measureGain supplies a fixed gain when a source has no loudness metadata.
// The shared decoder waits off the main thread and stops with the download.
func (p *Player) measureGain() {
	if p.measuring || p.stream == nil || p.track == nil || p.info == nil || p.info.Gain != 0 || p.info.Peak != 0 {
		return
	}
	p.measuring = true
	dl, _ := p.stream.source()
	duration, gen := p.track.Duration, p.gen
	go func() {
		s, err := openShared(dl, duration)
		if err != nil {
			return
		}
		defer s.Close()
		gain, err := measuredGain(s)
		if err == nil {
			p.post(func() {
				if p.gen == gen {
					p.gain = gain
					p.applyGain()
				}
			})
		}
	}()
}
