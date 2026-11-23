#include "yuv_egl.h"

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <stdlib.h>
#include <string.h>

#include "sv_log.h"

struct YuvRenderer {
  EGLDisplay display;
  EGLConfig config;
  EGLContext context;
  EGLSurface surface;
  ANativeWindow *window;  // referenced for as long as surface exists
  int width, height;      // the buffer size asked for
  int surface_w, surface_h;  // the size EGL reports, which is what gets drawn
  YuvPainter *painter;
  PFNEGLPRESENTATIONTIMEANDROIDPROC present_time;
};

YuvRenderer *yr_create(void) {
  YuvRenderer *r = (YuvRenderer *)calloc(1, sizeof *r);
  if (!r) return NULL;
  r->display = EGL_NO_DISPLAY;
  r->context = EGL_NO_CONTEXT;
  r->surface = EGL_NO_SURFACE;
  return r;
}

static int choose_config(YuvRenderer *r) {
  static const EGLint attribs[] = {
      EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT, EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
      EGL_RED_SIZE,        8,                  EGL_GREEN_SIZE,   8,
      EGL_BLUE_SIZE,       8,                  EGL_NONE,
  };
  EGLConfig configs[64];
  EGLint count = 0;
  if (!eglChooseConfig(r->display, attribs, configs, 64, &count) || count < 1) {
    SV_LOGE("no EGL config for an RGB8 ES2 window: 0x%x", eglGetError());
    return 0;
  }
  // Nothing drawn here is translucent, so prefer a format without alpha: the compositor then skips blending.
  r->config = configs[0];
  for (EGLint i = 0; i < count; i++) {
    EGLint alpha = -1;
    if (eglGetConfigAttrib(r->display, configs[i], EGL_ALPHA_SIZE, &alpha) && alpha == 0) {
      r->config = configs[i];
      break;
    }
  }
  return 1;
}

static int ensure_context(YuvRenderer *r) {
  if (r->context != EGL_NO_CONTEXT) return 1;
  r->display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
  // eglInitialize on a display that is up already succeeds. The display is shared with the rest of the process, so it
  // is never terminated here.
  if (r->display == EGL_NO_DISPLAY || !eglInitialize(r->display, NULL, NULL)) {
    SV_LOGE("eglInitialize failed: 0x%x", eglGetError());
    return 0;
  }
  if (!choose_config(r)) return 0;
  static const EGLint context_attribs[] = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE};
  r->context = eglCreateContext(r->display, r->config, EGL_NO_CONTEXT, context_attribs);
  if (r->context == EGL_NO_CONTEXT) {
    SV_LOGE("eglCreateContext failed: 0x%x", eglGetError());
    return 0;
  }
  const char *extensions = eglQueryString(r->display, EGL_EXTENSIONS);
  if (extensions && strstr(extensions, "EGL_ANDROID_presentation_time")) {
    r->present_time = (PFNEGLPRESENTATIONTIMEANDROIDPROC)eglGetProcAddress("eglPresentationTimeANDROID");
  }
  return 1;
}

