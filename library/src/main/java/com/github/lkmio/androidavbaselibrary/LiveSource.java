package com.github.lkmio.androidavbaselibrary;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.media.MediaRecorder;
import android.util.Log;
import android.util.Size;

import com.github.lkmio.androidavbaselibrary.camera.Camera2Session;
import com.github.lkmio.androidavbaselibrary.camera.PreprocessSurfaceTexture;
import com.github.lkmio.androidavbaselibrary.utils.CameraUtils;

import android.opengl.GLES20;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import com.github.lkmio.androidavbaselibrary.watermark.WatermarkManager;

public class LiveSource implements ILiveSource {
    private static final String TAG = "LiveSource";

    private static final long SLOW_CALLBACK_THRESHOLD_NS = 5_000_000L;

    CopyOnWriteArrayList<FrameSink> mAudioFrameSinks = new CopyOnWriteArrayList<>();

    CopyOnWriteArrayList<FrameSink> mVideoFrameSinks = new CopyOnWriteArrayList<>();

    CopyOnWriteArrayList<PacketSink> mAudioPacketSinks = new CopyOnWriteArrayList<>();

    CopyOnWriteArrayList<PacketSink> mVideoPacketSinks = new CopyOnWriteArrayList<>();

    Map<StreamSink, Long> mStreamSinks = new ConcurrentHashMap<>();

    private final Map<StreamSink, Boolean> mVideoKeyFrameDispatched = new ConcurrentHashMap<>();

    private final Map<AVMediaType, Track> mCachedTracks = new ConcurrentHashMap<>();

    private final Object mStreamSinkLock = new Object();

    private AVCodec[] mAudioCodecs;

    private AVCodec[] mVideoCodecs;

    private final AudioWorker.Config mAudioConfig;
    private final VideoWorker.Config mVideoConfig;
    private final Object mWorkLock = new Object();

    private volatile boolean mStarted = false;

    private AudioWorker mAudioWorker;

    private VideoWorker mVideoWorker;

    private Camera2Session.OnCameraOpenListener mPendingCameraOpenListener;

    private final List<CameraUtils.CameraInfo> mBuilderCameraInfos;

    private ILiveSource.SnapshotCallback mSnapshotCallback;
    private ByteBuffer mSnapshotBuffer;
    private Bitmap mSnapshotBitmap;

    private final WatermarkManager mWatermarkManager = new WatermarkManager();
    private ILiveSource.OnDeviceErrorListener mOnDeviceErrorListener;

    private LiveSource(Builder builder) {
        mAudioConfig = builder.buildAudioConfig();
        mVideoConfig = builder.buildVideoConfig();
        mBuilderCameraInfos = builder.mCameraInfos;
    }

    public AVCodec getVideoCodec() {
        if (mVideoConfig != null) {
            String mimeType = mVideoConfig.videoMimeType;
            if (android.media.MediaFormat.MIMETYPE_VIDEO_HEVC.equals(mimeType)) {
                return AVCodec.H265;
            } else if (android.media.MediaFormat.MIMETYPE_VIDEO_AVC.equals(mimeType)) {
                return AVCodec.H264;
            }
        }
        return AVCodec.NONE;
    }

    public static Builder builder(Context context) {
        return new Builder(context);
    }

    public static class Builder {
        private final Context mContext;

        private String mCameraId;

        private int mVideoWith;

        private int mVideoHeight;

        private int mFPS;

        private int mVideoBitRate = 2_000_000;

        private boolean mDynamicBitRate = true;

        private int mKeyFrameIntervalSec = 2;

        private List<CameraUtils.CameraInfo> mCameraInfos;

        private AVCodec mVideoCodecEnum = AVCodec.H264;

        private String mVideoCodecName;

        private String mAudioCodecName;

        private int mAudioSource = MediaRecorder.AudioSource.MIC;

        private int mAudioChannels = 1;

