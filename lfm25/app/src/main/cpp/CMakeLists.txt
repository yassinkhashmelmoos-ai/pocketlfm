cmake_minimum_required(VERSION 3.22.1)
project("thunder-agi")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Safe flags — no -ffast-math, no -ffinite-math-only (ggml forbids it)
set(CMAKE_C_FLAGS   "${CMAKE_C_FLAGS}   -O3 -DNDEBUG -fno-finite-math-only")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -DNDEBUG -fno-finite-math-only -fPIC")

add_compile_definitions(
    GGML_VERSION="b4351"
    GGML_COMMIT="unknown"
)

include_directories(${CMAKE_CURRENT_SOURCE_DIR})
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/ggml-cpu)

# GGML core sources
set(GGML_SOURCES
    ggml.c
    ggml-alloc.c
    ggml-backend.cpp
    ggml-backend-reg.cpp
    ggml-backend-dl.cpp
    ggml-quants.c
    ggml-opt.cpp
    ggml-threading.cpp
    gguf.cpp
)

# CPU backend sources
set(GGML_CPU_SOURCES
    ggml-cpu/ggml-cpu.c
    ggml-cpu/ggml-cpu.cpp
    ggml-cpu/ops.cpp
    ggml-cpu/vec.cpp
    ggml-cpu/traits.cpp
    ggml-cpu/repack.cpp
    ggml-cpu/unary-ops.cpp
    ggml-cpu/binary-ops.cpp
    ggml-cpu/quants.c
)

file(GLOB MODEL_SOURCES models/*.cpp)

set(LLAMA_SOURCES
    llama.cpp llama-adapter.cpp llama-arch.cpp llama-batch.cpp
    llama-chat.cpp llama-context.cpp llama-cparams.cpp
    llama-grammar.cpp llama-graph.cpp llama-hparams.cpp
    llama-impl.cpp llama-io.cpp llama-kv-cache.cpp
    llama-kv-cache-iswa.cpp llama-memory.cpp llama-memory-hybrid.cpp
    llama-memory-hybrid-iswa.cpp llama-memory-recurrent.cpp
    llama-mmap.cpp llama-model.cpp llama-model-loader.cpp
    llama-model-saver.cpp llama-quant.cpp llama-sampling.cpp
    llama-vocab.cpp unicode.cpp unicode-data.cpp
)

add_library(ggml  STATIC ${GGML_SOURCES} ${GGML_CPU_SOURCES})
add_library(llama STATIC ${LLAMA_SOURCES} ${MODEL_SOURCES})

# GGML_CPU_GENERIC ensures all quant dot-product functions are compiled in
target_compile_definitions(ggml  PUBLIC GGML_USE_CPU GGML_BUILD GGML_CPU_GENERIC)
target_compile_definitions(llama PUBLIC GGML_USE_CPU)

# ARM NEON — use armv8-a (safe for all Android phones since 2014)
# armv8.2-a caused missing quant symbols; keep it simple and reliable
if(ANDROID_ABI STREQUAL "arm64-v8a")
    target_compile_options(ggml  PRIVATE -march=armv8-a -O3 -D__ARM_NEON)
    target_compile_options(llama PRIVATE -march=armv8-a -O3 -D__ARM_NEON)
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    target_compile_options(ggml  PRIVATE -mfloat-abi=softfp -mfpu=neon -O3 -D__ARM_NEON)
    target_compile_options(llama PRIVATE -mfloat-abi=softfp -mfpu=neon -O3 -D__ARM_NEON)
endif()

target_link_libraries(llama ggml)

add_library(thunder-engine SHARED llama-jni.cpp chibi_scheme_jni.cpp)
target_link_libraries(thunder-engine llama ggml android log dl)
target_include_directories(thunder-engine PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
