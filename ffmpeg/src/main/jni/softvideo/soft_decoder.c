#include "soft_decoder.h"

#include <libavcodec/avcodec.h>
#include <libavutil/common.h>
#include <libavutil/frame.h>
#include <libavutil/mem.h>
#include <libavutil/pixdesc.h>
#include <libavutil/rational.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>

#include "deinterlace.h"
#include "sv_log.h"

// Pictures that were deinterlaced into buffers of ours keep them for the next ones.
#define POOL_FRAMES 8

// A decoder that fails this many times in a row, without one good picture between, is not going to recover.
#define MAX_CONSECUTIVE_ERRORS 100

// Used until the stream says its frame rate: 29.97 frames a second.
#define DEFAULT_FRAME_US 33367

struct SvDecoder {
  AVCodecContext *ctx;
  AVPacket *packet;
  AVFrame *frame; // where the decoder puts what it decodes
  AVFrame *cur;   // an interlaced frame whose second field is still to come out
  AVFrame *prev;  // the frame before it, to tell what moved
  int pending_second;
  int top_first;       // field order of cur
  int64_t cur_pts_us;  // when cur is shown
  int64_t prev_pts_us; // when prev was shown; AV_NOPTS_VALUE if there is no prev
  int64_t last_pts_us; // the last frame's time, to fill in a missing one
  int errors;
  uint8_t *scratch;
  size_t scratch_size;

  pthread_mutex_t lock; // guards live and pool: pictures are released from another thread
  SvPicture *live;
  AVFrame *pool[POOL_FRAMES];
  int pool_count;
};

int sv_has_decoder(void) { return avcodec_find_decoder_by_name("mpeg2video") != NULL; }

SvDecoder *sv_open(const uint8_t *extradata, int extradata_size, int threads) {
  const AVCodec *codec = avcodec_find_decoder_by_name("mpeg2video");
  if (!codec) {
    SV_LOGE("no mpeg2video decoder in this FFmpeg build");
    return NULL;
  }
  SvDecoder *d = (SvDecoder *)calloc(1, sizeof(*d));
  if (!d) return NULL;
  pthread_mutex_init(&d->lock, NULL);
  d->prev_pts_us = AV_NOPTS_VALUE;
  d->last_pts_us = AV_NOPTS_VALUE;
  d->packet = av_packet_alloc();
  d->frame = av_frame_alloc();
  d->cur = av_frame_alloc();
  d->prev = av_frame_alloc();
  d->ctx = avcodec_alloc_context3(codec);
  if (!d->packet || !d->frame || !d->cur || !d->prev || !d->ctx) goto fail;

  // The decoder has slice threads only: MPEG-2 pictures depend on the one before, so frames cannot decode apart.
  d->ctx->thread_type = FF_THREAD_SLICE;
  d->ctx->thread_count = threads > 1 ? threads : 1;
  d->ctx->pkt_timebase = (AVRational){1, 1000000};
  if (extradata && extradata_size > 0) {
    d->ctx->extradata = (uint8_t *)av_mallocz((size_t)extradata_size + AV_INPUT_BUFFER_PADDING_SIZE);
    if (!d->ctx->extradata) goto fail;
    memcpy(d->ctx->extradata, extradata, (size_t)extradata_size);
    d->ctx->extradata_size = extradata_size;
  }
  const int r = avcodec_open2(d->ctx, codec, NULL);
  if (r < 0) {
    SV_LOGE("avcodec_open2 failed: %d", r);
    goto fail;
  }
  return d;

fail:
  sv_close(d);
  return NULL;
}

static int64_t frame_duration_us(const SvDecoder *d) {
  const AVRational rate = d->ctx->framerate;
  return rate.num > 0 && rate.den > 0 ? av_rescale(1000000, rate.den, rate.num) : DEFAULT_FRAME_US;
}

static int is_interlaced(const AVFrame *f) {
#ifdef AV_FRAME_FLAG_INTERLACED
  return (f->flags & AV_FRAME_FLAG_INTERLACED) != 0;
#else
  return f->interlaced_frame != 0;
#endif
}

