package com.github.lkmio.androidavbaselibrary;

import android.graphics.Bitmap;
import android.graphics.Rect;

import com.github.lkmio.androidavbaselibrary.camera.Camera2Session;

public interface ILiveSource {
    void start();

    void stop();

    boolean addAudioFrameSink(FrameSink sink);

    boolean addVideoFrameSink(FrameSink sink);

    boolean addAudioPacketSink(PacketSink sink);

    boolean addVideoPacketSink(PacketSink sink);

    boolean removeAudioFrameSink(FrameSink sink);

    boolean removeVideoFrameSink(FrameSink sink);

    boolean removeAudioPacketSink(PacketSink sink);

    boolean removeVideoPacketSink(PacketSink sink);

    boolean addStreamSink(StreamSink sink);

    boolean addStreamSink(StreamSink sink, long timeoutMs);

    void removeStreamSink(StreamSink sink);

    int addStaticWatermark(Bitmap bitmap, int gravity, Rect margin);

    int addStaticWatermark(String text, int textSize, int color, int gravity, Rect margin);

    int addDynamicTextWatermark(DynamicOSD osd, int textSize, int color, int gravity, Rect margin);

    int addStaticWatermark(String targetCameraId, Integer targetFacing, Bitmap bitmap, int gravity, Rect margin);

    int addStaticWatermark(String targetCameraId, Integer targetFacing, String text, int textSize, int color, int gravity, Rect margin);

    int addDynamicTextWatermark(String targetCameraId, Integer targetFacing, DynamicOSD osd, int textSize, int color, int gravity, Rect margin);

    boolean removeWatermark(int index);

    void setRotation(int rotation);

    void setMirrorX(boolean mirrorX);

    void setMirrorY(boolean mirrorY);

    int getRotation();

    boolean isMirrorX();

    boolean isMirrorY();

    void setOnCameraOpenListener(Camera2Session.OnCameraOpenListener listener);

    String getCameraId();

    AVCodec getVideoCodec();

    void setVideoCodec(AVCodec codec);

    boolean switchCamera(String cameraId);

    interface OnDeviceErrorListener {
        void onCameraError(String cameraId, String errorMsg);
        void onAudioError(String errorMsg);
    }

    void setOnDeviceErrorListener(OnDeviceErrorListener listener);

    interface SnapshotCallback {
        void onSnapshot(Bitmap bitmap);
    }

    void takePhoto(SnapshotCallback callback);
}
