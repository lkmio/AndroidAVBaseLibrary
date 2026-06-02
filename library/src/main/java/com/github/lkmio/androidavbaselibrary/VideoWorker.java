package com.github.lkmio.androidavbaselibrary;

import android.content.Context;
import android.media.MediaCodec;
import android.opengl.GLES20;
import android.util.Log;

import com.github.lkmio.androidavbaselibrary.camera.Camera2Session;
import com.github.lkmio.androidavbaselibrary.camera.PreprocessSurfaceTexture;
import com.github.lkmio.androidavbaselibrary.codec.SurfaceVideoEncoder;
import com.github.lkmio.androidavbaselibrary.egl.EglBase;
import com.github.lkmio.androidavbaselibrary.fitler.Texture2DDrawer;

import java.nio.ByteBuffer;
import java.util.Map;

public class VideoWorker implements PreprocessSurfaceTexture.OnFrameAvailableListener {

    public PreprocessSurfaceTexture getPreprocessSurfaceTexture() {
        return mPreprocessSurfaceTexture;
    }

    public static class Config {
        public final Context context;

        public String cameraId;

        public int width;

        public int height;

        public final int fps;

        public int rotation;

        public final int bitRate;

        public final boolean dynamicBitRate;

        public final int keyFrameIntervalSec;

        public boolean mirrorX;

        public boolean mirrorY;

        public String videoMimeType;

        public String videoCodecName;

        public Config(Context context, String cameraId, int width, int height, int fps, int rotation,
                      int bitRate, boolean dynamicBitRate, int keyFrameIntervalSec,
                      boolean mirrorX, boolean mirrorY, String videoMimeType, String videoCodecName) {
            this.context = context;
            this.cameraId = cameraId;
            this.width = width;
            this.height = height;
            this.fps = fps;
            this.rotation = rotation;
            this.bitRate = bitRate;
            this.dynamicBitRate = dynamicBitRate;
            this.keyFrameIntervalSec = keyFrameIntervalSec;
            this.mirrorX = mirrorX;
            this.mirrorY = mirrorY;
            this.videoMimeType = videoMimeType;
            this.videoCodecName = videoCodecName;
        }
    }

    private final Object mLock = new Object();

    private final Config mConfig;

    private final DemandCallback mDemandCallback;

    private final OutputCallback mOutputCallback;

    private volatile boolean mRunning = false;

    private Thread mThread;

    private Camera2Session mCamera2Session;

    private PreprocessSurfaceTexture mPreprocessSurfaceTexture;

    private SurfaceVideoEncoder mSurfaceVideoEncoder;

    private EglBase mVideoEncodeEglBase;

    private Texture2DDrawer mTexture2DDrawer;

    private boolean mVideoSamplerStarted = false;

    private boolean mVideoEncoderStarted = false;

    private boolean mVideoTrackReported = false;

    private long mLastEncodedPresentationTimeUs = -1L;

    private Camera2Session.OnCameraOpenListener mPendingCameraOpenListener;

    private volatile boolean mCameraOpening = false;

    private volatile boolean mCameraStartFailed = false;

    private final Map<Integer, OSD> mStaticOsds = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<Integer, DynamicOSD> mDynamicOsds = new java.util.concurrent.ConcurrentHashMap<>();

    public void addStaticOsd(int id, OSD osd) {
        mStaticOsds.put(id, osd);
        if (mPreprocessSurfaceTexture != null) {
            mPreprocessSurfaceTexture.addStaticOsd(id, osd);
        }
    }

    public void addDynamicOsd(int id, DynamicOSD osd) {
        mDynamicOsds.put(id, osd);
        if (mPreprocessSurfaceTexture != null) {
            mPreprocessSurfaceTexture.addDynamicOsd(id, osd);
        }
    }

    public void removeOsd(int id) {
        mStaticOsds.remove(id);
        mDynamicOsds.remove(id);
        if (mPreprocessSurfaceTexture != null) {
            mPreprocessSurfaceTexture.removeOsd(id);
        }
    }

    public void removeAllOsd() {
        mStaticOsds.clear();
        mDynamicOsds.clear();
        if (mPreprocessSurfaceTexture != null) {
            mPreprocessSurfaceTexture.removeAllOsd();
        }
    }

    private ILiveSource.OnDeviceErrorListener mOnDeviceErrorListener;

    public void setOnDeviceErrorListener(ILiveSource.OnDeviceErrorListener listener) {
        mOnDeviceErrorListener = listener;
    }

