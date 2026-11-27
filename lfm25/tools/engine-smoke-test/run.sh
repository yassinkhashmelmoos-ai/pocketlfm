#!/usr/bin/env bash
# Engine smoke test.
#
# Builds the app's own vendored llama.cpp + JNI layer for THIS machine (x86-64 Linux), generates a tiny random-weight
# LFM2 model, and runs the app's real nativeLoadModel / nativeGenerate on it. If the compute graph of the model is
# broken (as the hand-edited models/lfm2.cpp was), the process aborts here instead of on your phone.
#
#   bash lfm25/tools/engine-smoke-test/run.sh
#
# Needs: g++ >= 9, make, python3 with `pip install gguf numpy`, and a JDK (only for the jni.h header).
# Env:   JOBS=<n> parallel compiles | OPT=-O0..-O2 | SANITIZE=1 (ASan+UBSan on the JNI layer) | BUILD_DIR=<dir> | CPP_DIR=<dir>
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CPP="$(cd "${CPP_DIR:-$HERE/../../app/src/main/cpp}" && pwd)"   # CPP_DIR: test another copy of the engine sources
BUILD="${BUILD_DIR:-$HERE/build}"
JOBS="${JOBS:-$(nproc 2>/dev/null || echo 2)}"
OPT="${OPT:--O1}"
SAN=""
[ "${SANITIZE:-0}" = "1" ] && SAN="-fsanitize=address,undefined -fno-omit-frame-pointer"
mkdir -p "$BUILD"

# 1. jni.h - the only thing taken from the JDK; no JVM is started
JNI_INC=""
for d in "${JAVA_HOME:-}" /usr/lib/jvm/*; do
  if [ -n "$d" ] && [ -f "$d/include/jni.h" ] && [ -d "$d/include/linux" ]; then JNI_INC="$d/include"; break; fi
done
if [ -z "$JNI_INC" ]; then
  echo "error: jni.h not found. Install a JDK (sudo apt-get install -y openjdk-17-jdk-headless) or set JAVA_HOME." >&2
  exit 2
fi
python3 -c "import gguf, numpy" 2>/dev/null || { echo "error: run  pip install gguf numpy" >&2; exit 2; }

# 2. engine source list: read from the app's CMakeLists.txt so this builds exactly what the app builds
python3 - "$CPP" "$BUILD/sources.mk" <<'PY'
import glob, os, re, sys
cpp, out = sys.argv[1], sys.argv[2]
text = re.sub(r"#[^\n]*", "", open(os.path.join(cpp, "CMakeLists.txt"), encoding="utf-8").read())
files = []
for name in ("GGML_SOURCES", "GGML_CPU_SOURCES", "LLAMA_SOURCES"):
    m = re.search(r"set\(\s*" + name + r"\b(.*?)\)", text, re.S)
    if not m:
        sys.exit("CMakeLists.txt: set(%s ...) not found - update tools/engine-smoke-test/run.sh" % name)
    files += m.group(1).split()
files += sorted(os.path.relpath(p, cpp) for p in glob.glob(os.path.join(cpp, "models", "*.cpp")))   # file(GLOB MODEL_SOURCES models/*.cpp)
c   = [f for f in files if f.endswith(".c")]
cxx = [f for f in files if f.endswith(".cpp")]
with open(out, "w") as o:
    o.write("C_SRCS := %s\nCXX_SRCS := %s\n" % (" ".join(c), " ".join(cxx)))
print("engine sources (from CMakeLists.txt): %d C + %d C++" % (len(c), len(cxx)))
PY

# 3. build
echo "building with $JOBS parallel jobs ($OPT) ..."
make -f "$HERE/Makefile" -j"$JOBS" BUILD="$BUILD" CPP="$CPP" HERE="$HERE" JNI_INC="$JNI_INC" OPT="$OPT" SAN="$SAN" "$BUILD/smoke_test"

# 4. test models + run
python3 "$HERE/make_tiny_lfm2.py" "$BUILD/tiny_lfm2"
status=0
for m in f32 q8; do
  echo; echo "################ tiny LFM2 ($m) ################"
  ASAN_OPTIONS=detect_leaks=0 "$BUILD/smoke_test" "$BUILD/tiny_lfm2_$m.gguf" 2>"$BUILD/engine_$m.log" || status=$?
  if [ "$status" -ne 0 ]; then
    echo "SMOKE TEST FAILED (exit code $status; 134 = abort(), 139 = segfault). Last engine output:"; tail -15 "$BUILD/engine_$m.log"; exit "$status"
  fi
done
echo; echo "engine smoke test passed"
