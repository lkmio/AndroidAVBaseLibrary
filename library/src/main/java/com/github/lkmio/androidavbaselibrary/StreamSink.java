package com.github.lkmio.androidavbaselibrary;

public interface StreamSink {

    void onTrack(Track track);

    Track getTrack(AVMediaType mediaType);

    AVCodec getSpecifyAudioCodec();

    boolean needVideoKeyFrame();

    PacketSink getAudioSink();

    PacketSink getVideoSink();

    boolean isCompleted();

    void writeHeader();

    int trackSize();
}
