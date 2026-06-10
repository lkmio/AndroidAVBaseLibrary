package com.github.lkmio.androidavbaselibrary;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;

public class RecordMP4Sink extends StreamSinkImpl {
    private static final String TAG = "RecordMP4Sink";

    private static final long INVALID_PTS_US = -1L;

    private static final long US_PER_SECOND = 1_000_000L;

    private final Object mLock = new Object();

    private final OnSegmentHandler mHandler;

    private int mSegmentDurationSeconds;

    private MP4Muxer mMuxer;

    private String mCurrentSegmentPath;

    private long mCurrentVideoPtsUs = 0L;

    private long mCurrentAudioPtsUs = 0L;

    private long mSegmentBasePtsUs = INVALID_PTS_US;

    private boolean mWaitingNextVideoBoundary = false;

    private volatile boolean mNeedKeyFrame = true;

    public interface OnSegmentHandler {
        String allocPath();

        void onSegment(String path);
    }

    public RecordMP4Sink(AVCodec videoCodec, OnSegmentHandler handler, int segmentDurationSeconds) {
        super(AVCodec.AAC, videoCodec);
        if (handler == null) {
            throw new IllegalArgumentException("handler == null");
        }

        mHandler = handler;
        mSegmentDurationSeconds = Math.max(60, segmentDurationSeconds);
    }

    public void setSegmentDurationSeconds(int seconds) {
        synchronized (mLock) {
            mSegmentDurationSeconds = Math.max(60, seconds);
            if (mMuxer != null && mMuxer.isStarted() && mMuxer.getDurationSeconds() >= mSegmentDurationSeconds) {
                mWaitingNextVideoBoundary = true;
            }
        }
    }

    @Override
    public void onPacket(Packet packet) {
        assert packet.codec == mSpecifyVideoCodec || packet.codec == AVCodec.AAC;
        if (mMuxer == null || !mMuxer.isStarted()) {
            return;
        }

        synchronized (mLock) {
            MediaCodec.BufferInfo bufferInfo = buildBufferInfo(packet);
            ByteBuffer data = packet.data.duplicate();
            data.position(0);
            data.limit(packet.data.limit());

            if (packet.codec == mSpecifyVideoCodec) {
                mMuxer.writeVideoSampleData(data, bufferInfo);
            } else if (packet.codec == AVCodec.AAC) {
                mMuxer.writeAudioSampleData(data, bufferInfo);
            } else {
                return;
            }

            if (mMuxer.getDurationSeconds() >= mSegmentDurationSeconds) {
                if (packet.codec == mSpecifyVideoCodec) {
                    rollSegmentLocked();
                } else {
                    mWaitingNextVideoBoundary = true;
                }
            } else if (mWaitingNextVideoBoundary && packet.codec == mSpecifyVideoCodec) {
                rollSegmentLocked();
            }
        }
    }

    @Override
    public void onTrack(Track track) {
        if (track == null || track.mediaFormat == null) {
            return;
        }

        Track cachedTrack = getTrack(track.mediaType);
        if (cachedTrack != null) {
            if (track.mediaType == AVMediaType.AV_MEDIA_TYPE_VIDEO) {
                if (track.width != cachedTrack.width || track.height != cachedTrack.height) {
                    synchronized (mLock) {
                        closeCurrentSegmentLocked();
                    }
                    removeTrack(cachedTrack);
                    addTrack(track);
                }
            }
            return;
        }

        if (!addTrack(track)) {
            return;
        }

        int expectTrackCount = 0;
        if (mSpecifyAudioCodec != AVCodec.NONE) {
            expectTrackCount++;
        }
        if (mSpecifyVideoCodec != AVCodec.NONE) {
            expectTrackCount++;
        }
        if (expectTrackCount == trackSize()) {
            createSegment();
        }
    }

    @Override
    public boolean needVideoKeyFrame() {
        if (mNeedKeyFrame) {
            mNeedKeyFrame = false;
            return true;
        }
        return false;
    }

    @Override
    public void close() {
        synchronized (mLock) {
            closeCurrentSegmentLocked();
        }
        releasePacketDispatcher();
    }

