package com.nphil.marquee.playback

/** A trailer under assets/trailers, and the bars baked into its first picture. */
internal class Clip(val file: String, val bars: Bars)

/**
 * The trailers the instrumented tests play. Each is 640x360, 15 frames a second, H.264 Constrained Baseline, no sound,
 * and a bright moving picture (a four-colour gradient drifting under a white square), so the only black in a clip is a
 * bar that was baked in. The bars are multiples of 16 pixels, which keeps the encoder's blocks from smearing their
 * edges.
 *
 *  - flat: 4 s, the picture fills the frame.
 *  - scope: 12 s, a letterbox: a 640x264 picture with 48 pixels of black above and below.
 *  - pillar: 4 s, a pillarbox: a 480x360 picture with 80 pixels of black at each side.
 *  - switch: 7.5 s, full frame for 2 s, then 2.5 s of the letterbox in another hue, then full frame for 3 s.
 *
 * Made with ffmpeg 5.1 and libx264; the gradient's seed is fixed, so the same commands give the same pictures:
 *
 *     pic() { echo "gradients=s=$1:r=15:d=$2:speed=0.04:seed=7:nb_colors=4:c0=0xff9a3d:c1=0x2dc8ff:c2=0xc8ff3d:c3=0xff3da6[bg];color=c=white:s=48x48:r=15:d=$2[box];[bg][box]overlay=x='mod(t*170,W-w)':y='(H-h)/2+(H-h)/2.4*sin(t*3)'"; }
 *     ENC="scale=out_color_matrix=bt709:out_range=tv,format=yuv420p"
 *     OUT="-c:v libx264 -profile:v baseline -level 3.0 -preset veryslow -crf 28 -g 30 -an -movflags +faststart -color_primaries bt709 -color_trc bt709 -colorspace bt709 -color_range tv"
 *     ffmpeg -y -v error -filter_complex "$(pic 640x360 4),$ENC" $OUT flat.mp4
 *     ffmpeg -y -v error -filter_complex "$(pic 640x264 12),pad=640:360:0:48:black,$ENC" $OUT scope.mp4
 *     ffmpeg -y -v error -filter_complex "$(pic 480x360 4),pad=640:360:80:0:black,$ENC" $OUT pillar.mp4
 *     ffmpeg -y -v error -filter_complex "$(pic 640x360 7.5),split=3[b1][b2][b3];[b1]trim=0:2,setpts=PTS-STARTPTS[a];[b2]trim=2:4.5,setpts=PTS-STARTPTS,scale=640:264,setsar=1,hue=h=90,pad=640:360:0:48:black[b];[b3]trim=4.5:7.5,setpts=PTS-STARTPTS[c];[a][b][c]concat=n=3:v=1:a=0,$ENC" $OUT switch.mp4
 */
internal object Clips {
	val Flat = Clip("trailers/flat.mp4", Bars.None)
	val Scope = Clip("trailers/scope.mp4", Bars(top = 48 / 360f, bottom = 48 / 360f))
	val Pillar = Clip("trailers/pillar.mp4", Bars(left = 80 / 640f, right = 80 / 640f))

	/** Opens on the full frame; [SwitchLetterboxedAt] and [SwitchFullAt] are inside its other two shots. */
	val Switch = Clip("trailers/switch.mp4", Bars.None)
	const val SwitchLetterboxedAt = 3_000L
	const val SwitchFullAt = 5_500L
}
