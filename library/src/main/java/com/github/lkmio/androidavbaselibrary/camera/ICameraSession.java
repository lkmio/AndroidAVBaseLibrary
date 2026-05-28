package com.github.lkmio.androidavbaselibrary.camera;

import androidx.lifecycle.LifecycleOwner;

public interface ICameraSession {
    // 启动相机会话
    void open(String cameraId);

    // 停止相机会话
    void close();

    void addSink();

    void addPreviewSink();

    void addPreviewSink(LifecycleOwner owner);

    void removeSink();

    int sinkCount();
}