        private int mAudioSampleRate = 8000;

        private Builder(Context context) {
            mContext = Objects.requireNonNull(context, "context is required");
        }

        public Builder setCameraId(String cameraId) {
            mCameraId = cameraId;
            return this;
        }

        public Builder setVideoWith(int videoWith) {
            mVideoWith = videoWith;
            return this;
        }

        public Builder setVideoHeight(int videoHeight) {
            mVideoHeight = videoHeight;
            return this;
        }

        public Builder setFPS(int FPS) {
            mFPS = FPS;
            return this;
        }

        public Builder setVideoBitRate(int videoBitRate) {
            mVideoBitRate = videoBitRate;
            return this;
        }

        public Builder setVideoCodec(AVCodec codec) {
            mVideoCodecEnum = codec;
            return this;
        }

        public Builder setVideoCodec(String videoCodecName) {
            mVideoCodecName = videoCodecName;
            return this;
        }

        public Builder setDynamicBitRate(boolean dynamicBitRate) {
            mDynamicBitRate = dynamicBitRate;
            return this;
        }

        public Builder setKeyFrameIntervalSec(int keyFrameIntervalSec) {
            mKeyFrameIntervalSec = keyFrameIntervalSec;
            return this;
        }

        public Builder setCameraCompatOverrides(CameraUtils.CameraInfo... infos) {
            if (infos != null && infos.length > 0) {
                if (mCameraInfos == null) {
                    mCameraInfos = new ArrayList<>();
                }
                for (CameraUtils.CameraInfo info : infos) {
                    if (info != null) {
                        mCameraInfos.add(info);
                    }
                }
            }
            return this;
        }

        public Builder setAudioSource(int audioSource) {
            mAudioSource = audioSource;
            return this;
        }

        public Builder setAudioChannels(int audioChannels) {
            mAudioChannels = audioChannels;
            return this;
        }

        public Builder setAudioSampleRate(int audioSampleRate) {
            mAudioSampleRate = audioSampleRate;
            return this;
        }

        public LiveSource build() {
            return new LiveSource(this);
        }

        private AudioWorker.Config buildAudioConfig() {
            return new AudioWorker.Config(mContext, mAudioSource, mAudioSampleRate, mAudioChannels);
        }

        private VideoWorker.Config buildVideoConfig() {
            int rotation = 0;
            boolean mirrorX = false;
            boolean mirrorY = true;
            int width = mVideoWith;
            int height = mVideoHeight;

            // 自动选择后置摄像头
            if (mCameraId == null || mCameraId.isEmpty()) {
                List<CameraUtils.CameraInfo> systemList = CameraUtils.getCameraInfoList(mContext);
                for (CameraUtils.CameraInfo info : systemList) {
                    if (info.facing != null && info.facing == CameraCharacteristics.LENS_FACING_BACK) {
                        mCameraId = info.cameraId;
                        break;
                    }
                }
                if ((mCameraId == null || mCameraId.isEmpty()) && !systemList.isEmpty()) {
                    mCameraId = systemList.get(0).cameraId;
                }
            }

            // 获取摄像头信息
            if (mCameraId != null && !mCameraId.isEmpty()) {
                CameraUtils.CameraInfo info = resolveCameraInfo(mContext, mCameraId, mCameraInfos);
                mirrorX = info.mirrorX;
                mirrorY = info.mirrorY;
                rotation = info.sensorOrientation;

                int[] size = new int[2];
                CameraUtils.resolveVideoSize(mContext, mCameraId, mVideoWith, mVideoHeight, size);
                width = size[0];
                height = size[1];
            }

            com.github.lkmio.androidavbaselibrary.utils.CodecUtils.CodecInfo codecInfo = 
                    com.github.lkmio.androidavbaselibrary.utils.CodecUtils.resolveVideoEncoder(mVideoCodecName, mVideoCodecEnum);
            String videoMimeType = codecInfo != null ? codecInfo.type : android.media.MediaFormat.MIMETYPE_VIDEO_AVC;
            String videoCodecName = codecInfo != null ? codecInfo.name : null;

            return new VideoWorker.Config(
                    mContext,
                    mCameraId,
                    width,
                    height,
                    mFPS,
                    rotation,
                    mVideoBitRate,
                    mDynamicBitRate,
                    mKeyFrameIntervalSec,
                    mirrorX,
                    mirrorY,
                    videoMimeType,
                    videoCodecName
            );
        }
    }

