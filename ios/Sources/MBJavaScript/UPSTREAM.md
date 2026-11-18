# QuickJS 来源

版本：2026-06-04。官方源：https://bellard.org/quickjs/quickjs-2026-06-04.tar.xz

原始压缩包 SHA256：b376e839b322978313d929fd20663b11ba58b75df5a46c126dd19ea2fa70ad2a

仅引入解释器及依赖；不编译 qjs、quickjs-libc、OS/文件/进程接口，不启用 JIT。许可证见 vendor/LICENSE。宿主桥接在 MBJavaScript.c，内存、栈、执行时间和取消由公开 API 限制。
