package com.github.lkmio.androidavbaselibrary;

public interface PacketSink {
    AVCodec getCodec();

    void onPacket(Packet packet);
}
