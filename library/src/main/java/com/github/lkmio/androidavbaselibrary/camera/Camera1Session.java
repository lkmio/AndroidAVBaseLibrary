package com.github.lkmio.androidavbaselibrary.camera;

import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.view.SurfaceHolder;

import java.io.IOException;
import java.util.List;

@SuppressWarnings("deprecation")
public class Camera1Session {
    private Camera mCamera;
    private int mCameraId;
    private PreviewCallback mPreviewCallback;
    private Camera.Size mPreviewSize;

    public interface PreviewCallback {
        void onPreviewFrame(byte[] data, Camera camera);
    }

    public void open(int cameraId) {
        mCameraId = cameraId;
        try {
            mCamera = Camera.open(mCameraId);
            setupCameraParameters();
        } catch (RuntimeException e) {
            e.printStackTrace();
        }
    }

    private void setupCameraParameters() {
        if (mCamera == null) {
            return;
        }
        Camera.Parameters params = mCamera.getParameters();
        List<Camera.Size> supportedPreviewSizes = params.getSupportedPreviewSizes();
        if (supportedPreviewSizes != null && !supportedPreviewSizes.isEmpty()) {
            // Simple logic to choose a preview size. You might want to implement more complex logic.
            mPreviewSize = supportedPreviewSizes.get(0);
            params.setPreviewSize(mPreviewSize.width, mPreviewSize.height);
        }

        List<int[]> supportedPreviewFpsRange = params.getSupportedPreviewFpsRange();
        if (supportedPreviewFpsRange != null && !supportedPreviewFpsRange.isEmpty()) {
            int[] range = supportedPreviewFpsRange.get(supportedPreviewFpsRange.size() - 1);
            params.setPreviewFpsRange(range[0], range[1]);
        }
        params.setPreviewFormat(ImageFormat.NV21);
        mCamera.setParameters(params);
    }


    public void setPreviewSurface(SurfaceHolder surfaceHolder) {
        if (mCamera != null) {
            try {
                mCamera.setPreviewDisplay(surfaceHolder);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public void setPreviewTexture(SurfaceTexture surfaceTexture) {
        if (mCamera != null) {
            try {
                mCamera.setPreviewTexture(surfaceTexture);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public void setPreviewCallback(PreviewCallback callback) {
        mPreviewCallback = callback;
    }

    public void startPreview() {
        if (mCamera != null) {
            if (mPreviewCallback != null) {
                int bufferSize = mPreviewSize.width * mPreviewSize.height * ImageFormat.getBitsPerPixel(ImageFormat.NV21) / 8;
                mCamera.addCallbackBuffer(new byte[bufferSize]);
                mCamera.setPreviewCallbackWithBuffer((data, camera) -> {
                    if (mPreviewCallback != null) {
                        mPreviewCallback.onPreviewFrame(data, camera);
                    }
                    if (mCamera != null) {
                        mCamera.addCallbackBuffer(data);
                    }
                });
            }
            mCamera.startPreview();
        }
    }

    public void stopPreview() {
        if (mCamera != null) {
            mCamera.setPreviewCallbackWithBuffer(null);
            mCamera.stopPreview();
        }
    }

    public void release() {
        if (mCamera != null) {
            stopPreview();
            mCamera.release();
            mCamera = null;
        }
        mPreviewCallback = null;
    }

    public Camera.Size getPreviewSize() {
        return mPreviewSize;
    }
}
