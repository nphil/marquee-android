// The JNI entry points of com.nphil.marquee.ffmpeg.FfmpegVideoDecoder: thin wrappers over soft_decoder.h and
// yuv_egl.h. They are found by name, so ffmpeg_jni.cc and its JNI_OnLoad are left as Media3 has them. Nothing here
// throws: a failure is a return code, and the Java side turns it into an exception.
#include <android/native_window_jni.h>
#include <jni.h>
#include <stdint.h>

extern "C" {
#include <libavutil/frame.h>
#include <libavutil/pixdesc.h>
}

#include "soft_decoder.h"
#include "yuv_egl.h"

#define VIDEO_JNI(RETURN_TYPE, NAME) \
  extern "C" JNIEXPORT RETURN_TYPE JNICALL Java_com_nphil_marquee_ffmpeg_FfmpegVideoDecoder_##NAME

static inline SvDecoder *decoder_of(jlong handle) { return reinterpret_cast<SvDecoder *>(static_cast<intptr_t>(handle)); }

static inline int ceil_shift(int value, int shift) { return (value + (1 << shift) - 1) >> shift; }

static bool image_of(const SvPicture *picture, YuvImage *image) {
  const AVFrame *frame = picture->frame;
  const AVPixFmtDescriptor *desc = av_pix_fmt_desc_get(static_cast<AVPixelFormat>(frame->format));
  if (!desc || desc->nb_components < 3 || !frame->data[0] || !frame->data[1] || !frame->data[2]) return false;
  for (int i = 0; i < 3; i++) {
    image->plane[i] = frame->data[i];
    image->stride[i] = frame->linesize[i];
  }
  image->width = frame->width;
  image->height = frame->height;
  image->chroma_width = ceil_shift(frame->width, desc->log2_chroma_w);
  image->chroma_height = ceil_shift(frame->height, desc->log2_chroma_h);
  image->bt709 = picture->matrix == SV_MATRIX_BT709;
  image->full_range = picture->full_range;
  return true;
}

VIDEO_JNI(jboolean, nativeHasDecoder)(JNIEnv *, jclass) { return sv_has_decoder() ? JNI_TRUE : JNI_FALSE; }

VIDEO_JNI(jlong, nativeOpen)(JNIEnv *env, jclass, jbyteArray extradata, jint threads) {
  jbyte *data = nullptr;
  jsize size = 0;
  if (extradata) {
    size = env->GetArrayLength(extradata);
    data = env->GetByteArrayElements(extradata, nullptr);
  }
  SvDecoder *decoder = sv_open(reinterpret_cast<const uint8_t *>(data), size, threads);
  if (data) env->ReleaseByteArrayElements(extradata, data, JNI_ABORT);
  return static_cast<jlong>(reinterpret_cast<intptr_t>(decoder));
}

VIDEO_JNI(jint, nativeSend)(JNIEnv *env, jclass, jlong handle, jobject buffer, jint size, jlong pts_us) {
  const uint8_t *data = static_cast<const uint8_t *>(env->GetDirectBufferAddress(buffer));
  return data ? sv_send(decoder_of(handle), data, size, pts_us) : SV_FAIL;
}

VIDEO_JNI(jint, nativeSendEnd)(JNIEnv *, jclass, jlong handle) { return sv_send_end(decoder_of(handle)); }

// out gets: picture handle, pts in microseconds, display width, height, SV_MATRIX_*, SV_*_FIELD.
VIDEO_JNI(jint, nativeReceive)(JNIEnv *env, jclass, jlong handle, jlongArray out, jboolean make_pixels) {
  SvPicture *picture = nullptr;
  const int status = sv_receive(decoder_of(handle), &picture, make_pixels == JNI_TRUE);
  if (status == SV_OK) {
    const jlong values[6] = {static_cast<jlong>(reinterpret_cast<intptr_t>(picture)), picture->pts_us,
                             picture->display_width,                                    picture->height,
                             picture->matrix,                                           picture->field};
    env->SetLongArrayRegion(out, 0, 6, values);
  }
  return status;
}

VIDEO_JNI(void, nativeFlush)(JNIEnv *, jclass, jlong handle) { sv_flush(decoder_of(handle)); }

VIDEO_JNI(void, nativeReleasePicture)(JNIEnv *, jclass, jlong handle, jlong picture) {
  sv_release_picture(decoder_of(handle), reinterpret_cast<SvPicture *>(static_cast<intptr_t>(picture)));
}

VIDEO_JNI(void, nativeClose)(JNIEnv *, jclass, jlong handle) { sv_close(decoder_of(handle)); }

VIDEO_JNI(jlong, nativeCreateRenderer)(JNIEnv *, jclass) {
  return static_cast<jlong>(reinterpret_cast<intptr_t>(yr_create()));
}

VIDEO_JNI(jint, nativeRender)(JNIEnv *env, jclass, jlong renderer, jobject surface, jlong picture_handle, jlong present_ns) {
  const SvPicture *picture = reinterpret_cast<const SvPicture *>(static_cast<intptr_t>(picture_handle));
  YuvImage image;
  if (!picture || !picture->frame || !image_of(picture, &image)) return YR_FAIL;
  ANativeWindow *window = surface ? ANativeWindow_fromSurface(env, surface) : nullptr;
  const int result = yr_render(reinterpret_cast<YuvRenderer *>(static_cast<intptr_t>(renderer)), window, &image,
                               picture->display_width, picture->height, present_ns);
  if (window) ANativeWindow_release(window);
  return result;
}

VIDEO_JNI(void, nativeDetachSurface)(JNIEnv *, jclass, jlong renderer) {
  yr_detach(reinterpret_cast<YuvRenderer *>(static_cast<intptr_t>(renderer)));
}

VIDEO_JNI(void, nativeDestroyRenderer)(JNIEnv *, jclass, jlong renderer) {
  yr_destroy(reinterpret_cast<YuvRenderer *>(static_cast<intptr_t>(renderer)));
}