    public static CameraUtils.CameraInfo resolveCameraInfo(Context context, String cameraId, List<CameraUtils.CameraInfo> overrides) {
        List<CameraUtils.CameraInfo> systemList = CameraUtils.getCameraInfoList(context);
        CameraUtils.CameraInfo systemInfo = null;
        for (CameraUtils.CameraInfo info : systemList) {
            if (cameraId.equals(info.cameraId)) {
                systemInfo = info;
                break;
            }
        }

        CameraUtils.CameraInfo matchedInfo = null;
        if (overrides != null) {
            for (CameraUtils.CameraInfo info : overrides) {
                if (cameraId.equals(info.cameraId)) {
                    matchedInfo = info;
                    break;
                }
            }
            if (matchedInfo == null && systemInfo != null) {
                for (CameraUtils.CameraInfo info : overrides) {
                    if ((info.cameraId == null || info.cameraId.isEmpty()) && info.facing != null && info.facing.equals(systemInfo.facing)) {
                        matchedInfo = info;
                        break;
                    }
                }
            }
        }

        if (matchedInfo == null) {
            matchedInfo = new CameraUtils.CameraInfo();
        }

        matchedInfo.cameraId = cameraId;
        if (matchedInfo.facing == null) {
            matchedInfo.facing = systemInfo != null ? systemInfo.facing : CameraCharacteristics.LENS_FACING_BACK;
        }
        if (matchedInfo.sensorOrientation == null) {
            matchedInfo.sensorOrientation = systemInfo != null ? systemInfo.sensorOrientation : 90;
        }

        boolean isFront = matchedInfo.facing == CameraCharacteristics.LENS_FACING_FRONT;

        if (matchedInfo.mirrorY == null) {
            matchedInfo.mirrorY = true;
        }
        if (matchedInfo.mirrorX == null) {
            matchedInfo.mirrorX = isFront;
        }

        return matchedInfo;
    }



    @Override
    public void start() {
        synchronized (mWorkLock) {
            if (mStarted) {
                return;
            }
            mStarted = true;
            startAudioWorkThreadIfNeeded();
            startVideoWorkThreadIfNeeded();
            wakeupAudioWorkThread();
            wakeupVideoWorkThread();
        }
    }

    @Override
    public void stop() {
        synchronized (mWorkLock) {
            if (!mStarted) {
                return;
            }
            mStarted = false;
            stopAudioWorkThread();
            stopVideoWorkThread();
            releaseAudioComponents();
            releaseVideoComponents();
            mCachedTracks.clear();
            mVideoKeyFrameDispatched.clear();
            mPendingCameraOpenListener = null;
            mStreamSinks.clear();
        }
    }

    @Override
    public boolean addAudioFrameSink(FrameSink sink) {
        boolean added = addAudioFrameSinkInternal(sink);
        if (added) {
            updateSinkStatus();
        }
        return added;
    }

    @Override
    public boolean addVideoFrameSink(FrameSink sink) {
        boolean added = addVideoFrameSinkInternal(sink);
        if (added) {
            updateSinkStatus();
        }
        return added;
    }

    public boolean addAudioPacketSink(PacketSink sink) {
        boolean added = addAudioPacketSinkInternal(sink);
        if (added) {
            updateSinkStatus();
        }
        return added;
    }

