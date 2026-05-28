package com.github.lkmio.androidavbaselibrary.utils;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.TextPaint;

public class OSDUtils {

    public static Bitmap createTextWatermark(String text, int textSize, int color) {
        TextPaint paint = new TextPaint();
        paint.setAntiAlias(true);
        paint.setColor(color);
        paint.setTextSize(textSize);
        paint.setTypeface(Typeface.DEFAULT_BOLD);

        // 测量文字占据的长宽
        float textWidth = paint.measureText(text);
        Paint.FontMetrics fm = paint.getFontMetrics();
        float textHeight = fm.bottom - fm.top;

        // 创建刚好包裹住文字的 Bitmap
        Bitmap bitmap = Bitmap.createBitmap((int) textWidth, (int) textHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        // 把文字画上去 (注意 Android Canvas 画文字 y 坐标是 Baseline)
        canvas.drawText(text, 0, -fm.top, paint);

        return bitmap;
    }


    public final Bitmap createTextImage(String text, int textSize, String textColor, String bgColor, int padding) {
        Paint paint = new Paint();
        paint.setColor(Color.parseColor(textColor));
        paint.setTextSize((float)textSize);
        paint.setStyle(Paint.Style.FILL);
        paint.setAntiAlias(true);
        float width = paint.measureText(text, 0, text.length());
        float top = paint.getFontMetrics().top;
        float bottom = paint.getFontMetrics().bottom;
        Bitmap bm = Bitmap.createBitmap((int)(width + (float)(padding * 2)), (int)(bottom - top + (float)(padding * 2)), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bm);
        canvas.drawColor(Color.parseColor(bgColor));
        canvas.drawText(text, (float)padding, -top + (float)padding, paint);
        return bm;
    }
}
