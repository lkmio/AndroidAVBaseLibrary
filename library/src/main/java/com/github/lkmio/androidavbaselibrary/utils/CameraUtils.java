package com.github.lkmio.androidavbaselibrary.utils;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class CameraUtils {

    /**
     * A simple data class to hold camera information.
     */
    
    public static class CameraInfo {
        public String cameraId;
        public Integer facing;
        public Integer sensorOrientation;
        public Boolean mirrorX;
        public Boolean mirrorY;

        public CameraInfo() {}

        public CameraInfo(String cameraId, Integer facing, Integer sensorOrientation) {
            this(cameraId, facing, sensorOrientation, null, null);
        }

        public CameraInfo(String cameraId, Integer facing, Integer sensorOrientation, Boolean mirrorX, Boolean mirrorY) {
            this.cameraId = cameraId;
            this.facing = facing;
            this.sensorOrientation = sensorOrientation;
            this.mirrorX = mirrorX;
            this.mirrorY = mirrorY;
        }

        public static CameraInfo forFacing(Integer facing, Integer sensorOrientation, Boolean mirrorX, Boolean mirrorY) {
            return new CameraInfo(null, facing, sensorOrientation, mirrorX, mirrorY);
        }
    }

    /**
     * Retrieves a list of available cameras and their characteristics.
     *
     * @param context The application context to access system services.
     * @return A list of {@link CameraInfo} objects.
     */
    public static List<CameraInfo> getCameraInfoList(Context context) {
        List<CameraInfo> cameraInfoList = new ArrayList<>();
        CameraManager cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        if (cameraManager == null) {
            return cameraInfoList;
        }

        try {
            String[] cameraIdList = cameraManager.getCameraIdList();
            for (String cameraId : cameraIdList) {
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                Integer orientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);

                if (facing != null && orientation != null) {
                    cameraInfoList.add(new CameraInfo(cameraId, facing, orientation));
                }
            }
        } catch (CameraAccessException e) {
            Log.w("CameraUtils", "getCameraInfoList failed", e);
        }
        return cameraInfoList;
    }

    /**
     * Retrieves a list of supported preview resolutions for a specific camera.
     * These sizes are suitable for preview streams like {@link SurfaceTexture}.
     *
     * @param context  The application context.
     * @param cameraId The ID of the camera.
     * @return A list of supported {@link Size} objects, or an empty list if unable to access camera info.
     */
    public static List<Size> getSupportedResolutions(Context context, String cameraId) {
        CameraManager cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        if (cameraManager == null) {
            return Collections.emptyList();
        }

        try {
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) {
                return Collections.emptyList();
            }

            // Get supported sizes for SurfaceTexture, which is a common target for camera previews.
            Size[] outputSizes = map.getOutputSizes(SurfaceTexture.class);
            if (outputSizes == null) {
                return Collections.emptyList();
            }
            return Arrays.asList(outputSizes);
        } catch (CameraAccessException e) {
            Log.w("CameraUtils", "getSupportedResolutions failed", e);
            return Collections.emptyList();
        }
    }

    /**
     * Gets the total number of cameras available on the device.
     *
     * @param context The application context.
     * @return The number of cameras.
     */
    public static int getCameraCount(Context context) {
        CameraManager cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        if (cameraManager == null) {
            return 0;
        }
        try {
            return cameraManager.getCameraIdList().length;
        } catch (CameraAccessException e) {
            Log.w("CameraUtils", "getCameraCount failed", e);
            return 0;
        }
    }

    /**
     * Calculates the rotation of the camera preview display in degrees.
     *
     * @param context           The application context.
     * @param sensorOrientation The orientation of the camera sensor, from {@link CameraCharacteristics#SENSOR_ORIENTATION}.
     * @param isFrontCamera     True if the camera is front-facing.
     * @return The display rotation in degrees (0, 90, 180, 270).
     */
    public static int getDisplayRotation(Context context, int sensorOrientation, boolean isFrontCamera) {
        WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (windowManager == null) {
            return 0;
        }
        int deviceRotation = windowManager.getDefaultDisplay().getRotation();
        int rotationCompensation = 0;
        switch (deviceRotation) {
            case Surface.ROTATION_0:
                rotationCompensation = 0;
                break;
            case Surface.ROTATION_90:
                rotationCompensation = 90;
                break;
            case Surface.ROTATION_180:
                rotationCompensation = 180;
                break;
            case Surface.ROTATION_270:
                rotationCompensation = 270;
                break;
        }

        int result;
        if (isFrontCamera) {
            result = (sensorOrientation + rotationCompensation) % 360;
            result = (360 - result) % 360;  // Compensate for the mirror effect
        } else {  // Back-facing
            result = (sensorOrientation - rotationCompensation + 360) % 360;
        }
        return result;
    }

    public static int getSensorOrientation(Context context, String cameraId) {
        CameraManager cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        if (cameraManager == null) {
            return 0;
        }
        try {
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
            return characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
        } catch (CameraAccessException e) {
            Log.w("CameraUtils", "getSensorOrientation failed", e);
        }
        return 0;
    }

    public static void resolveVideoSize(Context context, String cameraId, int targetWidth, int targetHeight, int[] outSize) {
        List<Size> supported = getSupportedResolutions(context, cameraId);
        if (!supported.isEmpty()) {
            Size closest = getClosestSupportedSize(supported, targetWidth, targetHeight);
            outSize[0] = closest.getWidth();
            outSize[1] = closest.getHeight();
        } else {
            outSize[0] = targetWidth;
            outSize[1] = targetHeight;
        }
    }

    public static Size getClosestSupportedSize(List<Size> supportedSizes, final int requestedWidth, final int requestedHeight) {
        return (Size) Collections.min(supportedSizes, new ClosestComparator<Size>() {
            int diff(Size size) {
                return Math.abs(requestedWidth - size.getWidth()) + Math.abs(requestedHeight - size.getHeight());
            }
        });
    }

    private abstract static class ClosestComparator<T> implements Comparator<T> {
        private ClosestComparator() {
        }

        abstract int diff(T var1);

        public int compare(T t1, T t2) {
            return this.diff(t1) - this.diff(t2);
        }
    }
}
