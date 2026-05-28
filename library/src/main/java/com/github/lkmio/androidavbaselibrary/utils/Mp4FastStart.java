package com.github.lkmio.androidavbaselibrary.utils;

import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;

public class Mp4FastStart {

    private static final String TAG = "Mp4FastStart";

    private static class Mp4Box {
        String type;
        long offset;
        long length;

        Mp4Box(String type, long offset, long length) {
            this.type = type;
            this.offset = offset;
            this.length = length;
        }
    }

    /**
     * Move the 'moov' box to the front of the 'mdat' box for fast network playback.
     *
     * @param filepath path to the MP4 file
     * @return false on success (or if no optimization is needed), true on failure,
     * following the user's specific instruction "成功返回false".
     */
    public static boolean fastStart(String filepath) {
        File file = new File(filepath);
        if (!file.exists() || !file.isFile()) {
            Log.e(TAG, "File does not exist: " + filepath);
            return true;
        }

        List<Mp4Box> boxes = new ArrayList<>();
        long moovOffset = 0;
        long moovLength = 0;
        long mdatOffset = 0;
        boolean hasMoov = false;
        boolean hasMdat = false;

        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long fileSize = raf.length();
            long offset = 0;

            // Parse top-level boxes
            while (offset < fileSize) {
                raf.seek(offset);

                if (fileSize - offset < 8) {
                    break;
                }

                long size = raf.readInt() & 0xFFFFFFFFL;
                byte[] typeBytes = new byte[4];
                raf.readFully(typeBytes);
                String type = new String(typeBytes);

                long headerLen = 8;
                if (size == 1) { // 64-bit size
                    if (fileSize - offset < 16) {
                        break;
                    }
                    size = raf.readLong();
                    headerLen = 16;
                } else if (size == 0) { // extends to EOF
                    size = fileSize - offset;
                }

                if (size < headerLen || offset + size > fileSize) {
                    break; // Invalid box size or malformed file
                }

                boxes.add(new Mp4Box(type, offset, size));

                if ("moov".equals(type)) {
                    moovOffset = offset;
                    moovLength = size;
                    hasMoov = true;
                } else if ("mdat".equals(type) && !hasMdat) {
                    mdatOffset = offset;
                    hasMdat = true;
                }

                offset += size;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse MP4: " + filepath, e);
            return true;
        }

        if (!hasMoov || !hasMdat) {
            Log.e(TAG, "Missing moov or mdat box. File cannot be optimized.");
            return true;
        }

        if (moovOffset < mdatOffset) {
            Log.i(TAG, "moov is already before mdat. No optimization needed.");
            return false; // Success, already optimized
        }

        Log.i(TAG, "moov is after mdat. Starting FastStart optimization...");

        File tmpFile = new File(filepath + ".tmp");

        try (RandomAccessFile inRaf = new RandomAccessFile(file, "r");
             FileOutputStream outStream = new FileOutputStream(tmpFile);
             FileInputStream inStream = new FileInputStream(file);
             FileChannel inChannel = inStream.getChannel();
             FileChannel outChannel = outStream.getChannel()) {

            // 1. Read moov box into memory
            byte[] moovData = new byte[(int) moovLength];
            inRaf.seek(moovOffset);
            inRaf.readFully(moovData);

            // 2. Patch moov box (update stco/co64 offsets)
            ByteBuffer moovBuffer = ByteBuffer.wrap(moovData);
            moovBuffer.order(ByteOrder.BIG_ENDIAN);
            patchMoov(moovBuffer, moovLength);

            // 3. Write boxes before mdat (except moov)
            for (Mp4Box box : boxes) {
                if (box.offset < mdatOffset && !"moov".equals(box.type)) {
                    inChannel.transferTo(box.offset, box.length, outChannel);
                }
            }

            // 4. Write patched moov
            outChannel.write(moovBuffer);

            // 5. Write mdat and remaining boxes (except moov)
            for (Mp4Box box : boxes) {
                if (box.offset >= mdatOffset && !"moov".equals(box.type)) {
                    inChannel.transferTo(box.offset, box.length, outChannel);
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Failed during FastStart optimization process.", e);
            tmpFile.delete();
            return true;
        }

        // 6. Replace original file
        if (!file.delete()) {
            Log.w(TAG, "Failed to delete original file. Temp file remains at: " + tmpFile.getAbsolutePath());
            return true;
        }
        if (!tmpFile.renameTo(file)) {
            Log.w(TAG, "Failed to rename temp file to original. Temp file remains at: " + tmpFile.getAbsolutePath());
            return true;
        }

        Log.i(TAG, "FastStart optimization successful! File is ready: " + filepath);
        return false; // Success
    }

    private static void patchMoov(ByteBuffer moov, long moovSizeDelta) {
        int pos = 8; // Skip moov header (size 4 + type 4)
        int limit = moov.capacity();

        while (pos + 8 <= limit) {
            long size = moov.getInt(pos) & 0xFFFFFFFFL;
            byte[] typeBytes = new byte[4];
            moov.position(pos + 4);
            moov.get(typeBytes);
            String type = new String(typeBytes);

            int headerLen = 8;
            if (size == 1) { // 64-bit size
                if (pos + 16 > limit) break;
                size = moov.getLong(pos + 8);
                headerLen = 16;
            }

            if (size < headerLen || pos + size > limit) {
                break; // Prevent infinite loop or parsing errors
            }

            if ("trak".equals(type) || "mdia".equals(type) || "minf".equals(type) || "stbl".equals(type)) {
                // Enter container box
                pos += headerLen;
            } else if ("stco".equals(type)) {
                // stco is a FullBox: header + version(1) + flags(3) + count(4)
                int countOffset = pos + headerLen + 4;
                if (countOffset + 4 <= limit) {
                    long count = moov.getInt(countOffset) & 0xFFFFFFFFL;
                    int entryPos = countOffset + 4;
                    for (int i = 0; i < count; i++) {
                        if (entryPos + 4 > pos + size) break;
                        long offset = moov.getInt(entryPos) & 0xFFFFFFFFL;
                        offset += moovSizeDelta;
                        moov.putInt(entryPos, (int) offset);
                        entryPos += 4;
                    }
                }
                pos += size;
            } else if ("co64".equals(type)) {
                // co64 is a FullBox: header + version(1) + flags(3) + count(4)
                int countOffset = pos + headerLen + 4;
                if (countOffset + 4 <= limit) {
                    long count = moov.getInt(countOffset) & 0xFFFFFFFFL;
                    int entryPos = countOffset + 4;
                    for (int i = 0; i < count; i++) {
                        if (entryPos + 8 > pos + size) break;
                        long offset = moov.getLong(entryPos);
                        offset += moovSizeDelta;
                        moov.putLong(entryPos, offset);
                        entryPos += 8;
                    }
                }
                pos += size;
            } else {
                // Skip unrelated box
                pos += size;
            }
        }
        
        // Reset position for writing
        moov.position(0);
    }
}
