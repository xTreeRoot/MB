#include "MBSignal.h"
#include <math.h>
#include <stdatomic.h>
#include <stdlib.h>

// 对照 main/internal/player/normalize.go；立体声共享增益，保留声像。
struct MBSignal {
    atomic_int references;
    atomic_int mode;
    _Atomic double fixedGain;
    _Atomic double bass;
    double low, bassPower, bassFilter, bassSmoothing;
    int previousMode;
    double power, gain, limit, meter, attack, release, recovery;
    AudioStreamBasicDescription format;
};

MBSignal *MBSignalCreate(void) {
    MBSignal *signal = calloc(1, sizeof(MBSignal));
    if (!signal) return NULL;
    atomic_init(&signal->references, 1);
    atomic_init(&signal->mode, 0);
    atomic_init(&signal->fixedGain, 1);
    atomic_init(&signal->bass, 0);
    MBSignalReset(signal, 48000);
    return signal;
}
void MBSignalRelease(MBSignal *signal) {
    if (signal && atomic_fetch_sub(&signal->references, 1) == 1) free(signal);
}
void MBSignalConfigure(MBSignal *signal, int mode, double fixedGain) {
    if (!signal) return;
    atomic_store(&signal->fixedGain, isfinite(fixedGain) && fixedGain > 0 ? fmin(4, fixedGain) : 1);
    atomic_store(&signal->mode, mode);
}
double MBSignalBass(MBSignal *signal) { return signal ? atomic_load(&signal->bass) : 0; }
void MBSignalReset(MBSignal *signal, double rate) {
    signal->power = 0; signal->gain = signal->limit = 1;
    rate = rate > 0 ? rate : 48000;
    signal->meter = 1 - exp(-1 / (rate * 0.4));
    signal->attack = 1 - exp(-1 / (rate * 0.03));
    signal->release = 1 - exp(-1 / rate);
    signal->low = signal->bassPower = 0; atomic_store(&signal->bass, 0);
    signal->bassFilter = 1 - exp(-2 * M_PI * 180 / rate);
    signal->bassSmoothing = 1 - exp(-1 / (rate * 0.08));
    signal->recovery = 1 - exp(-1 / (rate * 0.08));
}
static void meterBass(MBSignal *signal, double mono) {
    signal->low += (mono - signal->low) * signal->bassFilter;
    signal->bassPower += (signal->low * signal->low - signal->bassPower) * signal->bassSmoothing;
}
static double frameGain(MBSignal *signal, int mode, double fixed, double energy, double peak) {
    if (mode == 0) return 1;
    if (mode == 1) return fixed;
    signal->power += (energy - signal->power) * signal->meter;
    double target = signal->power >= 0.003 * 0.003 ? fmin(4, fmax(0.25, 0.126 / sqrt(signal->power))) : 1;
    signal->gain += (target - signal->gain) * (target < signal->gain ? signal->attack : signal->release);
    signal->limit += (1 - signal->limit) * signal->recovery;
    if (peak * signal->gain > 0.98) signal->limit = fmin(signal->limit, 0.98 / (peak * signal->gain));
    return signal->gain * signal->limit;
}
static int begin(MBSignal *signal) {
    int mode = atomic_load(&signal->mode);
    if (mode != signal->previousMode) {
        signal->power = 0; signal->gain = signal->limit = 1; signal->previousMode = mode;
    }
    return mode;
}
void MBSignalProcess(MBSignal *signal, float *samples, size_t frames, size_t channels) {
    if (!signal || !samples || channels == 0) return;
    int mode = begin(signal);
    double fixed = atomic_load(&signal->fixedGain);
    for (size_t frame = 0; frame < frames; frame++) {
        double energy = 0, peak = 0, mono = 0;
        for (size_t ch = 0; ch < channels; ch++) {
            double value = samples[frame * channels + ch];
            energy += value * value; peak = fmax(peak, fabs(value)); mono += value;
        }
        meterBass(signal, mono / channels);
        float gain = (float)frameGain(signal, mode, fixed, energy / channels, peak);
        for (size_t ch = 0; ch < channels; ch++) samples[frame * channels + ch] *= gain;
    }
    atomic_store(&signal->bass, fmin(1, sqrt(signal->bassPower) * 8));
}
static void tapInit(MTAudioProcessingTapRef tap, void *clientInfo, void **storage) {
    MBSignal *signal = clientInfo;
    atomic_fetch_add(&signal->references, 1);
    *storage = signal;
}
static void tapFinalize(MTAudioProcessingTapRef tap) { MBSignalRelease(MTAudioProcessingTapGetStorage(tap)); }
static void tapPrepare(MTAudioProcessingTapRef tap, CMItemCount maxFrames, const AudioStreamBasicDescription *format) {
    MBSignal *signal = MTAudioProcessingTapGetStorage(tap);
    signal->format = *format;
    MBSignalReset(signal, format->mSampleRate);
}
static void tapUnprepare(MTAudioProcessingTapRef tap) {}
static void tapProcess(MTAudioProcessingTapRef tap, CMItemCount frames, MTAudioProcessingTapFlags flags,
                       AudioBufferList *buffers, CMItemCount *outFrames, MTAudioProcessingTapFlags *outFlags) {
    OSStatus status = MTAudioProcessingTapGetSourceAudio(tap, frames, buffers, outFlags, NULL, outFrames);
    if (status != noErr) { *outFrames = 0; return; }
    MBSignal *signal = MTAudioProcessingTapGetStorage(tap);
    const AudioStreamBasicDescription *format = &signal->format;
    if (format->mFormatID != kAudioFormatLinearPCM || !(format->mFormatFlags & kAudioFormatFlagIsFloat) || format->mBitsPerChannel != 32) return;
    if (*outFlags & kMTAudioProcessingTapFlag_StartOfStream) MBSignalReset(signal, format->mSampleRate);
    if (!(format->mFormatFlags & kAudioFormatFlagIsNonInterleaved)) {
        if (buffers->mNumberBuffers == 1) MBSignalProcess(signal, buffers->mBuffers[0].mData, *outFrames, format->mChannelsPerFrame);
        return;
    }
    int mode = begin(signal);
    if (buffers->mNumberBuffers == 0) return;
    double fixed = atomic_load(&signal->fixedGain);
    for (CMItemCount frame = 0; frame < *outFrames; frame++) {
        double energy = 0, peak = 0, mono = 0;
        for (UInt32 ch = 0; ch < buffers->mNumberBuffers; ch++) {
            double value = ((float *)buffers->mBuffers[ch].mData)[frame];
            energy += value * value; peak = fmax(peak, fabs(value)); mono += value;
        }
        meterBass(signal, mono / buffers->mNumberBuffers);
        float gain = (float)frameGain(signal, mode, fixed, energy / buffers->mNumberBuffers, peak);
        for (UInt32 ch = 0; ch < buffers->mNumberBuffers; ch++) ((float *)buffers->mBuffers[ch].mData)[frame] *= gain;
    }
    atomic_store(&signal->bass, fmin(1, sqrt(signal->bassPower) * 8));
}
OSStatus MBSignalCreateTap(MBSignal *signal, MTAudioProcessingTapRef *tap) {
    if (!signal) return -50;
    MTAudioProcessingTapCallbacks callbacks = {
        kMTAudioProcessingTapCallbacksVersion_0, signal, tapInit, tapFinalize, tapPrepare, tapUnprepare, tapProcess
    };
    return MTAudioProcessingTapCreate(kCFAllocatorDefault, &callbacks, kMTAudioProcessingTapCreationFlag_PreEffects, tap);
}
