package com.pedro.encoder.input.decoder;

import com.pedro.encoder.input.audio.GetMicrophoneData;
import java.io.IOException;
import java.nio.ByteBuffer;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;

/**
 * Created by pedro on 20/06/17.
 */
public class AudioDecoder {

  private final String TAG = "AudioDecoder";

  private MediaExtractor audioExtractor;
  private MediaCodec audioDecoder;
  private MediaCodec.BufferInfo audioInfo = new MediaCodec.BufferInfo();
  private boolean decoding;
  private Thread thread;
  private GetMicrophoneData getMicrophoneData;
  private MediaFormat audioFormat;
  private String mime = "audio/mp4a-latm";
  private int sampleRate;
  private boolean isStereo;
  private byte[] pcmBuffer = new byte[4096];
  private byte[] pcmBufferMuted = new byte[11];
  private boolean loopMode = false;
  private boolean muted = false;

  public AudioDecoder(GetMicrophoneData getMicrophoneData) {
    this.getMicrophoneData = getMicrophoneData;
  }

  public boolean initExtractor(String filePath) throws IOException {
    decoding = false;
    audioExtractor = new MediaExtractor();
    audioExtractor.setDataSource(filePath);
    for (int i = 0; i < audioExtractor.getTrackCount(); i++) {
      audioFormat = audioExtractor.getTrackFormat(i);
      if (mime.equals(audioFormat.getString(MediaFormat.KEY_MIME))) {
        audioExtractor.selectTrack(i);
        break;
      } else {
        audioFormat = null;
      }
    }
    if (audioFormat != null) {
      try {
        isStereo = (audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == 2);
        sampleRate = audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
      } catch (NullPointerException e) {
        /*Some devices can't extract data from the file with MediaExtractor (Android bug?).
         In this case you can set it manually or get it with other way.
         At the moment, I don't know other way for audio because MediaPlayer has the same problem
         for audio so set defaults parameters*/
        isStereo = true;
        sampleRate = 44100;
      }
      return true;
    } else {
      return false;
    }
  }

  public boolean initExtractor(String filePath, boolean isStereo, int sampleRate)
      throws IOException {
    decoding = false;
    audioExtractor = new MediaExtractor();
    audioExtractor.setDataSource(filePath);
    for (int i = 0; i < audioExtractor.getTrackCount(); i++) {
      audioFormat = audioExtractor.getTrackFormat(i);
      if (mime.equals(audioFormat.getString(MediaFormat.KEY_MIME))) {
        audioExtractor.selectTrack(i);
        break;
      } else {
        audioFormat = null;
      }
    }
    this.isStereo = isStereo;
    this.sampleRate = sampleRate;
    return audioFormat != null;
  }

  public boolean prepareAudio() {
    try {
      audioDecoder = MediaCodec.createDecoderByType(mime);
      audioFormat.setInteger(MediaFormat.KEY_SAMPLE_RATE, sampleRate);
      audioFormat.setInteger(MediaFormat.KEY_CHANNEL_COUNT, isStereo ? 2 : 1);
      audioDecoder.configure(audioFormat, null, null, 0);
      return true;
    } catch (IOException e) {
      Log.e(TAG, "Prepare decoder error:", e);
      return false;
    }
  }

  public void start() {
    decoding = true;
    audioDecoder.start();
    thread = new Thread(new Runnable() {
      @Override
      public void run() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
        decodeAudio();
      }
    });
    thread.start();
  }

  public void stop() {
    decoding = false;
    if (thread != null) {
      thread.interrupt();
      try {
        thread.join();
      } catch (InterruptedException e) {
        thread.interrupt();
      }
      thread = null;
    }
    if (audioDecoder != null) {
      audioDecoder.stop();
      audioDecoder.release();
      audioDecoder = null;
    }
    if (audioExtractor != null) {
      audioExtractor.release();
      audioExtractor = null;
    }
  }

  private void decodeAudio() {
    ByteBuffer[] inputBuffers = audioDecoder.getInputBuffers();
    ByteBuffer[] outputBuffers = audioDecoder.getOutputBuffers();

    while (decoding) {
      int inIndex = audioDecoder.dequeueInputBuffer(-1);
      if (inIndex >= 0) {
        ByteBuffer buffer = inputBuffers[inIndex];
        int sampleSize = audioExtractor.readSampleData(buffer, 0);
        if (sampleSize < 0) {
          audioDecoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
        } else {
          audioDecoder.queueInputBuffer(inIndex, 0, sampleSize, audioExtractor.getSampleTime(), 0);
          audioExtractor.advance();
        }

        int outIndex = audioDecoder.dequeueOutputBuffer(audioInfo, 0);
        switch (outIndex) {
          case MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED:
            outputBuffers = audioDecoder.getOutputBuffers();
            break;
          case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
            break;
          case MediaCodec.INFO_TRY_AGAIN_LATER:
            break;
          default:
            ByteBuffer outBuffer = outputBuffers[outIndex];
            //This buffer is PCM data
            if (muted) {
              outBuffer.get(pcmBufferMuted, 0, pcmBufferMuted.length);
              getMicrophoneData.inputPcmData(pcmBufferMuted, pcmBufferMuted.length);
            } else {
              outBuffer.get(pcmBuffer, 0, pcmBuffer.length);
              getMicrophoneData.inputPcmData(pcmBuffer, pcmBuffer.length);
            }
            audioDecoder.releaseOutputBuffer(outIndex, false);
            break;
        }

        // All decoded frames have been rendered, we can stop playing now
        if ((audioInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
          if (loopMode) {
            audioExtractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
          } else {
            stop();
          }
        }
      }
    }
  }

  public void setLoopMode(boolean loopMode) {
    this.loopMode = loopMode;
  }

  public void mute() {
    muted = true;
  }

  public void unMute() {
    muted = false;
  }

  public boolean isMuted() {
    return muted;
  }

  public int getSampleRate() {
    return sampleRate;
  }

  public boolean isStereo() {
    return isStereo;
  }
}