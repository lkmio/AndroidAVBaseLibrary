package com.github.lkmio.androidavbaselibrary.fitler;

import android.opengl.GLES20;

import com.github.lkmio.androidavbaselibrary.utils.GlUtil;

import java.nio.FloatBuffer;

public class Texture2DDrawer {
    private static final String VERTEX_SHADER =
            "attribute vec4 aPosition;\n" +
                    "attribute vec2 aTexCoord;\n" +
                    "varying vec2 vTexCoord;\n" +
                    "void main() {\n" +
                    "  gl_Position = aPosition;\n" +
                    "  vTexCoord = aTexCoord;\n" +
                    "}";

    private static final String FRAGMENT_SHADER =
            "precision mediump float;\n" +
                    "varying vec2 vTexCoord;\n" +
                    "uniform sampler2D uTexture;\n" +
                    "void main() {\n" +
                    "  gl_FragColor = texture2D(uTexture, vTexCoord);\n" +
                    "}";

    private static final float[] VERTICES = {
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f
    };

    private static final float[] TEX_COORDS = {
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f
    };

    private int mProgram = 0;
    private int maPosition = 0;
    private int maTexCoord = 0;
    private int muTexture = 0;
    private FloatBuffer mVertexBuffer;
    private FloatBuffer mTexCoordBuffer;

    public void init() {
        mVertexBuffer = GlUtil.createFloatBuffer(VERTICES);
        mTexCoordBuffer = GlUtil.createFloatBuffer(TEX_COORDS);
        int vertexShader = createShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
        int fragmentShader = createShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
        mProgram = GLES20.glCreateProgram();
        GLES20.glAttachShader(mProgram, vertexShader);
        GLES20.glAttachShader(mProgram, fragmentShader);
        GLES20.glLinkProgram(mProgram);
        GLES20.glDeleteShader(vertexShader);
        GLES20.glDeleteShader(fragmentShader);
        maPosition = GLES20.glGetAttribLocation(mProgram, "aPosition");
        maTexCoord = GLES20.glGetAttribLocation(mProgram, "aTexCoord");
        muTexture = GLES20.glGetUniformLocation(mProgram, "uTexture");
    }

    public void draw(int textureId) {
        GLES20.glUseProgram(mProgram);
        mVertexBuffer.position(0);
        GLES20.glEnableVertexAttribArray(maPosition);
        GLES20.glVertexAttribPointer(maPosition, 2, GLES20.GL_FLOAT, false, 0, mVertexBuffer);
        mTexCoordBuffer.position(0);
        GLES20.glEnableVertexAttribArray(maTexCoord);
        GLES20.glVertexAttribPointer(maTexCoord, 2, GLES20.GL_FLOAT, false, 0, mTexCoordBuffer);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
        GLES20.glUniform1i(muTexture, 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        GLES20.glDisableVertexAttribArray(maPosition);
        GLES20.glDisableVertexAttribArray(maTexCoord);
        GLES20.glUseProgram(0);
    }

    public void release() {
        if (mProgram != 0) {
            GLES20.glDeleteProgram(mProgram);
            mProgram = 0;
        }
    }

    private int createShader(int shaderType, String source) {
        int shader = GLES20.glCreateShader(shaderType);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        return shader;
    }
}
