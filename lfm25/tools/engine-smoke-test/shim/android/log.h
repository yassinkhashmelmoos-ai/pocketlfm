// Host-side shim so the project's JNI file builds on Linux: __android_log_print -> stderr
#pragma once
#include <stdio.h>
#include <stdarg.h>
enum { ANDROID_LOG_UNKNOWN=0, ANDROID_LOG_DEFAULT, ANDROID_LOG_VERBOSE, ANDROID_LOG_DEBUG, ANDROID_LOG_INFO, ANDROID_LOG_WARN, ANDROID_LOG_ERROR, ANDROID_LOG_FATAL };
static inline int __android_log_print(int prio, const char* tag, const char* fmt, ...) {
    va_list ap; va_start(ap, fmt);
    static const char* L = "?VDIWEF";
    fprintf(stderr, "[%c/%s] ", L[(prio>=0&&prio<=7)?prio:0], tag);
    vfprintf(stderr, fmt, ap); fputc('\n', stderr); va_end(ap); return 0;
}