static int is_top_field_first(const AVFrame *f) {
#ifdef AV_FRAME_FLAG_TOP_FIELD_FIRST
  return (f->flags & AV_FRAME_FLAG_TOP_FIELD_FIRST) != 0;
#else
  return f->top_field_first != 0;
#endif
}

static int pixel_format_ok(int format) {
  const AVPixFmtDescriptor *desc = av_pix_fmt_desc_get((enum AVPixelFormat)format);
  return desc && desc->nb_components == 3 && (desc->flags & AV_PIX_FMT_FLAG_PLANAR) &&
         !(desc->flags & (AV_PIX_FMT_FLAG_RGB | AV_PIX_FMT_FLAG_PAL | AV_PIX_FMT_FLAG_HWACCEL)) &&
         desc->comp[0].depth == 8 && desc->comp[1].depth == 8 && desc->comp[2].depth == 8;
}

static void plane_sizes(const AVFrame *f, int width[3], int height[3]) {
  const AVPixFmtDescriptor *desc = av_pix_fmt_desc_get((enum AVPixelFormat)f->format);
  width[0] = f->width;
  height[0] = f->height;
  width[1] = width[2] = AV_CEIL_RSHIFT(f->width, desc->log2_chroma_w);
  height[1] = height[2] = AV_CEIL_RSHIFT(f->height, desc->log2_chroma_h);
}

static int matrix_of(const AVFrame *f) {
  switch (f->colorspace) {
    case AVCOL_SPC_BT709:
      return SV_MATRIX_BT709;
    case AVCOL_SPC_BT470BG:
    case AVCOL_SPC_SMPTE170M:
    case AVCOL_SPC_SMPTE240M:
      return SV_MATRIX_BT601;
    default: // not said: HD is BT.709 and SD BT.601, the way broadcasters and every player treat it
      return f->height > 576 ? SV_MATRIX_BT709 : SV_MATRIX_BT601;
  }
}

static int display_width_of(const AVFrame *f) {
  const AVRational sar = f->sample_aspect_ratio;
  if (sar.num <= 0 || sar.den <= 0) return f->width;
  const int64_t w = av_rescale(f->width, sar.num, sar.den);
  // A pixel aspect ratio that makes a picture over twice as wide or narrow is a damaged header, not a shape.
  return w >= f->width / 2 && w <= (int64_t)f->width * 2 ? (int)w : f->width;
}

static AVFrame *pool_take(SvDecoder *d, const AVFrame *like) {
  AVFrame *frame = NULL;
  pthread_mutex_lock(&d->lock);
  for (int i = 0; i < d->pool_count; i++) {
    AVFrame *candidate = d->pool[i];
    if (candidate->width == like->width && candidate->height == like->height && candidate->format == like->format) {
      frame = candidate;
      d->pool[i] = d->pool[--d->pool_count];
      break;
    }
  }
  if (!frame) {
    // The stream changed size; the buffers kept for the old one are of no use.
    while (d->pool_count > 0) {
      AVFrame *stale = d->pool[--d->pool_count];
      av_frame_free(&stale);
    }
  }
  pthread_mutex_unlock(&d->lock);
  if (frame) return frame;

  frame = av_frame_alloc();
  if (!frame) return NULL;
  frame->width = like->width;
  frame->height = like->height;
  frame->format = like->format;
  if (av_frame_get_buffer(frame, 32) < 0) {
    av_frame_free(&frame);
    return NULL;
  }
  return frame;
}

static void pool_put(SvDecoder *d, AVFrame *frame) {
  pthread_mutex_lock(&d->lock);
  if (d->pool_count < POOL_FRAMES) {
    d->pool[d->pool_count++] = frame;
    frame = NULL;
  }
  pthread_mutex_unlock(&d->lock);
  av_frame_free(&frame); // NULL when it went to the pool
}

