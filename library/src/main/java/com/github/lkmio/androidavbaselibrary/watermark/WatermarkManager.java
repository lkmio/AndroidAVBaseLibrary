package com.github.lkmio.androidavbaselibrary.watermark;

import android.graphics.Bitmap;
import android.graphics.Rect;

import com.github.lkmio.androidavbaselibrary.DynamicOSD;
import com.github.lkmio.androidavbaselibrary.OSD;
import com.github.lkmio.androidavbaselibrary.VideoWorker;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class WatermarkManager {
    public static class OsdRecord {
        public int id;
        public String targetCameraId;
        public Integer targetFacing;
        public OSD osd;

        public OsdRecord(int id, String targetCameraId, Integer targetFacing, OSD osd) {
            this.id = id;
            this.targetCameraId = targetCameraId;
            this.targetFacing = targetFacing;
            this.osd = osd;
        }

        public boolean matches(String currentCameraId, Integer currentFacing) {
            if (targetCameraId != null && !targetCameraId.equals(currentCameraId)) {
                return false;
            }
            if (targetFacing != null && !targetFacing.equals(currentFacing)) {
                return false;
            }
            return true;
        }
    }

    private final AtomicInteger mOsdIdGenerator = new AtomicInteger(1);
    private final Map<Integer, OsdRecord> mRecords = new ConcurrentHashMap<>();

    public int addStaticWatermark(String targetCameraId, Integer targetFacing, Bitmap bitmap, int gravity, Rect margin) {
        int id = mOsdIdGenerator.getAndIncrement();
        OSD osd = new OSD();
        osd.bitmap = bitmap;
        osd.gravity = gravity;
        if (margin != null) {
            osd.margin.set(margin);
        }
        mRecords.put(id, new OsdRecord(id, targetCameraId, targetFacing, osd));
        return id;
    }

    public int addStaticWatermark(String targetCameraId, Integer targetFacing, String text, int textSize, int color, int gravity, Rect margin) {
        int id = mOsdIdGenerator.getAndIncrement();
        OSD osd = new OSD();
        osd.text = text;
        osd.size = textSize;
        osd.color = color;
        osd.gravity = gravity;
        if (margin != null) {
            osd.margin.set(margin);
        }
        mRecords.put(id, new OsdRecord(id, targetCameraId, targetFacing, osd));
        return id;
    }

    public int addDynamicTextWatermark(String targetCameraId, Integer targetFacing, DynamicOSD osd, int textSize, int color, int gravity, Rect margin) {
        int id = mOsdIdGenerator.getAndIncrement();
        osd.size = textSize;
        osd.color = color;
        osd.gravity = gravity;
        if (margin != null) {
            osd.margin.set(margin);
        }
        mRecords.put(id, new OsdRecord(id, targetCameraId, targetFacing, osd));
        return id;
    }

    public boolean removeWatermark(int id) {
        return mRecords.remove(id) != null;
    }

    public void syncToVideoWorker(VideoWorker videoWorker, String currentCameraId, Integer currentFacing) {
        if (videoWorker == null) {
            return;
        }
        videoWorker.removeAllOsd();
        for (OsdRecord record : mRecords.values()) {
            if (record.matches(currentCameraId, currentFacing)) {
                if (record.osd instanceof DynamicOSD) {
                    videoWorker.addDynamicOsd(record.id, (DynamicOSD) record.osd);
                } else {
                    videoWorker.addStaticOsd(record.id, record.osd);
                }
            }
        }
    }

    public void syncAddedOsd(int id, VideoWorker videoWorker, String currentCameraId, Integer currentFacing) {
        if (videoWorker == null) {
            return;
        }
        OsdRecord record = mRecords.get(id);
        if (record != null && record.matches(currentCameraId, currentFacing)) {
            if (record.osd instanceof DynamicOSD) {
                videoWorker.addDynamicOsd(record.id, (DynamicOSD) record.osd);
            } else {
                videoWorker.addStaticOsd(record.id, record.osd);
            }
        }
    }
}
