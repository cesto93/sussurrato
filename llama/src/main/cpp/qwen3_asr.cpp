#include <jni.h>
#include <cstring>
#include <string>
#include <vector>
#include <mutex>

#include "llama.h"
#include "ggml.h"
#include "mtmd.h"
#include "mtmd-helper.h"
#include "logging.h"

static std::mutex g_mutex;

static llama_model    * g_model    = nullptr;
static llama_context  * g_ctx     = nullptr;
static mtmd_context   * g_mctx     = nullptr;
static llama_sampler  * g_smpl     = nullptr;
static const llama_vocab * g_vocab = nullptr;

static bool is_loaded() {
    return g_model != nullptr && g_ctx != nullptr && g_mctx != nullptr;
}

static void ggml_log_callback_adapter(enum ggml_log_level level, const char * text, void * /*user_data*/) {
    const int prio = android_log_prio_from_ggml(level);
    if (!ai_should_log(prio)) return;
    __android_log_write(prio, LOG_TAG, text);
}

extern "C" {

JNIEXPORT void JNICALL
Java_com_pierfrancescocontino_sussurrato_llama_internal_AsrEngineImpl_nativeInit(
    JNIEnv * env, jobject /*this*/) {
    std::lock_guard<std::mutex> lock(g_mutex);

    llama_log_set(ggml_log_callback_adapter, nullptr);
    mtmd_helper_log_set(ggml_log_callback_adapter, nullptr);

    llama_backend_init();
    llama_numa_init(GGML_NUMA_STRATEGY_DISABLED);

    LOGi("llama.cpp backend initialized");
}

JNIEXPORT jint JNICALL
Java_com_pierfrancescocontino_sussurrato_llama_internal_AsrEngineImpl_nativeLoadModel(
    JNIEnv * env, jobject /*this*/, jstring modelPath, jstring mmprojPath) {
    std::lock_guard<std::mutex> lock(g_mutex);

    const char * model_path  = env->GetStringUTFChars(modelPath,  nullptr);
    const char * mmproj_path = env->GetStringUTFChars(mmprojPath, nullptr);

    LOGi("Loading model: %s", model_path);
    LOGi("Loading mmproj: %s", mmproj_path);

    // --- load text model ---
    llama_model_params model_params = llama_model_default_params();
    model_params.use_mmap = true;
    model_params.use_mlock = false;
    g_model = llama_model_load_from_file(model_path, model_params);

    if (!g_model) {
        LOGe("Failed to load model: %s", model_path);
        env->ReleaseStringUTFChars(modelPath,  model_path);
        env->ReleaseStringUTFChars(mmprojPath, mmproj_path);
        return -1;
    }

    // --- create context ---
    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = 8192;
    ctx_params.n_batch = 1024;
    ctx_params.n_ubatch = 1024;
    ctx_params.n_threads = 4;
    ctx_params.n_threads_batch = 4;
    g_ctx = llama_init_from_model(g_model, ctx_params);

    if (!g_ctx) {
        LOGe("Failed to create context");
        llama_model_free(g_model);
        g_model = nullptr;
        env->ReleaseStringUTFChars(modelPath,  model_path);
        env->ReleaseStringUTFChars(mmprojPath, mmproj_path);
        return -2;
    }

    // --- get vocab ---
    g_vocab = llama_model_get_vocab(g_model);

    // --- init sampler ---
    g_smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(g_smpl, llama_sampler_init_greedy());

    // --- load mmproj (audio encoder) ---
    mtmd_context_params mparams = mtmd_context_params_default();
    mparams.use_gpu = false;
    mparams.n_threads = 4;
    g_mctx = mtmd_init_from_file(mmproj_path, g_model, mparams);

    if (!g_mctx) {
        LOGe("Failed to load mmproj: %s", mmproj_path);
        llama_sampler_free(g_smpl);
        g_smpl = nullptr;
        llama_free(g_ctx);
        g_ctx = nullptr;
        llama_model_free(g_model);
        g_model = nullptr;
        env->ReleaseStringUTFChars(modelPath,  model_path);
        env->ReleaseStringUTFChars(mmprojPath, mmproj_path);
        return -3;
    }

    LOGi("Model and mmproj loaded successfully");
    env->ReleaseStringUTFChars(modelPath,  model_path);
    env->ReleaseStringUTFChars(mmprojPath, mmproj_path);
    return 0;
}

JNIEXPORT jstring JNICALL
Java_com_pierfrancescocontino_sussurrato_llama_internal_AsrEngineImpl_nativeTranscribe(
    JNIEnv * env, jobject /*this*/, jfloatArray audioData, jint sampleRate) {
    std::lock_guard<std::mutex> lock(g_mutex);

    if (!is_loaded()) {
        LOGe("Transcribe called but model not loaded");
        return env->NewStringUTF("");
    }

    // get PCM float data
    jfloat * pcm = env->GetFloatArrayElements(audioData, nullptr);
    jsize n_samples = env->GetArrayLength(audioData);

    LOGi("Transcribing %d samples at %d Hz", (int)n_samples, sampleRate);

    // create audio bitmap
    mtmd_bitmap * audio_bmp = mtmd_bitmap_init_from_audio(
        (size_t)n_samples, (const float *)pcm);

    if (!audio_bmp) {
        LOGe("Failed to create audio bitmap");
        env->ReleaseFloatArrayElements(audioData, pcm, JNI_ABORT);
        return env->NewStringUTF("");
    }

    // tokenize prompt with media marker
    mtmd_input_text input_text;
    input_text.text = "<__media__>Transcribe the audio.";
    input_text.add_special = true;
    input_text.parse_special = true;

    mtmd_input_chunks * chunks = mtmd_input_chunks_init();
    const mtmd_bitmap * bitmaps[] = { audio_bmp };

    int32_t tokenize_res = mtmd_tokenize(g_mctx, chunks, &input_text, bitmaps, 1);

    if (tokenize_res != 0) {
        LOGe("mtmd_tokenize failed: %d", tokenize_res);
        mtmd_input_chunks_free(chunks);
        mtmd_bitmap_free(audio_bmp);
        env->ReleaseFloatArrayElements(audioData, pcm, JNI_ABORT);
        return env->NewStringUTF("");
    }

    LOGi("Tokenized %zu chunks, %zu total tokens",
         mtmd_input_chunks_size(chunks),
         mtmd_helper_get_n_tokens(chunks));

    // eval chunks (encode audio + decode text prompt)
    llama_pos n_past = 0;
    int32_t eval_res = mtmd_helper_eval_chunks(
        g_mctx, g_ctx, chunks, n_past, 0, 512, true, &n_past);

    mtmd_input_chunks_free(chunks);
    mtmd_bitmap_free(audio_bmp);
    env->ReleaseFloatArrayElements(audioData, pcm, JNI_ABORT);

    if (eval_res != 0) {
        LOGe("mtmd_helper_eval_chunks failed: %d", eval_res);
        return env->NewStringUTF("");
    }

    LOGi("Audio encoded, n_past=%d. Generating tokens...", n_past);

    // generate transcription tokens
    const int max_tokens = 512;
    std::string result;

    for (int i = 0; i < max_tokens; i++) {
        // sample next token
        llama_token id = llama_sampler_sample(g_smpl, g_ctx, -1);

        // stop at end-of-generation
        if (llama_vocab_is_eog(g_vocab, id)) {
            LOGi("EOG token %d, stopping generation", id);
            break;
        }

        // convert token to piece (UTF-8)
        char buf[128];
        int n = llama_token_to_piece(g_vocab, id, buf, sizeof(buf), 0, true);
        if (n > 0) {
            result.append(buf, (size_t)n);
        }

        // decode the token
        llama_batch batch = llama_batch_get_one(&id, 1);
        if (llama_decode(g_ctx, batch) != 0) {
            LOGe("llama_decode failed at token %d", i);
            break;
        }
        n_past++;
    }

    LOGi("Transcription complete: %zu chars", result.size());
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT void JNICALL
Java_com_pierfrancescocontino_sussurrato_llama_internal_AsrEngineImpl_nativeUnload(
    JNIEnv * env, jobject /*this*/) {
    std::lock_guard<std::mutex> lock(g_mutex);

    if (g_mctx) {
        mtmd_free(g_mctx);
        g_mctx = nullptr;
    }
    if (g_smpl) {
        llama_sampler_free(g_smpl);
        g_smpl = nullptr;
    }
    if (g_ctx) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    g_vocab = nullptr;

    LOGi("Model unloaded");
}

JNIEXPORT void JNICALL
Java_com_pierfrancescocontino_sussurrato_llama_internal_AsrEngineImpl_nativeShutdown(
    JNIEnv * env, jobject /*this*/) {
    std::lock_guard<std::mutex> lock(g_mutex);

    if (g_mctx) {
        mtmd_free(g_mctx);
        g_mctx = nullptr;
    }
    if (g_smpl) {
        llama_sampler_free(g_smpl);
        g_smpl = nullptr;
    }
    if (g_ctx) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    g_vocab = nullptr;

    llama_backend_free();

    LOGi("llama.cpp backend shut down");
}

} // extern "C"
