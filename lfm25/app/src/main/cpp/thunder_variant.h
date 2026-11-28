#pragma once
// Which version of the LFM2 compute graph models/lfm2.cpp builds:
//   1 = the project's own edits to the graph (the "IMPROVEMENTS" in lfm2.cpp)  - load profile 0, the default
//   0 = stock upstream llama.cpp behaviour                                       - safe-mode load profiles 1 and 2
// llama-jni.cpp sets it right before the model is loaded; it must not change while a model is loaded.
//
// If a launch ever dies natively while loading, the app's crash-loop breaker (LoadAttemptTracker.kt) moves to profile 1 on the
// next start, which builds the stock graph - so an experimental edit can no longer lock the app out of loading the model.
// To build the stock graph for every profile, define THUNDER_LFM2_EDITS_DEFAULT as 0 (change the line below or pass -D).
#ifndef THUNDER_LFM2_EDITS_DEFAULT
#define THUNDER_LFM2_EDITS_DEFAULT 1
#endif

inline int & thunder_lfm2_variant() {
    static int v = THUNDER_LFM2_EDITS_DEFAULT;
    return v;
}
