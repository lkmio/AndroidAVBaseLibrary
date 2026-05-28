package com.github.lkmio.androidavbaselibrary;

import android.util.Log;

import java.nio.ByteBuffer;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public final class AsyncStreamPacketDispatcher {
    private static final String TAG = "AsyncStreamPacket";
    private static final long SLOW_CALLBACK_THRESHOLD_NS = 5_000_000L;

    public interface Callback {
        void onPacket(Packet packet);
    }

    private final Callback mCallback;
    
    private final LinkedBlockingQueue<Packet> mQueue = new LinkedBlockingQueue<>();

    private volatile boolean mRunning = true;

    private final Thread mThread;

    public AsyncStreamPacketDispatcher(String threadName, Callback callback) {
        if (callback == null) {
            throw new IllegalArgumentException("callback == null");
        }
        mCallback = callback;
        mThread = new Thread(this::loop, threadName);
        mThread.start();
    }

    public void dispatch(Packet packet) {
        if (!mRunning || packet == null || packet.data == null) {
            return;
        }

        Packet pooledPacket = PacketPools.obtainVideoPacket(packet.isVideoKeyFrame());
        if (packet.codec != AVCodec.H264) {
            pooledPacket = PacketPools.obtainAudioPacket();
        }

        ByteBuffer source = packet.data.duplicate();
        source.position(0);
        source.limit(packet.data.limit());
        ByteBuffer target = PacketPools.ensureDataBuffer(pooledPacket, source.remaining(), packet.data.isDirect());
        target.put(source);
        target.flip();

        pooledPacket.codec = packet.codec;
        pooledPacket.presentationTimeUs = packet.presentationTimeUs;
        pooledPacket.flags = packet.flags;
        pooledPacket.duration = packet.duration;

        if (!mQueue.offer(pooledPacket)) {
            PacketPools.recycle(pooledPacket);
            Log.w(TAG, "drop packet because async queue is full");
        }
    }

    public void close() {
        mRunning = false;
        mThread.interrupt();
        try {
            mThread.join(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        drainQueue();
    }

    private void loop() {
        while (mRunning) {
            try {
                Packet packet = mQueue.poll(100, TimeUnit.MILLISECONDS);
                if (packet == null) {
                    continue;
                }
                long startNs = System.nanoTime();
                mCallback.onPacket(packet);
                long costNs = System.nanoTime() - startNs;
                if (costNs > SLOW_CALLBACK_THRESHOLD_NS) {
                    Log.w(TAG, "async sink callback too slow, costMs=" + (costNs / 1_000_000.0));
                }
                PacketPools.recycle(packet);
            } catch (InterruptedException e) {
                if (!mRunning) {
                    break;
                }
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                Log.w(TAG, "dispatch packet failed", e);
            }
        }
        drainQueue();
    }

    private void drainQueue() {
        Packet packet;
        while ((packet = mQueue.poll()) != null) {
            PacketPools.recycle(packet);
        }
    }
}
