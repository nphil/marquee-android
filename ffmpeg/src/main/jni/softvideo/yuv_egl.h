// Shows YuvImages on an Android Surface through an EGL window surface. Not thread safe: one thread (the player's)
// makes every call, because the GL context is current on it.
#ifndef MARQUEE_YUV_EGL_H
#define MARQUEE_YUV_EGL_H

#include <android/native_window.h>
#include <stdint.h>

#include "yuv_painter.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct YuvRenderer YuvRenderer;

enum {
  YR_OK = 0,
  YR_SKIPPED = 1,  // the window went away under the call; nothing was shown, and the next call may recover
  YR_FAIL = -1,    // GL or EGL cannot show pictures here
};

// Cheap: the GL context is made on the first yr_render.
YuvRenderer *yr_create(void);

// Draws img into a buffer of out_w x out_h on window, to be shown at present_ns (CLOCK_MONOTONIC, 0 for as soon as
// possible). The buffers are the picture's size, not the screen's, so the compositor does the scaling.
int yr_render(YuvRenderer *r, ANativeWindow *window, const YuvImage *img, int out_w, int out_h, int64_t present_ns);

// Lets go of the window, so a MediaCodec or another renderer can connect to the same Surface.
void yr_detach(YuvRenderer *r);

void yr_destroy(YuvRenderer *r);

#ifdef __cplusplus
}
#endif

#endif
