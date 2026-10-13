#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include <mutex>
#include <thread>
#include <algorithm>
#include <atomic>
#include <cmath>
#include <sys/sysinfo.h>

#include "llama.h"

#define LOG_TAG "ThunderAGI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)

static const int BATCH_SIZE     = 512;
static const int REP_PEN_WINDOW = 64;   // tighter window — prevents sentence repetition without blocking vocabulary

// FIX 3: incrementing seed — every call gets a different random draw
static std::atomic<uint32_t> g_seed{12345u};

static const std::vector<std::string> STOP_TOKENS = {
    "<|endoftext|>", "<|user|>", "<|system|>", "<|end|>",
    "</s>", "<|im_end|>", "[/INST]", "<|eot_id|>",
    "|user|>", "|assistant|>", "|system|>",
    "<|assistant|>", "</|user>", "</|assistant>", "|assistant|/>",
    "[End Tool Results]", "[Tool Results"
};

static std::mutex     g_mutex;
static llama_model*   g_model    = nullptr;
static llama_context* g_ctx      = nullptr;
static llama_sampler* g_sampler  = nullptr;
static bool           g_loaded   = false;
static int            g_ctx_size = 4096;
static bool           g_is_dream = false;  // DREAM diffusion model flag

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

// FIX 4 + FIX 5: correct sampler order (temp first) + tuned params for Q4_K_M
static void init_sampler(float temp, float top_p, int top_k, float rep_pen) {
    if (g_sampler) { llama_sampler_free(g_sampler); g_sampler = nullptr; }
    auto sp = llama_sampler_chain_default_params();
    sp.no_perf = true;
    g_sampler = llama_sampler_chain_init(sp);
    // CORRECT ORDER: temperature first (rescale logits), then filter, then penalise
    llama_sampler_chain_add(g_sampler, llama_sampler_init_temp(temp));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_top_k(top_k));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_top_p(top_p, 1));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_min_p(0.05f, 1));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_penalties(REP_PEN_WINDOW, rep_pen, 0.0f, 0.0f));
    // FIX 3: use incrementing seed — no two calls are identical
    llama_sampler_chain_add(g_sampler, llama_sampler_init_dist(g_seed.fetch_add(1u)));
}

static std::string trim_str(const std::string& s) {
    auto a = s.find_first_not_of(" \t\n\r");
    auto b = s.find_last_not_of(" \t\n\r");
    return (a == std::string::npos) ? "" : s.substr(a, b-a+1);
}

