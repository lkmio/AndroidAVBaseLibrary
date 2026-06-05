package com.github.lkmio.androidavbaselibrary.filter;

import android.graphics.Bitmap;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.opengl.Matrix;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * 核心渲染器：一次性完成 OES底图处理（旋转/镜像） + 静态水印 + 动态局部刷新水印
 * 附带：智能背景自适应变色 (Dynamic Contrast)
 */
public class CameraEffectFilter {
    private static final String TAG = "CameraEffectFilter";

    // ================= 着色器代码 =================
    private static final String VERTEX_SHADER =
            "attribute vec4 aPosition;\n" +
            "attribute vec4 aTexCoord;\n" +
            "uniform mat4 uTransformMatrix;\n" +
            "varying vec2 vTexCoord;\n" +
            "varying vec2 vStaticCoord;\n" +
            "varying vec2 vDynamicCoord;\n" +
            "void main() {\n" +
            "    gl_Position = uTransformMatrix * aPosition;\n" +
            "    vTexCoord = aTexCoord.xy;\n" +
            "    \n" +
            "    vec2 screenUV = vec2(gl_Position.x + 1.0, 1.0 - gl_Position.y) / 2.0;\n" +
            "    vStaticCoord = screenUV;\n" +
            "    vDynamicCoord = screenUV;\n" +
            "}";

    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 vTexCoord;\n" +
            "varying vec2 vStaticCoord;\n" +
            "varying vec2 vDynamicCoord;\n" +
            "uniform samplerExternalOES uTexture;\n" +
            "uniform sampler2D uStaticWatermark;\n" +
            "uniform sampler2D uDynamicWatermark;\n" +
            "uniform float uEnableStatic;\n" +
            "uniform float uEnableDynamic;\n" +
            "void main() {\n" +
            "    // 1. 获取 OES 视频底图颜色\n" +
            "    vec4 color = texture2D(uTexture, vTexCoord);\n" +
            "    \n" +
            "    // 2. 叠加静态水印\n" +
            "    if(uEnableStatic > 0.5 && vStaticCoord.x >= 0.0 && vStaticCoord.x <= 1.0 && vStaticCoord.y >= 0.0 && vStaticCoord.y <= 1.0) {\n" +
            "        vec4 staticColor = texture2D(uStaticWatermark, vStaticCoord);\n" +
            "        color = mix(color, staticColor, staticColor.a);\n" +
            "    }\n" +
            "    \n" +
            "    // 3. 叠加动态水印\n" +
            "    if(uEnableDynamic > 0.5 && vDynamicCoord.x >= 0.0 && vDynamicCoord.x <= 1.0 && vDynamicCoord.y >= 0.0 && vDynamicCoord.y <= 1.0) {\n" +
            "        vec4 dynamicColor = texture2D(uDynamicWatermark, vDynamicCoord);\n" +
            "        color = mix(color, dynamicColor, dynamicColor.a);\n" +
            "    }\n" +
            "    \n" +
            "    gl_FragColor = color;\n" +
            "}";

    // ================= 顶点与坐标数据 =================
    private static final float[] VERTEX_DATA = {
            -1f, -1f,   1f, -1f,
            -1f,  1f,   1f,  1f
    };
    private static final float[] TEX_COORD_DATA = {
             0f,  0f,   1f,  0f,
             0f,  1f,   1f,  1f
    };

    private FloatBuffer mVertexBuffer;
    private FloatBuffer mTexCoordBuffer;

    private int mProgram;
    private int aPositionLocation, aTexCoordLocation;
    private int uTransformMatrixLocation;
    private int uTextureLocation, uStaticLocation, uDynamicLocation;
    private int uEnableStaticLocation, uEnableDynamicLocation;

    private final float[] mTransformMatrix = new float[]{
            1, 0, 0, 0,  0, 1, 0, 0,  0, 0, 1, 0,  0, 0, 0, 1
    };

