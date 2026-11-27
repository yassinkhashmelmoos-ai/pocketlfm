// Engine smoke test: runs the app's REAL JNI entry points (llama-jni.cpp) on a desktop / CI runner, with a fake JNIEnv
// and a tiny random-weight LFM2 model (make_tiny_lfm2.py).
//
// Why it exists: a hand-edit of models/lfm2.cpp ("GeGLU instead of SiLU") made llama_init_from_model() hit
// GGML_ASSERT(ggml_can_repeat(b, a)) -> abort() on every launch of the app, right after the model copy. A native abort
// cannot be caught or logged by the app, and finding it took a multi-hour CI build + a phone. This test finds the same
// class of bug in a couple of minutes: if the compute graph of the model is broken, this process aborts (CI turns red).
//
// Usage: smoke_test <tiny_model.gguf>
#include <jni.h>

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <string>
#include <vector>
#include <unistd.h>

extern "C" {
JNIEXPORT jint    JNICALL Java_com_thunder_agi_llama_ThunderModel_nativeLoadModel(JNIEnv*, jclass, jstring, jint, jstring);
JNIEXPORT jstring JNICALL Java_com_thunder_agi_llama_ThunderModel_nativeGenerate(JNIEnv*, jclass, jstring, jfloat, jfloat, jint, jfloat, jint);
JNIEXPORT void    JNICALL Java_com_thunder_agi_llama_ThunderModel_nativeResetContext(JNIEnv*, jclass);
JNIEXPORT void    JNICALL Java_com_thunder_agi_llama_ThunderModel_nativeUnloadModel(JNIEnv*, jclass);
}

// ── fake JNI: the engine only uses GetStringUTFChars / ReleaseStringUTFChars / NewStringUTF ───────────────────────
struct FakeStr { std::string s; };
static const char* JNICALL f_GetStringUTFChars(JNIEnv*, jstring s, jboolean* isCopy) {
    if (isCopy) *isCopy = JNI_FALSE;
    return s ? reinterpret_cast<FakeStr*>(s)->s.c_str() : nullptr;
}
static void JNICALL f_ReleaseStringUTFChars(JNIEnv*, jstring, const char*) {}
static jstring JNICALL f_NewStringUTF(JNIEnv*, const char* utf) { return reinterpret_cast<jstring>(new FakeStr{utf ? utf : ""}); }

static JNINativeInterface_ g_table;
static JNIEnv g_env;
static jstring J(const std::string& s) { return reinterpret_cast<jstring>(new FakeStr{s}); }
static std::string C(jstring s) { return s ? reinterpret_cast<FakeStr*>(s)->s : std::string(); }

static int g_fail = 0;
#define CHECK(cond, ...) do { const bool ok_ = (cond); std::printf("  %s  ", ok_ ? "PASS" : "FAIL"); std::printf(__VA_ARGS__); std::printf("\n"); if (!ok_) ++g_fail; } while (0)

static jint load(const std::string& model, int profile, const std::string& trace) {
    return Java_com_thunder_agi_llama_ThunderModel_nativeLoadModel(&g_env, nullptr, J(model), profile, J(trace));
}
static std::string gen(const std::string& prompt, int max_tokens) {
    return C(Java_com_thunder_agi_llama_ThunderModel_nativeGenerate(&g_env, nullptr, J(prompt), 0.7f, 0.9f, 40, 1.1f, max_tokens));
}
// ChatML prompt of roughly n tokens (the tiny byte-level vocab makes ~1 token per character)
static std::string chat(int n) {
    std::string filler; while ((int)filler.size() < n) filler += "the cat sat on the mat ";
    return "<|im_start|>system\nYou are a test.<|im_end|>\n<|im_start|>user\n" + filler.substr(0, n) + "<|im_end|>\n<|im_start|>assistant\n";
}
static std::vector<std::string> lines(const std::string& path) {
    std::vector<std::string> v; std::ifstream f(path); std::string l; while (std::getline(f, l)) v.push_back(l); return v;
}
static bool has(const std::vector<std::string>& t, const char* needle) {
    for (auto& l : t) {
        if (l.find(needle) != std::string::npos) return true;
    }
    return false;
}

