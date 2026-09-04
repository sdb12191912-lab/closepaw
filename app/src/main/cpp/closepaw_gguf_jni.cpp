#include <jni.h>
#include <string>
#include <vector>
#include <atomic>
#include <cstring>
#include <chrono>
#include <sstream>
#include <iomanip>
#include <android/log.h>
#include "llama.h"

#define LOG_TAG "GgufNativeBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

struct GgufModelHandle {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    std::atomic<bool> stop_requested{false};

    long long load_ms = 0;
    long long prompt_ms = 0;
    long long ttft_ms = -1;
    long long total_ms = 0;
    int prompt_tokens = 0;
    int generated_tokens = 0;
    double prompt_tps = 0.0;
    double generation_tps = 0.0;
    int gpu_layers_requested = 999;
    bool offload_kqv_requested = true;
    bool flash_attn_requested = true;
};

static std::string metrics_json(const GgufModelHandle *handle) {
    if (!handle) {
        return "{}";
    }
    std::ostringstream ss;
    ss << "{";
    ss << "\"load_ms\":" << handle->load_ms << ",";
    ss << "\"prompt_ms\":" << handle->prompt_ms << ",";
    ss << "\"ttft_ms\":" << handle->ttft_ms << ",";
    ss << "\"total_ms\":" << handle->total_ms << ",";
    ss << "\"prompt_tokens\":" << handle->prompt_tokens << ",";
    ss << "\"generated_tokens\":" << handle->generated_tokens << ",";
    ss << "\"prompt_tps\":" << std::fixed << std::setprecision(4) << handle->prompt_tps << ",";
    ss << "\"generation_tps\":" << std::fixed << std::setprecision(4) << handle->generation_tps << ",";
    ss << "\"gpu_layers_requested\":" << handle->gpu_layers_requested << ",";
    ss << "\"offload_kqv_requested\":" << (handle->offload_kqv_requested ? "true" : "false") << ",";
    ss << "\"flash_attn_requested\":" << (handle->flash_attn_requested ? "true" : "false") << ",";
    ss << "\"backend_note\":\"Requested GPU offload is shown here. Exact tensors/layers accepted by Vulkan must be confirmed from llama.cpp backend logs on this pinned build.\"";
    ss << "}";
    return ss.str();
}

