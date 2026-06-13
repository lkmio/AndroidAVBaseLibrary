package com.github.lkmio.androidavbaselibrary.camera;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Gravity;
import android.view.Surface;

import com.github.lkmio.androidavbaselibrary.DynamicOSD;
import com.github.lkmio.androidavbaselibrary.Frame;
import com.github.lkmio.androidavbaselibrary.OSD;
import com.github.lkmio.androidavbaselibrary.egl.EglBase;
import com.github.lkmio.androidavbaselibrary.filter.CameraEffectFilter;
import com.github.lkmio.androidavbaselibrary.utils.GlUtil;
import com.github.lkmio.androidavbaselibrary.utils.OSDUtils;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages an EGL context and a SurfaceTexture to capture frames from a camera.
 * Integrates FBO Texture Pool and CameraEffectFilter for single-pass processing.
 */
public class PreprocessSurfaceTexture implements SurfaceTexture.OnFrameAvailableListener {

    private static final String TAG = "PreprocessSurface";
    private static final int POOL_SIZE = 3; // 缓冲池大小，3个基本足够应付所有波动

    private EglBase mRootEglBase;
    private EglBase mWorkerEglBase;
    private Surface mInputSurface;
    private volatile boolean mExited = false;
    private HandlerThread mProcessHandlerThread;
    private Handler mProcessHandler;
    
    // For async fallback
    private final Object mLock = new Object();
    private volatile boolean mFrameAvailable;
    private Thread mProcessThread;
    private HandlerThread mHandlerThread;
    private boolean mAsyncProcess = false;

    // EGL and Texture resources
    private int mOesTextureId;
    private SurfaceTexture mSurfaceTexture;

    // Transformation properties
    private int mRotation;
    private boolean mMirrorX;
    private boolean mMirrorY = true;

    // Output dimensions for FBO
    private int mInputWidth;
    private int mInputHeight;
    private int mOutputWidth;
    private int mOutputHeight;
    private int mFrameIdGenerator = 0;
    private long mLastFrameTimestampNs = -1L;
    private long mLastBrightnessCheckTime = 0; // 记录上次检测亮度的时间
    private final AtomicLong mDroppedFrameCount = new AtomicLong();
    private long mEglContextId;

    private CameraEffectFilter mEffectFilter;

    private LinkedBlockingDeque<Frame> mFreeQueue;

    private LinkedBlockingDeque<Frame> mReadyQueue;

    private List<Frame> mAllFrames; // 用于释放资源时遍历

    // Listener
    private OnFrameAvailableListener mOnFrameAvailableListener;

    private final ConcurrentHashMap<Integer, OSD> mStaticOsdMap = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<Integer, DynamicOSD> mDynamicOsdMap = new ConcurrentHashMap<>();

    // 动态文本的缓存，用于判断文本是否发生变化
    private final HashMap<Integer, String> mDynamicTextCache = new HashMap<>();

    // 脏标记
    private volatile boolean mStaticDirty = false;

    private volatile boolean mDynamicDirty = false;

    // 独立的 Canvas 层
    private Bitmap mStaticBitmap;

    private Canvas mStaticCanvas;

    private Bitmap mDynamicBitmap;

    private Canvas mDynamicCanvas;

    private boolean mDynamicTextureInitialized = false;


    public interface OnFrameAvailableListener {
        /**
         * 通知外部：有一帧处理好的 2D 纹理进入了 ReadyQueue。
         * 外部此时应该调用 getReadyFrame() 去拿，用完后调用 returnFrame()。
         */
        void onFrameAvailable(Frame frame);
    }

    public PreprocessSurfaceTexture() {
    }

    public void setAsyncProcess(boolean asyncProcess) {
        this.mAsyncProcess = asyncProcess;
    }

    public void setOnFrameAvailableListener(OnFrameAvailableListener listener) {
        this.mOnFrameAvailableListener = listener;
    }

    public void setRotation(int rotation) {
        this.mRotation = rotation;
        if (mEffectFilter != null) {
            mEffectFilter.setRotation(rotation);
        }
    }

    public void setMirrorX(boolean mirrorX) {
        this.mMirrorX = mirrorX;
        if (mEffectFilter != null) {
            mEffectFilter.setMirror(mMirrorX, mMirrorY);
        }
    }

