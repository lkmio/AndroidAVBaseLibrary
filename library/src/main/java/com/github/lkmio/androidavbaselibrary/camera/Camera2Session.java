package com.github.lkmio.androidavbaselibrary.camera;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CameraCharacteristics;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.NonNull;

import android.hardware.camera2.TotalCaptureResult;
import android.util.Range;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.UUID;

public class Camera2Session {

    private static final String TAG = "Camera2Session";

    private static Field sResultsField;
    private static Method sCloseMethod;

    private final Context mContext;
    private final String mSessionId;
    private String mCameraId;
    private CameraDevice mCameraDevice;
    private CameraCaptureSession mCaptureSession;
    private HandlerThread mCameraThread;
    private Handler mCameraHandler;

    // For Surface output
    private Surface mTargetSurface;

    // For YUV output
    private ImageReader mImageReader;
    private OnImageAvailableListener mOnImageAvailableListener;

    private int mFps = 25;

    public interface OnCameraOpenListener {
        void onCameraOpened(boolean success);
    }

    private OnCameraOpenListener mOnCameraOpenListener;

    public void setOnCameraOpenListener(OnCameraOpenListener listener) {
        mOnCameraOpenListener = listener;
    }

    /**
     * Listener for raw YUV image data.
     */
    public interface OnImageAvailableListener {
        /**
         * A new image is available. The implementing class must call {@link Image#close()}
         * on the image when it's done with it to prevent stalling the camera pipeline.
         */
        void onImageAvailable(Image image);
    }

    public Camera2Session(Context context) {
        this.mContext = context.getApplicationContext();
        this.mSessionId = UUID.randomUUID().toString().substring(0, 8);
    }

    public String getSessionId() {
        return mSessionId;
    }

    /**
     * Starts camera capture outputting to a Surface (for GPU processing).
     * @return true if camera opening request is successfully dispatched.
     */
    public boolean start(String cameraId, Surface surface, int fps) {
        if (isSessionActive()) {
            Log.w(TAG, "Session already started.");
            return false;
        }
        this.mFps = fps;
        this.mTargetSurface = surface;
        this.mCameraId = cameraId;
        return openCamera(cameraId);
    }

    /**
     * Starts camera capture outputting raw YUV data (for CPU processing).
     * @return true if camera opening request is successfully dispatched.
     */
    public boolean start(String cameraId, int width, int height, int fps, OnImageAvailableListener listener) {
        if (isSessionActive()) {
            Log.w(TAG, "Session already started.");
            return false;
        }
        this.mFps = fps;
        this.mOnImageAvailableListener = listener;

        startBackgroundThread(); // Start thread early to get a handler for the listener

        this.mImageReader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 2);
        this.mImageReader.setOnImageAvailableListener(reader -> {
            Image image = reader.acquireLatestImage();
            if (image != null) {
                if (mOnImageAvailableListener != null) {
                    mOnImageAvailableListener.onImageAvailable(image);
                } else {
                    // Important: Close the image if there's no one to handle it.
                    image.close();
                }
            }
        }, mCameraHandler);

