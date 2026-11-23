package com.nphil.marquee.ffmpeg;

import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import android.view.Surface;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.CryptoConfig;
import androidx.media3.decoder.Decoder;
import androidx.media3.decoder.DecoderException;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.VideoDecoderOutputBuffer;
import androidx.media3.exoplayer.DecoderReuseEvaluation;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.FormatHolder;
import androidx.media3.exoplayer.RendererCapabilities;
import androidx.media3.exoplayer.video.DecoderVideoRenderer;
import androidx.media3.exoplayer.video.VideoRendererEventListener;
import java.util.function.BooleanSupplier;

/**
 * Plays MPEG-2 video with FFmpeg's software decoder, for tablets whose codec list has no MPEG-2 decoder.
 *
 * <p>It is a second video renderer next to MediaCodecVideoRenderer, and it only claims MPEG-2 when the app says the
 * platform has no decoder for it, so a hardware decoder always keeps the stream. Interlaced pictures are deinterlaced
 * into one picture per field, so 1080i59.94 is shown as 59.94 pictures a second. Pictures are drawn with OpenGL
 * straight onto the surface the player was given, at the picture's own size; the compositor scales them.
 */
@UnstableApi
public final class FfmpegVideoRenderer extends DecoderVideoRenderer {

  private static final String TAG = "FfmpegVideoRenderer";

  private static final int INPUT_BUFFER_COUNT = 6;
  // Two pictures a frame at up to 60 frames a second: this many is about 150 ms of decoding ahead of the screen.
  private static final int OUTPUT_BUFFER_COUNT = 10;
  // Slice threads: more of them does not speed up what a picture has to do, which is 30 to 68 slices.
  private static final int MAX_THREADS = 4;
  // A picture is handed to the compositor at most this early, which keeps two or three in the buffer queue.
  private static final long MAX_AHEAD_US = 50_000;
  // A surface that cannot be drawn on for this long (5 seconds at 60 pictures a second) is not coming back.
  private static final int MAX_CONSECUTIVE_SKIPS = 300;

  private final BooleanSupplier softwareWanted;
  private final int threads;

  @Nullable private FfmpegVideoDecoder decoder;
  private long eglRenderer; // the native renderer, 0 until a picture is drawn
  @Nullable private Surface drawnSurface;
  private int consecutiveSkips;

  // What render() last was told, to say how long before the screen a picture is.
  private long positionUs;
  private long renderElapsedRealtimeUs;
  private float playbackSpeed = 1;

  private float inputFrameRate = Format.NO_VALUE;
  private boolean interlaced;
  @Nullable private Surface hintedSurface;
  private float hintedFrameRate;

  /**
   * @param softwareWanted Whether the platform has no MPEG-2 decoder, asked when a format is evaluated, on the
   *     playback thread. While it is false this renderer supports nothing, and MediaCodec plays MPEG-2.
   */
  public FfmpegVideoRenderer(
      long allowedJoiningTimeMs,
      @Nullable Handler eventHandler,
      @Nullable VideoRendererEventListener eventListener,
      int maxDroppedFramesToNotify,
      BooleanSupplier softwareWanted) {
    super(allowedJoiningTimeMs, eventHandler, eventListener, maxDroppedFramesToNotify);
    this.softwareWanted = softwareWanted;
    threads = Math.max(1, Math.min(MAX_THREADS, Runtime.getRuntime().availableProcessors()));
  }

  /** Whether the FFmpeg libraries are loaded and have the MPEG-2 video decoder. */
  public static boolean isAvailable() {
    return FfmpegVideoDecoder.isAvailable();
  }

  @Override
  public String getName() {
    return TAG;
  }

