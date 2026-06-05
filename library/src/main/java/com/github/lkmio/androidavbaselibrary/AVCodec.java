package com.github.lkmio.androidavbaselibrary;

public enum AVCodec {
    NONE,
    H264,
    H265,
    G711A,
    G711U,
    AAC,
    OPUS;

    public boolean isVideo() {
        return this == H264 || this == H265;
    }
}
