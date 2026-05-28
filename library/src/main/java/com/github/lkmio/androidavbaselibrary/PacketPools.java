package com.github.lkmio.androidavbaselibrary;

import com.github.lkmio.androidavbaselibrary.utils.SyncPool;

import java.nio.ByteBuffer;

public final class PacketPools {
    private static final SyncPool<Packet> AUDIO_POOL = createPool(32);
    private static final SyncPool<Packet> VIDEO_KEY_POOL = createPool(8);
    private static final SyncPool<Packet> VIDEO_DELTA_POOL = createPool(32);

    private PacketPools() {
    }

    public static Packet obtainAudioPacket() {
        return AUDIO_POOL.get();
    }

    public static Packet obtainVideoKeyPacket() {
        return VIDEO_KEY_POOL.get();
    }

    public static Packet obtainVideoDeltaPacket() {
        return VIDEO_DELTA_POOL.get();
    }

    public static Packet obtainVideoPacket(boolean keyFrame) {
        return keyFrame ? obtainVideoKeyPacket() : obtainVideoDeltaPacket();
    }

    public static void recycle(Packet packet) {
        if (packet == null) {
            return;
        }
        if (packet.codec == AVCodec.H264) {
            if (packet.isVideoKeyFrame()) {
                VIDEO_KEY_POOL.put(packet);
            } else {
                VIDEO_DELTA_POOL.put(packet);
            }
            return;
        }
        AUDIO_POOL.put(packet);
    }

    public static ByteBuffer ensureDataBuffer(Packet packet, int size, boolean direct) {
        if (packet == null) {
            throw new IllegalArgumentException("packet == null");
        }
        ByteBuffer data = packet.data;
        if (data == null || data.capacity() < size || data.isDirect() != direct) {
            data = direct ? ByteBuffer.allocateDirect(size) : ByteBuffer.allocate(size);
            packet.data = data;
        } else {
            data.clear();
        }
        return data;
    }

    private static SyncPool<Packet> createPool(int maxSharedSize) {
        return new SyncPool<>(
                new SyncPool.Factory<Packet>() {
                    @Override
                    public Packet create() {
                        return new Packet();
                    }
                },
                new SyncPool.Resetter<Packet>() {
                    @Override
                    public void reset(Packet packet) {
                        packet.codec = null;
                        if (packet.data != null) {
                            packet.data.clear();
                        }
                        packet.presentationTimeUs = -1L;
                        packet.flags = 0;
                        packet.duration = 0;
                    }
                },
                maxSharedSize
        );
    }
}