    public void setMirrorY(boolean mirrorY) {
        this.mMirrorY = mirrorY;
        if (mEffectFilter != null) {
            mEffectFilter.setMirror(mMirrorX, mMirrorY);
        }
    }

    public int getRotation() {
        return mRotation;
    }

    public boolean isMirrorX() {
        return mMirrorX;
    }

    public boolean isMirrorY() {
        return mMirrorY;
    }

    /**
     * Starts the processing thread and initializes EGL resources.
     *
     * @param outputWidth  你的推流/录制宽度
     * @param outputHeight 你的推流/录制高度
     */
    public void start(int inputWidth, int inputHeight, int outputWidth, int outputHeight) {
        if (mProcessHandlerThread != null || mProcessThread != null) return;
        this.mInputWidth = inputWidth;
        this.mInputHeight = inputHeight;
        this.mOutputWidth = outputWidth;
        this.mOutputHeight = outputHeight;
        mExited = false;
        mLastFrameTimestampNs = -1L;

        final CountDownLatch eglBarrier = new CountDownLatch(1);

        Runnable initTask = () -> {
            mRootEglBase = EglBase.create(null, EglBase.CONFIG_RECORDABLE);
            mWorkerEglBase = EglBase.create(mRootEglBase.getEglBaseContext(), EglBase.CONFIG_RECORDABLE);
            mEglContextId = System.nanoTime();
            mWorkerEglBase.createPbufferSurface(1, 1);
            mWorkerEglBase.makeCurrent();

            // 1. 初始化滤镜
            mEffectFilter = new CameraEffectFilter();
            mEffectFilter.init();
            mEffectFilter.setRotation(mRotation);
            mEffectFilter.setMirror(mMirrorX, mMirrorY);

            initOsdPainters();

            // 2. 初始化 FBO 纹理池
            initTexturePool();

            // 3. 初始化 OES
            mOesTextureId = GlUtil.generateTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES);
            mSurfaceTexture = new SurfaceTexture(mOesTextureId);
            mSurfaceTexture.setDefaultBufferSize(mInputWidth, mInputHeight);
            mInputSurface = new Surface(mSurfaceTexture);
        };

        if (mAsyncProcess) {
            mProcessThread = new Thread(() -> {
                initTask.run();

                mHandlerThread = new HandlerThread("FrameAvailableThread");
                mHandlerThread.start();
                mSurfaceTexture.setOnFrameAvailableListener(this, new Handler(mHandlerThread.getLooper()));

                eglBarrier.countDown();
                processAsync();
                releaseInternal();
            }, "PreprocessThread");

            mProcessThread.start();
        } else {
            mProcessHandlerThread = new HandlerThread("PreprocessThread");
            mProcessHandlerThread.start();
            mProcessHandler = new Handler(mProcessHandlerThread.getLooper());

            mProcessHandler.post(() -> {
                initTask.run();

                mSurfaceTexture.setOnFrameAvailableListener(this, mProcessHandler);

                eglBarrier.countDown();
            });
        }

