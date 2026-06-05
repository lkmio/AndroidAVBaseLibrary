package com.github.lkmio.androidavbaselibrary.widget;

import android.content.Context;
import android.graphics.PixelFormat;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.util.AttributeSet;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import com.github.lkmio.androidavbaselibrary.Frame;
import com.github.lkmio.androidavbaselibrary.FrameSink;
import com.github.lkmio.androidavbaselibrary.egl.EglBase;
import com.github.lkmio.androidavbaselibrary.filter.Simple2DDrawer;

import java.util.concurrent.CountDownLatch;

public class EglPreviewView extends SurfaceView implements SurfaceHolder.Callback, Handler.Callback, FrameSink {

    private static final String TAG = "EglPreviewView";

    public enum ScaleType {
        FIT_CENTER,
        CENTER_CROP
    }

    private static final int MSG_INIT = 1;
    private static final int MSG_SURFACE_CREATED = 2;
    private static final int MSG_SURFACE_CHANGED = 3;
    private static final int MSG_DRAW_FRAME = 4;
    private static final int MSG_SURFACE_DESTROYED = 5;
    private static final int MSG_RELEASE = 6;

    private HandlerThread mRenderThread;
    private Handler mRenderHandler;

    // EGL 资源
    private EglBase mEglBase;
    private EglBase.Context mSharedContext;
    private Simple2DDrawer mDrawer;
    private SurfaceHolder mPendingHolder;
    private boolean mDrawerInitialized = false;

    // 视图尺寸与矩阵
    private int mViewWidth = 0;
    private int mViewHeight = 0;
    private int mFrameWidth = 0;
    private int mFrameHeight = 0;
    private final float[] mMvpMatrix = new float[16];
    private volatile boolean mInitRequested = false;
    private ScaleType mScaleType = ScaleType.FIT_CENTER;

    public EglPreviewView(Context context) {
        super(context);
        initView();
    }

    public EglPreviewView(Context context, AttributeSet attrs) {
        super(context, attrs);
        initView();
    }

    private void initView() {
        getHolder().addCallback(this);
        // 让 SurfaceView 支持透明/半透明，且不在最底层
        getHolder().setFormat(PixelFormat.RGBA_8888);
        Matrix.setIdentityM(mMvpMatrix, 0);

        // 启动专属渲染线程
        mRenderThread = new HandlerThread("PreviewRenderThread");
        mRenderThread.start();
        mRenderHandler = new Handler(mRenderThread.getLooper(), this);
    }

    public void setScaleType(ScaleType scaleType) {
        if (scaleType != null && mScaleType != scaleType) {
            mScaleType = scaleType;
            updateMatrix();
        }
    }

    /**
     * 1. 业务层调用：初始化并传入共享上下文
     */
    public void init(EglBase.Context sharedContext) {
        if (mRenderHandler == null) {
            return;
        }
        // 如果正在使用同一个 context，无需重复初始化
        if (mInitRequested && mSharedContext == sharedContext) {
            return;
        }
        mInitRequested = true;
        mSharedContext = sharedContext;
        mRenderHandler.sendEmptyMessage(MSG_INIT);
    }

    /**
     * 2. 业务层调用：投递新的一帧纹理去渲染
     * （极速返回，绝不阻塞调用者线程）
     */
    public void requestRender(int textureId, int frameWidth, int frameHeight) {
        if (mRenderHandler != null) {
            Message msg = mRenderHandler.obtainMessage(MSG_DRAW_FRAME, frameWidth, frameHeight, textureId);
            mRenderHandler.sendMessage(msg);
        }
    }

    /**
     * 3. 业务层调用：销毁释放
     */
    public void release() {
        if (mRenderHandler != null) {
            mRenderHandler.sendEmptyMessage(MSG_RELEASE);
            mRenderThread.quitSafely();
            mRenderHandler = null;
            mRenderThread = null;
            mInitRequested = false;
        }
    }

    private boolean isSameContext(EglBase.Context c1, EglBase.Context c2) {
        if (c1 == c2) {
            return true;
        }
        if (c1 == null || c2 == null) {
            return false;
        }
        return c1.getNativeEglContext() == c2.getNativeEglContext();
    }

    @Override
    public void onFrame(Frame frame) {
        if (frame == null) {
            return;
        }
        if (!mInitRequested) {
            init(frame.eglContext);
        } else if (!isSameContext(mSharedContext, frame.eglContext)) {
            // EGL Context has changed (e.g. camera restarted), re-initialize
            init(frame.eglContext);
        }
        requestRender(frame.textureId, frame.width, frame.height);
    }