static void drop_surface(YuvRenderer *r) {
  if (r->surface != EGL_NO_SURFACE) {
    // A surface that is current is only destroyed once it is not, and the window stays connected until then.
    if (eglGetCurrentSurface(EGL_DRAW) == r->surface || eglGetCurrentSurface(EGL_READ) == r->surface) {
      eglMakeCurrent(r->display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    }
    eglDestroySurface(r->display, r->surface);
    r->surface = EGL_NO_SURFACE;
  }
  if (r->window) {
    ANativeWindow_release(r->window);
    r->window = NULL;
  }
}

static void drop_context(YuvRenderer *r) {
  drop_surface(r);
  yp_destroy(r->painter, 0);  // the context takes its GL objects with it
  r->painter = NULL;
  if (r->context != EGL_NO_CONTEXT) {
    if (eglGetCurrentContext() == r->context) eglMakeCurrent(r->display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    eglDestroyContext(r->display, r->context);
    r->context = EGL_NO_CONTEXT;
  }
}

// Whether an EGL error blames the window and not the GL set-up: the surface was abandoned, or another API such as a
// MediaCodec that is on its way out still holds it. The next picture may find it usable again.
static int window_error(EGLint error) { return error == EGL_BAD_NATIVE_WINDOW || error == EGL_BAD_SURFACE; }

// YR_OK, YR_SKIPPED when the window is to blame, YR_FAIL otherwise.
static int create_surface(YuvRenderer *r, ANativeWindow *window, int width, int height) {
  EGLint format = 0;
  eglGetConfigAttrib(r->display, r->config, EGL_NATIVE_VISUAL_ID, &format);
  // The buffers get the picture's size and the compositor scales them to the view, as it does for a MediaCodec. This
  // has to be set before the EGL surface is made, which reads it.
  if (ANativeWindow_setBuffersGeometry(window, width, height, format) != 0) {
    SV_LOGW("setBuffersGeometry %dx%d failed; drawing at the window's own size", width, height);
  }
  r->surface = eglCreateWindowSurface(r->display, r->config, window, NULL);
  if (r->surface == EGL_NO_SURFACE) {
    const EGLint error = eglGetError();
    SV_LOGE("eglCreateWindowSurface failed: 0x%x", error);
    return window_error(error) ? YR_SKIPPED : YR_FAIL;
  }
  ANativeWindow_acquire(window);
  r->window = window;
  r->width = width;
  r->height = height;
  if (!eglMakeCurrent(r->display, r->surface, r->surface, r->context)) {
    const EGLint error = eglGetError();
    SV_LOGE("eglMakeCurrent failed: 0x%x", error);
    drop_surface(r);
    return window_error(error) ? YR_SKIPPED : YR_FAIL;
  }
  // 1 so a frame is queued rather than replacing one that is waiting for its presentation time.
  eglSwapInterval(r->display, 1);
  EGLint w = 0, h = 0;
  eglQuerySurface(r->display, r->surface, EGL_WIDTH, &w);
  eglQuerySurface(r->display, r->surface, EGL_HEIGHT, &h);
  if (w != width || h != height) SV_LOGW("asked for %dx%d buffers, got %dx%d", width, height, w, h);
  r->surface_w = w > 0 ? w : width;
  r->surface_h = h > 0 ? h : height;
  return YR_OK;
}

int yr_render(YuvRenderer *r, ANativeWindow *window, const YuvImage *img, int out_w, int out_h, int64_t present_ns) {
  if (!window) {
    yr_detach(r);
    return YR_SKIPPED;
  }
  if (out_w <= 0 || out_h <= 0 || !ensure_context(r)) return YR_FAIL;
  if (r->surface != EGL_NO_SURFACE && (r->window != window || r->width != out_w || r->height != out_h)) drop_surface(r);
  if (r->surface == EGL_NO_SURFACE) {
    const int created = create_surface(r, window, out_w, out_h);
    if (created != YR_OK) return created;
  } else if (eglGetCurrentContext() != r->context || eglGetCurrentSurface(EGL_DRAW) != r->surface) {
    if (!eglMakeCurrent(r->display, r->surface, r->surface, r->context)) {
      const EGLint error = eglGetError();
      SV_LOGE("eglMakeCurrent failed: 0x%x", error);
      return window_error(error) ? YR_SKIPPED : YR_FAIL;
    }
  }
  if (!r->painter) {
    r->painter = yp_create();
    if (!r->painter) return YR_FAIL;
  }
  if (yp_draw(r->painter, img, r->surface_w, r->surface_h) != 0) {
    SV_LOGE("drawing a %dx%d picture failed", img->width, img->height);
    return YR_FAIL;
  }
  if (r->present_time && present_ns > 0) r->present_time(r->display, r->surface, (EGLnsecsANDROID)present_ns);
  if (!eglSwapBuffers(r->display, r->surface)) {
    const EGLint error = eglGetError();
    SV_LOGW("eglSwapBuffers failed: 0x%x", error);
    drop_surface(r);
    if (error == EGL_CONTEXT_LOST) drop_context(r);
    return (window_error(error) || error == EGL_CONTEXT_LOST) ? YR_SKIPPED : YR_FAIL;
  }
  return YR_OK;
}

void yr_detach(YuvRenderer *r) { drop_surface(r); }

void yr_destroy(YuvRenderer *r) {
  if (!r) return;
  drop_context(r);
  free(r);
}
