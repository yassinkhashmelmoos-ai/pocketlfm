# Engine smoke test

Builds the app's **own** vendored llama.cpp + JNI layer (`app/src/main/cpp`) for the machine you run it on, creates a tiny
random-weight LFM2 model, and runs the app's real `nativeLoadModel` / `nativeGenerate` on it - in every load profile
(normal / conservative / minimal) and for both an F32 and a Q8_0 model.

```
pip install gguf numpy          # one time
bash lfm25/tools/engine-smoke-test/run.sh
```

Needs g++, make, python3 and a JDK (only for the `jni.h` header - no JVM is started). A cold build takes a few minutes.
Options (env): `JOBS`, `OPT` (e.g. `-O0`), `SANITIZE=1` (AddressSanitizer + UBSan on the JNI layer), `BUILD_DIR`,
`CPP_DIR` (test a different copy of the engine sources).

## Why it exists

`models/lfm2.cpp` used to contain hand-made "improvements" to the model's compute graph (GeGLU instead of SiLU, an
extra SiLU on the short-conv gate). The GeGLU edit made `llama_init_from_model()` fail
`GGML_ASSERT(ggml_can_repeat(b, a))` and `abort()` **on every launch of the app**, a few seconds after the model had been
copied out of the APK. A native abort cannot be caught or logged by the app, so it only showed up as "Loading Thunder AGI..."
followed by the app closing.

The graph runs *pretrained* weights; changing its maths is not an optimisation, it is a bug. This test makes that kind of
mistake fail in minutes on a CI runner instead of after the APK build, on a phone. Run against the old `lfm2.cpp` it exits
with code 134 and prints the assertion.

* `smoke_test.cpp` - the driver: a fake `JNIEnv` + calls to the app's real JNI functions.
* `make_tiny_lfm2.py` - writes the tiny GGUF files (4 layers: 2 short-conv + 2 attention, 64-wide, byte-level BPE vocab
  with the ChatML control tokens the app's prompt builder uses).
* `shim/` - two tiny host-only headers (`android/log.h`, `amx/amx.h`); nothing here ships in the APK.
* `.github/workflows/engine-smoke-test.yml` runs it on every change to the engine sources.

It is not a quality test: the weights are random, the output is noise. It only proves that the engine loads the
architecture, builds and runs its compute graph, and survives prompts longer than its batch size without aborting.