    private void createSegment() {
        if (mMuxer != null) {
            return;
        }

        Track videoTrack = getTrack(AVMediaType.AV_MEDIA_TYPE_VIDEO);
        Track audioTrack = getTrack(AVMediaType.AV_MEDIA_TYPE_AUDIO);

        if (audioTrack == null && videoTrack == null) {
            return;
        }

        String path = mHandler.allocPath();
        if (path == null || path.isEmpty()) {
            Log.w(TAG, "allocPath returned empty path.");
            return;
        }

        try {
            MP4Muxer muxer = new MP4Muxer(path);
            if (audioTrack != null) {
                muxer.addAudioTrack(audioTrack.mediaFormat);
            }

            if (videoTrack != null) {
                muxer.addVideoTrack(videoTrack.mediaFormat);
            }

            muxer.writeHeader();
            mMuxer = muxer;
            mCurrentSegmentPath = path;
            mCurrentVideoPtsUs = 0L;
            mCurrentAudioPtsUs = 0L;
            mSegmentBasePtsUs = INVALID_PTS_US;
            mWaitingNextVideoBoundary = false;
            mNeedKeyFrame = true;
        } catch (IOException e) {
            Log.w(TAG, "create muxer failed, path=" + path, e);
        } catch (Exception e) {
            Log.w(TAG, "start muxer failed, path=" + path, e);
        }
    }

    private MediaCodec.BufferInfo buildBufferInfo(Packet packet) {
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        int size = packet.data.remaining();
        long ptsUs = resolvePresentationTimeUs(packet);
        bufferInfo.set(0, size, ptsUs, packet.flags);
        return bufferInfo;
    }

    private long resolvePresentationTimeUs(Packet packet) {
        if (packet.presentationTimeUs >= 0) {
            if (mSegmentBasePtsUs == INVALID_PTS_US) {
                mSegmentBasePtsUs = packet.presentationTimeUs;
            }
            long ptsUs = Math.max(0L, packet.presentationTimeUs - mSegmentBasePtsUs);
            if (packet.codec.isVideo()) {
                mCurrentVideoPtsUs = ptsUs;
            } else {
                mCurrentAudioPtsUs = ptsUs;
            }
            return ptsUs;
        }

        long ptsUs;
        if (packet.codec.isVideo()) {
            ptsUs = mCurrentVideoPtsUs;
            mCurrentVideoPtsUs += Math.max(1L, packet.duration) * 1000L;
        } else {
            ptsUs = mCurrentAudioPtsUs;
            mCurrentAudioPtsUs += resolveAudioDurationUs(packet);
        }
        return ptsUs;
    }

    private long resolveAudioDurationUs(Packet packet) {
        if (packet.duration > 0) {
            return Math.max(1L, packet.duration) * 1000L;
        }
        Track audioTrack = getTrack(AVMediaType.AV_MEDIA_TYPE_AUDIO);
        if (audioTrack == null || audioTrack.mediaFormat == null) {
            return 0L;
        }
        MediaFormat mediaFormat = audioTrack.mediaFormat;
        if (!mediaFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            return 0L;
        }
        int sampleRate = mediaFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        if (sampleRate <= 0) {
            return 0L;
        }
        if (packet.codec == AVCodec.AAC) {
            return 1024L * US_PER_SECOND / sampleRate;
        }
        return 20_000L;
    }

    private void rollSegmentLocked() {
        closeCurrentSegmentLocked();
        createSegment();
    }

    private void closeCurrentSegmentLocked() {
        if (mMuxer == null) {
            return;
        }
        try {
            mMuxer.stop();
        } catch (Exception e) {
            Log.w(TAG, "stop segment muxer failed", e);
        } finally {
            try {
                mMuxer.release();
            } catch (Exception e) {
                Log.w(TAG, "release segment muxer failed", e);
            }
        }

        String finishedPath = mCurrentSegmentPath;
        mMuxer = null;
        mCurrentSegmentPath = null;
        mCurrentVideoPtsUs = 0L;
        mCurrentAudioPtsUs = 0L;
        mSegmentBasePtsUs = INVALID_PTS_US;
        mWaitingNextVideoBoundary = false;
        if (finishedPath != null && !finishedPath.isEmpty()) {
            mHandler.onSegment(finishedPath);
        }
    }

    @Override
    public boolean isCompleted() {
        return mMuxer != null && mMuxer.isStarted();
    }

    @Override
    public void writeHeader() {
        createSegment();
    }
}