int main(int argc, char** argv) {
    if (argc < 2) { std::fprintf(stderr, "usage: %s tiny_model.gguf\n", argv[0]); return 64; }
    const std::string model = argv[1];
    std::memset(&g_table, 0, sizeof g_table);
    g_table.GetStringUTFChars = f_GetStringUTFChars;
    g_table.ReleaseStringUTFChars = f_ReleaseStringUTFChars;
    g_table.NewStringUTF = f_NewStringUTF;
    g_env.functions = &g_table;

    char dir[] = "/tmp/engine-smoke-XXXXXX"; if (!mkdtemp(dir)) { std::perror("mkdtemp"); return 65; }
    const std::string trace = std::string(dir) + "/engine_trace.log";

    std::printf("== failure paths: clean error codes, no abort, engine left usable ==\n");
    CHECK(load("/does/not/exist.gguf", 0, trace) == 1, "missing file -> LOAD_MODEL_FAILED");
    { std::string junk = std::string(dir) + "/junk.gguf"; std::ofstream(junk) << "not a gguf file"; CHECK(load(junk, 0, trace) == 1, "garbage file -> LOAD_MODEL_FAILED"); }
    { std::ifstream in(model, std::ios::binary); std::string all((std::istreambuf_iterator<char>(in)), {});
      std::string tr = std::string(dir) + "/trunc.gguf"; std::ofstream(tr, std::ios::binary).write(all.data(), all.size() / 2);
      CHECK(load(tr, 0, trace) == 1, "TRUNCATED model (half the bytes) -> LOAD_MODEL_FAILED"); }

    for (int profile = 0; profile <= 2; ++profile) {
        std::printf("== load profile %d ==\n", profile);
        std::remove(trace.c_str());
        // THIS is the call that used to abort(): it builds and reserves the compute graph of the model.
        CHECK(load(model, profile, trace) == 0, "nativeLoadModel -> LOAD_OK (a broken graph would abort the process here)");
        auto t = lines(trace);
        for (const char* stage : {"backend ready", "model loaded", "context create begin", "context created"})
            CHECK(has(t, stage), "breadcrumb '%s'", stage);
        CHECK(!t.empty() && t.back().size() >= 6 && t.back().compare(t.back().size() - 6, 6, " ready") == 0, "last breadcrumb = ready");
        CHECK(load(model, profile, trace) == 0, "a second load is a harmless no-op");

        std::string out = gen(chat(60), 16);
        CHECK(out.rfind("Error", 0) != 0, "generate works (%zu chars)", out.size());
        std::string big = gen(chat(700), 8);            // multi-chunk prompt: chunk size must never exceed n_batch
        CHECK(big.rfind("Error", 0) != 0, "700-token prompt works with this profile's batch size");
        if (profile == 2) { std::string huge = gen(chat(3000), 8); CHECK(huge.rfind("Error", 0) != 0, "3000-token prompt on a 1024 context (truncation path)"); }
        Java_com_thunder_agi_llama_ThunderModel_nativeResetContext(&g_env, nullptr);
        CHECK(gen(chat(60), 8).rfind("Error", 0) != 0, "generate works after a context reset");
        Java_com_thunder_agi_llama_ThunderModel_nativeUnloadModel(&g_env, nullptr);
    }
    std::printf("== generate with nothing loaded is a clean error ==\n");
    CHECK(gen("hello", 4).rfind("Error", 0) == 0, "nativeGenerate before load -> 'Error: ...'");

    std::printf("\nRESULT: %s\n", g_fail == 0 ? "ALL PASSED" : "FAILURES");
    return g_fail == 0 ? 0 : 1;
}
