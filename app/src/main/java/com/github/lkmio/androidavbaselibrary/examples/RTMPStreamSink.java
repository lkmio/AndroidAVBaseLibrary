package com.github.lkmio.androidavbaselibrary.examples;

import android.media.MediaCodec;
import android.media.MediaFormat;

import com.github.lkmio.androidavbaselibrary.AVCodec;
import com.github.lkmio.androidavbaselibrary.AVMediaType;
import com.github.lkmio.androidavbaselibrary.Packet;
import com.github.lkmio.androidavbaselibrary.StreamSinkImpl;
import com.github.lkmio.androidavbaselibrary.Track;
import com.pedro.rtmp.rtmp.RtmpClient;
import com.pedro.rtmp.utils.ConnectCheckerRtmp;

import java.nio.ByteBuffer;

public class RTMPStreamSink extends StreamSinkImpl {
    public interface Listener {
        void onConnectionStarted(String url);

        void onConnectionSuccess();

        void onConnectionFailed(String reason);

        void onDisconnect();

        void onAuthError();

        void onAuthSuccess();
    }

    private static final long INVALID_PTS_US = -1L;

    private final Object mLock = new Object();
    private final String mUrl;
    private final RtmpClient mRtmpClient;
    private volatile Listener mListener;

    private boolean mHeaderWritten = false;
    private boolean mClosed = false;
    private long mBasePtsUs = INVALID_PTS_US;
    private long mCurrentVideoPtsUs = 0L;
    private long mCurrentAudioPtsUs = 0L;

    public RTMPStreamSink(AVCodec videoCodec, String url, Listener listener) {
        super(AVCodec.AAC, videoCodec);
        if (url == null || url.trim().isEmpty()) {
            throw new IllegalArgumentException("url is empty");
        }
        mUrl = url.trim();
        mListener = listener;
        mRtmpClient = new RtmpClient(new ConnectCheckerRtmp() {
            @Override
            public void onConnectionStartedRtmp(String url) {
                if (mListener != null) {
                    mListener.onConnectionStarted(url);
                }
            }

            @Override
            public void onConnectionSuccessRtmp() {
                if (mListener != null) {
                    mListener.onConnectionSuccess();
                }
            }

            @Override
            public void onConnectionFailedRtmp(String reason) {
                if (mListener != null) {
                    mListener.onConnectionFailed(reason);
                }
            }

            @Override
            public void onDisconnectRtmp() {
                if (mListener != null) {
                    mListener.onDisconnect();
                }
            }

            @Override
            public void onAuthErrorRtmp() {
                if (mListener != null) {
                    mListener.onAuthError();
                }
            }

            @Override
            public void onAuthSuccessRtmp() {
                if (mListener != null) {
                    mListener.onAuthSuccess();
                }
            }

            @Override
            public void onNewBitrateRtmp(long bitrate) {
            }
        });
    }

    public RTMPStreamSink(AVCodec videoCodec, String url) {
        this(videoCodec, url, null);
    }

    public void setListener(Listener listener) {
        mListener = listener;
    }

    @Override
    public void onTrack(Track track) {
        if (track == null || track.mediaFormat == null) {
            return;
        }

        Track cachedTrack = getTrack(track.mediaType);
        if (cachedTrack != null) {
            if (track.mediaType == AVMediaType.AV_MEDIA_TYPE_VIDEO
                    && (track.width != cachedTrack.width || track.height != cachedTrack.height)) {
                removeTrack(cachedTrack);
                addTrack(track);
                synchronized (mLock) {
                    if (mHeaderWritten) {
                        mHeaderWritten = false;
                        mBasePtsUs = INVALID_PTS_US;
                        mCurrentVideoPtsUs = 0L;
                        mCurrentAudioPtsUs = 0L;
                        mRtmpClient.disconnect();
                    }
                }
                configureVideoTrack(track.mediaFormat);
                connectIfReadyLocked();
            }
            return;
        }

        if (!addTrack(track)) {
            return;
        }
        synchronized (mLock) {
            if (track.mediaType == AVMediaType.AV_MEDIA_TYPE_VIDEO) {
                configureVideoTrack(track.mediaFormat);
            } else if (track.mediaType == AVMediaType.AV_MEDIA_TYPE_AUDIO) {
                configureAudioTrack(track.mediaFormat);
            }
            connectIfReadyLocked();
        }
    }

    @Override
    public boolean needVideoKeyFrame() {
        return true;
    }

    @Override
    public boolean isCompleted() {
        synchronized (mLock) {
            return mHeaderWritten;
        }
    }

    @Override
    public void writeHeader() {
        synchronized (mLock) {
            connectIfReadyLocked();
        }
    }

