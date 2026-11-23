#include "yuv_painter.h"

#include <GLES2/gl2.h>
#include <stdlib.h>
#include <string.h>

#include "sv_log.h"

static const char kVertexShader[] =
    "attribute vec2 a_pos;\n"
    "attribute vec2 a_uv;\n"
    "uniform vec4 u_scale;\n"  // luma u,v and chroma u,v: visible size over texture size
    "varying highp vec2 v_luma;\n"
    "varying highp vec2 v_chroma;\n"
    "void main() {\n"
    "  gl_Position = vec4(a_pos, 0.0, 1.0);\n"
    "  v_luma = a_uv * u_scale.xy;\n"
    "  v_chroma = a_uv * u_scale.zw;\n"
    "}\n";

// u_edge is the centre of the last visible texel in each row. A bilinear tap past it would blend in the padding
// the decoder leaves at the end of a row, which shows as a coloured line when the picture is scaled down.
static const char kFragmentShader[] =
    "#ifdef GL_FRAGMENT_PRECISION_HIGH\n"
    "precision highp float;\n"
    "#else\n"
    "precision mediump float;\n"
    "#endif\n"
    "varying vec2 v_luma;\n"
    "varying vec2 v_chroma;\n"
    "uniform sampler2D u_y;\n"
    "uniform sampler2D u_u;\n"
    "uniform sampler2D u_v;\n"
    "uniform vec2 u_edge;\n"
    "uniform mat3 u_matrix;\n"
    "uniform vec3 u_bias;\n"
    "void main() {\n"
    "  float y = texture2D(u_y, vec2(min(v_luma.x, u_edge.x), v_luma.y)).r;\n"
    "  vec2 c = vec2(min(v_chroma.x, u_edge.y), v_chroma.y);\n"
    "  float u = texture2D(u_u, c).r;\n"
    "  float v = texture2D(u_v, c).r;\n"
    "  gl_FragColor = vec4(clamp(u_matrix * vec3(y, u, v) + u_bias, 0.0, 1.0), 1.0);\n"
    "}\n";

// Triangle strip: position, then texture coordinate. Texture row 0 is the top of the picture, so v runs downwards.
static const GLfloat kQuad[16] = {
    -1.f, -1.f, 0.f, 1.f,  //
    1.f,  -1.f, 1.f, 1.f,  //
    -1.f, 1.f,  0.f, 0.f,  //
    1.f,  1.f,  1.f, 0.f,
};

// Two sets of textures, alternated, so a new picture is never written into textures the previous draw still reads.
#define TEXTURE_SETS 2

struct YuvPainter {
  GLuint program, vbo;
  GLint a_pos, a_uv, u_scale, u_edge, u_matrix, u_bias, u_tex[3];
  GLuint tex[TEXTURE_SETS][3];
  int size[TEXTURE_SETS][3][2];
  int set;
};

static GLuint compile(GLenum type, const char *source) {
  GLuint shader = glCreateShader(type);
  if (!shader) return 0;
  glShaderSource(shader, 1, &source, NULL);
  glCompileShader(shader);
  GLint ok = 0;
  glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
  if (!ok) {
    char log[512] = "";
    glGetShaderInfoLog(shader, sizeof log, NULL, log);
    SV_LOGE("shader compile failed: %s", log);
    glDeleteShader(shader);
    return 0;
  }
  return shader;
}

static GLuint link(void) {
  GLuint vs = compile(GL_VERTEX_SHADER, kVertexShader);
  GLuint fs = vs ? compile(GL_FRAGMENT_SHADER, kFragmentShader) : 0;
  GLuint program = (vs && fs) ? glCreateProgram() : 0;
  if (program) {
    glAttachShader(program, vs);
    glAttachShader(program, fs);
    glLinkProgram(program);
    GLint ok = 0;
    glGetProgramiv(program, GL_LINK_STATUS, &ok);
    if (!ok) {
      char log[512] = "";
      glGetProgramInfoLog(program, sizeof log, NULL, log);
      SV_LOGE("program link failed: %s", log);
      glDeleteProgram(program);
      program = 0;
    }
  }
  if (vs) glDeleteShader(vs);
  if (fs) glDeleteShader(fs);
  return program;
}

YuvPainter *yp_create(void) {
  YuvPainter *p = (YuvPainter *)calloc(1, sizeof *p);
  if (!p) return NULL;
  p->program = link();
  if (!p->program) {
    free(p);
    return NULL;
  }
  p->a_pos = glGetAttribLocation(p->program, "a_pos");
  p->a_uv = glGetAttribLocation(p->program, "a_uv");
  p->u_scale = glGetUniformLocation(p->program, "u_scale");
  p->u_edge = glGetUniformLocation(p->program, "u_edge");
  p->u_matrix = glGetUniformLocation(p->program, "u_matrix");
  p->u_bias = glGetUniformLocation(p->program, "u_bias");
  p->u_tex[0] = glGetUniformLocation(p->program, "u_y");
  p->u_tex[1] = glGetUniformLocation(p->program, "u_u");
  p->u_tex[2] = glGetUniformLocation(p->program, "u_v");
  // The 8-bit result is exact to within a level; dithering it would only add noise to flat areas.
  glDisable(GL_DITHER);
  glGenBuffers(1, &p->vbo);
  glBindBuffer(GL_ARRAY_BUFFER, p->vbo);
  glBufferData(GL_ARRAY_BUFFER, sizeof kQuad, kQuad, GL_STATIC_DRAW);
  for (int s = 0; s < TEXTURE_SETS; s++) {
    glGenTextures(3, p->tex[s]);
    for (int i = 0; i < 3; i++) {
      glBindTexture(GL_TEXTURE_2D, p->tex[s][i]);
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    }
  }
  if (p->a_pos < 0 || p->a_uv < 0 || p->u_scale < 0 || p->u_edge < 0 || p->u_matrix < 0 || p->u_bias < 0 ||
      glGetError() != GL_NO_ERROR) {
    SV_LOGE("the YUV shader is missing an input");
    yp_destroy(p, 1);
    return NULL;
  }
  return p;
}

