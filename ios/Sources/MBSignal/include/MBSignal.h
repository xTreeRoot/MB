#ifndef MB_SIGNAL_H
#define MB_SIGNAL_H
#include <MediaToolbox/MediaToolbox.h>
#include <stddef.h>

typedef struct MBSignal MBSignal;
// 所有 UI 端参数使用原子传递；process/reset 只在音频线程执行。
MBSignal *MBSignalCreate(void);
void MBSignalRelease(MBSignal *signal);
void MBSignalConfigure(MBSignal *signal, int mode, double fixedGain);
double MBSignalBass(MBSignal *signal);
void MBSignalReset(MBSignal *signal, double sampleRate);
void MBSignalProcess(MBSignal *signal, float *samples, size_t frames, size_t channels);
OSStatus MBSignalCreateTap(MBSignal *signal, MTAudioProcessingTapRef *tap);

#endif