    public void close() {
        synchronized (mLock) {
            if (mClosed) {
                return;
            }
            mClosed = true;
            mHeaderWritten = false;
            mBasePtsUs = INVALID_PTS_US;
            mCurrentVideoPtsUs = 0L;
            mCurrentAudioPtsUs = 0L;
        }
        mRtmpClient.disconnect();
        releasePacketDispatcher();
    }

    @Override
    protected void onPacket(Packet packet) {
        if (packet == null || packet.data == null) {
            return;
        }

        MediaCodec.BufferInfo bufferInfo;
        synchronized (mLock) {
            if (mClosed || !mHeaderWritten) {
                return;
            }
            bufferInfo = buildBufferInfoLocked(packet);
        }

        ByteBuffer data = packet.data.duplicate();
        data.position(0);
        data.limit(packet.data.limit());
        if (packet.codec == mSpecifyVideoCodec) {
            mRtmpClient.sendVideo(data, bufferInfo);
        } else if (packet.codec == AVCodec.AAC) {
            mRtmpClient.sendAudio(data, bufferInfo);
        }
    }

    private void connectIfReadyLocked() {
        if (mClosed || mHeaderWritten) {
            return;
        }
        Track videoTrack = getTrack(AVMediaType.AV_MEDIA_TYPE_VIDEO);
        Track audioTrack = getTrack(AVMediaType.AV_MEDIA_TYPE_AUDIO);
        if (videoTrack == null || audioTrack == null) {
            return;
        }
        mHeaderWritten = true;
        mBasePtsUs = INVALID_PTS_US;
        mCurrentVideoPtsUs = 0L;
        mCurrentAudioPtsUs = 0L;
        mRtmpClient.connect(mUrl);
    }

    private void configureVideoTrack(MediaFormat mediaFormat) {
        int width = mediaFormat.containsKey(MediaFormat.KEY_WIDTH)
                ? mediaFormat.getInteger(MediaFormat.KEY_WIDTH)
                : 0;
        int height = mediaFormat.containsKey(MediaFormat.KEY_HEIGHT)
                ? mediaFormat.getInteger(MediaFormat.KEY_HEIGHT)
                : 0;
        if (width > 0 && height > 0) {
            mRtmpClient.setVideoResolution(width, height);
        }
        if (mediaFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) {
            int fps = mediaFormat.getInteger(MediaFormat.KEY_FRAME_RATE);
            if (fps > 0) {
                mRtmpClient.setFps(fps);
            }
        }
        ByteBuffer sps = mediaFormat.getByteBuffer("csd-0");
        ByteBuffer pps = mediaFormat.getByteBuffer("csd-1");
        if (sps != null && pps != null) {
            mRtmpClient.setVideoInfo(copyBuffer(sps), copyBuffer(pps), null);
        }
    }

    private void configureAudioTrack(MediaFormat mediaFormat) {
        if (!mediaFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                || !mediaFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            return;
        }
        int sampleRate = mediaFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        int channelCount = mediaFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        if (sampleRate > 0 && channelCount > 0) {
            mRtmpClient.setAudioInfo(sampleRate, channelCount >= 2);
        }
    }

    private MediaCodec.BufferInfo buildBufferInfoLocked(Packet packet) {
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        int size = packet.data.remaining();
        long ptsUs = resolvePresentationTimeUsLocked(packet);
        bufferInfo.set(0, size, ptsUs, packet.flags);
        return bufferInfo;
    }

    private long resolvePresentationTimeUsLocked(Packet packet) {
        if (packet.presentationTimeUs >= 0) {
            if (mBasePtsUs == INVALID_PTS_US) {
                mBasePtsUs = packet.presentationTimeUs;
            }
            long ptsUs = Math.max(0L, packet.presentationTimeUs - mBasePtsUs);
            if (packet.codec == mSpecifyVideoCodec) {
                mCurrentVideoPtsUs = ptsUs;
            } else {
                mCurrentAudioPtsUs = ptsUs;
            }
            return ptsUs;
        }

        long ptsUs;
        if (packet.codec == mSpecifyVideoCodec) {
            ptsUs = mCurrentVideoPtsUs;
            mCurrentVideoPtsUs += Math.max(1L, packet.duration) * 1000L;
        } else {
            ptsUs = mCurrentAudioPtsUs;
            mCurrentAudioPtsUs += resolveAudioDurationUs();
        }
        return ptsUs;
    }

    private long resolveAudioDurationUs() {
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
        return 1024_000L / sampleRate;
    }

    private ByteBuffer copyBuffer(ByteBuffer source) {
        ByteBuffer duplicate = source.duplicate();
        duplicate.position(0);
        ByteBuffer copy = ByteBuffer.allocateDirect(duplicate.remaining());
        copy.put(duplicate);
        copy.flip();
        return copy;
    }
}
