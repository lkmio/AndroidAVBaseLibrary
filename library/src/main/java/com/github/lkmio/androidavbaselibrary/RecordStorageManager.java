package com.github.lkmio.androidavbaselibrary;

import android.os.StatFs;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class RecordStorageManager {
    private static final String TAG = "RecordStorageManager";

    public RecordStorageManager() {
    }

    /**
     * Ensure there is at least minFreeSpaceBytes free space in the record directory.
     * If not, deletes the oldest .mp4 files.
     *
     * @return true if there is enough space (or space was freed successfully), false otherwise.
     */
    public boolean ensureFreeSpace(String recordDir, long minFreeSpaceBytes) {
        File dir = new File(recordDir);
        if (!dir.exists() && !dir.mkdirs()) {
            Log.e(TAG, "Failed to create record directory: " + recordDir);
            return false;
        }

        long freeSpace = getFreeSpaceBytes(recordDir);
        if (freeSpace >= minFreeSpaceBytes) {
            return true;
        }

        Log.i(TAG, "Insufficient space (" + freeSpace + " < " + minFreeSpaceBytes + "). Trying to delete old records.");

        // Define a target free space with a multiplier (e.g. 1.5) so we don't have to delete files too frequently
        long targetFreeSpaceBytes = (long) (minFreeSpaceBytes * 1.5);

        List<File> mp4Files = new ArrayList<>();
        collectMp4Files(dir, mp4Files);

        // Sort by last modified time ascending (oldest first)
        Collections.sort(mp4Files, (f1, f2) -> Long.compare(f1.lastModified(), f2.lastModified()));

        for (File file : mp4Files) {
            long size = file.length();
            if (file.delete()) {
                Log.i(TAG, "Deleted old record file: " + file.getAbsolutePath());
                freeSpace = getFreeSpaceBytes(recordDir);
                if (freeSpace >= targetFreeSpaceBytes) {
                    return true;
                }
            } else {
                Log.w(TAG, "Failed to delete record file: " + file.getAbsolutePath());
            }
        }

        // Clean up empty directories
        cleanEmptyDirectories(dir);

        freeSpace = getFreeSpaceBytes(recordDir);
        if (freeSpace < minFreeSpaceBytes) {
            Log.e(TAG, "Still insufficient space after deleting all possible record files. Free: " + freeSpace);
            return false;
        }
        return true;
    }

    private long getFreeSpaceBytes(String recordDir) {
        try {
            StatFs statFs = new StatFs(recordDir);
            return statFs.getAvailableBytes();
        } catch (Exception e) {
            Log.e(TAG, "Failed to get free space for: " + recordDir, e);
            return 0;
        }
    }

    private void collectMp4Files(File dir, List<File> files) {
        File[] list = dir.listFiles();
        if (list == null) {
            return;
        }
        for (File f : list) {
            if (f.isDirectory()) {
                collectMp4Files(f, files);
            } else if (f.isFile() && f.getName().toLowerCase().endsWith(".mp4")) {
                files.add(f);
            }
        }
    }

    private void cleanEmptyDirectories(File dir) {
        File[] list = dir.listFiles();
        if (list == null) {
            return;
        }
        for (File f : list) {
            if (f.isDirectory()) {
                cleanEmptyDirectories(f);
                File[] subList = f.listFiles();
                if (subList != null && subList.length == 0) {
                    if (f.delete()) {
                        Log.i(TAG, "Deleted empty directory: " + f.getAbsolutePath());
                    }
                }
            }
        }
    }
}