    private int mRotation = 0;
    private boolean mMirrorX = false;
    private boolean mMirrorY = false;
    private int mDummyWatermarkTextureId = -1;
    private boolean mIsInitialized = false;
    
    private int mStaticTextureId = -1;
    private Bitmap mStaticBitmap;
    private int mDynamicTextureId = -1;
    private Bitmap mDynamicBitmap;

    public CameraEffectFilter() {
        mVertexBuffer = ByteBuffer.allocateDirect(VERTEX_DATA.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(VERTEX_DATA);
        mVertexBuffer.position(0);
        mTexCoordBuffer = ByteBuffer.allocateDirect(TEX_COORD_DATA.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(TEX_COORD_DATA);
        mTexCoordBuffer.position(0);
    }

    public void init() {
        mProgram = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        if (mProgram == 0) throw new RuntimeException("Failed to create program");

        aPositionLocation = GLES20.glGetAttribLocation(mProgram, "aPosition");
        aTexCoordLocation = GLES20.glGetAttribLocation(mProgram, "aTexCoord");
        uTransformMatrixLocation = GLES20.glGetUniformLocation(mProgram, "uTransformMatrix");
        uTextureLocation = GLES20.glGetUniformLocation(mProgram, "uTexture");
        uStaticLocation = GLES20.glGetUniformLocation(mProgram, "uStaticWatermark");
        uDynamicLocation = GLES20.glGetUniformLocation(mProgram, "uDynamicWatermark");
        uEnableStaticLocation = GLES20.glGetUniformLocation(mProgram, "uEnableStatic");
        uEnableDynamicLocation = GLES20.glGetUniformLocation(mProgram, "uEnableDynamic");

        initDummyWatermarkTexture();
        mIsInitialized = true;
    }

    public void setRotation(int rotation) {
        mRotation = rotation;
        updateTransformMatrix();
    }

    public void setMirror(boolean mirrorX, boolean mirrorY) {
        mMirrorX = mirrorX;
        mMirrorY = mirrorY;
        updateTransformMatrix();
    }

    private void updateTransformMatrix() {
        Matrix.setIdentityM(mTransformMatrix, 0);
        float sx = mMirrorX ? -1f : 1f;
        float sy = mMirrorY ? -1f : 1f;
        Matrix.scaleM(mTransformMatrix, 0, sx, sy, 1f);
        Matrix.rotateM(mTransformMatrix, 0, mRotation, 0f, 0f, 1f);
    }

    public void setStaticWatermark(Bitmap watermarkBmp) {
        if (watermarkBmp == null) {
            mStaticBitmap = null;
            clearStaticWatermark();
            return;
        }
        mStaticBitmap = watermarkBmp;
        mStaticTextureId = uploadWatermarkTexture(mStaticTextureId, mStaticBitmap);
    }

    public void clearStaticWatermark() {
        if (mStaticTextureId != -1) {
            GLES20.glDeleteTextures(1, new int[]{mStaticTextureId}, 0);
            mStaticTextureId = -1;
        }
    }

    public void initDynamicWatermark(Bitmap watermarkBmp) {
        if (watermarkBmp == null) {
            mDynamicBitmap = null;
            clearDynamicWatermark();
            return;
        }
        mDynamicBitmap = watermarkBmp;
        mDynamicTextureId = uploadWatermarkTexture(mDynamicTextureId, mDynamicBitmap);
    }

    public void replaceDynamicWatermark(Bitmap newBitmap) {
        if (mDynamicTextureId != -1 && newBitmap != null && !newBitmap.isRecycled()) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mDynamicTextureId);
            GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, newBitmap);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        }
    }

    public void clearDynamicWatermark() {
        if (mDynamicTextureId != -1) {
            GLES20.glDeleteTextures(1, new int[]{mDynamicTextureId}, 0);
            mDynamicTextureId = -1;
        }
    }

    public void draw(int oesTextureId) {
        if (!mIsInitialized) return;

        GLES20.glUseProgram(mProgram);

        mVertexBuffer.position(0);
        GLES20.glVertexAttribPointer(aPositionLocation, 2, GLES20.GL_FLOAT, false, 0, mVertexBuffer);
        GLES20.glEnableVertexAttribArray(aPositionLocation);

        mTexCoordBuffer.position(0);
        GLES20.glVertexAttribPointer(aTexCoordLocation, 2, GLES20.GL_FLOAT, false, 0, mTexCoordBuffer);
        GLES20.glEnableVertexAttribArray(aTexCoordLocation);

        // 传形变矩阵
        GLES20.glUniformMatrix4fv(uTransformMatrixLocation, 1, false, mTransformMatrix, 0);

        // 绑定 OES
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId);
        GLES20.glUniform1i(uTextureLocation, 0);

        // 绑定 静态 画板
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        if (mStaticTextureId != -1) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mStaticTextureId);
            GLES20.glUniform1f(uEnableStaticLocation, 1.0f);
        } else {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mDummyWatermarkTextureId);
            GLES20.glUniform1f(uEnableStaticLocation, 0.0f);
        }
        GLES20.glUniform1i(uStaticLocation, 1);

        // 绑定 动态 画板
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
        if (mDynamicTextureId != -1) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mDynamicTextureId);
            GLES20.glUniform1f(uEnableDynamicLocation, 1.0f);
        } else {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mDummyWatermarkTextureId);
            GLES20.glUniform1f(uEnableDynamicLocation, 0.0f);
        }
        GLES20.glUniform1i(uDynamicLocation, 2);

        // Alpha 混合
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glDisableVertexAttribArray(aPositionLocation);
        GLES20.glDisableVertexAttribArray(aTexCoordLocation);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
    }

    private int uploadWatermarkTexture(int oldTextureId, Bitmap watermarkBmp) {
        if (watermarkBmp == null) return -1;
        int textureId = oldTextureId;
        if (textureId == -1) {
            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);
            textureId = textures[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
            GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        } else {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
        }
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, watermarkBmp, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        return textureId;
    }

    private void initDummyWatermarkTexture() {
        if (mDummyWatermarkTextureId != -1) return;
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        mDummyWatermarkTextureId = textures[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mDummyWatermarkTextureId);
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        ByteBuffer dummyPixel = ByteBuffer.allocateDirect(4);
        dummyPixel.put((byte) 0).put((byte) 0).put((byte) 0).put((byte) 0).position(0);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 1, 1, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, dummyPixel);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
    }

    private int createProgram(String vertexSource, String fragmentSource) {
        int vShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        if (vShader == 0) {
            android.util.Log.e(TAG, "Failed to compile vertex shader");
            return 0;
        }
        int fShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        if (fShader == 0) {
            android.util.Log.e(TAG, "Failed to compile fragment shader");
            GLES20.glDeleteShader(vShader);
            return 0;
        }
        int program = GLES20.glCreateProgram();
        if (program != 0) {
            GLES20.glAttachShader(program, vShader);
            GLES20.glAttachShader(program, fShader);
            GLES20.glLinkProgram(program);
            int[] linkStatus = new int[1];
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
            if (linkStatus[0] != GLES20.GL_TRUE) {
                android.util.Log.e(TAG, "Failed to link program: " + GLES20.glGetProgramInfoLog(program));
                GLES20.glDeleteProgram(program);
                program = 0;
            }
        }
        GLES20.glDeleteShader(vShader);
        GLES20.glDeleteShader(fShader);
        return program;
    }

    private int loadShader(int shaderType, String source) {
        int shader = GLES20.glCreateShader(shaderType);
        if (shader == 0) return 0;
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            android.util.Log.e(TAG, "Could not compile shader " + shaderType + ": " + GLES20.glGetShaderInfoLog(shader));
            GLES20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }
}
