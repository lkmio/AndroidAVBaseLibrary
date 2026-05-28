package com.github.lkmio.androidavbaselibrary;

import com.github.lkmio.androidavbaselibrary.egl.EglBase;

public class Frame {
    public int id;

    public byte[] data;
    public int size;
    public int textureId;
    public int fboId;
    public long timestamp;

    public int width;

    public int height;
    public EglBase.Context eglContext;
}
