package com.github.lkmio.androidavbaselibrary.fitler;
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
                    "uniform mat4 uMVPMatrix;\n" + // 接收外部传入的矩阵（用于 CenterCrop 裁剪）
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

    // (createProgram 和 loadShader 逻辑与之前相同，此处省略以保持代码紧凑...)
    private int createProgram(String vertexSource, String fragmentSource) {
        int vShader = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER);
        GLES20.glShaderSource(vShader, vertexSource);
        GLES20.glCompileShader(vShader);

        int fShader = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER);
        GLES20.glShaderSource(fShader, fragmentSource);
        GLES20.glCompileShader(fShader);

        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vShader);
        GLES20.glAttachShader(program, fShader);
        GLES20.glLinkProgram(program);
        return program;
    }
}
