package com.github.lkmio.androidavbaselibrary;

import android.media.MediaCodec;

import java.nio.ByteBuffer;

public class Packet {
    public AVCodec codec;

    public ByteBuffer data;

    public long presentationTimeUs = -1L;

    public int flags;

    public int duration;

    public boolean isVideoKeyFrame() {
        return (flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
    }
}
