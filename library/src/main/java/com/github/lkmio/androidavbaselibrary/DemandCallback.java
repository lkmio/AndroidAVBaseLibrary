package com.github.lkmio.androidavbaselibrary;

public interface DemandCallback {
    boolean needFrame();

    AVCodec[] needPacket();
}