  @Override
  public @RendererCapabilities.Capabilities int supportsFormat(Format format) {
    if (!MimeTypes.VIDEO_MPEG2.equals(format.sampleMimeType)) {
      return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE);
    }
    boolean libraryAvailable = FfmpegVideoDecoder.isAvailable();
    boolean wanted = libraryAvailable && softwareWanted.getAsBoolean();
    // The one trace of why a channel's MPEG-2 was, or was not, taken here instead of by MediaCodec.
    Log.d(TAG, "video/mpeg2: libraryAvailable=" + libraryAvailable + " softwareWanted=" + wanted);
    if (!wanted) {
      return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE);
    }
    if (format.cryptoType != C.CRYPTO_TYPE_NONE) {
      return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_DRM);
    }
    return RendererCapabilities.create(C.FORMAT_HANDLED, ADAPTIVE_SEAMLESS, TUNNELING_NOT_SUPPORTED);
  }

  @Override
  protected Decoder<DecoderInputBuffer, ? extends VideoDecoderOutputBuffer, ? extends DecoderException>
      createDecoder(Format format, @Nullable CryptoConfig cryptoConfig) throws DecoderException {
    FfmpegVideoDecoder created =
        new FfmpegVideoDecoder(format, INPUT_BUFFER_COUNT, OUTPUT_BUFFER_COUNT, threads);
    decoder = created;
    interlaced = false;
    return created;
  }

  @Override
  protected void releaseDecoder() {
    super.releaseDecoder();
    decoder = null;
  }

  @Override
  protected void setDecoderOutputMode(@C.VideoOutputMode int outputMode) {
    if (decoder != null) {
      decoder.setOutputMode(outputMode);
    }
  }

  @Override
  protected DecoderReuseEvaluation canReuseDecoder(String decoderName, Format oldFormat, Format newFormat) {
    // libavcodec follows a new sequence header, with another size, aspect or frame rate, by itself: a channel
    // that switches between SD and HD does not have to be reopened.
    return new DecoderReuseEvaluation(
        decoderName,
        oldFormat,
        newFormat,
        DecoderReuseEvaluation.REUSE_RESULT_YES_WITHOUT_RECONFIGURATION,
        /* discardReasons= */ 0);
  }

  @Override
  protected void onInputFormatChanged(FormatHolder formatHolder) throws ExoPlaybackException {
    super.onInputFormatChanged(formatHolder);
    inputFrameRate = formatHolder.format != null ? formatHolder.format.frameRate : Format.NO_VALUE;
  }

  @Override
  public void handleMessage(int messageType, @Nullable Object message) throws ExoPlaybackException {
    if (messageType == MSG_SET_VIDEO_OUTPUT && message != drawnSurface) {
      // The OpenGL window holds the surface it draws to, and nothing else can connect to that surface until it
      // lets go: a MediaCodec that plays the next programme, or this renderer on another surface.
      detach();
    }
    super.handleMessage(messageType, message);
  }

  @Override
  protected void onDisabled() {
    detach();
    if (eglRenderer != 0) {
      FfmpegVideoDecoder.destroyRenderer(eglRenderer);
      eglRenderer = 0;
    }
    super.onDisabled();
  }

  @Override
  public void setPlaybackSpeed(float currentPlaybackSpeed, float targetPlaybackSpeed)
      throws ExoPlaybackException {
    super.setPlaybackSpeed(currentPlaybackSpeed, targetPlaybackSpeed);
    playbackSpeed = currentPlaybackSpeed > 0 ? currentPlaybackSpeed : 1;
  }

  @Override
  public void render(long positionUs, long elapsedRealtimeUs) throws ExoPlaybackException {
    this.positionUs = positionUs;
    this.renderElapsedRealtimeUs = elapsedRealtimeUs;
    super.render(positionUs, elapsedRealtimeUs);
  }

  @Override
  protected void renderOutputBufferToSurface(VideoDecoderOutputBuffer outputBuffer, Surface surface)
      throws DecoderException {
    try {
      if (!surface.isValid()) {
        countSkippedPicture();
        return;
      }
      if (eglRenderer == 0) {
        eglRenderer = FfmpegVideoDecoder.createRenderer();
        if (eglRenderer == 0) {
          throw new DecoderException("Could not create the OpenGL renderer for MPEG-2 pictures");
        }
      }
      if (outputBuffer instanceof FfmpegVideoDecoder.Picture
          && ((FfmpegVideoDecoder.Picture) outputBuffer).field != FfmpegVideoDecoder.FIELD_WHOLE_FRAME) {
        interlaced = true;
      }
      hintFrameRate(surface);
      drawnSurface = surface;
      int result =
          FfmpegVideoDecoder.render(eglRenderer, surface, outputBuffer, presentationTimeNs(outputBuffer.timeUs));
      if (result == FfmpegVideoDecoder.RENDER_OK) {
        consecutiveSkips = 0;
      } else if (result == FfmpegVideoDecoder.RENDER_SKIPPED) {
        countSkippedPicture();
      } else {
        throw new DecoderException("OpenGL cannot draw MPEG-2 pictures on this surface");
      }
    } finally {
      outputBuffer.release();
    }
  }

  private void countSkippedPicture() throws DecoderException {
    if (++consecutiveSkips >= MAX_CONSECUTIVE_SKIPS) {
      throw new DecoderException("The video surface could not be drawn on for " + consecutiveSkips + " pictures");
    }
  }

  private void detach() {
    if (eglRenderer != 0) {
      FfmpegVideoDecoder.detachRenderer(eglRenderer);
    }
    drawnSurface = null;
    hintedSurface = null;
  }

  /**
   * When the screen should show a picture, on the System.nanoTime() clock: the time left until its place in the
   * stream, as measured when render() began, less what has passed since.
   */
  private long presentationTimeNs(long timeUs) {
    long sinceRenderUs = SystemClock.elapsedRealtime() * 1000 - renderElapsedRealtimeUs;
    long untilShownUs = (long) ((timeUs - positionUs) / playbackSpeed) - sinceRenderUs;
    return System.nanoTime() + Math.max(0, Math.min(untilShownUs, MAX_AHEAD_US)) * 1000;
  }

  /**
   * Tells the display how many pictures a second this surface gets, so a panel with several refresh rates picks one
   * that shows each picture for the same time. An interlaced frame is two pictures.
   */
  private void hintFrameRate(Surface surface) {
    if (Build.VERSION.SDK_INT < 30 || inputFrameRate <= 0) {
      return;
    }
    float frameRate = interlaced ? 2 * inputFrameRate : inputFrameRate;
    if (surface == hintedSurface && frameRate == hintedFrameRate) {
      return;
    }
    hintedSurface = surface;
    hintedFrameRate = frameRate;
    try {
      if (Build.VERSION.SDK_INT >= 31) {
        surface.setFrameRate(
            frameRate, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE, Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS);
      } else {
        surface.setFrameRate(frameRate, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE);
      }
    } catch (IllegalStateException | IllegalArgumentException e) {
      Log.w(TAG, "Could not tell the display the frame rate", e);
    }
  }
}
