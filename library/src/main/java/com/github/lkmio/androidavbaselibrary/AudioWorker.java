package com.github.lkmio.androidavbaselibrary;

import android.content.Context;
import android.media.AudioRecord;
import android.util.Log;

import com.github.lkmio.androidavbaselibrary.audio.AudioSample;
import com.github.lkmio.androidavbaselibrary.codec.AacEncoder;
import com.github.lkmio.androidavbaselibrary.codec.AudioEncoder;
import com.github.lkmio.androidavbaselibrary.codec.G711Encoder;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class AudioWorker {
    private static final String TAG = "AudioWorker";

    public static class Config {
        public final Context context;
        public final int audioSource;
        public final int sampleRate;
        public final int channelCount;
        public final int audioFrameSize;

        public Config(Context context, int audioSource, int sampleRate, int channelCount, int audioFrameSize) {
            this.context = context;
            this.audioSource = audioSource;
            this.sampleRate = sampleRate;
            this.channelCount = channelCount;
            this.audioFrameSize = audioFrameSize;
        }
    }

    private final Object mLock = new Object();

    private final AtomicInteger mFrameIdGenerator = new AtomicInteger(0);

    private final Frame mReusableFrame = new Frame();

    private final Config mConfig;

    private final DemandCallback mDemandCallback;

    private final OutputCallback mOutputCallback;

    private ILiveSource.OnDeviceErrorListener mOnDeviceErrorListener;

    public void setOnDeviceErrorListener(ILiveSource.OnDeviceErrorListener listener) {
        mOnDeviceErrorListener = listener;
    }

    private volatile boolean mRunning = false;

    private Thread mThread;

    private AudioSample mAudioSample;

    private boolean mAudioSamplerStarted = false;

    private boolean mAudioStartFailed = false;

    private int mEncoderMark = -1;

    private final ConcurrentHashMap<AVCodec, AudioEncoder> mEncoders = new ConcurrentHashMap<>();
    private final Set<AVCodec> mFailedAudioEncoders = new HashSet<>();

    public AudioWorker(Config config, DemandCallback demandCallback, OutputCallback outputCallback) {
        mConfig = config;
        mDemandCallback = demandCallback;
        mOutputCallback = outputCallback;
    }

    public void start() {
        synchronized (mLock) {
            if (mRunning) {
                return;
            }
            mRunning = true;
            mFailedAudioEncoders.clear();
            mThread = new Thread(this::workLoop, "LiveSource-AudioWorker");
            mThread.start();
        }
    }

    public void stop() {
        synchronized (mLock) {
            mRunning = false;
            mLock.notifyAll();
        }
        Thread thread = mThread;
        mThread = null;
        if (thread != null) {
            try {
                thread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        releaseAudioComponents();
    }

    public void wakeup() {
        synchronized (mLock) {
            mAudioStartFailed = false;
            mLock.notifyAll();
        }
    }

    private void workLoop() {
        byte[] readBuffer = null;
        while (mRunning) {
            boolean needAudioFrame = mDemandCallback.needFrame();
            AVCodec[] avCodecs = mDemandCallback.needPacket();
            int mark = 0;
            boolean needAudioPacket = false;
            if (avCodecs != null) {
                for (AVCodec codec : avCodecs) {
                    if (codec == null || codec == AVCodec.NONE || mFailedAudioEncoders.contains(codec)) {
                        continue;
                    }
                    mark += codec.ordinal();
                    needAudioPacket = true;
                }
            }
            boolean needAudio = needAudioFrame || needAudioPacket;

            // 不需要音频流
            if (!needAudio) {
                releaseAudioComponents();
                synchronized (mLock) {
                    if (mRunning) {
                        try {
                            mLock.wait(300);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
                continue;
            }

            if (mAudioStartFailed) {
                synchronized (mLock) {
                    if (mRunning) {
                        try {
                            mLock.wait();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
                continue;
            }

            // 开启音频采集
            if (!mAudioSamplerStarted) {
                try {
                    ensureAudioSamplerStarted();
                } catch (RuntimeException e) {
                    Log.w(TAG, "Failed to start audio sampler", e);
                    mAudioStartFailed = true;
                    if (mOnDeviceErrorListener != null) {
                        mOnDeviceErrorListener.onAudioError(e.getMessage());
                    }
                    continue;
                }
            }

            if (!mAudioSamplerStarted || mAudioSample == null) {
                mAudioStartFailed = true;
                if (mOnDeviceErrorListener != null) {
                    mOnDeviceErrorListener.onAudioError("AudioSample is null or not started");
                }
                continue;
            }

            // 关闭或开启音频编码器
            if (needAudioPacket) {
                // 新增或删除的编码器
                if (mEncoderMark != mark) {
                    reconfigureAudioEncoders(avCodecs);
                    mEncoderMark = mark;
                }
            } else if (!mEncoders.isEmpty()) {
                releaseAudioEncoder();
                mEncoderMark = -1;
            }

            // 创建读取PCM的Buffer
            int frameSize = mConfig.audioFrameSize >= 320 ? mConfig.audioFrameSize : mAudioSample.getBufferSizeInBytes();
            if (readBuffer == null || readBuffer.length < frameSize) {
                readBuffer = new byte[frameSize];
            }

            // 读取音频数据
            long timestampNs = System.nanoTime();
            int readSize = mAudioSample.read(readBuffer);
            if (readSize <= 0) {
                if (readSize == AudioRecord.ERROR_INVALID_OPERATION || readSize == AudioRecord.ERROR_DEAD_OBJECT) {
                    releaseAudioSampler();
                }
                continue;
            }

            // 回调音频帧
            if (needAudioFrame) {
                mReusableFrame.data = readBuffer;
                mReusableFrame.size = readSize;
                mOutputCallback.onFrame(mReusableFrame);
            }

            // 编码并且回调音频包
            if (needAudioPacket) {
                for (Map.Entry<AVCodec, AudioEncoder> entry : mEncoders.entrySet()) {
                    Packet packet = entry.getValue().encode(readBuffer, 0, readSize, timestampNs / 1000L);
                    if (packet != null) {
                        if (packet.presentationTimeUs < 0) {
                            packet.presentationTimeUs = timestampNs / 1000L;
                        }
                        mOutputCallback.onPacket(packet);
                    }
                }
            }
        }
    }

    private void ensureAudioSamplerStarted() {
        if (mAudioSamplerStarted) {
            return;
        }
        if (mAudioSample == null) {
            mAudioSample = new AudioSample();
        }
        mAudioSample.start(mConfig.context, mConfig.audioSource, mConfig.sampleRate, mConfig.channelCount);
        mAudioSamplerStarted = true;
    }

    private void reconfigureAudioEncoders(AVCodec[] avCodecs) {
        Set<AVCodec> requiredCodecs = new HashSet<>();
        if (avCodecs != null) {
            for (AVCodec codec : avCodecs) {
                if (codec == null || codec == AVCodec.NONE || mFailedAudioEncoders.contains(codec)) {
                    continue;
                }
                requiredCodecs.add(codec);
                if (!mEncoders.containsKey(codec)) {
                    AudioEncoder encoder = createAudioEncoder(codec);
                    if (encoder != null && encoder.start()) {
                        mEncoders.put(codec, encoder);
                        mOutputCallback.onTrack(buildAudioTrack(encoder));
                    } else {
                        Log.w(TAG, "Failed to start audio encoder, codec=" + codec);
                        mFailedAudioEncoders.add(codec);
                    }
                }
            }
        }

        for (Map.Entry<AVCodec, AudioEncoder> entry : mEncoders.entrySet()) {
            if (!requiredCodecs.contains(entry.getKey())) {
                entry.getValue().stop();
                mEncoders.remove(entry.getKey());
            }
        }
    }

    private AudioEncoder createAudioEncoder(AVCodec codec) {
        if (codec == AVCodec.AAC) {
            return new AacEncoder(mConfig.sampleRate, mConfig.channelCount);
        }
        if (codec == AVCodec.G711A || codec == AVCodec.G711U) {
            return new G711Encoder(codec);
        }
        return null;
    }

    private void releaseAudioEncoder() {
        for (Map.Entry<AVCodec, AudioEncoder> entry : mEncoders.entrySet()) {
            entry.getValue().stop();
        }

        mEncoders.clear();
        mEncoderMark = -1;
    }

    private void releaseAudioSampler() {
        if (mAudioSample != null && mAudioSamplerStarted) {
            mAudioSample.stop();
        }
        mAudioSamplerStarted = false;
    }

    private void releaseAudioComponents() {
        releaseAudioEncoder();
        releaseAudioSampler();
    }
    
    private Track buildAudioTrack(AudioEncoder encoder) {
        Track track = new Track();
        track.codec = encoder.codec();
        track.mediaType = AVMediaType.AV_MEDIA_TYPE_AUDIO;
        track.mediaFormat = encoder.getMediaFormat();
        track.extraData = encoder.getExtraData();
        return track;
    }
}