        try {
            eglBarrier.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void initTexturePool() {
        mFreeQueue = new LinkedBlockingDeque<>(POOL_SIZE);
        mReadyQueue = new LinkedBlockingDeque<>(POOL_SIZE);
        mAllFrames = new ArrayList<>(POOL_SIZE);

        for (int i = 0; i < POOL_SIZE; i++) {
            Frame frame = new Frame();
            frame.width = mOutputWidth;
            frame.height = mOutputHeight;

            // 创建 2D 纹理
            int[] tex = new int[1];
            GLES20.glGenTextures(1, tex, 0);
            frame.textureId = tex[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frame.textureId);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, mOutputWidth, mOutputHeight, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
            GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

            // 创建 FBO 并绑定该纹理
            int[] fbo = new int[1];
            GLES20.glGenFramebuffers(1, fbo, 0);
            frame.fboId = fbo[0];
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, frame.fboId);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, frame.textureId, 0);

            // 检查 FBO 状态
            int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
            if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                Log.e(TAG, "Framebuffer not complete, status: " + status);
            }

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);

            mFreeQueue.offer(frame);
            mAllFrames.add(frame);
        }
    }

    // ================== 提供给外部消费者的 API ==================

    /**
     * 编码器/推流器调用此方法获取处理好的帧。
     * (非阻塞，如果队列空返回 null)
     */
    public Frame getReadyFrame() {
        try {
            if (mReadyQueue != null) {
                return mReadyQueue.take();
                //return mReadyQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            Log.w(TAG, "getReadyFrame interrupted", e);
        }

        return null;
    }

    /**
     * 编码器/推流器用完纹理后，必须调用此方法归还！
     */
    public void returnFrame(Frame frame) {
        if (mFreeQueue != null && frame != null) {
            // 清除旧时间戳以防万一
            frame.timestamp = 0;
            mFreeQueue.offer(frame);
        }
    }

    public CameraEffectFilter getEffectFilter() {
        return mEffectFilter;
    }

    public long getDroppedFrameCount() {
        return mDroppedFrameCount.get();
    }

    public void flushReadyQueue() {
        if (mReadyQueue == null || mFreeQueue == null) {
            return;
        }
        Frame frame;
        while ((frame = mReadyQueue.poll()) != null) {
            frame.timestamp = 0;
            mFreeQueue.offer(frame);
        }
    }

    // =========================================================

    public Surface getInputSurface() {
        return mInputSurface;
    }

    public EglBase.Context getEglContext() {
        return mRootEglBase != null ? mRootEglBase.getEglBaseContext() : null;
    }

    public void release() {
        mExited = true;
        if (mAsyncProcess) {
            synchronized (mLock) {
                mLock.notifyAll();
            }
            if (mProcessThread != null) {
                try {
                    mProcessThread.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                mProcessThread = null;
            }
            if (mHandlerThread != null) {
                mHandlerThread.quitSafely();
                mHandlerThread = null;
            }
        } else {
            if (mProcessHandler != null) {
                mProcessHandler.post(() -> {
                    releaseInternal();
                    if (mProcessHandlerThread != null) {
                        mProcessHandlerThread.quitSafely();
                        mProcessHandlerThread = null;
                    }
                });
            }
        }
    }

    @Override
    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
        if (mAsyncProcess) {
            synchronized (mLock) {
                mFrameAvailable = true;
                mLock.notifyAll();
            }
        } else {
            if (mExited) return;
            processFrame();
        }
    }

    private void processAsync() {
        while (!mExited) {
            synchronized (mLock) {
                while (!mFrameAvailable && !mExited) {
                    try {
                        mLock.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        mExited = true;
                    }
                }
                if (mExited) break;
                mFrameAvailable = false;
            }
            processFrame();
        }
    }

    private void processFrame() {
        if (mExited) return;

        long timestamp = 0;
        try {
            mSurfaceTexture.updateTexImage();
            timestamp = mSurfaceTexture.getTimestamp();
            long deltaNs = mLastFrameTimestampNs >= 0 ? timestamp - mLastFrameTimestampNs : -1L;
                // if (deltaNs >= 0) {
                //     Log.d(TAG, "frame timestampNs=" + timestamp + ", deltaNs=" + deltaNs + ", deltaMs=" + (deltaNs / 1_000_000.0));
                // } else {
                //     Log.d(TAG, "frame timestampNs=" + timestamp + ", deltaNs=first-frame");
                // }
            mLastFrameTimestampNs = timestamp;
        } catch (Exception e) {
            Log.w(TAG, "processFrame: updateTexImage failed", e);
            return;
        }

        // 检查并更新水印
        checkAndUpdateWatermarks();

        // --- 核心滑动窗口策略：取画板 ---
        Frame renderFrame = mFreeQueue.poll();
        if (renderFrame == null) {
            // 编码器卡顿导致空闲队列空了，强行从 ReadyQueue 头部丢弃最老的一帧！
            renderFrame = mReadyQueue.poll();
            if (renderFrame == null) {
                return; // 极端异常情况保护
            }
            long droppedCount = mDroppedFrameCount.incrementAndGet();
            Log.w(TAG, "Encoder too slow! Dropped oldest frame to keep preview smooth. droppedCount=" + droppedCount);
        }

        // --- 绑定 FBO 并离屏渲染 ---
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, renderFrame.fboId);
        GLES20.glViewport(0, 0, mOutputWidth, mOutputHeight);

        // 清屏 (可选，防止有些画面裁剪比例没盖住底图残留)
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        // 仅通过外部参数控制旋转与镜像。
        mEffectFilter.draw(mOesTextureId);

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);

        // --- 更新数据并放入就绪队列 ---
        renderFrame.id = ++mFrameIdGenerator;
        renderFrame.timestamp = timestamp;
        renderFrame.eglContext = mRootEglBase != null ? mRootEglBase.getEglBaseContext() : null;
        renderFrame.eglContextId = mEglContextId;
        mReadyQueue.offer(renderFrame);

        if (mOnFrameAvailableListener != null) {
            mOnFrameAvailableListener.onFrameAvailable(renderFrame);
        }
    }

    private void releaseInternal() {
        if (mInputSurface != null) {
            mInputSurface.release();
            mInputSurface = null;
        }
        if (mSurfaceTexture != null) {
            mSurfaceTexture.release();
            mSurfaceTexture = null;
        }
        if (mOesTextureId != 0) {
            int[] textures = new int[]{mOesTextureId};
            GLES20.glDeleteTextures(1, textures, 0);
            mOesTextureId = 0;
        }

        // 释放 FBO 资源
        if (mAllFrames != null) {
            for (Frame frame : mAllFrames) {
                int[] tex = new int[]{frame.textureId};
                GLES20.glDeleteTextures(1, tex, 0);
                int[] fbo = new int[]{frame.fboId};
                GLES20.glDeleteFramebuffers(1, fbo, 0);
            }
            mAllFrames.clear();
            mFreeQueue.clear();
            mReadyQueue.clear();
        }

        if (mWorkerEglBase != null) {
            mWorkerEglBase.release();
            mWorkerEglBase = null;
        }
        if (mRootEglBase != null) {
            mRootEglBase.release();
            mRootEglBase = null;
        }
        mLastFrameTimestampNs = -1L;
    }

    public void addStaticOsd(int id, OSD osd) {
        mStaticOsdMap.put(id, osd);
        mStaticDirty = true;
    }

    public void addDynamicOsd(int id, DynamicOSD osd) {
        mDynamicOsdMap.put(id, osd);
        mDynamicDirty = true;
    }

    public void removeOsd(int id) {
        if (mStaticOsdMap.remove(id) != null) {
            mStaticDirty = true;
        }
        if (mDynamicOsdMap.remove(id) != null) {
            mDynamicDirty = true;
        }
    }

    public void removeAllOsd() {
        if (!mStaticOsdMap.isEmpty()) {
            mStaticOsdMap.clear();
            mStaticDirty = true;
        }
        if (!mDynamicOsdMap.isEmpty()) {
            mDynamicOsdMap.clear();
            mDynamicDirty = true;
        }
    }

    public void clearAllOsd() {
        mStaticOsdMap.clear();
        mDynamicOsdMap.clear();
        mStaticDirty = true;
        mDynamicDirty = true;
    }

    private void initOsdPainters() {
        mStaticBitmap = Bitmap.createBitmap(mOutputWidth, mOutputHeight, Bitmap.Config.ARGB_8888);
        mStaticCanvas = new Canvas(mStaticBitmap);
        mDynamicBitmap = Bitmap.createBitmap(mOutputWidth, mOutputHeight, Bitmap.Config.ARGB_8888);
        mDynamicCanvas = new Canvas(mDynamicBitmap);
    }

    private void checkAndUpdateWatermarks() {
        // --- 静态水印处理 ---
        if (mStaticDirty) {
            mStaticDirty = false;
            mStaticCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
            for (OSD osd : mStaticOsdMap.values()) {
                drawOsd(mStaticCanvas, osd);
            }
            // 因为是全屏画板，所以位置偏移传 0，缩放传 1.0
            mEffectFilter.setStaticWatermark(mStaticBitmap);
        }

        // --- 动态水印处理 ---
        boolean dynamicNeedsUpdate = mDynamicDirty;

        // 遍历所有动态水印，只要有一个文本发生了变化，就需要整体重绘
        for (Map.Entry<Integer, DynamicOSD> entry : mDynamicOsdMap.entrySet()) {
            DynamicOSD osd = entry.getValue();
            String currentText = osd.getText(); // 触发实时获取
            String cachedText = mDynamicTextCache.get(entry.getKey());

            if (!Objects.equals(currentText, cachedText)) {
                dynamicNeedsUpdate = true;
                mDynamicTextCache.put(entry.getKey(), currentText);
            }
        }

        if (dynamicNeedsUpdate) {
            mDynamicDirty = false;
            mDynamicCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
            for (DynamicOSD osd : mDynamicOsdMap.values()) {
                drawOsd(mDynamicCanvas, osd);
            }
            if (!mDynamicTextureInitialized) {
                // 第一次分配显存
                mEffectFilter.initDynamicWatermark(mDynamicBitmap);
                mDynamicTextureInitialized = true;
            } else {
                // 极速局部刷新
                mEffectFilter.replaceDynamicWatermark(mDynamicBitmap);
            }
        }
    }

    private void drawOsd(Canvas canvas, OSD osd) {
        if (osd.bitmap != null) {
            drawBitmapOsd(canvas, osd);
        } else {
            drawTextOsd(canvas, osd);
        }
    }

    private void drawBitmapOsd(Canvas canvas, OSD osd) {
        Bitmap bitmap = osd.bitmap;
        float x = 0;
        float y = 0;
        int align = osd.gravity;

        if ((align & Gravity.LEFT) == Gravity.LEFT) {
            x = osd.margin.left;
        } else if ((align & Gravity.RIGHT) == Gravity.RIGHT) {
            x = canvas.getWidth() - bitmap.getWidth() - osd.margin.right;
        } else if ((align & Gravity.CENTER_HORIZONTAL) == Gravity.CENTER_HORIZONTAL) {
            x = (canvas.getWidth() - bitmap.getWidth()) / 2f;
        }

        if ((align & Gravity.TOP) == Gravity.TOP) {
            y = osd.margin.top;
        } else if ((align & Gravity.BOTTOM) == Gravity.BOTTOM) {
            y = canvas.getHeight() - bitmap.getHeight() - osd.margin.bottom;
        } else if ((align & Gravity.CENTER_VERTICAL) == Gravity.CENTER_VERTICAL) {
            y = (canvas.getHeight() - bitmap.getHeight()) / 2f;
        }

        canvas.drawBitmap(bitmap, x, y, null);
    }

    private void drawTextOsd(Canvas canvas, OSD osd) {
        String text = osd instanceof DynamicOSD ? ((DynamicOSD)osd).getText() : osd.text;
        if (text == null || text.isEmpty()) return;

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(osd.size == 0 ? 14 : osd.size); // 需要您实现或使用传入的绝对像素
        paint.setTypeface(Typeface.DEFAULT_BOLD);

        float x = 0;
        float y = 0;
        float textWidth = paint.measureText(text);
        Paint.FontMetrics metrics = paint.getFontMetrics();
        float textHeight = metrics.bottom - metrics.top;

        int align = osd.gravity;

        // X轴处理
        if ((align & Gravity.LEFT) == Gravity.LEFT) {
            x = osd.margin.left;
        } else if ((align & Gravity.RIGHT) == Gravity.RIGHT) {
            x = canvas.getWidth() - textWidth - osd.margin.right;
        } else if ((align & Gravity.CENTER_HORIZONTAL) == Gravity.CENTER_HORIZONTAL) {
            x = (canvas.getWidth() - textWidth) / 2;
        }

        // Y轴处理，注意 drawText 的 Y 是 baseline
        if ((align & Gravity.TOP) == Gravity.TOP) {
            y = osd.margin.top - metrics.top; // top 是负值
        } else if ((align & Gravity.BOTTOM) == Gravity.BOTTOM) {
            y = canvas.getHeight() - osd.margin.bottom - metrics.bottom;
        } else if ((align & Gravity.CENTER_VERTICAL) == Gravity.CENTER_VERTICAL) {
            y = (canvas.getHeight() - textHeight) / 2 - metrics.top;
        }

        // 先画细边框 (Stroke) 增加一点层次但不会太突兀
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.5f); // 细细的边框
        paint.setColor(Color.argb(160, 0, 0, 0)); // 半透明黑色边框，视觉上更淡、更自然
        canvas.drawText(text, x, y, paint);

        // 再画文字主体 (Fill)
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(osd.color);
        canvas.drawText(text, x, y, paint);
    }
}
