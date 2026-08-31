// JNI bridge to llama.cpp, pinned at v0.3.0 (b10621).
//
// Deliberately thin: load, tokenize, generate, cancel, free. Every decision that
// can be made in Kotlin is made in Kotlin, because this is the layer that has to
// be re-verified by hand whenever the submodule is bumped.
//
// Two things here are load-bearing and easy to get wrong:
//
//   1. Prompt-cache reuse. Re-prefilling an unchanged conversation history costs
//      2-15 seconds on a phone, and it is the single most noticeable latency in
//      the app. We keep the tokens already in the KV cache and only decode the
//      new tail.
//   2. UTF-8 assembly. A token is a byte sequence, not a character. Emitting a
//      partial multi-byte sequence produces replacement characters -- which for
//      Khmer, Chinese or emoji output means visible corruption on almost every
//      token. Incomplete tails are held back until they complete.

#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

#define LOG_TAG "tbchat-llama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// Mirrors StopReason in InferenceEngine.kt. Kept as plain ints across the
// boundary so the enum can be reordered on the Kotlin side without a native
// rebuild breaking silently.
enum StopCode {
    STOP_END_OF_TURN = 0,
    STOP_MAX_TOKENS  = 1,
    STOP_SEQUENCE    = 2,
    STOP_CANCELLED   = 3,
    STOP_CONTEXT_FULL = 4,
    STOP_ERROR       = 5,
};

struct TbSession {
    llama_model   *model = nullptr;
    llama_context *ctx   = nullptr;
    const llama_vocab *vocab = nullptr;

    std::atomic<bool> cancel{false};
    std::mutex        busy;

    // Tokens currently represented in the KV cache for sequence 0.
    std::vector<llama_token> cached;
};

inline TbSession *as_session(jlong handle) {
    return reinterpret_cast<TbSession *>(handle);
}

std::string jstring_to_utf8(JNIEnv *env, jstring value) {
    if (value == nullptr) return {};
    const char *chars = env->GetStringUTFChars(value, nullptr);
    std::string out(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return out;
}

// How many bytes at the end of `buf` form an incomplete UTF-8 sequence.
// Returns 0 when the buffer ends on a character boundary.
size_t incomplete_utf8_tail(const std::string &buf) {
    const size_t n = buf.size();
    // A sequence is at most 4 bytes, so we never need to look further back.
    for (size_t back = 1; back <= 4 && back <= n; ++back) {
        const auto c = static_cast<unsigned char>(buf[n - back]);
        if ((c & 0xC0) == 0x80) continue;  // continuation byte, keep walking back

        size_t expected;
        if ((c & 0x80) == 0x00)      expected = 1;
        else if ((c & 0xE0) == 0xC0) expected = 2;
        else if ((c & 0xF0) == 0xE0) expected = 3;
        else if ((c & 0xF8) == 0xF0) expected = 4;
        else return 0;  // invalid lead byte; emit it and let the decoder cope

        return back < expected ? back : 0;
    }
    return 0;
}

std::string token_to_piece(const llama_vocab *vocab, llama_token token) {
    char buf[256];
    const int32_t n = llama_token_to_piece(vocab, token, buf, sizeof(buf), 0, /*special=*/false);
    if (n < 0) {
        std::string large(static_cast<size_t>(-n), '\0');
        const int32_t m = llama_token_to_piece(vocab, token, large.data(),
                                               static_cast<int32_t>(large.size()), 0, false);
        if (m < 0) return {};
        large.resize(static_cast<size_t>(m));
        return large;
    }
    return std::string(buf, static_cast<size_t>(n));
}

std::vector<llama_token> tokenize(const llama_vocab *vocab, const std::string &text,
                                  bool add_special, bool parse_special) {
    // Negative return is the required capacity, so one speculative call plus at
    // most one exact call.
    int32_t n = llama_tokenize(vocab, text.c_str(), static_cast<int32_t>(text.size()),
                               nullptr, 0, add_special, parse_special);
    if (n == 0) return {};
    std::vector<llama_token> out(static_cast<size_t>(n < 0 ? -n : n));
    const int32_t m = llama_tokenize(vocab, text.c_str(), static_cast<int32_t>(text.size()),
                                     out.data(), static_cast<int32_t>(out.size()),
                                     add_special, parse_special);
    if (m < 0) return {};
    out.resize(static_cast<size_t>(m));
    return out;
}

int64_t now_millis() {
    using namespace std::chrono;
    return duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
}

struct ProgressBridge {
    JNIEnv   *env;
    jobject   callback;
    jmethodID method;
};

bool load_progress(float progress, void *user_data) {
    auto *bridge = static_cast<ProgressBridge *>(user_data);
    if (bridge == nullptr || bridge->callback == nullptr) return true;
    bridge->env->CallVoidMethod(bridge->callback, bridge->method, static_cast<jfloat>(progress));
    if (bridge->env->ExceptionCheck()) {
        bridge->env->ExceptionClear();
        return false;  // a throwing callback aborts the load rather than being swallowed
    }
    return true;
}

void forward_log(ggml_log_level level, const char *text, void *) {
    if (text == nullptr) return;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: LOGE("%s", text); break;
        case GGML_LOG_LEVEL_WARN:  LOGW("%s", text); break;
        default: break;  // llama.cpp is extremely chatty at info level
    }
}

