#ifndef SV_LOG_H
#define SV_LOG_H

// Logging for the soft video code. It builds on a desktop too (the checks that run the deinterlacer and the GL
// painter there), where messages go to stderr.
#ifdef __ANDROID__
#include <android/log.h>
#define SV_LOGW(...) ((void)__android_log_print(ANDROID_LOG_WARN, "SoftVideo", __VA_ARGS__))
#define SV_LOGE(...) ((void)__android_log_print(ANDROID_LOG_ERROR, "SoftVideo", __VA_ARGS__))
#else
#include <stdio.h>
#define SV_LOGW(...) ((void)(fprintf(stderr, "W SoftVideo: " __VA_ARGS__), fputc('\n', stderr)))
#define SV_LOGE(...) ((void)(fprintf(stderr, "E SoftVideo: " __VA_ARGS__), fputc('\n', stderr)))
#endif

#endif
