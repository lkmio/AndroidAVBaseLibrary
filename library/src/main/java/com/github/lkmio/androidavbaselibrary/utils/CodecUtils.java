package com.github.lkmio.androidavbaselibrary.utils;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.util.Log;
import android.os.Build;

import java.util.ArrayList;
import java.util.List;

public class CodecUtils {

    /**
     * Data class to hold information about a media codec.
     */
    public static class CodecInfo {
        public final String name;
        public final String type;
        public final boolean isHardware;
        public final boolean isEncoder;
        public final List<String> capabilities = new ArrayList<>();

        public CodecInfo(String name, String type, boolean isHardware, boolean isEncoder) {
            this.name = name;
            this.type = type;
            this.isHardware = isHardware;
            this.isEncoder = isEncoder;
        }

        @Override
        public String toString() {
            return "CodecInfo{" +
                    "name='" + name + '\'' +
                    ", type='" + type + '\'' +
                    ", isHardware=" + isHardware +
                    ", isEncoder=" + isEncoder +
                    ", capabilities=" + capabilities +
                    '}';
        }
    }

    /**
     * Gets a list of all supported video encoders.
     * @return A list of {@link CodecInfo} for video encoders.
     */
    public static List<CodecInfo> getSupportedVideoEncoders() {
        return getCodecList("video/", true);
    }

    /**
     * Gets a list of all supported video decoders.
     * @return A list of {@link CodecInfo} for video decoders.
     */
    public static List<CodecInfo> getSupportedVideoDecoders() {
        return getCodecList("video/", false);
    }

    /**
     * Gets a list of all supported audio encoders.
     * @return A list of {@link CodecInfo} for audio encoders.
     */
    public static List<CodecInfo> getSupportedAudioEncoders() {
        return getCodecList("audio/", true);
    }

    /**
     * Gets a list of all supported audio decoders.
     * @return A list of {@link CodecInfo} for audio decoders.
     */
    public static List<CodecInfo> getSupportedAudioDecoders() {
        return getCodecList("audio/", false);
    }


    private static List<CodecInfo> getCodecList(String mimeTypePrefix, boolean isEncoder) {
        List<CodecInfo> codecList = new ArrayList<>();
        MediaCodecList mediaCodecList = new MediaCodecList(MediaCodecList.ALL_CODECS);
        android.media.MediaCodecInfo[] codecInfos = mediaCodecList.getCodecInfos();

        for (android.media.MediaCodecInfo codecInfo : codecInfos) {
            if (codecInfo.isEncoder() != isEncoder) {
                continue;
            }

            String[] types = codecInfo.getSupportedTypes();
            for (String type : types) {
                if (type.startsWith(mimeTypePrefix)) {
                    boolean isHardware = isHardwareCodec(codecInfo);
                    CodecInfo info = new CodecInfo(codecInfo.getName(), type, isHardware, isEncoder);

                    try {
                        MediaCodecInfo.CodecCapabilities capabilities = codecInfo.getCapabilitiesForType(type);
                        if (mimeTypePrefix.equals("video/")) {
                            // Add color formats for video codecs
                            for (int colorFormat : capabilities.colorFormats) {
                                info.capabilities.add("ColorFormat: " + colorFormat);
                            }
                        } else if (mimeTypePrefix.equals("audio/")) {
                            // Add profile levels for audio codecs
                            for (MediaCodecInfo.CodecProfileLevel profileLevel : capabilities.profileLevels) {
                                info.capabilities.add("Profile: " + profileLevel.profile + ", Level: " + profileLevel.level);
                            }
                        }
                    } catch (IllegalArgumentException e) {
                        // This can happen if the codec is not fully supported.
                        Log.w("CodecUtils", "getCapabilitiesForType failed for " + codecInfo.getName(), e);
                    }
                    codecList.add(info);
                }
            }
        }
        return codecList;
    }

    /**
     * Checks if a codec is hardware-accelerated.
     * For API 29+, this is a reliable check. For older APIs, it uses a heuristic
     * based on the codec name.
     *
     * @param codecInfo The codec info.
     * @return True if it's determined to be a hardware codec.
     */
    private static boolean isHardwareCodec(android.media.MediaCodecInfo codecInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return codecInfo.isHardwareAccelerated();
        }
        // Heuristic for older APIs: software codecs usually have "google" or "sw" in their name.
        String name = codecInfo.getName().toLowerCase();
        return !name.startsWith("omx.google.") && !name.contains(".sw.");
    }

    /**
     * Resolves the best matching video encoder based on the given codecName or fallbackCodec.
     * Priority:
     * 1. Exact match of codecName (String)
     * 2. Hardware encoder matching the fallback AVCodec enum
     * 3. Software encoder matching the fallback AVCodec enum
     */
    public static CodecInfo resolveVideoEncoder(String codecName, com.github.lkmio.androidavbaselibrary.AVCodec fallbackCodec) {
        List<CodecInfo> supportedEncoders = getSupportedVideoEncoders();

        // 1. Try to find the exact matching codec name if provided
        if (codecName != null && !codecName.isEmpty()) {
            for (CodecInfo info : supportedEncoders) {
                if (info.name.equals(codecName)) {
                    return info;
                }
            }
        }

        // 2. Fallback to enum AVCodec to determine mime type
        String targetMimeType;
        if (fallbackCodec == com.github.lkmio.androidavbaselibrary.AVCodec.H265) {
            targetMimeType = android.media.MediaFormat.MIMETYPE_VIDEO_HEVC;
        } else {
            targetMimeType = android.media.MediaFormat.MIMETYPE_VIDEO_AVC; // Default to H264
        }

        CodecInfo swFallback = null;

        // 3. Find hardware encoder first
        for (CodecInfo info : supportedEncoders) {
            if (info.type.equals(targetMimeType)) {
                if (info.isHardware) {
                    return info; // Found hardware encoder, return immediately
                } else if (swFallback == null) {
                    swFallback = info; // Save the first software encoder as fallback
                }
            }
        }

        return swFallback;
    }
}
