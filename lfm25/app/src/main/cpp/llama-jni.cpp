#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include <mutex>
#include <thread>
#include <algorithm>
#include <sys/sysinfo.h>

#include "llama.h"

#define LOG_TAG "ThunderAGI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)

static const int    BATCH_SIZE      = 512;
static const int    REP_PEN_WINDOW  = 512;   // larger window = less repetition

// CRITICAL FIX: We do NOT use a persistent KV cache across turns.
// The ViewModel already builds the full conversation prompt including history.
// Persistent KV cache was causing the repetition bug because old tokens
// from previous sessions contaminated the next generation.
// Instead: clear KV cache on every generate() call, feed full prompt fresh.
// This is slightly slower but produces correct multi-turn responses.
static const bool   FRESH_CONTEXT_PER_CALL = true;

static const std::vector<std::string> STOP_TOKENS = {
    "<|endoftext|>", "<|user|>", "<|system|>", "<|end|>",
    "</s>", "<|im_end|>", "[/INST]", "<|eot_id|>",
    "|user|>", "|assistant|>", "|system|>",
    "<|assistant|>", "</|user>", "</|assistant>", "|assistant|/>"
};

static std::mutex     g_mutex;
static llama_model*   g_model   = nullptr;
static llama_context* g_ctx     = nullptr;
static llama_sampler* g_sampler = nullptr;
static bool           g_loaded  = false;
static int            g_ctx_size = 4096;

static int detect_threads() {
    return std::min(std::max((int)std::thread::hardware_concurrency(), 2), 8);
}

static long get_free_ram_mb() {
    struct sysinfo info;
    if (sysinfo(&info) == 0) return (info.freeram * info.mem_unit) / (1024*1024);
    return 2048;
}

static void batch_add(llama_batch& b, llama_token tok, int32_t pos, bool logits) {
    b.token[b.n_tokens]     = tok;
    b.pos[b.n_tokens]       = pos;
    b.n_seq_id[b.n_tokens]  = 1;
    b.seq_id[b.n_tokens][0] = 0;
    b.logits[b.n_tokens]    = logits ? 1 : 0;
    b.n_tokens++;
}

static void init_sampler(float temp, float top_p, int top_k, float rep_pen) {
    if (g_sampler) { llama_sampler_free(g_sampler); g_sampler = nullptr; }
    auto sp = llama_sampler_chain_default_params();
    sp.no_perf = true;
    g_sampler = llama_sampler_chain_init(sp);
    // Large repetition penalty window to catch full-sentence repetition
    llama_sampler_chain_add(g_sampler, llama_sampler_init_penalties(REP_PEN_WINDOW, rep_pen, 0.0f, 0.0f));
    // Min-P: removes tokens below 5% of top token — improves coherence
    llama_sampler_chain_add(g_sampler, llama_sampler_init_min_p(0.05f, 1));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_top_k(top_k));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_top_p(top_p, 1));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_temp(temp));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
}