// rgb = M * (y, u, v) + bias, folding the range expansion and the chroma offset into the matrix. Column-major.
static void colour_matrix(int bt709, int full_range, GLfloat m[9], GLfloat bias[3]) {
  const float kr = bt709 ? 0.2126f : 0.299f, kb = bt709 ? 0.0722f : 0.114f, kg = 1.f - kr - kb;
  const float ys = full_range ? 1.f : 255.f / 219.f, cs = full_range ? 1.f : 255.f / 224.f;
  const float yo = full_range ? 0.f : 16.f / 255.f, co = 128.f / 255.f;
  const float rv = 2.f * (1.f - kr) * cs, bu = 2.f * (1.f - kb) * cs;
  const float gu = 2.f * kb * (1.f - kb) / kg * cs, gv = 2.f * kr * (1.f - kr) / kg * cs;
  m[0] = ys; m[1] = ys; m[2] = ys;     // the y column
  m[3] = 0.f; m[4] = -gu; m[5] = bu;   // the u column
  m[6] = rv; m[7] = -gv; m[8] = 0.f;   // the v column
  bias[0] = -ys * yo - rv * co;
  bias[1] = -ys * yo + (gu + gv) * co;
  bias[2] = -ys * yo - bu * co;
}

static void upload(YuvPainter *p, int plane, const uint8_t *data, int width, int height) {
  glActiveTexture(GL_TEXTURE0 + plane);
  glBindTexture(GL_TEXTURE_2D, p->tex[p->set][plane]);
  int *size = p->size[p->set][plane];
  if (size[0] != width || size[1] != height) {
    glTexImage2D(GL_TEXTURE_2D, 0, GL_LUMINANCE, width, height, 0, GL_LUMINANCE, GL_UNSIGNED_BYTE, data);
    size[0] = width;
    size[1] = height;
  } else {
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_LUMINANCE, GL_UNSIGNED_BYTE, data);
  }
}

int yp_draw(YuvPainter *p, const YuvImage *img, int view_w, int view_h) {
  if (img->width <= 0 || img->height <= 0 || img->chroma_width <= 0 || img->chroma_height <= 0 ||
      img->stride[0] < img->width || img->stride[1] < img->chroma_width || img->stride[2] < img->chroma_width ||
      img->stride[1] != img->stride[2]) {
    return -1;
  }
  p->set = (p->set + 1) % TEXTURE_SETS;
  glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
  upload(p, 0, img->plane[0], img->stride[0], img->height);
  upload(p, 1, img->plane[1], img->stride[1], img->chroma_height);
  upload(p, 2, img->plane[2], img->stride[2], img->chroma_height);

  GLfloat m[9], bias[3];
  colour_matrix(img->bt709, img->full_range, m, bias);

  glViewport(0, 0, view_w, view_h);
  // A clear tells a tiling GPU the old contents are not needed, which saves loading them first.
  glClearColor(0.f, 0.f, 0.f, 1.f);
  glClear(GL_COLOR_BUFFER_BIT);
  glUseProgram(p->program);
  for (int i = 0; i < 3; i++) {
    glActiveTexture(GL_TEXTURE0 + i);
    glBindTexture(GL_TEXTURE_2D, p->tex[p->set][i]);
    glUniform1i(p->u_tex[i], i);
  }
  glUniform4f(p->u_scale, (GLfloat)img->width / img->stride[0], 1.f, (GLfloat)img->chroma_width / img->stride[1], 1.f);
  glUniform2f(p->u_edge, (img->width - 0.5f) / img->stride[0], (img->chroma_width - 0.5f) / img->stride[1]);
  glUniformMatrix3fv(p->u_matrix, 1, GL_FALSE, m);
  glUniform3fv(p->u_bias, 1, bias);
  glBindBuffer(GL_ARRAY_BUFFER, p->vbo);
  glEnableVertexAttribArray((GLuint)p->a_pos);
  glEnableVertexAttribArray((GLuint)p->a_uv);
  glVertexAttribPointer((GLuint)p->a_pos, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(GLfloat), (const void *)0);
  glVertexAttribPointer((GLuint)p->a_uv, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(GLfloat), (const void *)(2 * sizeof(GLfloat)));
  glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
  return glGetError() == GL_NO_ERROR ? 0 : -1;
}

void yp_destroy(YuvPainter *p, int delete_gl_objects) {
  if (!p) return;
  if (delete_gl_objects) {
    for (int s = 0; s < TEXTURE_SETS; s++) glDeleteTextures(3, p->tex[s]);
    if (p->vbo) glDeleteBuffers(1, &p->vbo);
    if (p->program) glDeleteProgram(p->program);
  }
  free(p);
}