static SvPicture *picture_new(SvDecoder *d, AVFrame *frame, int owned, const AVFrame *source, int64_t pts_us,
                              int field) {
  SvPicture *pic = (SvPicture *)calloc(1, sizeof(*pic));
  if (!pic) {
    if (frame) {
      if (owned) {
        pool_put(d, frame);
      } else {
        av_frame_free(&frame);
      }
    }
    return NULL;
  }
  pic->frame = frame;
  pic->owned = owned;
  pic->pts_us = pts_us;
  pic->display_width = display_width_of(source);
  pic->height = source->height;
  pic->matrix = matrix_of(source);
  pic->full_range = source->color_range == AVCOL_RANGE_JPEG;
  pic->field = field;
  pthread_mutex_lock(&d->lock);
  pic->live_next = d->live;
  if (d->live) d->live->live_prev = pic;
  d->live = pic;
  pthread_mutex_unlock(&d->lock);
  return pic;
}

void sv_release_picture(SvDecoder *d, SvPicture *pic) {
  if (!pic) return;
  AVFrame *frame = pic->frame;
  const int owned = pic->owned;
  pthread_mutex_lock(&d->lock);
  if (pic->live_prev) {
    pic->live_prev->live_next = pic->live_next;
  } else {
    d->live = pic->live_next;
  }
  if (pic->live_next) pic->live_next->live_prev = pic->live_prev;
  pthread_mutex_unlock(&d->lock);
  free(pic);
  if (!frame) return;
  if (owned) {
    pool_put(d, frame);
  } else {
    av_frame_free(&frame);
  }
}

// The picture for one field of cur: its rows, and the rows between them, rebuilt (deinterlace.h).
static SvPicture *field_picture(SvDecoder *d, int keep_bottom, int64_t pts_us, int field) {
  const AVFrame *cur = d->cur;
  int width[3], height[3];
  plane_sizes(cur, width, height);

  const size_t scratch_size = di_scratch_size(width[0]);
  if (d->scratch_size < scratch_size) {
    uint8_t *grown = (uint8_t *)realloc(d->scratch, scratch_size);
    if (!grown) return NULL;
    d->scratch = grown;
    d->scratch_size = scratch_size;
  }

  AVFrame *out = pool_take(d, cur);
  if (!out) return NULL;

  // The previous frame only tells what moved, and only if it is the frame just before, of the same size.
  const AVFrame *prev = d->prev;
  const int usable_prev = prev->data[0] && prev->width == cur->width && prev->height == cur->height &&
                          prev->format == cur->format && d->prev_pts_us != AV_NOPTS_VALUE &&
                          d->cur_pts_us - d->prev_pts_us <= 3 * frame_duration_us(d) &&
                          d->cur_pts_us >= d->prev_pts_us;

  uint8_t *const dst[3] = {out->data[0], out->data[1], out->data[2]};
  const int dst_stride[3] = {out->linesize[0], out->linesize[1], out->linesize[2]};
  const uint8_t *const cur_data[3] = {cur->data[0], cur->data[1], cur->data[2]};
  const int cur_stride[3] = {cur->linesize[0], cur->linesize[1], cur->linesize[2]};
  const uint8_t *const prev_data[3] = {prev->data[0], prev->data[1], prev->data[2]};
  const int prev_stride[3] = {prev->linesize[0], prev->linesize[1], prev->linesize[2]};
  di_bob_frame(dst, dst_stride, cur_data, cur_stride, usable_prev ? prev_data : NULL, prev_stride, width, height,
               keep_bottom, d->scratch);

  return picture_new(d, out, 1, cur, pts_us, field);
}

int sv_send(SvDecoder *d, const uint8_t *data, int size, int64_t pts_us) {
  if (size <= 0) return SV_OK;
  // The decoder reads a little past the end of what it is given, so the bytes go to a buffer with room for it.
  if (av_new_packet(d->packet, size) < 0) return SV_FAIL;
  memcpy(d->packet->data, data, (size_t)size);
  d->packet->pts = pts_us;
  d->packet->dts = AV_NOPTS_VALUE;
  const int r = avcodec_send_packet(d->ctx, d->packet);
  av_packet_unref(d->packet);
  if (r == 0 || r == AVERROR_INVALIDDATA) return SV_OK;
  SV_LOGE("avcodec_send_packet failed: %d", r);
  return SV_FAIL;
}

int sv_send_end(SvDecoder *d) {
  const int r = avcodec_send_packet(d->ctx, NULL);
  return r == 0 || r == AVERROR_EOF ? SV_OK : SV_FAIL;
}