    @Override
    public boolean addVideoPacketSink(PacketSink sink) {
        boolean added = addVideoPacketSinkInternal(sink);
        if (added) {
            updateSinkStatus();
        }
        return added;
    }

    @Override
    public boolean removeAudioFrameSink(FrameSink sink) {
        boolean removed = removeAudioFrameSinkInternal(sink);
        if (removed) {
            updateSinkStatus();
        }
        return removed;
    }

    @Override
    public boolean removeVideoFrameSink(FrameSink sink) {
        boolean removed = removeVideoFrameSinkInternal(sink);
        if (removed) {
            updateSinkStatus();
        }
        return removed;
    }

    @Override
    public boolean removeAudioPacketSink(PacketSink sink) {
        boolean removed = removeAudioPacketSinkInternal(sink);
        if (removed) {
            updateSinkStatus();
        }
        return removed;
    }

    @Override
    public boolean removeVideoPacketSink(PacketSink sink) {
        boolean removed = removeVideoPacketSinkInternal(sink);
        if (removed) {
            updateSinkStatus();
        }
        return removed;
    }

    @Override
    public boolean addStreamSink(StreamSink sink) {
        return addStreamSink(sink, 0L);
    }

    @Override
    public boolean addStreamSink(StreamSink sink, long timeoutMs) {
        if (sink == null) {
            return false;
        }
        boolean added;
        long deadline = timeoutMs > 0 ? System.currentTimeMillis() + timeoutMs : 0L;
        synchronized (mStreamSinkLock) {
            if (mStreamSinks.containsKey(sink)) {
                added = false;
            } else {
                mStreamSinks.put(sink, deadline);
                added = true;
            }
        }
        List<Track> cachedTracks = new ArrayList<>(mCachedTracks.values());
        if (added) {
            for (Track track : cachedTracks) {
                sink.onTrack(track);
                if (sink.isCompleted()) {
                    break;
                }
            }
            updateSinkStatus();
            if (mVideoWorker != null && hasVideoPacketSinkDemand()) {
                mVideoWorker.requestKeyFrame();
            }
        }
        return added;
    }

    @Override
    public void removeStreamSink(StreamSink sink) {
        if (sink == null) {
            return;
        }
        boolean removed = mStreamSinks.remove(sink) != null;
        if (removed) {
            mVideoKeyFrameDispatched.remove(sink);
            updateSinkStatus();
        }
    }

    @Override
    public void setRotation(int rotation) {
        if (mVideoWorker != null) {
            mVideoWorker.setRotation(rotation);
        }
    }

    @Override
    public void setMirrorX(boolean mirrorX) {
        if (mVideoWorker != null) {
            mVideoWorker.setMirrorX(mirrorX);
        }
    }

    @Override
    public void setMirrorY(boolean mirrorY) {
        if (mVideoWorker != null) {
            mVideoWorker.setMirrorY(mirrorY);
        }
    }

    @Override
    public int getRotation() {
        if (mVideoWorker != null) {
            return mVideoWorker.getRotation();
        }
        return CameraUtils.getSensorOrientation(mVideoConfig.context, mVideoConfig.cameraId);
    }

    @Override
    public boolean isMirrorX() {
        if (mVideoWorker != null) {
            return mVideoWorker.isMirrorX();
        }
        return false;
    }

    @Override
    public boolean isMirrorY() {
        if (mVideoWorker != null) {
            return mVideoWorker.isMirrorY();
        }
        return true;
    }

    @Override
    public void setOnCameraOpenListener(Camera2Session.OnCameraOpenListener listener) {
        mPendingCameraOpenListener = listener;
        if (mVideoWorker != null) {
            mVideoWorker.setOnCameraOpenListener(listener);
        }
    }

    @Override
    public void setOnDeviceErrorListener(ILiveSource.OnDeviceErrorListener listener) {
        mOnDeviceErrorListener = listener;
        if (mVideoWorker != null) {
            mVideoWorker.setOnDeviceErrorListener(listener);
        }
        if (mAudioWorker != null) {
            mAudioWorker.setOnDeviceErrorListener(listener);
        }
    }

