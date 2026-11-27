#pragma once
// Pure helpers for the model-loading path of llama-jni.cpp.
//
// Deliberately free of llama.cpp / JNI / Android dependencies so they can be unit-tested on a desktop
// (see the host tests in the repo's docs) and cannot themselves become a reason loading fails.

#include <algorithm>
#include <chrono>
#include <cstdarg>
#include <cstdio>
#include <string>

namespace thunder {

// Return codes of nativeLoadModel(). Kotlin mirrors these in ThunderModel.LOAD_*.
enum LoadResult : int {
    LOAD_OK             = 0,
    LOAD_MODEL_FAILED   = 1,   // the GGUF could not be read / is not supported / is corrupt
    LOAD_CONTEXT_FAILED = 2,   // model is fine but the inference context could not be created (memory, ...)
    LOAD_EXCEPTION      = 3,   // a C++ exception was thrown - caught here instead of letting it abort() the process
};

struct LoadParams {
    int  n_ctx;
    int  n_batch;       // also used as n_ubatch; prompt chunks MUST NOT exceed it (llama_decode aborts otherwise)
    int  n_threads;
    bool flash_attn;    // false = LLAMA_FLASH_ATTN_TYPE_DISABLED, true = AUTO
    bool use_mmap;
};

// Profile 0 = normal.  1 = conservative.  2 = minimal.
// The Kotlin side starts at 0 and only moves up after a previous attempt died natively (see LoadAttemptTracker.kt),
// so a phone that handles the normal settings never runs the slower ones.
inline LoadParams params_for_profile(int profile, long free_mb, int hw_threads, int normal_batch) {
    const int ctx     = free_mb > 4000 ? 8192 : (free_mb > 2000 ? 4096 : 2048);
    const int threads = std::min(std::max(hw_threads, 2), 8);
    LoadParams p;
    switch (profile) {
        case 0:  p = { ctx,                    normal_batch, threads,                 true,  true  }; break;
        case 1:  p = { std::min(ctx, 2048),    128,          std::min(threads, 4),    false, true  }; break;
        default: p = { 1024,                   64,           2,                       false, false }; break;
    }
    return p;
}

// Appends ONE line ("<epoch-ms> <text>") to the breadcrumb file and closes it again, so the line is already in the
// kernel when the very next instruction crashes the process. Best effort: tracing must never break loading.
#if defined(__GNUC__) || defined(__clang__)
__attribute__((format(printf, 2, 3)))   // lets the compiler check every format string passed to trace_line
#endif
inline void trace_line(const std::string& path, const char* fmt, ...) {
    if (path.empty()) return;
    FILE* f = std::fopen(path.c_str(), "a");
    if (!f) return;
    char msg[480];
    va_list ap;
    va_start(ap, fmt);
    std::vsnprintf(msg, sizeof msg, fmt, ap);
    va_end(ap);
    const long long ms = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::system_clock::now().time_since_epoch()).count();
    std::fprintf(f, "%lld %s\n", ms, msg);
    std::fflush(f);
    std::fclose(f);
}

} // namespace thunder
