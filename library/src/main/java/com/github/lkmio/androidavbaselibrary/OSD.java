package com.github.lkmio.androidavbaselibrary;

import android.graphics.Rect;

public class OSD {
    public String text;
    public android.graphics.Bitmap bitmap;

    public int color = 0xFFFFFFFF; // 默认白色
    public int gravity;
    public Rect margin = new Rect();

    public int size;    // 0-自动调整
}