static std::string trim_str(const std::string& s) {
    auto a = s.find_first_not_of(" \t\n\r");
    auto b = s.find_last_not_of(" \t\n\r");
    return (a == std::string::npos) ? "" : s.substr(a, b-a+1);
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_example_thunder_agi_llama_ThunderModel_nativeLoadModel(
        JNIEnv* env, jclass, jstring model_path) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_loaded) { LOGI("Already loaded"); return JNI_TRUE; }

    const char* path = env->GetStringUTFChars(model_path, nullptr);
    LOGI("Loading: %s", path);
    llama_backend_init();

    long free_mb = get_free_ram_mb();
    if      (free_mb > 4000) g_ctx_size = 8192;
    else if (free_mb > 2000) g_ctx_size = 4096;
    else                     g_ctx_size = 2048;
    LOGI("RAM: %ld MB → ctx: %d", free_mb, g_ctx_size);

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    mp.use_mmap  = true;
    mp.use_mlock = false;

    g_model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(model_path, path);
    if (!g_model) { LOGE("Failed to load"); llama_backend_free(); return JNI_FALSE; }

    int n_threads = detect_threads();
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx           = g_ctx_size;
    cp.n_batch         = BATCH_SIZE;
    cp.n_ubatch        = BATCH_SIZE;
    cp.n_threads       = n_threads;
    cp.n_threads_batch = n_threads;
    cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;
    cp.offload_kqv     = false;
    cp.type_k = GGML_TYPE_F16;
    cp.type_v = GGML_TYPE_F16;

    g_ctx = llama_init_from_model(g_model, cp);
    if (!g_ctx) {
        llama_model_free(g_model); g_model = nullptr;
        llama_backend_free(); return JNI_FALSE;
    }

    init_sampler(0.4f, 0.92f, 50, 1.2f);
    g_loaded = true;
    LOGI("Ready. threads=%d ctx=%d", n_threads, g_ctx_size);
    LOGI("Sys: %s", llama_print_system_info());
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_com_example_thunder_agi_llama_ThunderModel_nativeGenerate(
        JNIEnv* env, jclass,
        jstring j_prompt,
        jfloat temperature, jfloat top_p, jint top_k,
        jfloat repeat_penalty, jint max_tokens) {

    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_loaded || !g_ctx) return env->NewStringUTF("Error: model not loaded.");

    const char* raw = env->GetStringUTFChars(j_prompt, nullptr);
    std::string prompt(raw);
    env->ReleaseStringUTFChars(j_prompt, raw);
    if (prompt.empty()) return env->NewStringUTF("");

    LOGI("Generate: len=%zu temp=%.2f max=%d", prompt.size(), temperature, max_tokens);

    // CRITICAL FIX: Always start fresh — clear KV cache before every generation.
    // The ViewModel sends the complete conversation history in the prompt,
    // so we don't need incremental KV cache. Fresh context = no cross-turn pollution.
    llama_memory_clear(llama_get_memory(g_ctx), true);
    int n_past = 0;

    const llama_vocab* vocab = llama_model_get_vocab(g_model);

    // Tokenise with generous buffer for Arabic/multilingual content
    int buf = (int)prompt.size() * 4 + 256;
    std::vector<llama_token> tokens(buf);
    int n_tokens = llama_tokenize(vocab,
        prompt.c_str(), (int)prompt.size(),
        tokens.data(), buf, true, true);

    if (n_tokens < 0) {
        tokens.resize(-n_tokens + 8);
        n_tokens = llama_tokenize(vocab,
            prompt.c_str(), (int)prompt.size(),
            tokens.data(), (int)tokens.size(), true, true);
    }
    if (n_tokens <= 0) return env->NewStringUTF("...");
    tokens.resize(n_tokens);

    // Truncate if prompt exceeds context
    if (n_tokens > g_ctx_size - max_tokens - 64) {
        int keep = g_ctx_size - max_tokens - 64;
        // Keep the first 256 tokens (system prompt) + last (keep-256) tokens
        std::vector<llama_token> trimmed;
        trimmed.insert(trimmed.end(), tokens.begin(), tokens.begin() + std::min(256, keep/2));
        trimmed.insert(trimmed.end(), tokens.end() - (keep - trimmed.size()), tokens.end());
        tokens = trimmed;
        n_tokens = (int)tokens.size();
        LOGW("Prompt truncated to %d tokens", n_tokens);
    }

    init_sampler(temperature, top_p, top_k, repeat_penalty);

    // Feed prompt in chunks
    llama_batch batch = llama_batch_init(BATCH_SIZE, 0, 1);
    for (int i = 0; i < n_tokens; ) {
        batch.n_tokens = 0;
        int end = std::min(i + BATCH_SIZE, n_tokens);
        for (int j = i; j < end; j++)
            batch_add(batch, tokens[j], n_past + (j-i), j == n_tokens-1);
        if (llama_decode(g_ctx, batch) != 0) {
            llama_batch_free(batch);
            return env->NewStringUTF("...");
        }
        i = end;
    }
    n_past = n_tokens;

    // Generate
    std::string response;
    response.reserve(512);

    for (int i = 0; i < max_tokens; i++) {
        llama_token tok = llama_sampler_sample(g_sampler, g_ctx, -1);
        llama_sampler_accept(g_sampler, tok);
        if (llama_vocab_is_eog(vocab, tok)) break;

        char piece[512];
        int nc = llama_token_to_piece(vocab, tok, piece, sizeof(piece), 0, true);
        if (nc > 0) {
            response.append(piece, nc);
            bool stopped = false;
            for (auto& stop : STOP_TOKENS) {
                if (response.size() >= stop.size() &&
                    response.compare(response.size()-stop.size(), stop.size(), stop) == 0) {
                    response = response.substr(0, response.size()-stop.size());
                    stopped = true; break;
                }
            }
            if (stopped) break;
        }

        batch.n_tokens = 0;
        batch_add(batch, tok, n_past, true);
        if (llama_decode(g_ctx, batch) != 0) break;
        n_past++;
    }

    llama_batch_free(batch);
    response = trim_str(response);
    if (response.empty()) response = "...";
    LOGI("Done: %zu chars", response.size());
    return env->NewStringUTF(response.c_str());
}

JNIEXPORT void JNICALL
Java_com_example_thunder_agi_llama_ThunderModel_nativeResetContext(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_ctx) llama_memory_clear(llama_get_memory(g_ctx), true);
    LOGI("Context reset");
}

JNIEXPORT void JNICALL
Java_com_example_thunder_agi_llama_ThunderModel_nativeUnloadModel(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_sampler) { llama_sampler_free(g_sampler); g_sampler = nullptr; }
    if (g_ctx)     { llama_free(g_ctx);             g_ctx     = nullptr; }
    if (g_model)   { llama_model_free(g_model);     g_model   = nullptr; }
    llama_backend_free();
    g_loaded = false;
    LOGI("Unloaded");
}

JNIEXPORT jstring JNICALL
Java_com_example_thunder_agi_llama_ThunderModel_nativeGetSystemInfo(JNIEnv* env, jclass) {
    return env->NewStringUTF(llama_print_system_info());
}

JNIEXPORT jint JNICALL
Java_com_example_thunder_agi_llama_ThunderModel_nativeGetContextSize(JNIEnv*, jclass) {
    return g_ctx_size;
}

JNIEXPORT jboolean JNICALL
Java_com_example_thunder_agi_llama_ThunderModel_nativeIsLoaded(JNIEnv*, jclass) {
    return g_loaded ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