        this.mTargetSurface = mImageReader.getSurface();
        this.mCameraId = cameraId;
        return openCamera(cameraId);
    }

    private boolean openCamera(String cameraId) {
        if (mCameraHandler == null) {
            startBackgroundThread();
        }

        CameraManager cameraManager = (CameraManager) mContext.getSystemService(Context.CAMERA_SERVICE);
        if (cameraManager == null) {
            Log.e(TAG, "CameraManager is not available.");
            return false;
        }

        try {
            cameraManager.openCamera(cameraId, mDeviceStateCallback, mCameraHandler);
            return true;
        } catch (CameraAccessException | SecurityException | IllegalArgumentException e) {
            Log.e(TAG, "Failed to open camera", e);
            return false;
        }
    }

    public void stop() {
        Log.d(TAG, "Stopping camera session.");
        try {
            if (mCaptureSession != null) {
                mCaptureSession.close();
                mCaptureSession = null;
            }
            if (mCameraDevice != null) {
                mCameraDevice.close();
                mCameraDevice = null;
            }
            if (mImageReader != null) {
                mImageReader.close();
                mImageReader = null;
            }
        } finally {
            mTargetSurface = null;
            mOnImageAvailableListener = null;
            stopBackgroundThread();
        }
    }

    private boolean isSessionActive() {
        return mCameraThread != null || mCameraDevice != null;
    }

    static void recycle(TotalCaptureResult tcr) {
        try {
            if (sResultsField == null) {
                sResultsField = tcr.getClass().getSuperclass().getDeclaredField("mResults");
                sResultsField.setAccessible(true);
            }
            if (sCloseMethod == null) {
                Class<?> aClass = Class.forName("android.hardware.camera2.impl.CameraMetadataNative");
                Method[] declaredMethods = aClass.getDeclaredMethods();

                Method close = null;
                Method finalize = null;
                for (Method m : declaredMethods) {
                    if (m.getName().contains("close")) {
                        close = m;
                        break;
                    } else if (m.getName().contains("finalize")) {
                        finalize = m;
                    }
                }

                sCloseMethod = close == null ? finalize : close;
                if (sCloseMethod == null) {
                    return;
                }

                sCloseMethod.setAccessible(true);

            }
            sCloseMethod.invoke(sResultsField.get(tcr));
        } catch (Exception e) {
            // Ignore on devices where reflection is blocked or fields are changed
        }
    }

    private final CameraDevice.StateCallback mDeviceStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            Log.d(TAG, "Camera device opened.");
            mCameraDevice = camera;
            if (mOnCameraOpenListener != null) {
                mOnCameraOpenListener.onCameraOpened(true);
            }
            createCameraPreviewSession();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            Log.w(TAG, "Camera device disconnected.");
            camera.close();
            mCameraDevice = null;
            if (mOnCameraOpenListener != null) {
                mOnCameraOpenListener.onCameraOpened(false);
            }
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            Log.e(TAG, "Camera device error: " + error);
            camera.close();
            mCameraDevice = null;
            if (mOnCameraOpenListener != null) {
                mOnCameraOpenListener.onCameraOpened(false);
            }
        }
    };

    private void createCameraPreviewSession() {
        if (mCameraDevice == null || mTargetSurface == null || !mTargetSurface.isValid()) {
            Log.e(TAG, "Cannot create session, camera or surface is not ready.");
            return;
        }

        try {
            final CaptureRequest.Builder previewRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewRequestBuilder.addTarget(mTargetSurface);

            mCameraDevice.createCaptureSession(Collections.singletonList(mTargetSurface), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    if (mCameraDevice == null) return;
                    mCaptureSession = session;
                    try {
                        CameraManager manager = (CameraManager) mContext.getSystemService(Context.CAMERA_SERVICE);
                        if (manager != null && mCameraId != null) {
                            CameraCharacteristics characteristics = manager.getCameraCharacteristics(mCameraId);
                            int[] afModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
                            if (afModes != null) {
                                for (int mode : afModes) {
                                    if (mode == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) {
                                        previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                        break;
                                    }
                                }
                            }

                            Range<Integer>[] fpsRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
                            if (fpsRanges != null && mFps > 0) {
                                Range<Integer> bestRange = null;
                                for (Range<Integer> range : fpsRanges) {
                                    if (range.getUpper() == mFps) {
                                        if (bestRange == null || range.getLower() < bestRange.getLower()) {
                                            bestRange = range;
                                        }
                                    }
                                }
                                if (bestRange == null) {
                                    for (Range<Integer> range : fpsRanges) {
                                        if (range.getUpper() >= mFps) {
                                            if (bestRange == null || range.getUpper() < bestRange.getUpper()) {
                                                bestRange = range;
                                            }
                                        }
                                    }
                                }
                                if (bestRange != null) {
                                    previewRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, bestRange);
                                }
                            }
                        }
                        mCaptureSession.setRepeatingRequest(previewRequestBuilder.build(), new CameraCaptureSession.CaptureCallback() {
                            @Override
                            public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                                super.onCaptureCompleted(session, request, result);
                                recycle(result);
                            }
                        }, mCameraHandler);
                    } catch (CameraAccessException e) {
                        Log.e(TAG, "Failed to start repeating request", e);
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    Log.e(TAG, "Failed to configure camera capture session.");
                }
            }, mCameraHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to create capture session", e);
        }
    }

    private void startBackgroundThread() {
        mCameraThread = new HandlerThread("Camera2Background");
        mCameraThread.start();
        mCameraHandler = new Handler(mCameraThread.getLooper());
    }

    private void stopBackgroundThread() {
        if (mCameraThread != null) {
            mCameraThread.quitSafely();
            try {
                mCameraThread.join(500);
            } catch (InterruptedException e) {
                Log.e(TAG, "Failed to stop background thread", e);
            } finally {
                mCameraThread = null;
                mCameraHandler = null;
            }
        }
    }
}
