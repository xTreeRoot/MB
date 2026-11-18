package player

import (
	"errors"
	"io"
	"math"
	"slices"
	"sync"
	"testing"

	"github.com/egoist/mygo/audio"
	"mygo-music/internal/music"
)

type normalizationPCM struct {
	samples             []float32
	pos, rate, channels int
}

func (s *normalizationPCM) SampleRate() int                { return s.rate }
func (s *normalizationPCM) Channels() int                  { return s.channels }
func (s *normalizationPCM) Len() int64                     { return int64(len(s.samples) / s.channels) }
func (s *normalizationPCM) Close() error                   { return nil }
func (s *normalizationPCM) source() (*download, *spectrum) { return nil, nil }
func (s *normalizationPCM) SetPosition(frame int64) error {
	s.pos = int(frame) * s.channels
	return nil
}
func (s *normalizationPCM) Read(dst []float32) (int, error) {
	n := copy(dst, s.samples[s.pos:])
	s.pos += n
	if s.pos == len(s.samples) {
		return n, io.EOF
	}
	return n, nil
}

func normalizationTone(rate, channels int, amplitudes ...float64) *normalizationPCM {
	s := &normalizationPCM{rate: rate, channels: channels}
	for _, amp := range amplitudes {
		for i := range rate * 6 {
			v := float32(amp * math.Sin(2*math.Pi*1000*float64(i)/float64(rate)))
			for channel := range channels {
				s.samples = append(s.samples, v/float32(channel+1))
			}
		}
	}
	return s
}

func readNormalized(t *testing.T, s *normalizer, chunk int) []float32 {
	t.Helper()
	var samples []float32
	buf := make([]float32, chunk*s.Channels())
	for {
		n, err := s.Read(buf)
		samples = append(samples, buf[:n]...)
		if err != nil {
			if err != io.EOF {
				t.Fatal(err)
			}
			return samples
		}
	}
}

func normalizationRMS(samples []float32) float64 {
	var power float64
	for _, v := range samples {
		power += float64(v) * float64(v)
	}
	return math.Sqrt(power / float64(len(samples)))
}

// Dynamic mode lifts quiet passages and reduces loud ones at each sample rate.
func TestDynamicNormalization(t *testing.T) {
	for _, rate := range []int{44100, 48000, 96000} {
		s := normalizationTone(rate, 2, 0.02, 0.7)
		n := newNormalizer(s)
		n.setMode(NormalizationDynamic)
		got := readNormalized(t, n, 2048)
		quiet := normalizationRMS(got[rate*5*2 : rate*6*2])
		loud := normalizationRMS(got[len(got)-rate*2:])
		if quiet < 0.04 || loud > 0.16 || loud/quiet > 4 {
			t.Errorf("rate %d: quiet %f, loud %f", rate, quiet, loud)
		}
		for i := 0; i < len(got); i += 2 {
			if math.Abs(float64(got[i]-2*got[i+1])) > 1e-6 {
				t.Fatal("stereo balance changed")
			}
		}
	}
}

// Silence stays silent and a full-scale transient cannot clip after quiet audio.
func TestDynamicNormalizationPeaksAndSilence(t *testing.T) {
	s := normalizationTone(48000, 1, 0, 0.02)
	s.samples = append(s.samples, 1, -1, 0.9, -0.9)
	n := newNormalizer(s)
	n.setMode(NormalizationDynamic)
	got := readNormalized(t, n, 137)
	for i, v := range got {
		if math.Abs(float64(v)) > normCeiling+1e-6 {
			t.Fatalf("clipped sample %d: %f", i, v)
		}
		if i < 48000*6 && v != 0 {
			t.Fatal("silence was amplified")
		}
	}
}

// Standard and Off bypass adaptive processing, including after switching modes.
func TestNormalizationBypass(t *testing.T) {
	for _, mode := range []NormalizationMode{NormalizationStandard, NormalizationOff} {
		s := normalizationTone(48000, 1, 0.02)
		n := newNormalizer(s)
		n.setMode(NormalizationDynamic)
		readNormalized(t, n, 2048)
		n.setMode(mode)
		if err := n.SetPosition(0); err != nil {
			t.Fatal(err)
		}
		if got := readNormalized(t, n, 2048); !slices.Equal(got, s.samples) {
			t.Fatalf("%s modified audio", mode)
		}
	}
}