extern "C" {

JNIEXPORT jstring JNICALL
Java_ai_closepaw_llm_gguf_GgufNativeBridge_getLlamaVersion(
        JNIEnv *env,
        jobject /* this */) {
    const char *sys_info = llama_print_system_info();
    std::string info = sys_info ? sys_info : "llama.cpp (system info unavailable)";
    return env->NewStringUTF(info.c_str());
}

JNIEXPORT void JNICALL
Java_ai_closepaw_llm_gguf_GgufNativeBridge_initBackend(
        JNIEnv * /* env */,
        jobject /* this */) {
    LOGI("Initializing llama.cpp backend");
    llama_backend_init();
}

JNIEXPORT void JNICALL
Java_ai_closepaw_llm_gguf_GgufNativeBridge_freeBackend(
        JNIEnv * /* env */,
        jobject /* this */) {
    LOGI("Freeing llama.cpp backend");
    llama_backend_free();
}

JNIEXPORT jlong JNICALL
Java_ai_closepaw_llm_gguf_GgufNativeBridge_nativeLoadModelFromFilePath(
        JNIEnv *env,
        jobject /* this */,
        jstring filePathStr,
        jint contextLength,
        jint threads) {
    if (!filePathStr) {
        LOGE("LoadModel failed: filePathStr is null");
        return 0L;
    }

    const char *filePath = env->GetStringUTFChars(filePathStr, nullptr);
    if (!filePath) {
        LOGE("LoadModel failed: GetStringUTFChars returned null");
        return 0L;
    }

    LOGI("Loading model from path: %s (contextLength=%d, threads=%d)", filePath, contextLength, threads);

    const auto load_start = std::chrono::steady_clock::now();

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 999;
    mparams.main_gpu = 0;

    LOGI("Model params: n_gpu_layers=%d main_gpu=%d", mparams.n_gpu_layers, mparams.main_gpu);

    llama_model *model = llama_model_load_from_file(filePath, mparams);

    env->ReleaseStringUTFChars(filePathStr, filePath);

    if (!model) {
        LOGE("llama_model_load_from_file failed with GPU offload enabled");
        return 0L;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = contextLength > 0 ? (uint32_t)contextLength : 2048;
    cparams.n_threads = threads > 0 ? threads : 4;
    cparams.n_threads_batch = cparams.n_threads;
    cparams.offload_kqv = true;
    cparams.flash_attn = true;

    LOGI("Context params: n_ctx=%u n_threads=%d n_threads_batch=%d offload_kqv=%d flash_attn=%d",
         cparams.n_ctx,
         cparams.n_threads,
         cparams.n_threads_batch,
         cparams.offload_kqv ? 1 : 0,
         cparams.flash_attn ? 1 : 0);

    llama_context *ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        LOGE("llama_init_from_model failed");
        llama_model_free(model);
        return 0L;
    }

    const llama_vocab *vocab = llama_model_get_vocab(model);
    if (!vocab) {
        LOGE("llama_model_get_vocab failed");
        llama_free(ctx);
        llama_model_free(model);
        return 0L;
    }

    GgufModelHandle *handle = new GgufModelHandle();
    handle->model = model;
    handle->ctx = ctx;
    handle->vocab = vocab;
    handle->stop_requested = false;
    handle->gpu_layers_requested = mparams.n_gpu_layers;
    handle->offload_kqv_requested = cparams.offload_kqv;
    handle->flash_attn_requested = cparams.flash_attn;

    const auto load_end = std::chrono::steady_clock::now();
    handle->load_ms = std::chrono::duration_cast<std::chrono::milliseconds>(load_end - load_start).count();
    LOGI("Successfully loaded GGUF model and initialized context in %lld ms", handle->load_ms);
    return reinterpret_cast<jlong>(handle);
}

JNIEXPORT jstring JNICALL
Java_ai_closepaw_llm_gguf_GgufNativeBridge_nativeGenerate(
        JNIEnv *env,
        jobject /* this */,
        jlong handlePtr,
        jstring promptStr,
        jint maxTokens) {
    GgufModelHandle *handle = reinterpret_cast<GgufModelHandle *>(handlePtr);
    if (!handle || !handle->model || !handle->ctx || !handle->vocab) {
        LOGE("Generate failed: invalid model handle");
        return env->NewStringUTF("[Error: Model not loaded]");
    }

    if (!promptStr) {
        return env->NewStringUTF("");
    }

    const char *prompt = env->GetStringUTFChars(promptStr, nullptr);
    if (!prompt) {
        return env->NewStringUTF("");
    }

    handle->stop_requested = false;
    handle->prompt_ms = 0;
    handle->ttft_ms = -1;
    handle->total_ms = 0;
    handle->prompt_tokens = 0;
    handle->generated_tokens = 0;
    handle->prompt_tps = 0.0;
    handle->generation_tps = 0.0;

    int prompt_len = strlen(prompt);
    int n_prompt_max = prompt_len + 512;
    std::vector<llama_token> prompt_tokens(n_prompt_max);

    int n_prompt = llama_tokenize(
            handle->vocab,
            prompt,
            prompt_len,
            prompt_tokens.data(),
            prompt_tokens.size(),
            true,
            true
    );

    if (n_prompt < 0) {
        prompt_tokens.resize(-n_prompt);
        n_prompt = llama_tokenize(
                handle->vocab,
                prompt,
                prompt_len,
                prompt_tokens.data(),
                prompt_tokens.size(),
                true,
                true
        );
    }

    env->ReleaseStringUTFChars(promptStr, prompt);

    if (n_prompt < 0) {
        LOGE("llama_tokenize failed");
        return env->NewStringUTF("[Error: Tokenization failed]");
    }

    handle->prompt_tokens = n_prompt;

    const auto generation_start = std::chrono::steady_clock::now();
    const auto prompt_decode_start = std::chrono::steady_clock::now();

    llama_batch batch = llama_batch_get_one(prompt_tokens.data(), n_prompt);
    if (llama_decode(handle->ctx, batch) != 0) {
        LOGE("llama_decode prompt failed");
        return env->NewStringUTF("[Error: Prompt decode failed]");
    }

    const auto prompt_decode_end = std::chrono::steady_clock::now();
    handle->prompt_ms = std::chrono::duration_cast<std::chrono::milliseconds>(prompt_decode_end - prompt_decode_start).count();
    handle->prompt_tps = handle->prompt_ms > 0 ? (1000.0 * n_prompt / (double) handle->prompt_ms) : 0.0;
    LOGI("Prompt decode: tokens=%d elapsed_ms=%lld tok_s=%.2f", n_prompt, handle->prompt_ms, handle->prompt_tps);

    auto sparams = llama_sampler_chain_default_params();
    llama_sampler *smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.95f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(1234));

    std::string output_text;
    int n_tokens_generated = 0;
    int max_gen = maxTokens > 0 ? maxTokens : 512;

    while (n_tokens_generated < max_gen && !handle->stop_requested) {
        const auto token_start = std::chrono::steady_clock::now();

        llama_token id = llama_sampler_sample(smpl, handle->ctx, -1);

        if (llama_vocab_is_eog(handle->vocab, id)) {
            LOGI("EOG token reached, generation complete");
            break;
        }

        char piece_buf[256] = {0};
        int n_piece = llama_token_to_piece(handle->vocab, id, piece_buf, sizeof(piece_buf), 0, true);
        if (n_piece > 0) {
            output_text.append(piece_buf, n_piece);
        }

        llama_batch next_batch = llama_batch_get_one(&id, 1);
        if (llama_decode(handle->ctx, next_batch) != 0) {
            LOGE("llama_decode failed during generation at token %d", n_tokens_generated);
            break;
        }

        n_tokens_generated++;
        handle->generated_tokens = n_tokens_generated;

        const auto token_end = std::chrono::steady_clock::now();
        const auto token_ms = std::chrono::duration_cast<std::chrono::milliseconds>(token_end - token_start).count();
        if (n_tokens_generated == 1) {
            handle->ttft_ms = std::chrono::duration_cast<std::chrono::milliseconds>(token_end - generation_start).count();
            LOGI("First generated token: ttft_ms=%lld", handle->ttft_ms);
        }
        LOGI("Generated token %d/%d decode_ms=%lld", n_tokens_generated, max_gen, (long long) token_ms);
    }

    llama_sampler_free(smpl);

    const auto generation_end = std::chrono::steady_clock::now();
    handle->total_ms = std::chrono::duration_cast<std::chrono::milliseconds>(generation_end - generation_start).count();
    const long long decode_only_ms = handle->total_ms > handle->prompt_ms ? handle->total_ms - handle->prompt_ms : handle->total_ms;
    handle->generation_tps = decode_only_ms > 0 ? (1000.0 * n_tokens_generated / (double) decode_only_ms) : 0.0;

    LOGI("Generation finished. Total tokens generated: %d total_ms=%lld prompt_ms=%lld ttft_ms=%lld gen_tok_s=%.2f",
         n_tokens_generated,
         handle->total_ms,
         handle->prompt_ms,
         handle->ttft_ms,
         handle->generation_tps);
    return env->NewStringUTF(output_text.c_str());
}

