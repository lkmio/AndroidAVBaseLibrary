package com.github.lkmio.androidavbaselibrary.codec;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.util.Log;

import com.github.lkmio.androidavbaselibrary.AVCodec;
import com.github.lkmio.androidavbaselibrary.Packet;

import java.nio.ByteBuffer;

public class AacEncoder implements AudioEncoder {
    private static final String TAG = "AacEncoder";
    private static final String MIME_TYPE = MediaFormat.MIMETYPE_AUDIO_AAC;
    private static final long DEQUEUE_TIMEOUT_US = 10_000L;
    private static final int DEFAULT_BIT_RATE = 64_000;

    private final Object mStateLock = new Object();
    private final MediaCodec.BufferInfo mBufferInfo = new MediaCodec.BufferInfo();

    private MediaCodec mCodec;
    private boolean mStarted;
    private byte[] mAudioSpecificConfig;
    private MediaFormat mOutputFormat;
    private final Packet mReusablePacket = new Packet();
    private ByteBuffer mOutputDataBuffer;

    private final int mSampleRate;

    private final int mChannelCount;

    public AacEncoder(int sampleRate, int channelCount) {
        mSampleRate = sampleRate;
        mChannelCount = channelCount;
    }

    public boolean start() {
        try {
            start(mSampleRate, mChannelCount, DEFAULT_BIT_RATE);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
        }

        return false;
    }