    @Override
    public String getCameraId() {
        if (mVideoWorker != null) {
            return mVideoWorker.getCameraId();
        }
        return mVideoConfig.cameraId;
    }

    private Integer getCurrentFacing() {
        String currentCameraId = getCameraId();
        if (currentCameraId != null) {
            CameraUtils.CameraInfo info = resolveCameraInfo(mVideoConfig.context, currentCameraId, mBuilderCameraInfos);
            return info.facing;
        }
        return null;
    }

    @Override
    public boolean switchCamera(String cameraId) {
        if (mVideoWorker == null || cameraId == null) {
            return false;
        }
        CameraUtils.CameraInfo info = resolveCameraInfo(mVideoConfig.context, cameraId, mBuilderCameraInfos);
        int[] size = new int[2];
        CameraUtils.resolveVideoSize(mVideoConfig.context, cameraId, mVideoConfig.width, mVideoConfig.height, size);
        boolean switched = mVideoWorker.switchCamera(cameraId, info.sensorOrientation, info.mirrorX, info.mirrorY, size[0], size[1]);
        if (switched) {
            mWatermarkManager.syncToVideoWorker(mVideoWorker, cameraId, info.facing);
        }
        return switched;
    }

    @Override
    public int addStaticWatermark(Bitmap bitmap, int gravity, Rect margin) {
        return addStaticWatermark(null, null, bitmap, gravity, margin);
    }

    @Override
    public int addStaticWatermark(String text, int textSize, int color, int gravity, Rect margin) {
        return addStaticWatermark(null, null, text, textSize, color, gravity, margin);
    }

    @Override
    public int addDynamicTextWatermark(DynamicOSD osd, int textSize, int color, int gravity, Rect margin) {
        return addDynamicTextWatermark(null, null, osd, textSize, color, gravity, margin);
    }

    @Override
    public int addStaticWatermark(String targetCameraId, Integer targetFacing, Bitmap bitmap, int gravity, Rect margin) {
        int id = mWatermarkManager.addStaticWatermark(targetCameraId, targetFacing, bitmap, gravity, margin);
        mWatermarkManager.syncAddedOsd(id, mVideoWorker, getCameraId(), getCurrentFacing());
        return id;
    }

    @Override
    public int addStaticWatermark(String targetCameraId, Integer targetFacing, String text, int textSize, int color, int gravity, Rect margin) {
        int id = mWatermarkManager.addStaticWatermark(targetCameraId, targetFacing, text, textSize, color, gravity, margin);
        mWatermarkManager.syncAddedOsd(id, mVideoWorker, getCameraId(), getCurrentFacing());
        return id;
    }

    @Override
    public int addDynamicTextWatermark(String targetCameraId, Integer targetFacing, DynamicOSD osd, int textSize, int color, int gravity, Rect margin) {
        int id = mWatermarkManager.addDynamicTextWatermark(targetCameraId, targetFacing, osd, textSize, color, gravity, margin);
        mWatermarkManager.syncAddedOsd(id, mVideoWorker, getCameraId(), getCurrentFacing());
        return id;
    }

    @Override
    public boolean removeWatermark(int index) {
        boolean removed = mWatermarkManager.removeWatermark(index);
        if (removed && mVideoWorker != null) {
            mVideoWorker.removeOsd(index);
        }
        return removed;
    }


    private boolean addAudioFrameSinkInternal(FrameSink sink) {
        return mAudioFrameSinks.addIfAbsent(sink);
    }

    private boolean addVideoFrameSinkInternal(FrameSink sink) {
        return mVideoFrameSinks.addIfAbsent(sink);
    }

    private boolean addAudioPacketSinkInternal(PacketSink sink) {
        return mAudioPacketSinks.addIfAbsent(sink);
    }