std::atomic<bool> g_backend_ready{false};

}  // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_tannmenghong_tbchat_inference_llamacpp_LlamaNative_nativeInit(JNIEnv *, jobject) {
    bool expected = false;
    if (g_backend_ready.compare_exchange_strong(expected, true)) {
        llama_log_set(forward_log, nullptr);
        llama_backend_init();
        LOGI("llama backend initialised, mmap supported=%d", llama_supports_mmap() ? 1 : 0);
    }
}

JNIEXPORT jlong JNICALL
Java_com_tannmenghong_tbchat_inference_llamacpp_LlamaNative_nativeLoad(
        JNIEnv *env, jobject, jstring model_path, jint n_ctx, jint n_batch, jint n_threads,
        jint kv_type_code, jboolean flash_attn, jint n_gpu_layers, jobject progress_cb) {

    const std::string path = jstring_to_utf8(env, model_path);
    if (path.empty()) {
        LOGE("nativeLoad called with an empty path");
        return 0;
    }

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = n_gpu_layers;

    ProgressBridge bridge{env, nullptr, nullptr};
    if (progress_cb != nullptr) {
        jclass cls = env->GetObjectClass(progress_cb);
        bridge.callback = progress_cb;
        bridge.method = env->GetMethodID(cls, "onLoadProgress", "(F)V");
        if (bridge.method != nullptr) {
            mparams.progress_callback = load_progress;
            mparams.progress_callback_user_data = &bridge;
        }
        env->DeleteLocalRef(cls);
    }

    llama_model *model = llama_model_load_from_file(path.c_str(), mparams);
    if (model == nullptr) {
        LOGE("failed to load model at %s", path.c_str());
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx           = static_cast<uint32_t>(n_ctx);
    cparams.n_batch         = static_cast<uint32_t>(n_batch);
    cparams.n_ubatch        = static_cast<uint32_t>(n_batch);
    cparams.n_threads       = n_threads;
    cparams.n_threads_batch = n_threads;
    cparams.flash_attn_type = flash_attn ? LLAMA_FLASH_ATTN_TYPE_ENABLED
                                         : LLAMA_FLASH_ATTN_TYPE_AUTO;
    cparams.no_perf         = true;

    // Quantising the KV cache roughly halves the memory that context costs, for
    // a quality difference nobody notices in chat. 1 = f16, 8 = q8_0.
    if (kv_type_code == 8) {
        cparams.type_k = GGML_TYPE_Q8_0;
        cparams.type_v = GGML_TYPE_Q8_0;
    }

    llama_context *ctx = llama_init_from_model(model, cparams);
    if (ctx == nullptr) {
        LOGE("failed to create context (n_ctx=%d)", n_ctx);
        llama_model_free(model);
        return 0;
    }

    auto *session = new TbSession();
    session->model = model;
    session->ctx   = ctx;
    session->vocab = llama_model_get_vocab(model);

    LOGI("loaded %s: n_ctx=%u params=%llu", path.c_str(), llama_n_ctx(ctx),
         static_cast<unsigned long long>(llama_model_n_params(model)));
    return reinterpret_cast<jlong>(session);
}

JNIEXPORT void JNICALL
Java_com_tannmenghong_tbchat_inference_llamacpp_LlamaNative_nativeFree(JNIEnv *, jobject, jlong handle) {
    auto *s = as_session(handle);
    if (s == nullptr) return;
    s->cancel.store(true);
    {
        // Wait for any in-flight generation to notice the cancel and return
        // before tearing the context down underneath it.
        std::lock_guard<std::mutex> guard(s->busy);
        if (s->ctx) llama_free(s->ctx);
        if (s->model) llama_model_free(s->model);
        s->ctx = nullptr;
        s->model = nullptr;
    }
    delete s;
}

JNIEXPORT void JNICALL
Java_com_tannmenghong_tbchat_inference_llamacpp_LlamaNative_nativeCancel(JNIEnv *, jobject, jlong handle) {
    auto *s = as_session(handle);
    if (s != nullptr) s->cancel.store(true);
}

JNIEXPORT void JNICALL
Java_com_tannmenghong_tbchat_inference_llamacpp_LlamaNative_nativeResetCache(JNIEnv *, jobject, jlong handle) {
    auto *s = as_session(handle);
    if (s == nullptr || s->ctx == nullptr) return;
    std::lock_guard<std::mutex> guard(s->busy);
    llama_memory_clear(llama_get_memory(s->ctx), true);
    s->cached.clear();
}

JNIEXPORT jint JNICALL
Java_com_tannmenghong_tbchat_inference_llamacpp_LlamaNative_nativeContextSize(JNIEnv *, jobject, jlong handle) {
    auto *s = as_session(handle);
    return (s && s->ctx) ? static_cast<jint>(llama_n_ctx(s->ctx)) : 0;
}

JNIEXPORT jlongArray JNICALL
Java_com_tannmenghong_tbchat_inference_llamacpp_LlamaNative_nativeModelInfo(JNIEnv *env, jobject, jlong handle) {
    auto *s = as_session(handle);
    jlongArray out = env->NewLongArray(6);
    if (s == nullptr || s->model == nullptr) return out;

    jlong values[6] = {
        static_cast<jlong>(llama_model_n_params(s->model)),
        static_cast<jlong>(llama_model_n_layer(s->model)),
        static_cast<jlong>(llama_model_n_head_kv(s->model)),
        static_cast<jlong>(llama_model_n_embd(s->model)),
        static_cast<jlong>(llama_model_n_ctx_train(s->model)),
        static_cast<jlong>(llama_vocab_n_tokens(s->vocab)),
    };
    env->SetLongArrayRegion(out, 0, 6, values);
    return out;
}

JNIEXPORT jstring JNICALL
Java_com_tannmenghong_tbchat_inference_llamacpp_LlamaNative_nativeDescribe(JNIEnv *env, jobject, jlong handle) {
    auto *s = as_session(handle);
    if (s == nullptr || s->model == nullptr) return env->NewStringUTF("");
    char buf[256] = {0};
    llama_model_desc(s->model, buf, sizeof(buf));
    return env->NewStringUTF(buf);
}

JNIEXPORT jint JNICALL
Java_com_tannmenghong_tbchat_inference_llamacpp_LlamaNative_nativeTokenCount(
        JNIEnv *env, jobject, jlong handle, jstring text) {
    auto *s = as_session(handle);
    if (s == nullptr || s->vocab == nullptr) return 0;
    return static_cast<jint>(tokenize(s->vocab, jstring_to_utf8(env, text), false, true).size());
}

/**
 * Renders a conversation with the model's own chat template. Falling back to a
 * generic template when a GGUF carries none is important: an imported model with
 * no template still has to produce something coherent rather than raw text.
 */
JNIEXPORT jstring JNICALL
Java_com_tannmenghong_tbchat_inference_llamacpp_LlamaNative_nativeApplyChatTemplate(
        JNIEnv *env, jobject, jlong handle, jobjectArray roles, jobjectArray contents,
        jboolean add_assistant) {

    auto *s = as_session(handle);
    if (s == nullptr || s->model == nullptr) return env->NewStringUTF("");

    const jsize count = env->GetArrayLength(roles);
    std::vector<std::string> role_store(count), content_store(count);
    std::vector<llama_chat_message> messages(count);

    size_t total_chars = 0;
    for (jsize i = 0; i < count; ++i) {
        auto role = reinterpret_cast<jstring>(env->GetObjectArrayElement(roles, i));
        auto body = reinterpret_cast<jstring>(env->GetObjectArrayElement(contents, i));
        role_store[i]    = jstring_to_utf8(env, role);
        content_store[i] = jstring_to_utf8(env, body);
        total_chars += role_store[i].size() + content_store[i].size();
        messages[i].role    = role_store[i].c_str();
        messages[i].content = content_store[i].c_str();
        env->DeleteLocalRef(role);
        env->DeleteLocalRef(body);
    }

    const char *tmpl = llama_model_chat_template(s->model, nullptr);
    if (tmpl == nullptr) tmpl = "chatml";  // safe generic fallback

    // The docs recommend 2x the total message length; add a floor so short
    // conversations still have room for the template's own markup.
    std::vector<char> buf(total_chars * 2 + 1024);
    int32_t written = llama_chat_apply_template(tmpl, messages.data(), messages.size(),
                                                add_assistant == JNI_TRUE, buf.data(),
                                                static_cast<int32_t>(buf.size()));
    if (written > static_cast<int32_t>(buf.size())) {
        buf.resize(static_cast<size_t>(written) + 1);
        written = llama_chat_apply_template(tmpl, messages.data(), messages.size(),
                                            add_assistant == JNI_TRUE, buf.data(),
                                            static_cast<int32_t>(buf.size()));
    }
    if (written < 0) {
        LOGW("chat template failed, falling back to plain concatenation");
        std::string plain;
        for (jsize i = 0; i < count; ++i) {
            plain += role_store[i] + ": " + content_store[i] + "\n";
        }
        if (add_assistant) plain += "assistant: ";
        return env->NewStringUTF(plain.c_str());
    }
    return env->NewStringUTF(std::string(buf.data(), static_cast<size_t>(written)).c_str());
}

/**
 * The generation loop.
 *
 * Returns a stats array:
 *   [0] prompt tokens        [1] generated tokens
 *   [2] prefill millis       [3] decode millis
 *   [4] first token millis   [5] total millis
 *   [6] context used         [7] context total
 *   [8] stop code
 */
JNIEXPORT jlongArray JNICALL
Java_com_tannmenghong_tbchat_inference_llamacpp_LlamaNative_nativeGenerate(
        JNIEnv *env, jobject, jlong handle, jstring prompt,
        jfloat temperature, jfloat top_p, jint top_k, jfloat min_p, jfloat repeat_penalty,
        jint max_tokens, jlong seed, jobject callback) {

    jlongArray result = env->NewLongArray(9);
    jlong stats[9] = {0, 0, 0, 0, 0, 0, 0, 0, STOP_ERROR};

    auto *s = as_session(handle);
    if (s == nullptr || s->ctx == nullptr) {
        env->SetLongArrayRegion(result, 0, 9, stats);
        return result;
    }

    std::lock_guard<std::mutex> guard(s->busy);
    s->cancel.store(false);

    jclass cb_class = env->GetObjectClass(callback);
    jmethodID on_token   = env->GetMethodID(cb_class, "onToken", "(Ljava/lang/String;)V");
    jmethodID on_prefill = env->GetMethodID(cb_class, "onPrefill", "(II)V");

    const int32_t n_ctx = static_cast<int32_t>(llama_n_ctx(s->ctx));
    const int32_t n_batch = static_cast<int32_t>(llama_n_batch(s->ctx));

    std::vector<llama_token> tokens = tokenize(s->vocab, jstring_to_utf8(env, prompt), true, true);
    stats[0] = static_cast<jlong>(tokens.size());
    stats[7] = n_ctx;

    if (static_cast<int32_t>(tokens.size()) >= n_ctx) {
        stats[8] = STOP_CONTEXT_FULL;
        env->SetLongArrayRegion(result, 0, 9, stats);
        return result;
    }

    const int64_t t_start = now_millis();

    // --- Prompt cache reuse -------------------------------------------------
    // Find how much of the previous prompt this one still shares, keep that
    // much of the KV cache, and only decode the tail. Saves the 2-15 s
    // re-prefill that otherwise happens on every single turn.
    size_t common = 0;
    while (common < s->cached.size() && common < tokens.size() &&
           s->cached[common] == tokens[common]) {
        ++common;
    }
    // Never reuse the entire prompt: at least one token must be decoded to
    // produce logits to sample from.
    if (common == tokens.size() && common > 0) --common;

    llama_memory_t memory = llama_get_memory(s->ctx);
    if (common < s->cached.size()) {
        llama_memory_seq_rm(memory, 0, static_cast<llama_pos>(common), -1);
    }
    s->cached.resize(common);

    const int64_t t_prefill_start = now_millis();
    const int32_t to_process = static_cast<int32_t>(tokens.size() - common);

    for (int32_t offset = 0; offset < to_process; offset += n_batch) {
        if (s->cancel.load()) {
            stats[8] = STOP_CANCELLED;
            stats[5] = now_millis() - t_start;
            env->SetLongArrayRegion(result, 0, 9, stats);
            return result;
        }
        const int32_t chunk = std::min(n_batch, to_process - offset);
        llama_batch batch = llama_batch_get_one(tokens.data() + common + offset, chunk);
        if (llama_decode(s->ctx, batch) != 0) {
            LOGE("prefill decode failed at offset %d", offset);
            stats[8] = STOP_ERROR;
            stats[5] = now_millis() - t_start;
            env->SetLongArrayRegion(result, 0, 9, stats);
            return result;
        }
        if (on_prefill) {
            env->CallVoidMethod(callback, on_prefill, offset + chunk, to_process);
            if (env->ExceptionCheck()) env->ExceptionClear();
        }
    }
    s->cached = tokens;

    const int64_t t_prefill_end = now_millis();
    stats[2] = t_prefill_end - t_prefill_start;

    // --- Sampler chain ------------------------------------------------------
    auto sparams = llama_sampler_chain_default_params();
    sparams.no_perf = true;
    llama_sampler *chain = llama_sampler_chain_init(sparams);

    llama_sampler_chain_add(chain, llama_sampler_init_penalties(
            llama_vocab_n_tokens(s->vocab), 64, repeat_penalty, 0.0f, 0.0f));

    if (temperature <= 0.0f) {
        // Greedy: no point paying for the truncation samplers.
        llama_sampler_chain_add(chain, llama_sampler_init_greedy());
    } else {
        if (top_k > 0)  llama_sampler_chain_add(chain, llama_sampler_init_top_k(top_k));
        if (top_p < 1.0f) llama_sampler_chain_add(chain, llama_sampler_init_top_p(top_p, 1));
        if (min_p > 0.0f) llama_sampler_chain_add(chain, llama_sampler_init_min_p(min_p, 1));
        llama_sampler_chain_add(chain, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(chain, llama_sampler_init_dist(
                seed < 0 ? LLAMA_DEFAULT_SEED : static_cast<uint32_t>(seed)));
    }

    // --- Decode loop --------------------------------------------------------
    std::string pending;   // holds an incomplete UTF-8 tail between tokens
    int32_t generated = 0;
    int64_t t_first_token = 0;
    int stop_code = STOP_MAX_TOKENS;

    while (generated < max_tokens) {
        if (s->cancel.load()) { stop_code = STOP_CANCELLED; break; }

        if (static_cast<int32_t>(s->cached.size()) >= n_ctx) {
            stop_code = STOP_CONTEXT_FULL;
            break;
        }

        const llama_token token = llama_sampler_sample(chain, s->ctx, -1);
        llama_sampler_accept(chain, token);

        if (llama_vocab_is_eog(s->vocab, token)) { stop_code = STOP_END_OF_TURN; break; }

        if (generated == 0) t_first_token = now_millis() - t_start;
        ++generated;

        pending += token_to_piece(s->vocab, token);
        const size_t tail = incomplete_utf8_tail(pending);
        if (tail < pending.size()) {
            const std::string emit = pending.substr(0, pending.size() - tail);
            pending = pending.substr(pending.size() - tail);
            jstring piece = env->NewStringUTF(emit.c_str());
            env->CallVoidMethod(callback, on_token, piece);
            env->DeleteLocalRef(piece);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                stop_code = STOP_CANCELLED;
                break;
            }
        }

        s->cached.push_back(token);
        llama_batch batch = llama_batch_get_one(&s->cached.back(), 1);
        if (llama_decode(s->ctx, batch) != 0) {
            LOGE("decode failed after %d generated tokens", generated);
            stop_code = STOP_ERROR;
            break;
        }
    }

    // Anything still buffered is either a complete character we have not sent or
    // a genuinely truncated sequence; either way the user should see it.
    if (!pending.empty()) {
        jstring piece = env->NewStringUTF(pending.c_str());
        env->CallVoidMethod(callback, on_token, piece);
        env->DeleteLocalRef(piece);
        if (env->ExceptionCheck()) env->ExceptionClear();
    }

    llama_sampler_free(chain);
    env->DeleteLocalRef(cb_class);

    const int64_t t_end = now_millis();
    stats[1] = generated;
    stats[3] = t_end - t_prefill_end;
    stats[4] = t_first_token;
    stats[5] = t_end - t_start;
    stats[6] = static_cast<jlong>(s->cached.size());
    stats[8] = stop_code;

    env->SetLongArrayRegion(result, 0, 9, stats);
    return result;
}

}  // extern "C"
