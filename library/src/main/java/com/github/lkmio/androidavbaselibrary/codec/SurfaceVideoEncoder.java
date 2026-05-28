package com.github.lkmio.androidavbaselibrary.codec;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Bundle;
import android.view.Surface;

import com.github.lkmio.androidavbaselibrary.Packet;

import java.nio.ByteBuffer;

public class SurfaceVideoEncoder {
    
    private static final long DEQUEUE_TIMEOUT_US = 10_000L;

    private final Object mStateLock = new Object();

    private final MediaCodec.BufferInfo mBufferInfo = new MediaCodec.BufferInfo();

    private MediaCodec mCodec;

    private MediaFormat mOutputFormat;

    private byte[] mCodecConfigData;

    private final Packet mReusablePacket = new Packet();

    private ByteBuffer mReusableOutputBuffer;

    private Surface mInputSurface;

    private boolean mInputEosSignaled = false;

    private boolean mOutputEosReached = false;

    public void start(String mimeType, String codecName, int width, int height, int fps, int bitRate, boolean dynamicBitRate, int keyFrameIntervalSec) throws Exception {
        synchronized (mStateLock) {
            if (mCodec != null) {
                return;
            }
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("width/height must > 0");
            }

            int resolvedBitRate = Math.max(1, bitRate);
            int resolvedKeyFrameInterval = Math.max(1, keyFrameIntervalSec);
            int bitRateMode = dynamicBitRate
                    ? MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                    : MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR;

            try {
                MediaFormat format = MediaFormat.createVideoFormat(mimeType, width, height);
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                format.setInteger(MediaFormat.KEY_BIT_RATE, resolvedBitRate);
                format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
                format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, resolvedKeyFrameInterval);
                format.setInteger(MediaFormat.KEY_BITRATE_MODE, bitRateMode);

                if (codecName != null && !codecName.isEmpty()) {
                    mCodec = MediaCodec.createByCodecName(codecName);
                } else {
                    mCodec = MediaCodec.createEncoderByType(mimeType);
                }
                mCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                mInputSurface = mCodec.createInputSurface();
                mCodec.start();

                mOutputFormat = mCodec.getOutputFormat();
                mInputEosSignaled = false;
                mOutputEosReached = false;
            } catch (Exception e) {
                releaseInternal();
                throw new IllegalStateException("start surface video encoder failed", e);
            }
        }
    }

    public void stop() {
        synchronized (mStateLock) {
            if (mCodec == null) {
                return;
            }
            signalEndOfInputStream();
            // 尽量排空编码器中的剩余输出
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            for (int i = 0; i < 20; i++) {
                Packet packet = drainBuffer(bufferInfo);
                if (packet == null && mOutputEosReached) {
                    break;
                }
            }
            releaseInternal();
        }
    }

    public Surface getInputSurface() {
        synchronized (mStateLock) {
            return mInputSurface;
        }
    }

    public MediaFormat getMediaFormat() {
        synchronized (mStateLock) {
            return mOutputFormat;
        }
    }

    public byte[] getExtraData() {
        synchronized (mStateLock) {
            return mCodecConfigData != null ? mCodecConfigData.clone() : null;
        }
    }

    public void requestSyncFrame() {
        synchronized (mStateLock) {
            if (mCodec == null) {
                return;
            }
            Bundle params = new Bundle();
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            mCodec.setParameters(params);
        }
    }

    public void signalEndOfInputStream() {
        synchronized (mStateLock) {
            if (mCodec == null || mInputEosSignaled) {
                return;
            }
            try {
                mCodec.signalEndOfInputStream();
            } catch (Exception ignored) {
            }
            mInputEosSignaled = true;
        }
    }

    public Packet drainBuffer(MediaCodec.BufferInfo outBufferInfo) {
        synchronized (mStateLock) {
            if (mCodec == null || mOutputEosReached) {
                return null;
            }
            if (outBufferInfo == null) {
                throw new IllegalArgumentException("outBufferInfo == null");
            }

            int outputIndex = mCodec.dequeueOutputBuffer(mBufferInfo, DEQUEUE_TIMEOUT_US);
            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                return null;
            }
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                mOutputFormat = mCodec.getOutputFormat();
                return null;
            }
            if (outputIndex < 0) {
                return null;
            }

            ByteBuffer outputBuffer = mCodec.getOutputBuffer(outputIndex);
            if (outputBuffer != null && mBufferInfo.size > 0) {
                ByteBuffer sourceBuffer = outputBuffer.duplicate();
                sourceBuffer.position(mBufferInfo.offset);
                sourceBuffer.limit(mBufferInfo.offset + mBufferInfo.size);
                ByteBuffer dataBuffer = ensureOutputBufferCapacity(mBufferInfo.size);
                dataBuffer.put(sourceBuffer);
                dataBuffer.flip();
                mReusablePacket.data = dataBuffer;
                mReusablePacket.presentationTimeUs = mBufferInfo.presentationTimeUs;
                mReusablePacket.flags = mBufferInfo.flags;
                outBufferInfo.set(0, mBufferInfo.size, mBufferInfo.presentationTimeUs, mBufferInfo.flags);
                if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    mCodecConfigData = new byte[dataBuffer.remaining()];
                    dataBuffer.duplicate().get(mCodecConfigData);
                }
                boolean eos = (mBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                mCodec.releaseOutputBuffer(outputIndex, false);
                if (eos) {
                    mOutputEosReached = true;
                }
                return mReusablePacket;
            }

            boolean eos = (mBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            mCodec.releaseOutputBuffer(outputIndex, false);
            if (eos) {
                mOutputEosReached = true;
            }
            return null;
        }
    }

    private void releaseInternal() {
        if (mCodec != null) {
            try {
                mCodec.stop();
            } catch (Exception ignored) {
            }
            try {
                mCodec.release();
            } catch (Exception ignored) {
            }
            mCodec = null;
        }
        mOutputFormat = null;
        mCodecConfigData = null;

        if (mInputSurface != null) {
            try {
                mInputSurface.release();
            } catch (Exception ignored) {
            }
            mInputSurface = null;
        }
        mInputEosSignaled = false;
        mOutputEosReached = false;
    }

    private ByteBuffer ensureOutputBufferCapacity(int size) {
        if (mReusableOutputBuffer == null || mReusableOutputBuffer.capacity() < size) {
            mReusableOutputBuffer = ByteBuffer.allocateDirect(size);
        } else {
            mReusableOutputBuffer.clear();
        }
        return mReusableOutputBuffer;
    }
}
