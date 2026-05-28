package com.github.lkmio.androidavbaselibrary.codec;

import android.media.MediaFormat;

import com.github.lkmio.androidavbaselibrary.AVCodec;
import com.github.lkmio.androidavbaselibrary.Packet;

public interface AudioEncoder {
    AVCodec codec();

    Packet encode(byte[] pcmData, int offset, int size, long ptsUs);

    MediaFormat getMediaFormat();

    byte[] getExtraData();

    boolean start();

    void stop();
}