JNIEXPORT jstring JNICALL
Java_ai_closepaw_llm_gguf_GgufNativeBridge_nativeGetLastMetricsJson(
        JNIEnv *env,
        jobject /* this */,
        jlong handlePtr) {
    GgufModelHandle *handle = reinterpret_cast<GgufModelHandle *>(handlePtr);
    const std::string json = metrics_json(handle);
    return env->NewStringUTF(json.c_str());
}

JNIEXPORT void JNICALL
Java_ai_closepaw_llm_gguf_GgufNativeBridge_nativeStopGeneration(
        JNIEnv * /* env */,
        jobject /* this */,
        jlong handlePtr) {
    GgufModelHandle *handle = reinterpret_cast<GgufModelHandle *>(handlePtr);
    if (handle) {
        LOGI("Stop generation requested");
        handle->stop_requested = true;
    }
}

JNIEXPORT void JNICALL
Java_ai_closepaw_llm_gguf_GgufNativeBridge_nativeFreeModel(
        JNIEnv * /* env */,
        jobject /* this */,
        jlong handlePtr) {
    GgufModelHandle *handle = reinterpret_cast<GgufModelHandle *>(handlePtr);
    if (handle) {
        LOGI("Freeing GGUF model and context");
        if (handle->ctx) {
            llama_free(handle->ctx);
        }
        if (handle->model) {
            llama_model_free(handle->model);
        }
        delete handle;
    }
}

} // extern "C"