    private boolean addVideoPacketSinkInternal(PacketSink sink) {
        return mVideoPacketSinks.addIfAbsent(sink);
    }

    private boolean removeAudioFrameSinkInternal(FrameSink sink) {
        return mAudioFrameSinks.remove(sink);
    }

    private boolean removeVideoFrameSinkInternal(FrameSink sink) {
        return mVideoFrameSinks.remove(sink);
    }

    private boolean removeAudioPacketSinkInternal(PacketSink sink) {
        return mAudioPacketSinks.remove(sink);
    }

    private boolean removeVideoPacketSinkInternal(PacketSink sink) {
        return mVideoPacketSinks.remove(sink);
    }

    // 根据sink来判断是否开启或停止采集/编码
    private synchronized void updateSinkStatus() {
        // 统计需要的编码器
        List<AVCodec> audioCodecs = new ArrayList<>();
        List<AVCodec> videoCodecs = new ArrayList<>();
        for (PacketSink sink : mAudioPacketSinks) {
            audioCodecs.add(sink.getCodec());
        }
        for (PacketSink sink : mVideoPacketSinks) {
            videoCodecs.add(sink.getCodec());
        }
        for (StreamSink sink : mStreamSinks.keySet()) {
            PacketSink audioSink = sink.getAudioSink();
            if (audioSink != null && audioSink.getCodec() != AVCodec.NONE) {
                audioCodecs.add(audioSink.getCodec());
            }
            PacketSink videoSink = sink.getVideoSink();
            if (videoSink != null && videoSink.getCodec() != AVCodec.NONE) {
                videoCodecs.add(videoSink.getCodec());
            }
        }

        mAudioCodecs = audioCodecs.toArray(new AVCodec[0]);
        mVideoCodecs = videoCodecs.toArray(new AVCodec[0]);

        start();
        wakeupAudioWorkThread();
        wakeupVideoWorkThread();
    }

    private boolean hasAudioFrameSink() {
        return !mAudioFrameSinks.isEmpty();
    }

    private boolean hasVideoFrameSink() {
        return !mVideoFrameSinks.isEmpty();
    }

    private boolean hasVideoPacketSinkDemand() {
        if (!mVideoPacketSinks.isEmpty()) {
            return true;
        }
        for (StreamSink sink : mStreamSinks.keySet()) {
            PacketSink videoSink = sink.getVideoSink();
            if (videoSink != null && videoSink.getCodec() != AVCodec.NONE) {
                return true;
            }
        }
        return false;
    }

    private void wakeupAudioWorkThread() {
        if (mAudioWorker != null) {
            mAudioWorker.wakeup();
        }
    }

    private void wakeupVideoWorkThread() {
        if (mVideoWorker != null) {
            mVideoWorker.wakeup();
        }
    }

    private void startAudioWorkThreadIfNeeded() {
        if (mAudioWorker != null) {
            return;
        }

        mAudioWorker = new AudioWorker(
                mAudioConfig,
                new DemandCallback() {
                    @Override
                    public boolean needFrame() {
                        return hasAudioFrameSink();
                    }

                    @Override
                    public AVCodec[] needPacket() {
                        return mAudioCodecs;
                    }
                },
                new OutputCallback() {
                    @Override
                    public void onFrame(Frame frame) {
                        processStreamSinkTimeouts();
                        dispatchAudioFrame(frame);
                    }

                    @Override
                    public void onPacket(Packet packet) {
                        processStreamSinkTimeouts();
                        dispatchAudioPacket(packet);
                    }

                    @Override
                    public void onTrack(Track track) {
                        processStreamSinkTimeouts();
                        dispatchTrack(track);
                    }
                }
        );
        if (mOnDeviceErrorListener != null) {
            mAudioWorker.setOnDeviceErrorListener(mOnDeviceErrorListener);
        }
        mAudioWorker.start();
    }

