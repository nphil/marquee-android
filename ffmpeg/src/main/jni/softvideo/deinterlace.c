#include "deinterlace.h"

#include <string.h>

// A pixel and the ones beside and between the rows around it count as still while the picture changed by less than
// STILL_BELOW since the previous frame, and as moving from MOVING_ABOVE on. A still picture through MPEG-2 compression
// steps by a few levels from frame to frame; an edge that moves by a pixel steps by tens.
#define STILL_BELOW 12
#define MOVING_ABOVE 28
_Static_assert(64 % (MOVING_ABOVE - STILL_BELOW) == 0, "the mix weight must be a whole number of steps");
#define MIX_STEP (64 / (MOVING_ABOVE - STILL_BELOW))

// Room beside each scratch row, so the loops can read one pixel past either end.
#define PAD 16

static inline int iabs(int v) { return v < 0 ? -v : v; }
static inline int imax(int a, int b) { return a > b ? a : b; }

// How much the area around each pixel of the row moved since the previous frame: the largest change over the row
// and the two beside it (ca/cc/cb in the current frame, pa/pc/pb in the previous one). Wide enough for the diagonal
// reach of the interpolation below.
static void change_row(uint8_t *restrict diff, const uint8_t *restrict ca, const uint8_t *restrict pa,
                       const uint8_t *restrict cc, const uint8_t *restrict pc, const uint8_t *restrict cb,
                       const uint8_t *restrict pb, int w) {
  for (int x = 0; x < w; x++) {
    diff[x] = (uint8_t)imax(iabs(ca[x] - pa[x]), imax(iabs(cc[x] - pc[x]), iabs(cb[x] - pb[x])));
  }
}

// One row that the field does not have. above and below are padded copies of the field's rows beside it, weave the
// frame's own row at this height, diff the change row.
static void synthesize_row(uint8_t *restrict dst, const uint8_t *restrict above, const uint8_t *restrict below,
                           const uint8_t *restrict weave, const uint8_t *restrict diff, int w) {
  for (int x = 0; x < w; x++) {
    const int a0 = above[x], b0 = below[x];

    // Edge-directed interpolation: average the pair of pixels, straight or on either diagonal, that look most
    // alike. A tie keeps the straight pair, which is the safest guess.
    int best = iabs(a0 - b0);
    int val = (a0 + b0 + 1) >> 1;
    const int cost_dr = iabs(above[x - 1] - below[x + 1]);
    const int val_dr = (above[x - 1] + below[x + 1] + 1) >> 1;
    val = cost_dr < best ? val_dr : val;
    best = cost_dr < best ? cost_dr : best;
    const int cost_dl = iabs(above[x + 1] - below[x - 1]);
    const int val_dl = (above[x + 1] + below[x - 1] + 1) >> 1;
    val = cost_dl < best ? val_dl : val;

    // The weave weighs 64 where nothing moved and 0 where it did.
    const int moved = imax(diff[x - 1], imax(diff[x], diff[x + 1]));
    int k = (MOVING_ABOVE - moved) * MIX_STEP;
    k = k < 0 ? 0 : (k > 64 ? 64 : k);
    dst[x] = (uint8_t)((val * (64 - k) + weave[x] * k + 32) >> 6);
  }
}

static void pad_row(uint8_t *row, const uint8_t *src, int w) {
  memcpy(row, src, (size_t)w);
  row[-1] = row[0];
  row[w] = row[w - 1];
}

static void bob_plane(uint8_t *dst, int dst_stride, const uint8_t *cur, int cur_stride, const uint8_t *prev,
                      int prev_stride, int w, int h, int keep, uint8_t *scratch) {
  uint8_t *const above = scratch + PAD;
  uint8_t *const below = above + w + 2 * PAD;
  uint8_t *const diff = below + w + 2 * PAD;

  for (int y = 0; y < h; y++) {
    uint8_t *const d = dst + (ptrdiff_t)y * dst_stride;
    const uint8_t *const c = cur + (ptrdiff_t)y * cur_stride;
    if ((y & 1) == keep || h < 2) {
      memcpy(d, c, (size_t)w);
      continue;
    }
    // The field's rows beside this one; at the top or bottom edge only one of them exists, and stands for both.
    const int ya = y > 0 ? y - 1 : y + 1;
    const int yb = y + 1 < h ? y + 1 : y - 1;
    const uint8_t *const ra = cur + (ptrdiff_t)ya * cur_stride;
    const uint8_t *const rb = cur + (ptrdiff_t)yb * cur_stride;
    pad_row(above, ra, w);
    pad_row(below, rb, w);
    if (prev) {
      change_row(diff, ra, prev + (ptrdiff_t)ya * prev_stride, c, prev + (ptrdiff_t)y * prev_stride, rb,
                 prev + (ptrdiff_t)yb * prev_stride, w);
      diff[-1] = diff[0];
      diff[w] = diff[w - 1];
    } else {
      memset(diff - 1, 255, (size_t)w + 2);
    }
    synthesize_row(d, above, below, c, diff, w);
  }
}

size_t di_scratch_size(int max_width) { return 3 * ((size_t)max_width + 2 * PAD); }

void di_bob_frame(uint8_t *const dst[3], const int dst_stride[3], const uint8_t *const cur[3],
                  const int cur_stride[3], const uint8_t *const prev[3], const int prev_stride[3],
                  const int plane_w[3], const int plane_h[3], int keep_bottom, uint8_t *scratch) {
  for (int p = 0; p < 3; p++) {
    if (plane_w[p] < 1 || plane_h[p] < 1) continue;
    bob_plane(dst[p], dst_stride[p], cur[p], cur_stride[p], prev ? prev[p] : NULL, prev ? prev_stride[p] : 0,
              plane_w[p], plane_h[p], keep_bottom & 1, scratch);
  }
}