    private void start(int sampleRate, int channelCount, int bitRate) {

        synchronized (mStateLock) {
            if (mStarted) {
                return;
            }

            if (sampleRate <= 0) {
                throw new IllegalArgumentException("sampleRate must > 0");
            }
            if (channelCount != 1 && channelCount != 2) {
                throw new IllegalArgumentException("channelCount only supports 1 or 2");
            }
            if (bitRate <= 0) {
                throw new IllegalArgumentException("bitRate must > 0");
            }

            try {
                MediaFormat format = MediaFormat.createAudioFormat(MIME_TYPE, sampleRate, channelCount);
                format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
                format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4096);

                MediaCodec codec = MediaCodec.createEncoderByType(MIME_TYPE);
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                codec.start();

                mCodec = codec;
                mAudioSpecificConfig = buildAudioSpecificConfig(sampleRate, channelCount);
                format.setByteBuffer("csd-0", ByteBuffer.wrap(mAudioSpecificConfig));
                mOutputFormat = format;
                mStarted = true;
            } catch (Exception e) {
                stopInternal();
                throw new IllegalStateException("start AAC encoder failed", e);
            }
        }
    }

    public void stop() {
        synchronized (mStateLock) {
            if (!mStarted && mCodec == null) {
                return;
            }
            drainOutput(false);
            queueEos();
            drainOutput(true);
            stopInternal();
        }
    }

    public Packet encode(byte[] pcmData) {
        return encode(pcmData, 0, pcmData != null ? pcmData.length : 0, System.nanoTime() / 1000L);
    }

    public Packet encode(byte[] pcmData, long ptsUs) {
        return encode(pcmData, 0, pcmData != null ? pcmData.length : 0, ptsUs);
    }

    @Override
    public AVCodec codec() {
        return AVCodec.AAC;
    }

    public Packet encode(byte[] pcmData, int offset, int size, long ptsUs) {
        synchronized (mStateLock) {
            if (!mStarted || mCodec == null) {
                throw new IllegalStateException("encoder is not started");
            }
            if (pcmData == null) {
                throw new IllegalArgumentException("pcmData is null");
            }
            if (offset < 0 || size < 0 || offset + size > pcmData.length) {
                throw new IllegalArgumentException("invalid offset/size");
            }

            int inputIndex = mCodec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US);
            if (inputIndex >= 0) {
                ByteBuffer inputBuffer = mCodec.getInputBuffer(inputIndex);
                if (inputBuffer == null) {
                    return null;
                }
                inputBuffer.clear();
                inputBuffer.put(pcmData, offset, size);
                mCodec.queueInputBuffer(inputIndex, 0, size, ptsUs, 0);
            } else {
                return null;
            }

            return drainOutput(false);
        }
    }

    public byte[] getAudioSpecificConfig() {
        synchronized (mStateLock) {
            return mAudioSpecificConfig != null ? mAudioSpecificConfig.clone() : null;
        }
    }

    @Override
    public MediaFormat getMediaFormat() {
        synchronized (mStateLock) {
            return mOutputFormat;
        }
    }

    @Override
    public byte[] getExtraData() {
        return getAudioSpecificConfig();
    }

    private void queueEos() {
        if (mCodec == null) {
            return;
        }
        int inputIndex = mCodec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US);
        if (inputIndex >= 0) {
            ByteBuffer inputBuffer = mCodec.getInputBuffer(inputIndex);
            if (inputBuffer != null) {
                inputBuffer.clear();
            }
            mCodec.queueInputBuffer(inputIndex, 0, 0, System.nanoTime() / 1000L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
        }
    }

    private Packet drainOutput(boolean untilEos) {
        if (mCodec == null) {
            return null;
        }

        while (true) {
            int outputIndex = mCodec.dequeueOutputBuffer(mBufferInfo, DEQUEUE_TIMEOUT_US);
            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                return null;
            }
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat outputFormat = mCodec.getOutputFormat();
                mOutputFormat = outputFormat;
                ByteBuffer csd0 = outputFormat.getByteBuffer("csd-0");
                if (csd0 != null) {
                    ByteBuffer duplicate = csd0.duplicate();
                    byte[] config = new byte[duplicate.remaining()];
                    duplicate.get(config);
                    mAudioSpecificConfig = config;
                }
                continue;
            }
            if (outputIndex < 0) {
                continue;
            }

            ByteBuffer outputBuffer = mCodec.getOutputBuffer(outputIndex);
            if (outputBuffer != null && mBufferInfo.size > 0) {
                ByteBuffer dataBuffer = outputBuffer.duplicate();
                dataBuffer.position(mBufferInfo.offset);
                dataBuffer.limit(mBufferInfo.offset + mBufferInfo.size);
                ensureOutputBufferCapacity(mBufferInfo.size);
                mOutputDataBuffer.clear();
                mOutputDataBuffer.put(dataBuffer);
                mOutputDataBuffer.flip();

                boolean isConfig = (mBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                if (!isConfig) {
                    mReusablePacket.codec = AVCodec.AAC;
                    mReusablePacket.data = mOutputDataBuffer;
                    mReusablePacket.presentationTimeUs = mBufferInfo.presentationTimeUs;
                    mReusablePacket.duration = (int) Math.max(1L, Math.round(1024_000.0 / mSampleRate));
                    mReusablePacket.flags = mBufferInfo.flags;
                    mCodec.releaseOutputBuffer(outputIndex, false);
                    return mReusablePacket;
                }
            }
            mCodec.releaseOutputBuffer(outputIndex, false);

            if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                break;
            }
            if (!untilEos) {
                break;
            }
        }
        return null;
    }

    private void stopInternal() {
        if (mCodec != null) {
            try {
                mCodec.stop();
            } catch (Exception e) {
                Log.w(TAG, "stop codec failed", e);
            }
            try {
                mCodec.release();
            } catch (Exception e) {
                Log.w(TAG, "release codec failed", e);
            }
            mCodec = null;
        }
        mStarted = false;
        mOutputFormat = null;
    }

    private byte[] buildAudioSpecificConfig(int sampleRate, int channelCount) {
        int sampleRateIndex = getSampleRateIndex(sampleRate);
        int audioObjectType = 2; // AAC LC
        byte[] config = new byte[2];
        config[0] = (byte) ((audioObjectType << 3) | (sampleRateIndex >> 1));
        config[1] = (byte) (((sampleRateIndex & 0x01) << 7) | (channelCount << 3));
        return config;
    }

    private int getSampleRateIndex(int sampleRate) {
        final int[] sampleRates = new int[]{
                96000, 88200, 64000, 48000, 44100, 32000, 24000,
                22050, 16000, 12000, 11025, 8000, 7350
        };
        for (int i = 0; i < sampleRates.length; i++) {
            if (sampleRates[i] == sampleRate) {
                return i;
            }
        }
        throw new IllegalArgumentException("Unsupported AAC sample rate: " + sampleRate);
    }

    private void ensureOutputBufferCapacity(int size) {
        if (mOutputDataBuffer == null || mOutputDataBuffer.capacity() < size) {
            mOutputDataBuffer = ByteBuffer.allocateDirect(size);
        }
    }
}