    private void stopAudioWorkThread() {
        if (mAudioWorker != null) {
            mAudioWorker.stop();
            mAudioWorker = null;
        }
    }

    private void startVideoWorkThreadIfNeeded() {
        if (mVideoWorker != null) {
            return;
        }

        mVideoWorker = new VideoWorker(
                mVideoConfig,
                new DemandCallback() {
                    @Override
                    public boolean needFrame() {
                        return hasVideoFrameSink();
                    }

                    @Override
                    public AVCodec[] needPacket() {
                        return mVideoCodecs;
                    }
                },
                new OutputCallback() {
                    @Override
                    public void onFrame(Frame frame) {
                        processStreamSinkTimeouts();
                        dispatchVideoFrame(frame);
                    }

                    @Override
                    public void onPacket(Packet packet) {
                        processStreamSinkTimeouts();
                        dispatchVideoPacket(packet);
                    }

                    @Override
                    public void onTrack(Track track) {
                        processStreamSinkTimeouts();
                        dispatchTrack(track);
                    }
                }
        );

        mWatermarkManager.syncToVideoWorker(mVideoWorker, mVideoConfig.cameraId, getCurrentFacing());

        if (mPendingCameraOpenListener != null) {
            mVideoWorker.setOnCameraOpenListener(mPendingCameraOpenListener);
        }
        if (mOnDeviceErrorListener != null) {
            mVideoWorker.setOnDeviceErrorListener(mOnDeviceErrorListener);
        }

        mVideoWorker.start();
    }

    private void stopVideoWorkThread() {
        if (mVideoWorker != null) {
            mVideoWorker.stop();
            mVideoWorker = null;
        }
    }

    private void releaseAudioComponents() {
        if (mAudioWorker != null) {
            mAudioWorker.stop();
            mAudioWorker = null;
        }
    }

    private void releaseVideoComponents() {
        if (mVideoWorker != null) {
            mVideoWorker.stop();
            mVideoWorker = null;
        }
    }

    private void dispatchAudioFrame(Frame frame) {
        for (FrameSink sink : mAudioFrameSinks) {
            long startNs = System.nanoTime();
            sink.onFrame(frame);
            logSlowCallback("audioFrame", sink, startNs);
        }
    }

    private void dispatchAudioPacket(Packet packet) {
        for (PacketSink sink : mAudioPacketSinks) {
            if (packet.codec == sink.getCodec()) {
                long startNs = System.nanoTime();
                sink.onPacket(packet);
                logSlowCallback("audioPacket", sink, startNs);
            }
        }
        for (StreamSink sink : mStreamSinks.keySet()) {
            PacketSink audioSink = sink.getAudioSink();
            if (audioSink != null && sink.isCompleted() && packet.codec == audioSink.getCodec()) {
                long startNs = System.nanoTime();
                audioSink.onPacket(packet);
                logSlowCallback("streamAudioPacket", audioSink, startNs);
            }
        }
    }

    @Override
    public void takePhoto(SnapshotCallback callback) {
        mSnapshotCallback = callback;
    }

