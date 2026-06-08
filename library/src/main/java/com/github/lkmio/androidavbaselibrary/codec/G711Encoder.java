package com.github.lkmio.androidavbaselibrary.codec;

import android.media.MediaFormat;

import com.github.lkmio.androidavbaselibrary.AVCodec;
import com.github.lkmio.androidavbaselibrary.Packet;
import com.theeasiestway.pcmau.G711;

import java.nio.ByteBuffer;

public class G711Encoder implements AudioEncoder {
    private static final String MIME_G711A = "audio/g711-alaw";
    private static final String MIME_G711U = "audio/g711-mlaw";

    AVCodec mCodec;

    private MediaFormat mMediaFormat;

    private final Packet mReusablePacket = new Packet();

    private byte[] mOutputData;

    private ByteBuffer mOutputDataBuffer;

    public G711Encoder(AVCodec codec) {
        assert AVCodec.G711A == codec || AVCodec.G711U == codec;
        this.mCodec = codec;
    }

    @Override
    public AVCodec codec() {
        return mCodec;
    }

    @Override
    public Packet encode(byte[] pcmData, int offset, int size, long ptsUs) {
        int outputSize = size / 2;
        ensureOutputBufferCapacity(outputSize);
        if (AVCodec.G711A == mCodec) {
            G711.encodeA(pcmData, offset, size, mOutputData, 0);
        } else if (AVCodec.G711U == mCodec) {
            G711.encodeU(pcmData, offset, size, mOutputData, 0);
        }

        mOutputDataBuffer.position(0);
        mOutputDataBuffer.limit(outputSize);
        mReusablePacket.data = mOutputDataBuffer;
        mReusablePacket.codec = mCodec;
        mReusablePacket.presentationTimeUs = ptsUs;
        mReusablePacket.duration = (int) (1000 / (8000.0 * 2 / size));
        mReusablePacket.flags = 0;
        return mReusablePacket;
    }

    @Override
    public boolean start() {
        String mime = AVCodec.G711A == mCodec ? MIME_G711A : MIME_G711U;
        mMediaFormat = MediaFormat.createAudioFormat(mime, 8000, 1);
        return true;
    }

    @Override
    public MediaFormat getMediaFormat() {
        return mMediaFormat;
    }

    @Override
    public byte[] getExtraData() {
        return null;
    }

    @Override
    public void stop() {
        mMediaFormat = null;
    }

    private void ensureOutputBufferCapacity(int size) {
        if (mOutputData == null || mOutputData.length < size) {
            mOutputData = new byte[size];
            mOutputDataBuffer = ByteBuffer.wrap(mOutputData);
        }
    }
}
