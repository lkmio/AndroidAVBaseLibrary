package com.github.lkmio.androidavbaselibrary.filter;

import android.opengl.GLES20;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * 基础 2D 纹理渲染器
 * 专门用于将 FBO 处理好的 GL_TEXTURE_2D 纹理渲染上屏
 */
public class Simple2DDrawer {
    private static final String TAG = "Simple2DDrawer";

    private static final String VERTEX_SHADER =
            "attribute vec4 aPosition;\n" +
                    "attribute vec2 aTexCoord;\n" +
                    "uniform mat4 uMVPMatrix;\n" +
                    "varying vec2 vTexCoord;\n" +
                    "void main() {\n" +
                    "    gl_Position = uMVPMatrix * aPosition;\n" +
                    "    vTexCoord = aTexCoord;\n" +
                    "}";

    private static final String FRAGMENT_SHADER =
            "precision mediump float;\n" +
                    "varying vec2 vTexCoord;\n" +
                    "uniform sampler2D uTexture;\n" +
                    "void main() {\n" +
                    "    gl_FragColor = texture2D(uTexture, vTexCoord);\n" +
                    "}";

    // 顶点坐标 (全屏)
    private static final float[] VERTEX_DATA = {
            -1f, -1f,
            1f, -1f,
            -1f,  1f,
            1f,  1f
    };

    // 纹理坐标 (标准 2D 纹理坐标，无需翻转，因为 FBO 里已经处理过了)
    private static final float[] TEX_COORD_DATA = {
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f
    };

    private FloatBuffer mVertexBuffer;
    private FloatBuffer mTexCoordBuffer;

    private int mProgram;
    private int aPositionLocation;
    private int aTexCoordLocation;
    private int uMVPMatrixLocation;
    private int uTextureLocation;

    public void init() {
        mVertexBuffer = ByteBuffer.allocateDirect(VERTEX_DATA.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer().put(VERTEX_DATA);
        mVertexBuffer.position(0);

        mTexCoordBuffer = ByteBuffer.allocateDirect(TEX_COORD_DATA.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer().put(TEX_COORD_DATA);
        mTexCoordBuffer.position(0);

        mProgram = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        aPositionLocation = GLES20.glGetAttribLocation(mProgram, "aPosition");
        aTexCoordLocation = GLES20.glGetAttribLocation(mProgram, "aTexCoord");
        uMVPMatrixLocation = GLES20.glGetUniformLocation(mProgram, "uMVPMatrix");
        uTextureLocation = GLES20.glGetUniformLocation(mProgram, "uTexture");
    }

    /**
     * 绘制 2D 纹理
     * @param textureId 2D 纹理 ID
     * @param mvpMatrix 投影矩阵 (用于修复画面拉伸比例)
     */
    public void draw(int textureId, float[] mvpMatrix) {
        if (mProgram == 0) return;

        GLES20.glUseProgram(mProgram);

        mVertexBuffer.position(0);
        GLES20.glVertexAttribPointer(aPositionLocation, 2, GLES20.GL_FLOAT, false, 0, mVertexBuffer);
        GLES20.glEnableVertexAttribArray(aPositionLocation);

        mTexCoordBuffer.position(0);
        GLES20.glVertexAttribPointer(aTexCoordLocation, 2, GLES20.GL_FLOAT, false, 0, mTexCoordBuffer);
        GLES20.glEnableVertexAttribArray(aTexCoordLocation);

        // 传矩阵
        GLES20.glUniformMatrix4fv(uMVPMatrixLocation, 1, false, mvpMatrix, 0);

        // 绑定 2D 纹理
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
        GLES20.glUniform1i(uTextureLocation, 0);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glDisableVertexAttribArray(aPositionLocation);
        GLES20.glDisableVertexAttribArray(aTexCoordLocation);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
    }

    public void release() {
        if (mProgram != 0) {
            GLES20.glDeleteProgram(mProgram);
            mProgram = 0;
        }
    }

    private int createProgram(String vertexSource, String fragmentSource) {
        int vShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        if (vShader == 0) return 0;
        int fShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        if (fShader == 0) {
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
