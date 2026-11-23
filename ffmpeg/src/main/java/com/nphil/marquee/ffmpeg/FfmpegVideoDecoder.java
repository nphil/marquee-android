package com.nphil.marquee.ffmpeg;

import android.view.Surface;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.Decoder;
import androidx.media3.decoder.DecoderException;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.DecoderOutputBuffer;
import androidx.media3.decoder.VideoDecoderOutputBuffer;
import androidx.media3.decoder.ffmpeg.FfmpegLibrary;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.concurrent.Executor;

/**
 * Decodes MPEG-2 video with FFmpeg on a thread of its own, and hands out the pictures in the order they are shown.
 *
 * <p>The structure is Media3's SimpleDecoder, with one difference: an input buffer is a frame, and a frame can come
 * out as no picture (the decoder holds it back to reorder) or as two (an interlaced frame is deinterlaced into one
 * picture per field, soft_decoder.h). The slice threads and the deinterlacer run on the decode thread, so the
 * player's thread only draws.
 */
@UnstableApi
final class FfmpegVideoDecoder
    implements Decoder<DecoderInputBuffer, VideoDecoderOutputBuffer, DecoderException>,
        DecoderOutputBuffer.Owner<VideoDecoderOutputBuffer> {

  // The values of SV_*_FIELD, SV_MATRIX_*, SV_OK.. in soft_decoder.h and YR_* in yuv_egl.h.
  static final int FIELD_WHOLE_FRAME = 0;
  static final int FIELD_SECOND = 2;
  private static final int MATRIX_BT709 = 1;
  private static final int STATUS_OK = 0;
  private static final int STATUS_AGAIN = 1;
  private static final int STATUS_END = 2;
  static final int RENDER_OK = 0;
  static final int RENDER_SKIPPED = 1;

  /** What input buffers start at when the stream does not say how large its frames get; a larger one grows it. */
  private static final int DEFAULT_INPUT_BUFFER_SIZE = 256 * 1024;

  /** A picture to show. */
  static final class Picture extends VideoDecoderOutputBuffer {
    /** FIELD_WHOLE_FRAME, or which field of its frame this picture was made from. */
    int field;

    Picture(Owner<VideoDecoderOutputBuffer> owner) {
      super(owner);
    }

    @Override
    public void clear() {
      super.clear();
      field = FIELD_WHOLE_FRAME;
    }
  }

  private final String name;
  private final Thread decodeThread;
  private final long[] received = new long[6]; // the decode thread's: what nativeReceive reports

  private final Object lock = new Object();
  private final ArrayDeque<DecoderInputBuffer> queuedInputBuffers = new ArrayDeque<>();
  private final ArrayDeque<VideoDecoderOutputBuffer> queuedOutputBuffers = new ArrayDeque<>();
  private final DecoderInputBuffer[] availableInputBuffers;
  private final VideoDecoderOutputBuffer[] availableOutputBuffers;

  private int availableInputBufferCount;
  private int availableOutputBufferCount;
  @Nullable private DecoderInputBuffer dequeuedInputBuffer;
  @Nullable private DecoderException exception;
  private boolean flushed;
  private boolean released;
  private long outputStartTimeUs = C.TIME_UNSET;
  // Frames that were decoded but not shown, to be told to the renderer with the next picture.
  private int skippedFrameCount;
  @Nullable private Executor executor;
  @Nullable private Runnable onOutputBufferAvailable;
  @Nullable private Runnable onInputBufferAvailable;

  private long handle; // the native decoder; 0 once released
  private volatile @C.VideoOutputMode int outputMode = C.VIDEO_OUTPUT_MODE_NONE;

  /**
   * @param threads How many slice threads the decoder works on, or 1 for none.
   * @throws DecoderException If the decoder cannot be opened.
   */
  FfmpegVideoDecoder(Format format, int inputBufferCount, int outputBufferCount, int threads)
      throws DecoderException {
    if (!FfmpegLibrary.isAvailable()) {
      throw new DecoderException("The FFmpeg library is not loaded");
    }
    @Nullable
    byte[] extradata = format.initializationData.isEmpty() ? null : format.initializationData.get(0);
    handle = nativeOpen(extradata, threads);
    if (handle == 0) {
      throw new DecoderException("The FFmpeg MPEG-2 decoder did not open");
    }
    name = "ffmpeg" + FfmpegLibrary.getVersion() + "-mpeg2video";

    int inputSize = format.maxInputSize != Format.NO_VALUE ? format.maxInputSize : DEFAULT_INPUT_BUFFER_SIZE;
    availableInputBuffers = new DecoderInputBuffer[inputBufferCount];
    for (int i = 0; i < inputBufferCount; i++) {
      DecoderInputBuffer buffer = new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT);
      buffer.ensureSpaceForWrite(inputSize);
      availableInputBuffers[i] = buffer;
    }
    availableInputBufferCount = inputBufferCount;
    availableOutputBuffers = new VideoDecoderOutputBuffer[outputBufferCount];
    for (int i = 0; i < outputBufferCount; i++) {
      availableOutputBuffers[i] = new Picture(this);
    }
    availableOutputBufferCount = outputBufferCount;

    decodeThread =
        new Thread("Marquee:FfmpegVideoDecoder") {
          @Override
          public void run() {
            FfmpegVideoDecoder.this.run();
          }
        };
    decodeThread.start();
  }

  /** Whether the FFmpeg libraries are loaded and have the MPEG-2 video decoder. */
  static boolean isAvailable() {
    if (!FfmpegLibrary.isAvailable()) {
      return false;
    }
    try {
      return nativeHasDecoder();
    } catch (UnsatisfiedLinkError e) {
      // Libraries from before the video decoder were put in place; they still serve the audio.
      return false;
    }
  }

  /**
   * Sets what the pictures are made for: {@link C#VIDEO_OUTPUT_MODE_SURFACE_YUV} pictures hold pixels, anything
   * else is only counted, which saves the deinterlacing.
   */
  void setOutputMode(@C.VideoOutputMode int outputMode) {
    this.outputMode = outputMode;
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public void setOutputStartTimeUs(long outputStartTimeUs) {
    synchronized (lock) {
      this.outputStartTimeUs = outputStartTimeUs;
    }
  }

  @Override
  public void setCallback(Callback callback, Executor executor) {
    synchronized (lock) {
      this.executor = executor;
      this.onOutputBufferAvailable = callback::onOutputBufferAvailable;
      this.onInputBufferAvailable = callback::onInputBufferAvailable;
    }
  }

  @Override
  @Nullable
  public DecoderInputBuffer dequeueInputBuffer() throws DecoderException {
    synchronized (lock) {
      throwIfFailed();
      if (dequeuedInputBuffer != null) {
        throw new IllegalStateException("The dequeued input buffer was not queued");
      }
      dequeuedInputBuffer =
          availableInputBufferCount == 0 ? null : availableInputBuffers[--availableInputBufferCount];
      return dequeuedInputBuffer;
    }
  }

  @Override
  public void queueInputBuffer(DecoderInputBuffer inputBuffer) throws DecoderException {
    synchronized (lock) {
      throwIfFailed();
      if (inputBuffer != dequeuedInputBuffer) {
        throw new IllegalArgumentException("Not the dequeued input buffer");
      }
      queuedInputBuffers.addLast(inputBuffer);
      dequeuedInputBuffer = null;
      lock.notifyAll();
    }
  }

  @Override
  @Nullable
  public VideoDecoderOutputBuffer dequeueOutputBuffer() throws DecoderException {
    synchronized (lock) {
      throwIfFailed();
      return queuedOutputBuffers.pollFirst();
    }
  }

  @Override
  public void releaseOutputBuffer(VideoDecoderOutputBuffer outputBuffer) {
    synchronized (lock) {
      // The native decoder is gone when it was released first; it freed the picture with itself.
      if (handle != 0) {
        nativeReleasePicture(handle, outputBuffer.decoderPrivate);
      }
      recycleOutputBuffer(outputBuffer);
    }
  }

  @Override
  public void flush() {
    synchronized (lock) {
      flushed = true;
      skippedFrameCount = 0;
      if (dequeuedInputBuffer != null) {
        recycleInputBuffer(dequeuedInputBuffer, /* notify= */ false);
        dequeuedInputBuffer = null;
      }
      while (!queuedInputBuffers.isEmpty()) {
        recycleInputBuffer(queuedInputBuffers.removeFirst(), /* notify= */ false);
      }
      while (!queuedOutputBuffers.isEmpty()) {
        queuedOutputBuffers.removeFirst().release();
      }
      lock.notifyAll();
    }
  }

  @Override
  public void release() {
    synchronized (lock) {
      released = true;
      lock.notifyAll();
    }
    boolean interrupted = false;
    while (decodeThread.isAlive()) {
      try {
        decodeThread.join();
      } catch (InterruptedException e) {
        interrupted = true;
      }
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
    synchronized (lock) {
      nativeClose(handle);
      handle = 0;
    }
  }

  private void throwIfFailed() throws DecoderException {
    @Nullable DecoderException failure = exception;
    if (failure != null) {
      throw failure;
    }
  }

  private void run() {
    try {
      while (decodeNext()) {
        // Until released or broken.
      }
    } catch (InterruptedException e) {
      throw new IllegalStateException(e);
    }
  }

  private boolean decodeNext() throws InterruptedException {
    DecoderInputBuffer input;
    boolean reset;
    synchronized (lock) {
      while (!released && queuedInputBuffers.isEmpty()) {
        lock.wait();
      }
      if (released) {
        return false;
      }
      input = queuedInputBuffers.removeFirst();
      reset = flushed;
      flushed = false;
    }

    @Nullable DecoderException failure;
    try {
      failure = decode(input, reset);
    } catch (RuntimeException | OutOfMemoryError e) {
      // A damaged stream must not take the process down, but the player has to hear of it.
      failure = new DecoderException("Unexpected error decoding MPEG-2 video", e);
    }
    synchronized (lock) {
      if (failure != null) {
        exception = failure;
        return false;
      }
      recycleInputBuffer(input, /* notify= */ true);
    }
    return true;
  }

  @Nullable
  private DecoderException decode(DecoderInputBuffer input, boolean reset) throws InterruptedException {
    if (reset) {
      nativeFlush(handle);
    }
    if (input.isEndOfStream()) {
      if (nativeSendEnd(handle) != STATUS_OK) {
        return new DecoderException("The FFmpeg MPEG-2 decoder failed at the end of the stream");
      }
      @Nullable DecoderException failure = drain();
      if (failure != null) {
        return failure;
      }
      @Nullable VideoDecoderOutputBuffer end = takeOutputBuffer();
      if (end != null) {
        end.addFlag(C.BUFFER_FLAG_END_OF_STREAM);
        publish(end);
      }
      return null;
    }
    @Nullable ByteBuffer data = input.data;
    if (data != null && nativeSend(handle, data, data.limit(), input.timeUs) != STATUS_OK) {
      return new DecoderException("The FFmpeg MPEG-2 decoder failed");
    }
    return drain();
  }

  /** Queues every picture the decoder has ready; until it wants more input, or the stream's end. */
  @Nullable
  private DecoderException drain() throws InterruptedException {
    while (true) {
      boolean hasPixels = outputMode == C.VIDEO_OUTPUT_MODE_SURFACE_YUV;
      int status = nativeReceive(handle, received, hasPixels);
      if (status == STATUS_AGAIN || status == STATUS_END) {
        return null;
      }
      if (status != STATUS_OK) {
        return new DecoderException("The FFmpeg MPEG-2 decoder failed");
      }
      long picture = received[0];
      @Nullable VideoDecoderOutputBuffer buffer = takeOutputBuffer();
      if (buffer == null) {
        // Flushed or released while waiting for a buffer: what the decoder holds is from before.
        nativeReleasePicture(handle, picture);
        return null;
      }
      Picture output = (Picture) buffer;
      long timeUs = received[1];
      output.init(timeUs, hasPixels ? C.VIDEO_OUTPUT_MODE_SURFACE_YUV : C.VIDEO_OUTPUT_MODE_NONE, null);
      output.decoderPrivate = picture;
      output.width = (int) received[2];
      output.height = (int) received[3];
      output.colorspace =
          received[4] == MATRIX_BT709
              ? VideoDecoderOutputBuffer.COLORSPACE_BT709
              : VideoDecoderOutputBuffer.COLORSPACE_BT601;
      output.field = (int) received[5];
      // The renderer counts one frame in the decoder per input buffer, and takes one off per picture; the
      // second field of a frame therefore gives one back.
      int frames = output.field == FIELD_SECOND ? 0 : 1;
      if (!publishOrDiscard(output, frames)) {
        return null;
      }
    }
  }

  /** Waits for a free output buffer; null if the decoder was flushed or released meanwhile. */
  @Nullable
  private VideoDecoderOutputBuffer takeOutputBuffer() throws InterruptedException {
    synchronized (lock) {
      while (!released && !flushed && availableOutputBufferCount == 0) {
        lock.wait();
      }
      if (released || flushed) {
        return null;
      }
      return availableOutputBuffers[--availableOutputBufferCount];
    }
  }

  /**
   * Hands a picture to the renderer, unless the decoder was flushed since it was decoded, or it is before the
   * output start time (a seek lands inside a group of pictures, and what comes before the target is not shown).
   *
   * @return false if the picture was from before a flush, so the rest of what the decoder holds is too.
   */
  private boolean publishOrDiscard(VideoDecoderOutputBuffer buffer, int frames) {
    boolean keep;
    boolean stale;
    synchronized (lock) {
      stale = flushed;
      keep = !stale && (outputStartTimeUs == C.TIME_UNSET || buffer.timeUs >= outputStartTimeUs);
      if (keep) {
        buffer.skippedOutputBufferCount = skippedFrameCount + frames - 1;
        skippedFrameCount = 0;
        queuedOutputBuffers.addLast(buffer);
        @Nullable Executor current = executor;
        @Nullable Runnable notifyRenderer = onOutputBufferAvailable;
        if (current != null && notifyRenderer != null) {
          current.execute(notifyRenderer);
        }
      } else if (!stale) {
        skippedFrameCount += frames;
      }
    }
    if (!keep) {
      releaseOutputBuffer(buffer);
    }
    return !stale;
  }

  private void publish(VideoDecoderOutputBuffer buffer) {
    synchronized (lock) {
      if (flushed) {
        recycleOutputBuffer(buffer);
        return;
      }
      queuedOutputBuffers.addLast(buffer);
      @Nullable Executor current = executor;
      @Nullable Runnable notifyRenderer = onOutputBufferAvailable;
      if (current != null && notifyRenderer != null) {
        current.execute(notifyRenderer);
      }
    }
  }

  // Both are called with the lock held.

  private void recycleInputBuffer(DecoderInputBuffer buffer, boolean notify) {
    buffer.clear();
    availableInputBuffers[availableInputBufferCount++] = buffer;
    @Nullable Executor current = executor;
    @Nullable Runnable notifyRenderer = onInputBufferAvailable;
    if (notify && current != null && notifyRenderer != null) {
      current.execute(notifyRenderer);
    }
  }

  private void recycleOutputBuffer(VideoDecoderOutputBuffer buffer) {
    buffer.decoderPrivate = 0;
    buffer.clear();
    availableOutputBuffers[availableOutputBufferCount++] = buffer;
    lock.notifyAll();
  }

  /** Draws a picture on the surface; see RENDER_* and yuv_egl.h. */
  static int render(long renderer, Surface surface, VideoDecoderOutputBuffer picture, long presentNs) {
    return nativeRender(renderer, surface, picture.decoderPrivate, presentNs);
  }

  static long createRenderer() {
    return nativeCreateRenderer();
  }

  static void detachRenderer(long renderer) {
    nativeDetachSurface(renderer);
  }

  static void destroyRenderer(long renderer) {
    nativeDestroyRenderer(renderer);
  }

  private static native boolean nativeHasDecoder();

  private static native long nativeOpen(@Nullable byte[] extradata, int threads);

  private static native int nativeSend(long decoder, ByteBuffer data, int size, long ptsUs);

  private static native int nativeSendEnd(long decoder);

  /** On STATUS_OK, out holds the picture's handle, time in microseconds, width, height, matrix and field. */
  private static native int nativeReceive(long decoder, long[] out, boolean hasPixels);

  private static native void nativeFlush(long decoder);

  private static native void nativeReleasePicture(long decoder, long picture);

  private static native void nativeClose(long decoder);

  private static native long nativeCreateRenderer();

  private static native int nativeRender(long renderer, Surface surface, long picture, long presentNs);

  private static native void nativeDetachSurface(long renderer);

  private static native void nativeDestroyRenderer(long renderer);
}
