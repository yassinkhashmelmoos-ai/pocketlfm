> **Update - supersedes the "Engine" and CI statements further down.**
> * `models/lfm2.cpp` is **not** replaced by upstream any more. All of your edits are kept; only the broken parts are fixed: the GeGLU edit now uses
>   `LLM_FFN_GELU` + `LLM_FFN_PAR` (the real GeGLU path of `build_ffn`; the fused `LLM_FFN_GEGLU` is what aborted), and one assert that could only abort was removed.
>   Each edit sits behind `thunder_lfm2_variant()` (`thunder_variant.h`): load profile 0 builds YOUR graph, the safe-mode profiles 1/2 build the stock upstream
>   graph, so if an edit ever kills a launch the next start can no longer hit it. Verified on a tiny LFM2: loads, deterministic, finite logits, numerically different from stock.
>   Not verified: whether the two math-changing edits (GeGLU, extra SiLU on the conv gate) help or hurt quality on the real model - compare on the phone.
> * `engine-smoke-test.yml` is manual-only now (it can no longer run alongside the APK build).
> * `build-apk.yml`: one extra non-fatal step ("Protect the runner agent ...") after the swap step; nothing else in that file changed.

# Launch-crash fix (v1.5)

## What was wrong

**The crash was not a race. It was a deterministic native abort in the model graph, hit on every launch.**

`lfm25/app/src/main/cpp/models/lfm2.cpp` had been hand-edited with five "IMPROVEMENTS" to the compute graph of LFM2.
Improvement 3 ("GeGLU instead of SiLU in the dense layers") calls `build_ffn(..., LLM_FFN_GEGLU, LLM_FFN_PAR)`.
In `build_ffn` that activation is the *fused* GLU: it halves the activation width, and unlike the `LLM_FFN_GELU` branch it never
switches the gate to sequential mode, so the next `ggml_mul()` multiplies a 64-wide tensor by a 128-wide one and trips
`GGML_ASSERT(ggml_can_repeat(b, a))` -> `abort()`. That happens inside `llama_init_from_model()` (graph reservation): after the 218 MB model
copy has finished (your "user data = 230 MB"), while "Loading Thunder AGI..." is on screen. The process dies; nothing can catch or log it.

Another edit put an extra SiLU on the short-conv gate, which silently changes what every recurrent layer computes. The graph runs
**pretrained** weights - changing its maths is not an optimisation. `models/dream.cpp` had the same kind of edits.

Upstream check: your vendored engine is llama.cpp at commit `6156ae5` (2026-02-02). Only `lfm2.cpp`, `dream.cpp`, an ARM-only include guard in
`ggml-cpu.cpp` and the project's own files differ from it.

## Other real bugs found (the races / ordering problems you suspected)

| Bug | Effect |
|---|---|
| The model started loading in `ChatViewModel.init`, for everyone, before any access check | the activation window could never come first; a locked user got the 218 MB copy + engine start |
| `ThunderModel.resolveModelFile` wrote straight into `model.gguf` and treated any file > 50 MB as complete; no lock | a copy interrupted at 23 % was loaded forever; two loads at once copied the file twice and handed out a half-written one |
| `System.loadLibrary` in a static initializer | any failure to load the engine killed the app during the first composition |
| License check (`EncryptedSharedPreferences`) ran on the main thread during composition with no error handling | a restored backup / wiped Keystore key = crash on every launch |
| `AccessGate` got the license result in a later effect | asking the gate earlier would see "no license" for a licensed user |
| `ensureSession()` read the sessions list before its Flow had emitted | an extra empty "New Chat" on every re-open |
| `ChatDatabase.getInstance` double-checked locking without the inner check | two threads could each build a database |
| `CrashReporter` was written but never installed (no `Application` class) | no crash was ever recorded; the Error Report dialog could never show anything |

## What changed

* **Engine**: `models/lfm2.cpp`, `models/dream.cpp` restored to upstream (bodies byte-identical, plus a warning banner).
* **Launch order** (`MainActivity`, `ChatViewModel`, `ChatScreen`): license check once, off the main thread, result handed to `AccessGate`
  *before* anything asks it. Unlocked -> model loads. Locked -> **the activation window comes first and nothing is loaded**; "Not now" still
  opens the chat (history readable), and pressing send asks again.
* **Loader** (`ModelFileInstaller`, `ThunderModel`): atomic copy (`.partial` -> verify size + GGUF magic -> fsync -> rename), process-wide lock,
  free-space check, single-flight load, native library loaded lazily with errors reported on screen instead of crashing.
* **Native crash safety net** (`llama-jni.cpp`, `load_profile.h`, `LoadAttemptTracker`): the engine writes a breadcrumb before each loading step;
  a marker survives a native death, so the next start knows it died and *where*. It retries with gentler settings (profiles 0 -> 1 -> 2) and after
  3 crashes in a row stops loading automatically (Retry lifts it once) instead of crash-looping. Crash evidence is scoped to the build, so installing
  a fix is never blocked by the build it replaced. C++ exceptions can no longer escape a JNI call, and prompt chunks are sized from the context's
  real `n_batch` (`llama_decode` aborts if it is exceeded).
* **Diagnostics** (`ThunderApp`, `StartupDiagnostics`): installs `CrashReporter`; at the next start shows one copyable report (engine step reached,
  Android's exit reason incl. signal / low-memory kill / memory held, saved Java crash).
* **Hardening**: `LicenseValidator.checkSafely()` + self-healing prefs; `ChatDatabase` locking + one-shot `latest()`; ONNX Runtime environment is lazy;
  backup/transfer rules exclude the model, the ONNX payload, the engine state and the encrypted license prefs; version 1.4 -> 1.5.
* **Tests / tooling**: 26 new unit tests (installer, tracker, safe-init); `lfm25/tools/engine-smoke-test/` + `.github/workflows/engine-smoke-test.yml` build
  the vendored engine on a CI runner and load / generate with a tiny LFM2 model through the app's real JNI functions (fails with exit 134 on the old `lfm2.cpp`).

## Verified here / not verified

Verified: the abort reproduced and gone (x86-64, tiny synthetic LFM2, F32 + Q8_0, all load profiles); real native aborts through the real Kotlin
`ThunderModel` (crash ladder, block, Retry, update-over); installer vs. the old logic under truncation and concurrency; every changed Kotlin file
compiles against the real Android API 34 jar (ViewModel + MainActivity against stand-ins for Compose/Room/WorkManager/ONNX, 0 errors, 0 warnings);
JNI file clean under GCC 13 and Clang 18 + libc++ and under ASan/UBSan; the repo's own `AccessGateTest` still passes.

Not verified (no phone / Gradle / Android SDK here): the real LFM2.5-VL-450M model on ARM, the Compose UI actually running (the flow was compiled and
traced, not observed), Room's KSP step, Lint, and your CI. If anything still goes wrong the next start shows a startup report - send it.