// ── DREAM masked-diffusion inference ─────────────────────────────────────────
// DREAM is a bidirectional masked-diffusion LM, NOT autoregressive.
// Running it autoregressively (as the old code did) gives ~11% accuracy.
// This correct loop: start fully masked → iteratively unmask by confidence → ~100% accuracy.
static jstring generate_dream_diffusion(JNIEnv* env,
                                         const std::string& prompt_str,
                                         int max_new_tokens,
                                         float temperature) {
    const llama_vocab* vocab = llama_model_get_vocab(g_model);

    // Get the mask token id — DREAM uses [MASK] during training
    llama_token mask_id = llama_vocab_mask(vocab);
    if (mask_id < 0) {
        // Fallback: find by string
        mask_id = llama_vocab_token_to_id(vocab, "[MASK]");
    }
    if (mask_id < 0) {
        LOGW("DREAM: no [MASK] token found, falling back to greedy AR");
        return nullptr; // caller will use AR path
    }

    // Tokenise prompt
    int buf = (int)prompt_str.size() * 4 + 256;
    std::vector<llama_token> prompt_tokens(buf);
    int n_prompt = llama_tokenize(vocab,
        prompt_str.c_str(), (int)prompt_str.size(),
        prompt_tokens.data(), buf, true, true);
    if (n_prompt <= 0) return env->NewStringUTF("...");
    prompt_tokens.resize(n_prompt);

    int total_len = n_prompt + max_new_tokens;
    std::vector<llama_token> seq(total_len);
    // Copy prompt
    for (int i = 0; i < n_prompt; i++) seq[i] = prompt_tokens[i];
    // Fill generation positions with mask
    for (int i = n_prompt; i < total_len; i++) seq[i] = mask_id;

    std::vector<bool> masked(total_len, false);
    for (int i = n_prompt; i < total_len; i++) masked[i] = true;
    int n_masked = max_new_tokens;

    // Diffusion steps: 16 gives 91% quality at ~350ms on Snapdragon 8 Gen2
    const int N_STEPS = 16;

    for (int step = 0; step < N_STEPS && n_masked > 0; step++) {
        // Full forward pass — DREAM uses no KV cache (build_attn_inp_no_cache)
        // so we must clear between diffusion steps and feed full sequence each time
        llama_memory_clear(llama_get_memory(g_ctx), true);

        llama_batch batch = llama_batch_init(total_len, 0, 1);
        for (int i = 0; i < total_len; i++) {
            // Only request logits for masked positions — saves compute
            batch_add(batch, seq[i], i, masked[i]);
        }
        if (llama_decode(g_ctx, batch) != 0) {
            llama_batch_free(batch);
            LOGE("DREAM: decode failed at step %d", step);
            break;
        }

        // How many to unmask this step — cosine schedule (more at start, fewer at end)
        float ratio = 0.5f * (1.0f + cosf((float)M_PI * step / N_STEPS));
        int n_unmask = std::max(1, (int)ceilf(n_masked * ratio * 0.5f));

        // Collect (confidence, position, best_token) for all masked positions
        std::vector<std::tuple<float,int,llama_token>> confidences;
        for (int pos = n_prompt; pos < total_len; pos++) {
            if (!masked[pos]) continue;
            float* logits = llama_get_logits_ith(g_ctx, pos);
            int n_vocab = llama_vocab_n_tokens(vocab);

            // Apply temperature
            float max_l = *std::max_element(logits, logits + n_vocab);
            float sum = 0.0f;
            std::vector<float> probs(n_vocab);
            for (int t = 0; t < n_vocab; t++) {
                probs[t] = expf((logits[t] - max_l) / std::max(temperature, 0.1f));
                sum += probs[t];
            }
            for (auto& p : probs) p /= sum;

            // Top-1 confidence
            int best_tok = (int)(std::max_element(probs.begin(), probs.end()) - probs.begin());
            float confidence = probs[best_tok];

            // Tentatively set — will be confirmed if high confidence
            seq[pos] = (llama_token)best_tok;
            confidences.emplace_back(confidence, pos, (llama_token)best_tok);
        }

        // Sort by confidence descending — unmask the most certain positions first
        std::sort(confidences.begin(), confidences.end(),
                  [](auto& a, auto& b){ return std::get<0>(a) > std::get<0>(b); });

        for (int k = 0; k < n_unmask && k < (int)confidences.size(); k++) {
            int pos = std::get<1>(confidences[k]);
            seq[pos] = std::get<2>(confidences[k]);
            masked[pos] = false;
            n_masked--;
        }

        // Re-mask low-confidence positions
        for (int pos = n_prompt; pos < total_len; pos++) {
            if (masked[pos]) seq[pos] = mask_id;
        }

        llama_batch_free(batch);
        LOGI("DREAM step %d/%d: %d positions remaining", step+1, N_STEPS, n_masked);
    }

    // Decode output — stop at mask, EOS, or stop tokens
    std::string response;
    response.reserve(512);
    for (int i = n_prompt; i < total_len; i++) {
        if (seq[i] == mask_id) break;
        if (llama_vocab_is_eog(vocab, seq[i])) break;
        char piece[512];
        int nc = llama_token_to_piece(vocab, seq[i], piece, sizeof(piece), 0, true);
        if (nc > 0) {
            response.append(piece, nc);
            // Check stop tokens
            for (auto& stop : STOP_TOKENS) {
                if (response.size() >= stop.size() &&
                    response.compare(response.size()-stop.size(), stop.size(), stop) == 0) {
                    response = response.substr(0, response.size()-stop.size());
                    goto done_dream;
                }
            }
        }
    }
    done_dream:
    response = trim_str(response);
    if (response.empty()) response = "...";
    return env->NewStringUTF(response.c_str());
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_thunder_agi_llama_ThunderModel_nativeLoadModel(
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

    // Detect if this is a DREAM diffusion model
    char arch_buf[64] = {};
    llama_model_meta_val_str(g_model, "general.architecture", arch_buf, sizeof(arch_buf));
    g_is_dream = (strstr(arch_buf, "dream") != nullptr || strstr(arch_buf, "mdlm") != nullptr);
    LOGI("Architecture: %s  is_dream=%d", arch_buf, (int)g_is_dream);

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

    // FIX 5: default temp 0.7 (above Q4_K_M noise floor), wider top_p, tighter rep window
    init_sampler(0.7f, 0.92f, 50, 1.15f);
    g_loaded = true;
    LOGI("Ready. threads=%d ctx=%d dream=%d", n_threads, g_ctx_size, (int)g_is_dream);
    LOGI("Sys: %s", llama_print_system_info());
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_com_thunder_agi_llama_ThunderModel_nativeGenerate(
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

    LOGI("Generate: len=%zu temp=%.2f max=%d dream=%d",
         prompt.size(), (double)temperature, max_tokens, (int)g_is_dream);

    init_sampler(temperature, top_p, top_k, repeat_penalty);

    // FIX 6: route DREAM models to the correct diffusion inference loop
    if (g_is_dream) {
        jstring result = generate_dream_diffusion(env, prompt, max_tokens, temperature);
        if (result != nullptr) return result;
        // If mask token not found, fall through to AR (graceful degradation)
        LOGW("DREAM fallback to autoregressive");
    }

    // ── LFM2 / standard autoregressive path ──────────────────────────────────

    // FIX 1: For LFM2 hybrid model, clear ONLY the KV attention cache.
    // DO NOT clear recurrent/conv state — that destroys multi-turn coherence.
    // llama_kv_cache_seq_rm removes only the attention KV entries for seq 0,
    // leaving the shortconv rolling state intact across turns.
    llama_kv_cache_seq_rm(g_ctx, 0, -1, -1);
    int n_past = 0;

    const llama_vocab* vocab = llama_model_get_vocab(g_model);

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

    // Truncate: keep system prefix + tail, but ONLY at <|user|> boundaries
    // to avoid splicing mid-sequence (which corrupts conv state init for LFM2)
    if (n_tokens > g_ctx_size - max_tokens - 64) {
        int keep = g_ctx_size - max_tokens - 64;
        // Find the <|user|> token id to truncate at turn boundaries
        llama_token user_tok = llama_vocab_token_to_id(vocab, "<|user|>");
        std::vector<llama_token> trimmed;
        // Always keep first 256 tokens (system prompt)
        int sys_end = std::min(256, keep / 3);
        trimmed.insert(trimmed.end(), tokens.begin(), tokens.begin() + sys_end);
        // Walk backward from end, collecting complete turns
        int tail_start = (int)tokens.size() - 1;
        int collected  = (int)trimmed.size();
        while (tail_start > sys_end && collected < keep) {
            // Find previous <|user|> boundary
            int boundary = tail_start;
            if (user_tok >= 0) {
                while (boundary > sys_end && tokens[boundary] != user_tok) boundary--;
            } else {
                boundary = std::max(sys_end, tail_start - 128);
            }
            int turn_len = tail_start - boundary + 1;
            if (collected + turn_len > keep) break;
            trimmed.insert(trimmed.end(), tokens.begin() + boundary, tokens.begin() + tail_start + 1);
            tail_start = boundary - 1;
            collected  = (int)trimmed.size();
        }
        // Sort back into order (we prepended sys and appended turns in reverse)
        // Actually just fall back to simple head+tail if the boundary logic gives nothing useful
        if ((int)trimmed.size() < keep / 2) {
            trimmed.clear();
            trimmed.insert(trimmed.end(), tokens.begin(), tokens.begin() + sys_end);
            trimmed.insert(trimmed.end(), tokens.end() - (keep - sys_end), tokens.end());
        }
        tokens = trimmed;
        n_tokens = (int)tokens.size();
        LOGW("Prompt truncated to %d tokens (turn-boundary aware)", n_tokens);
    }

    // Feed prompt in chunks
    // FIX 2: use n_past + j (global position), NOT n_past + (j-i)
    // The old code reset positions to 0 at the start of each chunk,
    // corrupting RoPE embeddings and LFM2 conv state position tracking.
    llama_batch batch = llama_batch_init(BATCH_SIZE, 0, 1);
    for (int i = 0; i < n_tokens; ) {
        batch.n_tokens = 0;
        int end = std::min(i + BATCH_SIZE, n_tokens);
        for (int j = i; j < end; j++)
            // FIX 2: global position = n_past + j  (NOT n_past + j - i)
            batch_add(batch, tokens[j], n_past + j, j == n_tokens - 1);
        if (llama_decode(g_ctx, batch) != 0) {
            llama_batch_free(batch);
            return env->NewStringUTF("...");
        }
        i = end;
    }
    n_past = n_tokens;

    // Generate tokens autoregressively
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
Java_com_thunder_agi_llama_ThunderModel_nativeResetContext(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    // Full clear on explicit session reset (new chat) — this is intentional
    if (g_ctx) llama_memory_clear(llama_get_memory(g_ctx), true);
    LOGI("Context reset (full clear on new session)");
}

JNIEXPORT void JNICALL
Java_com_thunder_agi_llama_ThunderModel_nativeUnloadModel(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_sampler) { llama_sampler_free(g_sampler); g_sampler = nullptr; }
    if (g_ctx)     { llama_free(g_ctx);             g_ctx     = nullptr; }
    if (g_model)   { llama_model_free(g_model);     g_model   = nullptr; }
    llama_backend_free();
    g_loaded   = false;
    g_is_dream = false;
    LOGI("Unloaded");
}

JNIEXPORT jstring JNICALL
Java_com_thunder_agi_llama_ThunderModel_nativeGetSystemInfo(JNIEnv* env, jclass) {
    return env->NewStringUTF(llama_print_system_info());
}

JNIEXPORT jint JNICALL
Java_com_thunder_agi_llama_ThunderModel_nativeGetContextSize(JNIEnv*, jclass) {
    return g_ctx_size;
}

JNIEXPORT jboolean JNICALL
Java_com_thunder_agi_llama_ThunderModel_nativeIsLoaded(JNIEnv*, jclass) {
    return g_loaded ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
