package com.theeasiestway.pcmau;

public class G711 {

    static {
        System.loadLibrary("easypcmau");
    }

    //
    // Encoding
    //

    public static native byte[] encodeA(byte[] bytes);

    public static native void encodeA(byte[] pcm, int offset, int length, byte[] g711a, int g711a_offset);

    public static native short[] encodeA(short[] shorts);

    public static native byte[] encodeU(byte[] bytes);

    public static native void encodeU(byte[] pcm, int offset, int length, byte[] g711u, int g711u_offset);

    public static native void encodeADirect(java.nio.ByteBuffer pcm, java.nio.ByteBuffer g711a);

    public static native void encodeUDirect(java.nio.ByteBuffer pcm, java.nio.ByteBuffer g711u);

    public static native short[] encodeU(short[] shorts);

    //
    // Decoding
    //

    public static native byte[] decodeA(byte[] bytes);

    public static native void decodeA(byte[] g711a, int offset, int length, byte[] pcm, int pcm_offset);

    public static native short[] decodeA(short[] shorts);

    public static native byte[] decodeU(byte[] bytes);

    public static native void decodeU(byte[] g711u, int offset, int length, byte[] pcm, int pcm_offset);

    public static native void decodeADirect(java.nio.ByteBuffer g711a, java.nio.ByteBuffer pcm);

    public static native void decodeUDirect(java.nio.ByteBuffer g711u, java.nio.ByteBuffer pcm);

    public static native short[] decodeU(short[] shorts);

    //
    // Converting
    //

    public static native byte[] convertAtoU(byte[] bytes);

    public static native short[] convertAtoU(short[] shorts);

    public static native byte[] convertUtoA(byte[] bytes);

    public static native short[] convertUtoA(short[] shorts);

    public static native short[] convert(byte[] bytes);

    public static native byte[] convert(short[] shorts);
}