    // ================== SurfaceHolder Callbacks (运行在主线程) ==================

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        // 主线程通知渲染线程创建 EGLSurface
        Message.obtain(mRenderHandler, MSG_SURFACE_CREATED, holder).sendToTarget();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Message.obtain(mRenderHandler, MSG_SURFACE_CHANGED, width, height).sendToTarget();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        // 【核心防御】：这里必须同步阻塞主线程，直到 GL 线程把 EGLSurface 销毁！
        // 否则主线程把底层的 Surface 物理销毁了，GL 线程还在画，必报 BufferQueue crash。
        CountDownLatch latch = new CountDownLatch(1);
        Message.obtain(mRenderHandler, MSG_SURFACE_DESTROYED, latch).sendToTarget();
        try {
            latch.await(); // 等待 GL 线程回执
        } catch (InterruptedException e) {
            Log.w(TAG, "await interrupted", e);
        }
    }

    // ================== Render Thread (GL 线程处理逻辑) ==================

    @Override
    public boolean handleMessage(Message msg) {
        switch (msg.what) {
            case MSG_INIT:
                // 清理可能存在的旧 EGL 资源
                if (mEglBase != null) {
                    try {
                        mEglBase.makeCurrent();
                        if (mDrawer != null) {
                            mDrawer.release();
                            mDrawer = null;
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "release drawer on MSG_INIT failed", e);
                    }
                    mEglBase.releaseSurface();
                    mEglBase.release();
                    mEglBase = null;
                }
                mDrawerInitialized = false;

                mEglBase = EglBase.create(mSharedContext, EglBase.CONFIG_PLAIN);
                mDrawer = new Simple2DDrawer();
                if (mPendingHolder != null) {
                    ensureSurfaceReady(mPendingHolder);
                }
                break;

            case MSG_SURFACE_CREATED:
                SurfaceHolder holder = (SurfaceHolder) msg.obj;
                mPendingHolder = holder;
                ensureSurfaceReady(holder);
                break;

            case MSG_SURFACE_CHANGED:
                mViewWidth = msg.arg1;
                mViewHeight = msg.arg2;
                updateMatrix(); // 尺寸变了，重新计算防拉伸矩阵
                break;

            case MSG_DRAW_FRAME:
                int texWidth = msg.arg1;
                int texHeight = msg.arg2;
                int textureId = (int) msg.obj;

                // 防御：如果 SurfaceView 还没准备好物理尺寸就强行清屏绘制，会导致纯黑闪烁
                if (mViewWidth == 0 || mViewHeight == 0) {
                    break;
                }

                // 如果传入的画面尺寸变了，也需要重新计算矩阵
                if (mFrameWidth != texWidth || mFrameHeight != texHeight) {
                    mFrameWidth = texWidth;
                    mFrameHeight = texHeight;
                    updateMatrix();
                }

                if (mEglBase != null && mEglBase.hasSurface()) {
                    GLES20.glViewport(0, 0, mViewWidth, mViewHeight);
                    GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

                    if (mDrawer != null) {
                        mDrawer.draw(textureId, mMvpMatrix);
                    }
                    mEglBase.swapBuffers(); // 触发 Vsync 等待，完美平滑
                }
                break;

            case MSG_SURFACE_DESTROYED:
                if (mEglBase != null) {
                    mEglBase.makeCurrent();
                    // 不要释放 mDrawer，因为 EGL Context 并没有销毁，还可以继续使用
                    mEglBase.releaseSurface(); // 仅仅释放 EGL 窗口表面
                }
                mPendingHolder = null;
                CountDownLatch latch = (CountDownLatch) msg.obj;
                latch.countDown(); // 通知主线程：我释完了，你可以把物理 Surface 炸掉了
                break;

            case MSG_RELEASE:
                if (mEglBase != null) {
                    try {
                        mEglBase.makeCurrent();
                        if (mDrawer != null) {
                            mDrawer.release();
                            mDrawer = null;
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "release drawer on MSG_RELEASE failed", e);
                    }
                    mEglBase.releaseSurface();
                    mEglBase.release();
                    mEglBase = null;
                }
                break;
        }
        return true;
    }

    private void updateMatrix() {
        if (mViewWidth == 0 || mViewHeight == 0 || mFrameWidth == 0 || mFrameHeight == 0) return;

        float viewRatio = (float) mViewWidth / mViewHeight;
        float frameRatio = (float) mFrameWidth / mFrameHeight;

        Matrix.setIdentityM(mMvpMatrix, 0);

        if (mScaleType == ScaleType.CENTER_CROP) {
            if (frameRatio > viewRatio) {
                float scale = frameRatio / viewRatio;
                Matrix.scaleM(mMvpMatrix, 0, scale, 1.0f, 1.0f);
            } else {
                float scale = viewRatio / frameRatio;
                Matrix.scaleM(mMvpMatrix, 0, 1.0f, scale, 1.0f);
            }
        } else {
            if (frameRatio > viewRatio) {
                float scale = viewRatio / frameRatio;
                Matrix.scaleM(mMvpMatrix, 0, 1.0f, scale, 1.0f);
            } else {
                float scale = frameRatio / viewRatio;
                Matrix.scaleM(mMvpMatrix, 0, scale, 1.0f, 1.0f);
            }
        }
    }

    private void ensureSurfaceReady(SurfaceHolder holder) {
        if (holder == null || mEglBase == null) {
            return;
        }
        if (!mEglBase.hasSurface()) {
            mEglBase.createSurface(holder.getSurface());
        }
        mEglBase.makeCurrent();
        if (!mDrawerInitialized) {
            if (mDrawer != null) {
                mDrawer.init();
            }
            mDrawerInitialized = true;
        }
    }
}
