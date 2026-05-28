package com.github.lkmio.androidavbaselibrary;

public interface OutputCallback {
    void onFrame(Frame frame);

    void onPacket(Packet packet);

    void onTrack(Track track);
}
