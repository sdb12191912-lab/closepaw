#include <jni.h>
#include <string>
#include <vector>
#include <atomic>
#include <cstring>
#include <chrono>
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
};

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
    // Offload as many model layers as the active Vulkan backend can accept.
    // llama.cpp keeps CPU as a fallback for tensors/ops that cannot be offloaded.
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

    const auto load_end = std::chrono::steady_clock::now();
    const auto load_ms = std::chrono::duration_cast<std::chrono::milliseconds>(load_end - load_start).count();
    LOGI("Successfully loaded GGUF model and initialized context in %lld ms", (long long) load_ms);
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

    const auto generation_start = std::chrono::steady_clock::now();
    const auto prompt_decode_start = std::chrono::steady_clock::now();

    llama_batch batch = llama_batch_get_one(prompt_tokens.data(), n_prompt);
    if (llama_decode(handle->ctx, batch) != 0) {
        LOGE("llama_decode prompt failed");
        return env->NewStringUTF("[Error: Prompt decode failed]");
    }

    const auto prompt_decode_end = std::chrono::steady_clock::now();
    const auto prompt_decode_ms = std::chrono::duration_cast<std::chrono::milliseconds>(prompt_decode_end - prompt_decode_start).count();
    const double prompt_tps = prompt_decode_ms > 0 ? (1000.0 * n_prompt / (double) prompt_decode_ms) : 0.0;
    LOGI("Prompt decode: tokens=%d elapsed_ms=%lld tok_s=%.2f", n_prompt, (long long) prompt_decode_ms, prompt_tps);

    auto sparams = llama_sampler_chain_default_params();
    llama_sampler *smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.95f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(1234));

    std::string output_text;
    int n_tokens_generated = 0;
    int max_gen = maxTokens > 0 ? maxTokens : 512;
    long long first_token_ms = -1;

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

        const auto token_end = std::chrono::steady_clock::now();
        const auto token_ms = std::chrono::duration_cast<std::chrono::milliseconds>(token_end - token_start).count();
        if (n_tokens_generated == 1) {
            first_token_ms = std::chrono::duration_cast<std::chrono::milliseconds>(token_end - generation_start).count();
            LOGI("First generated token: ttft_ms=%lld", first_token_ms);
        }
        LOGI("Generated token %d/%d decode_ms=%lld", n_tokens_generated, max_gen, (long long) token_ms);
    }

    llama_sampler_free(smpl);

    const auto generation_end = std::chrono::steady_clock::now();
    const auto generation_ms = std::chrono::duration_cast<std::chrono::milliseconds>(generation_end - generation_start).count();
    const long long decode_only_ms = generation_ms > prompt_decode_ms ? generation_ms - prompt_decode_ms : generation_ms;
    const double generation_tps = decode_only_ms > 0 ? (1000.0 * n_tokens_generated / (double) decode_only_ms) : 0.0;

    LOGI("Generation finished. Total tokens generated: %d total_ms=%lld prompt_ms=%lld ttft_ms=%lld gen_tok_s=%.2f",
         n_tokens_generated,
         (long long) generation_ms,
         (long long) prompt_decode_ms,
         first_token_ms,
         generation_tps);
    return env->NewStringUTF(output_text.c_str());
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
