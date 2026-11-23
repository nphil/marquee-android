#ifndef SV_DEINTERLACE_H
#define SV_DEINTERLACE_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Motion-adaptive bob deinterlacer for 8-bit planar frames.
 *
 * An interlaced frame holds two fields, one in the even rows and one in the odd rows, captured half a frame apart.
 * Showing it as it is combs every moving edge. Bobbing shows each field as a picture of its own, so 29.97
 * interlaced frames a second become 59.94 progressive pictures a second.
 *
 * Marquee's own code. FFmpeg's yadif and bwdif do this job, but they are GPL and the FFmpeg in this app is
 * LGPL-only, and nothing here is derived from them: the picture is built from the field's own rows, plus, for the
 * rows between them,
 *   - the row of the same frame woven in, where the picture has not moved since the previous frame, or
 *   - an interpolation from the field's rows above and below it (along whichever of the straight and two diagonal
 *     directions fits best), where it has,
 * mixed by how much it moved so that the change from one to the other does not show.
 *
 * Integer arithmetic only, so every CPU gives the same picture.
 */

/** Bytes of scratch di_bob_frame() needs for planes up to max_width pixels wide. */
size_t di_scratch_size(int max_width);

/**
 * Builds one progressive picture from one field of the frame cur.
 *
 * dst, cur and prev hold three planes each with their strides, plane_w and plane_h their sizes (the chroma planes
 * are smaller than the luma plane, and the rows of every plane belong to alternating fields). prev is the frame
 * before cur, of the same sizes; it may be NULL, and every row between the field's is then interpolated.
 * keep_bottom selects the field to show: 0 copies the even rows (the top field), 1 the odd rows (the bottom field).
 * scratch is di_scratch_size() bytes for the widest plane.
 */
void di_bob_frame(uint8_t *const dst[3], const int dst_stride[3], const uint8_t *const cur[3],
                  const int cur_stride[3], const uint8_t *const prev[3], const int prev_stride[3],
                  const int plane_w[3], const int plane_h[3], int keep_bottom, uint8_t *scratch);

#ifdef __cplusplus
}
#endif

#endif
