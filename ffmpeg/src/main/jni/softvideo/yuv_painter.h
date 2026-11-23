// Draws one planar 4:2:0 picture into the current OpenGL ES 2 framebuffer. Needs a current GL context and nothing
// else, so it runs the same under an EGL window surface (Android) and a pbuffer (the host test).
#ifndef MARQUEE_YUV_PAINTER_H
#define MARQUEE_YUV_PAINTER_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct YuvPainter YuvPainter;

typedef struct {
  const uint8_t *plane[3];  // Y, U, V
  int stride[3];            // bytes per row, also the texture width: GLES2 cannot upload a sub-row
  int width, height;        // visible luma size
  int chroma_width, chroma_height;
  int bt709;                // 0: BT.601, 1: BT.709
  int full_range;
} YuvImage;

// Compiles the shaders; NULL if the GL driver refuses them. The context must be current.
YuvPainter *yp_create(void);

// Uploads img and draws it over the whole view_w x view_h viewport. 0 on success, -1 on a GL error.
int yp_draw(YuvPainter *p, const YuvImage *img, int view_w, int view_h);

// Frees the painter. With delete_gl_objects the textures, buffer and program are deleted too, which needs the context
// that made them to be current; pass 0 when that context is being destroyed or is lost, which frees them anyway.
void yp_destroy(YuvPainter *p, int delete_gl_objects);

#ifdef __cplusplus
}
#endif

#endif
