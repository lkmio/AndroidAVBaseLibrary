package com.github.lkmio.androidavbaselibrary;

import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * 封装 MediaExtractor 的 MP4 解析器。
 * 内部统一维护一块可动态扩容的 DirectBuffer，以最高效地复用内存。
 */
public class Mp4Demuxer {
    private static final String TAG = "Mp4Demuxer";

    private MediaExtractor mExtractor;
    private ByteBuffer mDirectBuffer;

    public Mp4Demuxer() {
        mExtractor = new MediaExtractor();
        // 初始分配 1MB 的 DirectBuffer，后续可根据情况动态扩容
        mDirectBuffer = ByteBuffer.allocateDirect(1024 * 1024);
    }

    /**
     * 设置数据源并探测内部轨道，探测成功返回 true。
     */
    public boolean setDataSource(String path) {
        try {
            mExtractor.setDataSource(path);
            int maxInputSize = 0;
            int trackCount = mExtractor.getTrackCount();
            for (int i = 0; i < trackCount; i++) {
                MediaFormat format = mExtractor.getTrackFormat(i);
                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    maxInputSize = Math.max(maxInputSize, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
                }
            }
            // 如果某条轨道声明的单帧最大体积超出了当前的 Buffer，则进行扩容
            if (maxInputSize > mDirectBuffer.capacity()) {
                mDirectBuffer = ByteBuffer.allocateDirect(maxInputSize);
                Log.i(TAG, "Reallocated DirectBuffer to track KEY_MAX_INPUT_SIZE: " + maxInputSize);
            }
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Failed to set data source: " + path, e);
            return false;
        }
    }

    /**
     * 获取文件中的所有轨道信息。
     */
    public List<MediaFormat> getTracks() {
        List<MediaFormat> tracks = new ArrayList<>();
        int count = mExtractor.getTrackCount();
        for (int i = 0; i < count; i++) {
            tracks.add(mExtractor.getTrackFormat(i));
        }
        return tracks;
    }

    /**
     * 选中指定的轨道，以便后续通过 readSampleData 读取该轨道的数据。
     */
    public void selectTrack(int index) {
        mExtractor.selectTrack(index);
    }

    /**
     * 取消选中指定的轨道。
     */
    public void unselectTrack(int index) {
        mExtractor.unselectTrack(index);
    }

    /**
     * 跳转到指定时间。
     * @param timeUs 时间（微秒）
     * @param mode 例如 MediaExtractor.SEEK_TO_PREVIOUS_SYNC
     */
    public void seekTo(long timeUs, int mode) {
        mExtractor.seekTo(timeUs, mode);
    }

    /**
     * 获取当前 Sample 所属的轨道 Index。
     */
    public int getSampleTrackIndex() {
        return mExtractor.getSampleTrackIndex();
    }

    /**
     * 获取当前 Sample 的时间戳（微秒）。
     */
    public long getSampleTime() {
        return mExtractor.getSampleTime();
    }

    /**
     * 获取当前 Sample 的 Flags（如 MediaCodec.BUFFER_FLAG_KEY_FRAME）。
     */
    public int getSampleFlags() {
        return mExtractor.getSampleFlags();
    }

    /**
     * 读取当前的 Sample 数据，存入内部封装的 DirectBuffer 中。
     * 读取成功后可通过 {@link #getSampleDataBuffer()} 获取数据内容（其 limit 为实际读取大小，position 为 0）。
     *
     * @return 实际读取到的字节数；如果已经读到文件末尾，则返回 -1。
     */
    public int readSampleData() {
        try {
            mDirectBuffer.clear();
            int size = mExtractor.readSampleData(mDirectBuffer, 0);
            if (size > 0) {
                mDirectBuffer.limit(size);
            }
            return size;
        } catch (IllegalArgumentException e) {
            // 当遇到的帧体积异常庞大，超出了预估分配的缓冲池容量时，执行动态两倍扩容
            int newCapacity = mDirectBuffer.capacity() * 2;
            Log.w(TAG, "DirectBuffer capacity too small. Reallocating to " + newCapacity + " bytes.");
            mDirectBuffer = ByteBuffer.allocateDirect(newCapacity);
            return readSampleData(); // 扩容后重试一次
        }
    }

    /**
     * 推进到下一帧。通常在 {@link #readSampleData()} 之后调用。
     * @return 成功推进返回 true；若已无更多数据返回 false。
     */
    public boolean advance() {
        return mExtractor.advance();
    }

    /**
     * 获取内部统一维护的 DirectBuffer。
     * 每次调用 {@link #readSampleData()} 后，该 Buffer 内将装载当前帧的数据，并自动设置好 limit 和 position。
     */
    public ByteBuffer getSampleDataBuffer() {
        return mDirectBuffer;
    }

    /**
     * 释放解析器资源。
     */
    public void release() {
        if (mExtractor != null) {
            mExtractor.release();
            mExtractor = null;
        }
        mDirectBuffer = null;
    }
}
