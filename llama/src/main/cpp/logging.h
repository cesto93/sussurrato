#ifndef SUSSURRATO_LOGGING_H
#define SUSSURRATO_LOGGING_H

#pragma once
#include <android/log.h>

#ifndef LOG_TAG
#define LOG_TAG "sussurrato-llama"
#endif

#ifndef LOG_MIN_LEVEL
#if defined(NDEBUG)
#define LOG_MIN_LEVEL ANDROID_LOG_INFO
#else
#define LOG_MIN_LEVEL ANDROID_LOG_VERBOSE
#endif
#endif

static inline int ai_should_log(int /*prio*/) { return 1; }

#if LOG_MIN_LEVEL <= ANDROID_LOG_VERBOSE
#define LOGv(...) __android_log_print(ANDROID_LOG_VERBOSE, LOG_TAG, __VA_ARGS__)
#else
#define LOGv(...) ((void)0)
#endif

#if LOG_MIN_LEVEL <= ANDROID_LOG_DEBUG
#define LOGd(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#else
#define LOGd(...) ((void)0)
#endif

#define LOGi(...) __android_log_print(ANDROID_LOG_INFO , LOG_TAG, __VA_ARGS__)
#define LOGw(...) __android_log_print(ANDROID_LOG_WARN , LOG_TAG, __VA_ARGS__)
#define LOGe(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static inline int android_log_prio_from_ggml(enum ggml_log_level level) {
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: return ANDROID_LOG_ERROR;
        case GGML_LOG_LEVEL_WARN:  return ANDROID_LOG_WARN;
        case GGML_LOG_LEVEL_INFO:  return ANDROID_LOG_INFO;
        case GGML_LOG_LEVEL_DEBUG: return ANDROID_LOG_DEBUG;
        default:                   return ANDROID_LOG_DEFAULT;
    }
}

static inline void sussurrato_android_log_callback(enum ggml_log_level level,
                                                    const char* text,
                                                    void* /*user*/) {
    __android_log_write(android_log_prio_from_ggml(level), LOG_TAG, text);
}

#endif //SUSSURRATO_LOGGING_H
