package com.github.lkmio.androidavbaselibrary.audio;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.util.Objects;

public class AudioSample {
    private static final int DEFAULT_BUFFER_MULTIPLIER = 2;

    private final Object mStateLock = new Object();

    private volatile boolean mRunning = false;

    private AudioRecord mAudioRecord;

    private int mAudioSource = MediaRecorder.AudioSource.MIC;
    private int mSampleRate = 8000;
    private int mChannelCount = 1;
    private int mPcmEncoding = AudioFormat.ENCODING_PCM_16BIT;
    private int mBufferSizeInBytes = 0;

    public void start(Context context, int micType, int sampleRate, int channelCount) {
        AudioParams params = new AudioParams(micType, sampleRate, channelCount);
        start(context, params);
    }

    public void start(Context context, AudioParams params) {
        Objects.requireNonNull(params, "audio params is required");
        synchronized (mStateLock) {
            if (mRunning) {
                return;
            }

            int channelConfig = toChannelConfig(params.channelCount);
            int minBufferSize = AudioRecord.getMinBufferSize(
                    params.sampleRate,
                    channelConfig,
                    params.pcmEncoding
            );
            if (minBufferSize <= 0) {
                throw new IllegalArgumentException("unsupported audio params, minBufferSize=" + minBufferSize);
            }

            int targetBufferSize = params.bufferSizeInBytes > 0
                    ? Math.max(params.bufferSizeInBytes, minBufferSize)
                    : minBufferSize * DEFAULT_BUFFER_MULTIPLIER;

            AudioRecord audioRecord = new AudioRecord(
                    params.audioSource,
                    params.sampleRate,
                    channelConfig,
                    params.pcmEncoding,
                    targetBufferSize
            );
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                audioRecord.release();
                throw new IllegalStateException("AudioRecord init failed");
            }

            mAudioSource = params.audioSource;
            mSampleRate = params.sampleRate;
            mChannelCount = params.channelCount;
            mPcmEncoding = params.pcmEncoding;
            mBufferSizeInBytes = targetBufferSize;
            mAudioRecord = audioRecord;
            mRunning = true;

            try {
                mAudioRecord.startRecording();
            } catch (SecurityException | IllegalStateException e) {
                mRunning = false;
                mAudioRecord.release();
                mAudioRecord = null;
                throw e;
            }
        }
    }

    public void stop() {
        AudioRecord audioRecordToStop;
        synchronized (mStateLock) {
            if (!mRunning && mAudioRecord == null) {
                return;
            }
            mRunning = false;
            audioRecordToStop = mAudioRecord;
            mAudioRecord = null;
        }

        if (audioRecordToStop != null) {
            try {
                audioRecordToStop.stop();
            } catch (IllegalStateException ignored) {
            }
            audioRecordToStop.release();
        }
    }

    public int getAudioSource() {
        return mAudioSource;
    }

    public int getSampleRate() {
        return mSampleRate;
    }

    public int getChannelCount() {
        return mChannelCount;
    }

    public int getPcmEncoding() {
        return mPcmEncoding;
    }

    public int getBufferSizeInBytes() {
        return mBufferSizeInBytes;
    }

    public int read(byte[] dst) {
        return read(dst, 0, dst != null ? dst.length : 0);
    }

    public int read(byte[] dst, int offset, int size) {
        if (dst == null) {
            throw new IllegalArgumentException("dst is null");
        }
        if (offset < 0 || size < 0 || offset + size > dst.length) {
            throw new IllegalArgumentException("invalid offset/size");
        }
        AudioRecord record = mAudioRecord;
        if (!mRunning || record == null) {
            return AudioRecord.ERROR_INVALID_OPERATION;
        }
        return record.read(dst, offset, size);
    }


    private int toChannelConfig(int channelCount) {
        if (channelCount == 1) {
            return AudioFormat.CHANNEL_IN_MONO;
        }
        if (channelCount == 2) {
            return AudioFormat.CHANNEL_IN_STEREO;
        }
        throw new IllegalArgumentException("channelCount only supports 1 or 2");
    }

    public static class AudioParams {
        public final int audioSource;
        public final int sampleRate;
        public final int channelCount;
        public int pcmEncoding = AudioFormat.ENCODING_PCM_16BIT;
        public int bufferSizeInBytes = 0;

        public AudioParams(int audioSource, int sampleRate, int channelCount) {
            this.audioSource = audioSource;
            this.sampleRate = sampleRate;
            this.channelCount = channelCount;
        }

        public AudioParams setPcmEncoding(int pcmEncoding) {
            this.pcmEncoding = pcmEncoding;
            return this;
        }

        public AudioParams setBufferSizeInBytes(int bufferSizeInBytes) {
            this.bufferSizeInBytes = bufferSizeInBytes;
            return this;
        }
    }

}