    public VideoWorker(Config config, DemandCallback demandCallback, OutputCallback outputCallback) {
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
            mThread = new Thread(this::workLoop, "LiveSource-VideoWorker");
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
            thread.interrupt();
            try {
                thread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        releaseVideoComponents();
    }

    public void wakeup() {
        synchronized (mLock) {
            mCameraStartFailed = false;
            mLock.notifyAll();
        }
    }

    public void requestKeyFrame() {
        if (mSurfaceVideoEncoder != null) {
            mSurfaceVideoEncoder.requestSyncFrame();
        }
    }

    public void setRotation(int rotation) {
        mConfig.rotation = rotation;
        if (mPreprocessSurfaceTexture != null) {
            mPreprocessSurfaceTexture.setRotation(rotation);
        }
    }

    public void setMirrorX(boolean mirrorX) {
        mConfig.mirrorX = mirrorX;
        if (mPreprocessSurfaceTexture != null) {
            mPreprocessSurfaceTexture.setMirrorX(mirrorX);
        }
    }

    public void setMirrorY(boolean mirrorY) {
        mConfig.mirrorY = mirrorY;
        if (mPreprocessSurfaceTexture != null) {
            mPreprocessSurfaceTexture.setMirrorY(mirrorY);
        }
    }

    public int getRotation() {
        return mConfig.rotation;
    }

    public boolean isMirrorX() {
        return mConfig.mirrorX;
    }

    public boolean isMirrorY() {
        return mConfig.mirrorY;
    }

    public Camera2Session getCamera2Session() {
        return mCamera2Session;
    }

    public void setOnCameraOpenListener(Camera2Session.OnCameraOpenListener listener) {
        mPendingCameraOpenListener = listener;
        if (mCamera2Session != null) {
            mCamera2Session.setOnCameraOpenListener(listener);
        }
    }

    public String getCameraId() {
        return mConfig.cameraId;
    }

    public boolean switchCamera(String newCameraId, int rotation, boolean mirrorX, boolean mirrorY, int width, int height) {
        if (mCameraOpening) {
            return false;
        }
        if (newCameraId == null || newCameraId.equals(mConfig.cameraId)) {
            return false;
        }
        synchronized (mLock) {
            if (mCameraOpening) {
                return false;
            }
            boolean sizeChanged = width != mConfig.width || height != mConfig.height;

            mConfig.cameraId = newCameraId;
            mConfig.width = width;
            mConfig.height = height;
            mConfig.rotation = rotation;
            mConfig.mirrorX = mirrorX;
            mConfig.mirrorY = mirrorY;

            if (sizeChanged) {
                releaseVideoComponents();
            } else {
                releaseVideoSampler();
                if (mPreprocessSurfaceTexture != null) {
                    mPreprocessSurfaceTexture.flushReadyQueue();
                }
                applyTransformToSurfaceTexture();
            }
            mCameraStartFailed = false;
            mLock.notifyAll();
            if (mThread != null) {
                mThread.interrupt();
            }
            return true;
        }
    }

    private void applyTransformToSurfaceTexture() {
        if (mPreprocessSurfaceTexture != null) {
            mPreprocessSurfaceTexture.setRotation(mConfig.rotation);
            mPreprocessSurfaceTexture.setMirrorX(mConfig.mirrorX);
            mPreprocessSurfaceTexture.setMirrorY(mConfig.mirrorY);
        }
    }


    @Override
    public void onFrameAvailable(Frame frame) {
        boolean needVideoFrame = mDemandCallback.needFrame();
        AVCodec[] avCodecs = mDemandCallback.needPacket();
        boolean needVideoPacket = avCodecs != null && avCodecs.length > 0;
        if (!needVideoFrame && !needVideoPacket) {
            if (mPreprocessSurfaceTexture != null) {
                mPreprocessSurfaceTexture.returnFrame(frame);
            }
            return;
        }
        if (needVideoFrame) {
            mOutputCallback.onFrame(frame);
        }
        if (!needVideoPacket && mPreprocessSurfaceTexture != null) {
            mPreprocessSurfaceTexture.returnFrame(frame);
        }
    }

    private void workLoop() {
        boolean videoEncoderStartFailed = false;
        while (mRunning) {
            if (mCameraStartFailed) {
                releaseVideoComponents();
                synchronized (mLock) {
                    try {
                        mLock.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                continue;
            }

            AVCodec[] avCodecs = mDemandCallback.needPacket();
            boolean needVideoPacket = avCodecs != null && avCodecs.length > 0;
            boolean needVideo = mDemandCallback.needFrame() || needVideoPacket;

            if (!needVideo) {
                releaseVideoComponents();
                synchronized (mLock) {
                    try {
                        mLock.wait(300);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                continue;
            }

            ensureVideoSamplerStarted();

            // 视频采集失败
            if (!mVideoSamplerStarted) {
                continue;
            } else if (!needVideoPacket) {
                //  不需要视频包
                releaseVideoEncoder();
                videoEncoderStartFailed = false;
                synchronized (mLock) {
                    try {
                        mLock.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                continue;
            } else if (videoEncoderStartFailed) {
                // 音频编码器启动失败, 不再重试
                continue;
            }

            try {
                ensureVideoEncoderStarted();
            } catch (Exception e) {
                e.printStackTrace();
                videoEncoderStartFailed = true;
                releaseVideoEncoder();
                continue;
            }

            // 读取视频帧, 丢给视频编码器
            Frame textureFrame = mPreprocessSurfaceTexture.getReadyFrame();
            if (textureFrame == null) {
                continue;
            }

            renderFrameToVideoEncoder(textureFrame);
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            for (int i = 0; i < 3; i++) {
                Packet packet = mSurfaceVideoEncoder != null ? mSurfaceVideoEncoder.drainBuffer(bufferInfo) : null;
                if (packet == null) {
                    break;
                }

                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    if (!mVideoTrackReported) {
                        mOutputCallback.onTrack(mSurfaceVideoEncoder.getTrack());
                        mVideoTrackReported = true;
                    }
                    continue;
                }
                if (bufferInfo.size <= 0) {
                    continue;
                }
                long presentationTimeUs = bufferInfo.presentationTimeUs;
                long deltaUs = mLastEncodedPresentationTimeUs >= 0
                        ? presentationTimeUs - mLastEncodedPresentationTimeUs
                        : -1L;
//                if (deltaUs >= 0) {
//                    Log.d("VideoWorker", "packet presentationTimeUs=" + presentationTimeUs
//                            + ", deltaUs=" + deltaUs
//                            + ", deltaMs=" + (deltaUs / 1000.0));
//                } else {
//                    Log.d("VideoWorker", "packet presentationTimeUs=" + presentationTimeUs
//                            + ", deltaUs=first-frame");
//                }
                mLastEncodedPresentationTimeUs = presentationTimeUs;

                packet.presentationTimeUs = presentationTimeUs;
                packet.flags = bufferInfo.flags;
                packet.duration = deltaUs >= 0
                        ? (int) Math.max(1L, Math.round(deltaUs / 1000.0))
                        : 0;
                mOutputCallback.onPacket(packet);
            }
            mPreprocessSurfaceTexture.returnFrame(textureFrame);
        }
    }

    private void ensureVideoSamplerStarted() {
        if (mVideoSamplerStarted) {
            return;
        }

        if (mPreprocessSurfaceTexture == null) {
            mPreprocessSurfaceTexture = new PreprocessSurfaceTexture();
            mPreprocessSurfaceTexture.setOnFrameAvailableListener(this);

            for (Map.Entry<Integer, OSD> entry : mStaticOsds.entrySet()) {
                mPreprocessSurfaceTexture.addStaticOsd(entry.getKey(), entry.getValue());
            }
            for (Map.Entry<Integer, DynamicOSD> entry : mDynamicOsds.entrySet()) {
                mPreprocessSurfaceTexture.addDynamicOsd(entry.getKey(), entry.getValue());
            }
        }

        if (mCamera2Session == null) {
            mCamera2Session = new Camera2Session(mConfig.context);
        }
        mCamera2Session.setOnCameraOpenListener(success -> {
            synchronized (mLock) {
                mCameraOpening = false;
                mLock.notifyAll();
            }
            Log.i("VideoWorker", "Camera open result: " + success + ", sessionId: " + mCamera2Session.getSessionId());
            if (!success) {
                mCameraStartFailed = true;
                if (mThread != null) {
                    mThread.interrupt(); // 打断可能阻塞在 getReadyFrame() 的操作
                }
                if (mOnDeviceErrorListener != null) {
                    mOnDeviceErrorListener.onCameraError(mConfig.cameraId, "Camera failed to open or was disconnected");
                }
            }
            if (mPendingCameraOpenListener != null) {
                mPendingCameraOpenListener.onCameraOpened(success);
            }
        });
        mPreprocessSurfaceTexture.setRotation(mConfig.rotation);
        mPreprocessSurfaceTexture.setMirrorX(mConfig.mirrorX);
        mPreprocessSurfaceTexture.setMirrorY(mConfig.mirrorY);
        mPreprocessSurfaceTexture.start(
                mConfig.width,
                mConfig.height,
                getOutputWidth(),
                getOutputHeight()
        );
        synchronized (mLock) {
            mCameraOpening = true;
        }
        Log.i("VideoWorker", "Start camera session, sessionId: " + mCamera2Session.getSessionId());
        boolean startSuccess = mCamera2Session.start(mConfig.cameraId, mPreprocessSurfaceTexture.getInputSurface());
        if (!startSuccess) {
            synchronized (mLock) {
                mCameraOpening = false;
                mLock.notifyAll();
            }
            mCameraStartFailed = true;
            Log.e("VideoWorker", "Failed to start camera session immediately.");
            if (mPendingCameraOpenListener != null) {
                mPendingCameraOpenListener.onCameraOpened(false);
            }
            if (mOnDeviceErrorListener != null) {
                mOnDeviceErrorListener.onCameraError(mConfig.cameraId, "Failed to dispatch camera open request");
            }
            return;
        }
        mVideoSamplerStarted = true;
    }

    private void ensureVideoEncoderStarted() throws Exception {
        if (mVideoEncoderStarted) {
            return;
        }
        if (!mVideoSamplerStarted || mPreprocessSurfaceTexture == null) {
            return;
        }
        if (mSurfaceVideoEncoder == null) {
            mSurfaceVideoEncoder = new SurfaceVideoEncoder();
        }
        mSurfaceVideoEncoder.start(
                mConfig.videoMimeType,
                mConfig.videoCodecName,
                getOutputWidth(),
                getOutputHeight(),
                mConfig.fps,
                mConfig.bitRate,
                mConfig.dynamicBitRate,
                mConfig.keyFrameIntervalSec
        );
        mVideoEncodeEglBase = EglBase.create(mPreprocessSurfaceTexture.getEglContext(), EglBase.CONFIG_RECORDABLE);
        mVideoEncodeEglBase.createSurface(mSurfaceVideoEncoder.getInputSurface());
        mVideoEncodeEglBase.makeCurrent();
        mTexture2DDrawer = new Texture2DDrawer();
        mTexture2DDrawer.init();
        mVideoEncoderStarted = true;
        mVideoTrackReported = false;
    }

    private void renderFrameToVideoEncoder(Frame textureFrame) {
        if (mVideoEncodeEglBase == null || mTexture2DDrawer == null) {
            return;
        }
        mVideoEncodeEglBase.makeCurrent();
        GLES20.glViewport(0, 0, getOutputWidth(), getOutputHeight());
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        mTexture2DDrawer.draw(textureFrame.textureId);
        mVideoEncodeEglBase.swapBuffers(textureFrame.timestamp);
    }

    private boolean isQuarterTurnRotation() {
        int normalized = ((mConfig.rotation % 360) + 360) % 360;
        return normalized == 90 || normalized == 270;
    }

    private int getOutputWidth() {
        return isQuarterTurnRotation() ? mConfig.height : mConfig.width;
    }

    private int getOutputHeight() {
        return isQuarterTurnRotation() ? mConfig.width : mConfig.height;
    }

    private void releaseVideoEncoder() {
        if (mTexture2DDrawer != null) {
            mTexture2DDrawer.release();
            mTexture2DDrawer = null;
        }
        if (mVideoEncodeEglBase != null) {
            mVideoEncodeEglBase.releaseSurface();
            mVideoEncodeEglBase.release();
            mVideoEncodeEglBase = null;
        }
        if (mSurfaceVideoEncoder != null && mVideoEncoderStarted) {
            mSurfaceVideoEncoder.stop();
        }
        mVideoEncoderStarted = false;
        mVideoTrackReported = false;
        mLastEncodedPresentationTimeUs = -1L;
    }

    private void releaseVideoSampler() {
        synchronized (mLock) {
            while (mCameraOpening) {
                Log.d("VideoWorker", "releaseVideoSampler: Waiting for camera to open");
                try {
                    mLock.wait();
                } catch (InterruptedException e) {
                    Log.w("VideoWorker", "releaseVideoSampler interrupted while waiting.");
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (mVideoSamplerStarted) {
            if (mCamera2Session != null) {
                Log.i("VideoWorker", "Stop camera session, sessionId: " + mCamera2Session.getSessionId());
                mCamera2Session.stop();
                mCamera2Session = null;
            }
            mVideoSamplerStarted = false;
        }
    }

    private void releaseVideoComponents() {
        releaseVideoEncoder();
        releaseVideoSampler();
        if (mPreprocessSurfaceTexture != null) {
            mPreprocessSurfaceTexture.release();
            mPreprocessSurfaceTexture = null;
        }
    }
}
