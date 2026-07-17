#include <jni.h>
#include <string>
#include <vector>
#include <fstream>
#include <mutex>
#include <android/log.h>
#include "llama.h"
#include "common.h"

#define TAG "MikGPT_Native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static llama_model* model = nullptr;
static llama_context* ctx = nullptr;

static std::string log_file_path = "/data/data/com.mikgpt/files/llama.log";
static std::mutex log_mutex;

// Redirect llama.cpp logs to Logcat and a local diagnostics file
void llama_log_callback_android(enum ggml_log_level level, const char * text, void * user_data) {
    (void)user_data;
    
    android_LogPriority priority = ANDROID_LOG_INFO;
    if (level == GGML_LOG_LEVEL_ERROR) {
        priority = ANDROID_LOG_ERROR;
    } else if (level == GGML_LOG_LEVEL_WARN) {
        priority = ANDROID_LOG_WARN;
    } else if (level == GGML_LOG_LEVEL_DEBUG) {
        priority = ANDROID_LOG_DEBUG;
    }
    __android_log_print(priority, "LlamaCPP", "%s", text);

    std::lock_guard<std::mutex> lock(log_mutex);
    std::ofstream outfile;
    outfile.open(log_file_path, std::ios_base::app);
    if (outfile.is_open()) {
        outfile << text;
        outfile.close();
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mikgpt_LlamaInference_loadModel(JNIEnv* env, jobject thiz, jstring model_path) {
    // Clear and initialize log file
    {
        std::lock_guard<std::mutex> lock(log_mutex);
        std::ofstream outfile;
        outfile.open(log_file_path, std::ios_base::trunc);
        if (outfile.is_open()) {
            outfile << "--- Engine Load Attempt ---\n";
            outfile.close();
        }
    }
    llama_log_set(llama_log_callback_android, nullptr);

    if (model != nullptr) {
        llama_model_free(model);
        model = nullptr;
    }
    if (ctx != nullptr) {
        llama_free(ctx);
        ctx = nullptr;
    }

    const char* path = env->GetStringUTFChars(model_path, nullptr);
    LOGI("Loading model from: %s", path);

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 99; // GPU offloading if compiled with Vulkan/OpenCL

    model = llama_model_load_from_file(path, model_params);
    env->ReleaseStringUTFChars(model_path, path);

    if (model == nullptr) {
        LOGE("Failed to load model");
        return JNI_FALSE;
    }

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = 2048; // Context size
    ctx_params.n_threads = 4;
    ctx_params.n_threads_batch = 4;

    ctx = llama_init_from_model(model, ctx_params);
    if (ctx == nullptr) {
        LOGE("Failed to create context");
        llama_model_free(model);
        model = nullptr;
        return JNI_FALSE;
    }

    LOGI("Model loaded successfully");
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_mikgpt_LlamaInference_generate(JNIEnv* env, jobject thiz, jstring prompt_str, jstring grammar_str, jobject callback) {
    if (model == nullptr || ctx == nullptr) {
        return env->NewStringUTF("Model not loaded");
    }

    const char* prompt = env->GetStringUTFChars(prompt_str, nullptr);
    const char* grammar_rules = grammar_str ? env->GetStringUTFChars(grammar_str, nullptr) : nullptr;

    LOGI("Generating text with prompt length: %d", (int)strlen(prompt));

    // Get the vocabulary from the model
    const llama_vocab* vocab = llama_model_get_vocab(model);

    // Tokenize prompt using new vocabulary API
    std::vector<llama_token> tokens;
    tokens.resize(strlen(prompt) + 4);
    int n_tokens = llama_tokenize(vocab, prompt, strlen(prompt), tokens.data(), tokens.size(), true, true);
    tokens.resize(n_tokens);

    // Prepare sampler and context
    llama_sampler* sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_min_p(0.05f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(42));

    // Clear context KV cache
    llama_kv_cache_clear(ctx);

    std::string response = "";

    // JNI callback cache
    jclass callback_class = env->GetObjectClass(callback);
    jmethodID on_token_method = env->GetMethodID(callback_class, "onToken", "(Ljava/lang/String;)V");

    // Perform simple decode loop
    int n_past = 0;
    for (size_t i = 0; i < tokens.size(); i++) {
        llama_batch batch = llama_batch_get_one(&tokens[i], 1);
        if (llama_decode(ctx, batch) != 0) {
            LOGE("Failed to decode token");
            break;
        }
    }

    llama_token curr_token = llama_sampler_sample(sampler, ctx, -1);
    int max_tokens = 512;
    int gen_count = 0;

    // Use llama_vocab_eos(vocab) instead of deprecated llama_token_eos(model)
    while (curr_token != llama_vocab_eos(vocab) && gen_count < max_tokens) {
        char buf[256];
        // Use vocab instead of model for token to piece mapping
        int n = llama_token_to_piece(vocab, curr_token, buf, sizeof(buf), 0, true);
        if (n > 0) {
            buf[n] = '\0';
            std::string piece(buf);
            response += piece;

            // Stream token back to Java
            jstring jpiece = env->NewStringUTF(buf);
            env->CallVoidMethod(callback, on_token_method, jpiece);
            env->DeleteLocalRef(jpiece);
        }

        llama_batch batch = llama_batch_get_one(&curr_token, 1);
        if (llama_decode(ctx, batch) != 0) {
            LOGE("Failed to decode next token");
            break;
        }

        curr_token = llama_sampler_sample(sampler, ctx, -1);
        gen_count++;
    }

    llama_sampler_free(sampler);

    env->ReleaseStringUTFChars(prompt_str, prompt);
    if (grammar_str) {
        env->ReleaseStringUTFChars(grammar_str, grammar_rules);
    }

    return env->NewStringUTF(response.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_mikgpt_LlamaInference_unloadModel(JNIEnv* env, jobject thiz) {
    if (ctx != nullptr) {
        llama_free(ctx);
        ctx = nullptr;
    }
    if (model != nullptr) {
        llama_model_free(model);
        model = nullptr;
    }
    LOGI("Model unloaded");
}