static void remember_frame_as_prev(SvDecoder *d, const AVFrame *frame, int64_t pts_us) {
  av_frame_unref(d->prev);
  av_frame_ref(d->prev, frame);
  d->prev_pts_us = pts_us;
}

int sv_receive(SvDecoder *d, SvPicture **out, int make_pixels) {
  *out = NULL;

  if (d->pending_second) {
    d->pending_second = 0;
    const int64_t pts_us = d->cur_pts_us + frame_duration_us(d) / 2;
    SvPicture *pic = field_picture(d, d->top_first ? 1 : 0, pts_us, SV_SECOND_FIELD);
    // Done with the frame: it is the one before the next.
    av_frame_unref(d->prev);
    av_frame_move_ref(d->prev, d->cur);
    d->prev_pts_us = d->cur_pts_us;
    if (!pic) return SV_FAIL;
    *out = pic;
    return SV_OK;
  }

  for (;;) {
    const int r = avcodec_receive_frame(d->ctx, d->frame);
    if (r == AVERROR(EAGAIN)) return SV_AGAIN;
    if (r == AVERROR_EOF) return SV_END;
    if (r < 0) {
      // Damaged data in the stream: the decoder has dropped the picture and goes on with the next.
      if (++d->errors >= MAX_CONSECUTIVE_ERRORS) {
        SV_LOGE("the decoder keeps failing (%d)", r);
        return SV_FAIL;
      }
      continue;
    }
    d->errors = 0;

    AVFrame *frame = d->frame;
    if (!pixel_format_ok(frame->format)) {
      SV_LOGE("unsupported pixel format %d", frame->format);
      av_frame_unref(frame);
      return SV_FAIL;
    }
    int64_t pts_us = frame->pts;
    if (pts_us == AV_NOPTS_VALUE) pts_us = frame->best_effort_timestamp;
    if (pts_us == AV_NOPTS_VALUE) {
      pts_us = d->last_pts_us == AV_NOPTS_VALUE ? 0 : d->last_pts_us + frame_duration_us(d);
    }
    d->last_pts_us = pts_us;

    if (!make_pixels) {
      *out = picture_new(d, NULL, 0, frame, pts_us, SV_WHOLE_FRAME);
      av_frame_unref(frame);
      return *out ? SV_OK : SV_FAIL;
    }

    if (!is_interlaced(frame)) {
      // 720p, film, and the frames of a broadcast that are progressive: shown as they are.
      AVFrame *shown = av_frame_clone(frame);
      SvPicture *pic = shown ? picture_new(d, shown, 0, frame, pts_us, SV_WHOLE_FRAME) : NULL;
      remember_frame_as_prev(d, frame, pts_us);
      av_frame_unref(frame);
      if (!pic) return SV_FAIL;
      *out = pic;
      return SV_OK;
    }

    // Interlaced: the first field comes out now, the second at the next call.
    d->top_first = is_top_field_first(frame);
    d->cur_pts_us = pts_us;
    av_frame_unref(d->cur);
    av_frame_move_ref(d->cur, frame);
    SvPicture *pic = field_picture(d, d->top_first ? 0 : 1, pts_us, SV_FIRST_FIELD);
    if (!pic) return SV_FAIL;
    d->pending_second = 1;
    *out = pic;
    return SV_OK;
  }
}

void sv_flush(SvDecoder *d) {
  avcodec_flush_buffers(d->ctx);
  av_frame_unref(d->frame);
  av_frame_unref(d->cur);
  av_frame_unref(d->prev);
  d->pending_second = 0;
  d->prev_pts_us = AV_NOPTS_VALUE;
  d->last_pts_us = AV_NOPTS_VALUE;
  d->errors = 0;
}

void sv_close(SvDecoder *d) {
  if (!d) return;
  while (d->live) sv_release_picture(d, d->live);
  while (d->pool_count > 0) av_frame_free(&d->pool[--d->pool_count]);
  avcodec_free_context(&d->ctx);
  av_packet_free(&d->packet);
  av_frame_free(&d->frame);
  av_frame_free(&d->cur);
  av_frame_free(&d->prev);
  free(d->scratch);
  pthread_mutex_destroy(&d->lock);
  free(d);
}
