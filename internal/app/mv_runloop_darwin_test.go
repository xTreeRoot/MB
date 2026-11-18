package app

import "github.com/ebitengine/purego"

// AVFoundation delivers network-media work through the main run loop; the
// headless app tests otherwise only service the mainThread channel.
func init() {
	cf, err := purego.Dlopen("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation", purego.RTLD_GLOBAL|purego.RTLD_NOW)
	if err != nil {
		panic(err)
	}
	var run func(uintptr, float64, bool) int32
	purego.RegisterLibFunc(&run, cf, "CFRunLoopRunInMode")
	var makeString func(uintptr, string, uint32) uintptr
	var release func(uintptr)
	purego.RegisterLibFunc(&makeString, cf, "CFStringCreateWithCString")
	purego.RegisterLibFunc(&release, cf, "CFRelease")
	pumpMVRunLoop = func() {
		onMainThread(func() {
			mode := makeString(0, "kCFRunLoopDefaultMode", 0x08000100) // UTF-8
			defer release(mode)
			run(mode, 0.001, true)
		})
	}
}