// Seeking discards the old envelope, and decoder chunk sizes do not change gain.
func TestNormalizationSeekAndChunks(t *testing.T) {
	s := normalizationTone(48000, 1, 0.02, 0.7)
	n := newNormalizer(s)
	n.setMode(NormalizationDynamic)
	want := readNormalized(t, n, 2048)
	if err := n.SetPosition(0); err != nil {
		t.Fatal(err)
	}
	if got := readNormalized(t, n, 137); !slices.Equal(got, want) {
		t.Fatal("seek or chunk size changed normalization")
	}
}

// A fixed gain ignores silent blocks, respects peaks, and preserves dynamics.
func TestMeasuredNormalizationGain(t *testing.T) {
	for _, amplitude := range []float64{0, 0.02, 0.7} {
		s := normalizationTone(48000, 2, 0, amplitude)
		gain, err := measuredGain(s)
		if err != nil {
			t.Fatal(err)
		}
		if amplitude == 0 {
			if gain != 1 {
				t.Fatal("silence gained volume")
			}
			continue
		}
		original := normalizationRMS(s.samples[48000*6*2:])
		want := min(normMaxGain, normTarget/original)
		if math.Abs(gain-want) > 1e-5 {
			t.Errorf("amplitude %f: gain %f, want %f", amplitude, gain, want)
		}
	}
	s := normalizationTone(48000, 1, 0.02)
	s.samples = append(s.samples, 1)
	if gain, err := measuredGain(s); err != nil || gain > normCeiling {
		t.Fatalf("gain %f, error %v", gain, err)
	}
}

type failedNormalizationPCM struct{ *normalizationPCM }

func (s failedNormalizationPCM) Read([]float32) (int, error) { return 0, io.ErrUnexpectedEOF }

// An interrupted analysis cannot publish a gain measured from a partial song.
func TestMeasuredNormalizationFailure(t *testing.T) {
	_, err := measuredGain(failedNormalizationPCM{normalizationTone(48000, 1, 0.02)})
	if !errors.Is(err, io.ErrUnexpectedEOF) {
		t.Fatalf("error %v", err)
	}
}

// Fixed song gain applies only in Standard; Dynamic and Off retain user volume.
func TestNormalizationPlayerGain(t *testing.T) {
	mode := NormalizationStandard
	p := &Player{gain: 0.5, Normalize: func() NormalizationMode { return mode }}
	out := audio.NewPlayer(silence{})
	defer out.Close()
	p.fade.attach(out, 1)
	for _, tc := range []struct {
		mode NormalizationMode
		want float64
	}{
		{NormalizationStandard, 0.125}, {NormalizationDynamic, 0.25}, {NormalizationOff, 0.25},
	} {
		mode = tc.mode
		p.SetVolume(0.5)
		if got := out.Volume(); got != tc.want {
			t.Errorf("%s: volume %f, want %f", mode, got, tc.want)
		}
	}
}

// Mode changes can arrive from settings while the decoder is processing audio.
func TestNormalizationConcurrentModeChange(t *testing.T) {
	n := newNormalizer(normalizationTone(48000, 1, 0.02))
	var wg sync.WaitGroup
	wg.Go(func() {
		for range 10000 {
			n.setMode(NormalizationDynamic)
			n.setMode(NormalizationOff)
		}
	})
	readNormalized(t, n, 137)
	wg.Wait()
}

// A song with no source measurement gets its fixed gain from the shared audio.
func TestPlayerMeasuresNormalization(t *testing.T) {
	p, ch, _, tracks := servedQueue(t, "1")
	p.Normalize = func() NormalizationMode { return NormalizationStandard }
	p.PlayQueue(tracks, 0)
	for p.loading {
		next(t, ch)
	}
	if p.normalizer == nil || p.watch.stream != p.normalizer {
		t.Fatal("audio bypassed the normalizer")
	}
	for p.gain == normGain(&music.Stream{}) {
		next(t, ch)
	}
	if p.gain <= 0 || p.gain > normMaxGain {
		t.Fatalf("measured gain %f", p.gain)
	}
}
