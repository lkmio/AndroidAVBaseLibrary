package com.github.lkmio.androidavbaselibrary;

import java.util.concurrent.ConcurrentHashMap;

public abstract class StreamSinkImpl implements StreamSink {
    private final ConcurrentHashMap<AVMediaType, Track> mTracks = new ConcurrentHashMap<>();

    private volatile AsyncStreamPacketDispatcher mAsyncPacketDispatcher;

    protected final AVCodec mSpecifyAudioCodec;

    protected final AVCodec mSpecifyVideoCodec;

    public final PacketSink mAudioPacketSink = new PacketSink() {
        @Override
        public AVCodec getCodec() {
            return mSpecifyAudioCodec;
        }

        @Override
        public void onPacket(Packet packet) {
            dispatchPacket(packet);
        }
    };

    public final PacketSink mVideoPacketSink = new PacketSink() {
        @Override
        public AVCodec getCodec() {
            return mSpecifyVideoCodec;
        }

        @Override
        public void onPacket(Packet packet) {
            dispatchPacket(packet);
        }
    };

    public StreamSinkImpl(AVCodec audioCodec, AVCodec videoCodec) {
        this.mSpecifyAudioCodec = audioCodec;
        this.mSpecifyVideoCodec = videoCodec;
    }

    @Override
    public Track getTrack(AVMediaType mediaType) {
        return mTracks.get(mediaType);
    }

    @Override
    public AVCodec getSpecifyAudioCodec() {
        return mSpecifyAudioCodec;
    }

    protected boolean addTrack(Track track) {
        return mTracks.putIfAbsent(track.mediaType, track) == null;
    }

    protected void removeTrack(Track track) {
        if (track != null) {
            mTracks.remove(track.mediaType);
        }
    }

    @Override
    public int trackSize() {
        return mTracks.size();
    }

    protected abstract void onPacket(Packet packet);

    @Override
    public PacketSink getAudioSink() {
        return mAudioPacketSink;
    }

    @Override
    public PacketSink getVideoSink() {
        return mVideoPacketSink;
    }

    public void setPacketDispatcher(AsyncStreamPacketDispatcher packetDispatcher) {
        AsyncStreamPacketDispatcher oldDispatcher = mAsyncPacketDispatcher;
        mAsyncPacketDispatcher = packetDispatcher;
        if (oldDispatcher != null && oldDispatcher != packetDispatcher) {
            oldDispatcher.close();
        }
    }

    protected void releasePacketDispatcher() {
        AsyncStreamPacketDispatcher packetDispatcher = mAsyncPacketDispatcher;
        mAsyncPacketDispatcher = null;
        if (packetDispatcher != null) {
            packetDispatcher.close();
        }
    }

    private void dispatchPacket(Packet packet) {
        AsyncStreamPacketDispatcher packetDispatcher = mAsyncPacketDispatcher;
        if (packetDispatcher != null) {
            packetDispatcher.dispatch(packet);
            return;
        }
        onPacket(packet);
    }
}
