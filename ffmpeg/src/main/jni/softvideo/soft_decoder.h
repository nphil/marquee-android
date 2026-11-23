#ifndef SV_SOFT_DECODER_H
#define SV_SOFT_DECODER_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

struct AVFrame;

enum { SV_MATRIX_BT601 = 0, SV_MATRIX_BT709 = 1 };

/** Where a picture sits in the frame it was made from. */
enum { SV_WHOLE_FRAME = 0, SV_FIRST_FIELD = 1, SV_SECOND_FIELD = 2 };

/** A picture to show: one instant as a whole image, so the planes can be uploaded as they are. */
typedef struct SvPicture {
  struct AVFrame *frame; /* NULL when nobody will look at the picture */
  int64_t pts_us;
  int display_width; /* the width after the pixel aspect ratio: what shape the picture is shown in */
  int height;        /* rows in the picture, which the pixel aspect ratio leaves alone */
  int matrix;        /* SV_MATRIX_* */
  int full_range;    /* luma spans 0..255, not 16..235 */
  int field;         /* SV_WHOLE_FRAME, SV_FIRST_FIELD or SV_SECOND_FIELD */
  int owned;         /* frame was made for this picture, and goes back to the pool with it */
  struct SvPicture *live_prev, *live_next;
} SvPicture;

typedef struct SvDecoder SvDecoder;

enum { SV_OK = 0, SV_AGAIN = 1, SV_END = 2, SV_FAIL = -1 };

/** Whether the FFmpeg libraries have the MPEG-2 video decoder. */
int sv_has_decoder(void);

/**
 * Opens a decoder. extradata is the stream's sequence header and its extension, or NULL. The decoder works on
 * `threads` slice threads (1 for none). Returns NULL, after logging why, if it cannot be opened.
 */
SvDecoder *sv_open(const uint8_t *extradata, int extradata_size, int threads);

/**
 * Feeds one coded picture. Data FFmpeg cannot use is dropped and the stream carries on, as a hardware decoder
 * would; SV_FAIL means the decoder is broken.
 */
int sv_send(SvDecoder *d, const uint8_t *data, int size, int64_t pts_us);

/** Ends the stream: sv_receive() hands out the pictures still held back, then SV_END. */
int sv_send_end(SvDecoder *d);

/**
 * Takes the next picture, in the order they are shown. An interlaced frame comes out as two, one per field.
 * SV_AGAIN asks for more input. With make_pixels 0 the picture is only counted, without a frame.
 * The picture belongs to the caller until sv_release_picture().
 */
int sv_receive(SvDecoder *d, SvPicture **out, int make_pixels);

/** Forgets everything in flight, for a seek. Pictures already handed out stay valid. */
void sv_flush(SvDecoder *d);

void sv_release_picture(SvDecoder *d, SvPicture *picture);

/** Closes the decoder and frees every picture, handed out or not. */
void sv_close(SvDecoder *d);

#ifdef __cplusplus
}
#endif

#endif
