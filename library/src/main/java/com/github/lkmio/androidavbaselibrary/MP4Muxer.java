package com.github.lkmio.androidavbaselibrary;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;

public class MP4Muxer {
    private static final String TAG = "MP4Muxer";
    private static final long INVALID_PTS_US = Long.MIN_VALUE;

    private final Object mLock = new Object();
    private final MediaMuxer mMediaMuxer;

    private int mVideoTrackIndex = -1;
    private int mAudioTrackIndex = -1;
    private boolean mStarted = false;
    private boolean mReleased = false;
    private long mFirstVideoPtsUs = INVALID_PTS_US;
    private long mLastVideoPtsUs = INVALID_PTS_US;
    private long mFirstAudioPtsUs = INVALID_PTS_US;
    private long mLastAudioPtsUs = INVALID_PTS_US;

    public MP4Muxer(String outputPath) throws IOException {
        mMediaMuxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
    }

    public int addVideoTrack(MediaFormat mediaFormat) {
        synchronized (mLock) {
            checkReleased();
            if (mVideoTrackIndex >= 0) {
                return mVideoTrackIndex;
            }
            mVideoTrackIndex = mMediaMuxer.addTrack(mediaFormat);
            startIfReadyLocked(true);
            return mVideoTrackIndex;
        }
    }

    public int addAudioTrack(MediaFormat mediaFormat) {
        synchronized (mLock) {
            checkReleased();
            if (mAudioTrackIndex >= 0) {
                return mAudioTrackIndex;
            }
            mAudioTrackIndex = mMediaMuxer.addTrack(mediaFormat);
            startIfReadyLocked(true);
            return mAudioTrackIndex;
        }
    }

    public void writeVideoSampleData(ByteBuffer byteBuffer, MediaCodec.BufferInfo bufferInfo) throws Exception {
        writeSampleData(true, byteBuffer, bufferInfo);
    }

    public void writeAudioSampleData(ByteBuffer byteBuffer, MediaCodec.BufferInfo bufferInfo) throws Exception {
        writeSampleData(false, byteBuffer, bufferInfo);
    }

    public boolean isStarted() {
        synchronized (mLock) {
            return mStarted;
        }
    }

    public double getDurationSeconds() {
        synchronized (mLock) {
            long videoDurationUs = calculateDurationUs(mFirstVideoPtsUs, mLastVideoPtsUs);
            long audioDurationUs = calculateDurationUs(mFirstAudioPtsUs, mLastAudioPtsUs);
            long durationUs = Math.max(videoDurationUs, audioDurationUs);
            return durationUs / 1_000_000.0;
        }
    }

    public void stop() {
        synchronized (mLock) {
            if (mReleased || !mStarted) {
                return;
            }
            try {
                mMediaMuxer.stop();
            } catch (Exception e) {
                Log.w(TAG, "stop muxer failed", e);
            } finally {
                mStarted = false;
            }
        }
    }

    public void release() {
        synchronized (mLock) {
            if (mReleased) {
                return;
            }
            if (mStarted) {
                try {
                    mMediaMuxer.stop();
                } catch (Exception e) {
                    Log.w(TAG, "stop before release failed", e);
                } finally {
                    mStarted = false;
                }
            }
            mMediaMuxer.release();
            mReleased = true;
        }
    }

    private void writeSampleData(boolean isVideo, ByteBuffer byteBuffer, MediaCodec.BufferInfo bufferInfo) throws Exception {
        synchronized (mLock) {
            checkReleased();
            if (byteBuffer == null || bufferInfo == null || bufferInfo.size <= 0) {
                return;
            }
            if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                return;
            }
            if (!mStarted) {
                return;
            }

            int trackIndex = isVideo ? mVideoTrackIndex : mAudioTrackIndex;
            if (trackIndex < 0) {
                return;
            }

            try {
                mMediaMuxer.writeSampleData(trackIndex, byteBuffer, bufferInfo);
                updateDurationLocked(isVideo, bufferInfo.presentationTimeUs);
            } catch (Exception e) {
                Log.w(TAG, "writeSampleData failed, track=" + trackIndex, e);
                throw e;
            }
        }
    }

    private void startIfReadyLocked(boolean complete) {
        if (mStarted) {
            return;
        }

        boolean videoReady = mVideoTrackIndex >= 0;
        boolean audioReady = mAudioTrackIndex >= 0;
        if ((videoReady && audioReady) || !complete && (videoReady || audioReady)) {
            mMediaMuxer.start();
            mStarted = true;
        }
    }

    public boolean writeHeader() {
        startIfReadyLocked(false);
        return mStarted;
    }

    private void checkReleased() {
        if (mReleased) {
            throw new IllegalStateException("MP4Muxer already released");
        }
    }

    private void updateDurationLocked(boolean isVideo, long presentationTimeUs) {
        if (presentationTimeUs < 0) {
            return;
        }
        if (isVideo) {
            if (mFirstVideoPtsUs == INVALID_PTS_US) {
                mFirstVideoPtsUs = presentationTimeUs;
            }
            if (presentationTimeUs > mLastVideoPtsUs) {
                mLastVideoPtsUs = presentationTimeUs;
            }
            return;
        }
        if (mFirstAudioPtsUs == INVALID_PTS_US) {
            mFirstAudioPtsUs = presentationTimeUs;
        }
        if (presentationTimeUs > mLastAudioPtsUs) {
            mLastAudioPtsUs = presentationTimeUs;
        }
    }

    private long calculateDurationUs(long firstPtsUs, long lastPtsUs) {
        if (firstPtsUs == INVALID_PTS_US || lastPtsUs == INVALID_PTS_US) {
            return 0L;
        }
        return Math.max(0L, lastPtsUs - firstPtsUs);
    }

}