    private Bitmap extractBitmap(Frame frame) {
        int width = frame.width;
        int height = frame.height;
        int capacity = width * height * 4;
        if (mSnapshotBuffer == null || mSnapshotBuffer.capacity() < capacity) {
            mSnapshotBuffer = ByteBuffer.allocateDirect(capacity);
            mSnapshotBuffer.order(ByteOrder.nativeOrder());
        }
        mSnapshotBuffer.clear();
        mSnapshotBuffer.limit(capacity);

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, frame.fboId);
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, mSnapshotBuffer);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);

        int rowBytes = width * 4;
        byte[] topRow = new byte[rowBytes];
        byte[] bottomRow = new byte[rowBytes];
        for (int i = 0; i < height / 2; i++) {
            int topOffset = i * rowBytes;
            int bottomOffset = (height - 1 - i) * rowBytes;

            mSnapshotBuffer.position(topOffset);
            mSnapshotBuffer.get(topRow, 0, rowBytes);

            mSnapshotBuffer.position(bottomOffset);
            mSnapshotBuffer.get(bottomRow, 0, rowBytes);

            mSnapshotBuffer.position(topOffset);
            mSnapshotBuffer.put(bottomRow, 0, rowBytes);

            mSnapshotBuffer.position(bottomOffset);
            mSnapshotBuffer.put(topRow, 0, rowBytes);
        }

        if (mSnapshotBitmap == null || mSnapshotBitmap.getWidth() != width || mSnapshotBitmap.getHeight() != height) {
            if (mSnapshotBitmap != null) {
                mSnapshotBitmap.recycle();
            }
            mSnapshotBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        }
        mSnapshotBuffer.rewind();
        mSnapshotBitmap.copyPixelsFromBuffer(mSnapshotBuffer);

        return mSnapshotBitmap;
    }

    private void dispatchVideoFrame(Frame frame) {
        ILiveSource.SnapshotCallback callback = mSnapshotCallback;
        if (callback != null) {
            mSnapshotCallback = null;
            try {
                Bitmap bitmap = extractBitmap(frame);
                callback.onSnapshot(bitmap);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        for (FrameSink sink : mVideoFrameSinks) {
            long startNs = System.nanoTime();
            sink.onFrame(frame);
            logSlowCallback("videoFrame", sink, startNs);
        }
    }

    private void dispatchVideoPacket(Packet packet) {
        for (PacketSink sink : mVideoPacketSinks) {
            if (packet.codec == sink.getCodec()) {
                long startNs = System.nanoTime();
                sink.onPacket(packet);
                logSlowCallback("videoPacket", sink, startNs);
            }
        }
        boolean isKeyFrame = packet.isVideoKeyFrame();
        for (StreamSink sink : mStreamSinks.keySet()) {
            PacketSink videoSink = sink.getVideoSink();
            if (videoSink == null || !sink.isCompleted() || packet.codec != videoSink.getCodec()) {
                continue;
            }
            if (!mVideoKeyFrameDispatched.containsKey(sink)) {
                if (!isKeyFrame) {
                    continue;
                }
                mVideoKeyFrameDispatched.put(sink, Boolean.TRUE);
            }
            long startNs = System.nanoTime();
            videoSink.onPacket(packet);
            logSlowCallback("streamVideoPacket", videoSink, startNs);
        }
    }

    private void dispatchTrack(Track track) {
        mCachedTracks.put(track.mediaType, track);
        for (StreamSink sink : mStreamSinks.keySet()) {
            long startNs = System.nanoTime();
            sink.onTrack(track);
            logSlowCallback("track", sink, startNs);
        }
    }

    private void processStreamSinkTimeouts() {
        List<StreamSink> timedOutSinks = new ArrayList<>();
        long now = System.currentTimeMillis();
        synchronized (mStreamSinkLock) {
            for (Map.Entry<StreamSink, Long> entry : mStreamSinks.entrySet()) {
                StreamSink sink = entry.getKey();
                Long deadline = entry.getValue();
                if (sink.isCompleted()) {
                    continue;
                }
                if (deadline != null && deadline > 0 && now >= deadline) {
                    mStreamSinks.put(sink, 0L);
                    timedOutSinks.add(sink);
                }
            }
        }
        for (StreamSink sink : timedOutSinks) {
            sink.writeHeader();
        }
    }

    private void logSlowCallback(String callbackType, Object sink, long startNs) {
        long costNs = System.nanoTime() - startNs;
        if (costNs > SLOW_CALLBACK_THRESHOLD_NS) {
            Log.w(TAG, "callback too slow: type=" + callbackType
                    + ", sink=" + (sink != null ? sink.getClass().getSimpleName() : "null")
                    + ", costMs=" + (costNs / 1_000_000.0));
        }
    }
}


