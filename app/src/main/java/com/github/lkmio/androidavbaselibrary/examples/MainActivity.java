package com.github.lkmio.androidavbaselibrary.examples;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraMetadata;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.github.lkmio.androidavbaselibrary.AVCodec;
import com.github.lkmio.androidavbaselibrary.AsyncStreamPacketDispatcher;
import com.github.lkmio.androidavbaselibrary.DynamicOSD;
import com.github.lkmio.androidavbaselibrary.LiveSource;
import com.github.lkmio.androidavbaselibrary.Packet;
import com.github.lkmio.androidavbaselibrary.R;
import com.github.lkmio.androidavbaselibrary.RecordMP4Sink;
import com.github.lkmio.androidavbaselibrary.camera.Camera2Session;
import com.github.lkmio.androidavbaselibrary.widget.EglPreviewView;
import com.github.lkmio.androidavbaselibrary.utils.CameraUtils;
import com.hjq.permissions.XXPermissions;
import com.hjq.permissions.permission.PermissionLists;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";

    private static final java.util.Map<String, List<CameraUtils.CameraInfo>> sCameraCompatMap = new java.util.HashMap<>();

    static {
        // 示例：为特定的终端设备（如特定的大屏、定制机）手动兼容旋转和镜像
        // 例如，如果某个型号的前置摄像头硬件实际是后置，或者被物理旋转了180度
        // String model = "Some_Custom_Device_Model";
        // overrides.add(new CameraUtils.CameraInfo("1", CameraMetadata.LENS_FACING_FRONT, 180, false, false));
        // sCameraCompatMap.put(model, overrides);

        List<CameraUtils.CameraInfo> overrides = new java.util.ArrayList<>();
        overrides.add(new CameraUtils.CameraInfo("", CameraMetadata.LENS_FACING_FRONT, 180, false, false));
        //sCameraCompatMap.put("", overrides);
    }

    private EglPreviewView mPreviewView;

    private LiveSource mLiveSource;

    private Button mBtnRecord;
    private Button mBtnPhoto;

    private EditText mEtRtmpUrl;

    private Button mBtnStream;

    private Button mBtnPreviewScale;

    private Button mBtnRotation;

    private Button mBtnMirrorX;

    private Button mBtnMirrorY;

    private Button mBtnCameraFacing;

    private Button mBtnSwitchCamera;

    private RecordMP4Sink mRecordSink;

    private RTMPStreamSink mRtmpStreamSink;

    private List<CameraUtils.CameraInfo> mCameraInfoList;

    private boolean mIsRecording = false;

    private boolean mIsStreaming = false;

    private LinearLayout mLlCtrlBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mLlCtrlBar = findViewById(R.id.ll_ctrl_bar);
        mPreviewView = findViewById(R.id.preview_view);
        mEtRtmpUrl = findViewById(R.id.et_rtmp_url);
        mBtnStream = findViewById(R.id.btn_stream);
        mBtnPreviewScale = findViewById(R.id.btn_preview_scale);
        mBtnRotation = findViewById(R.id.btn_rotation);
        mBtnMirrorX = findViewById(R.id.btn_mirror_x);
        mBtnMirrorY = findViewById(R.id.btn_mirror_y);
        mBtnCameraFacing = findViewById(R.id.btn_camera_facing);
        mBtnSwitchCamera = findViewById(R.id.btn_switch_camera);
        mBtnRecord = findViewById(R.id.btn_record);
        mBtnPhoto = findViewById(R.id.btn_photo);
        mBtnPhoto.setOnClickListener(v -> takePhoto());
        mBtnStream.setOnClickListener(v -> {
            if (mIsStreaming) {
                stopStreaming();
            } else {
                startStreaming();
            }
        });
        mBtnRecord.setOnClickListener(v -> {
            if (mIsRecording) {
                stopRecording();
            } else {
                startRecording();
            }
        });

        mPreviewView.setScaleType(EglPreviewView.ScaleType.FIT_CENTER);
        mBtnPreviewScale.setOnClickListener(v -> {
            if (mPreviewView == null) {
                return;
            }
            if (mBtnPreviewScale.getText().equals("全屏预览")) {
                mPreviewView.setScaleType(EglPreviewView.ScaleType.CENTER_CROP);
                mBtnPreviewScale.setText("完整画面");
            } else {
                mPreviewView.setScaleType(EglPreviewView.ScaleType.FIT_CENTER);
                mBtnPreviewScale.setText("全屏预览");
            }
        });

        mBtnRotation.setOnClickListener(v -> {
            if (mLiveSource == null) {
                return;
            }
            int current = mLiveSource.getRotation();
            int next = ((current / 90) + 1) % 4 * 90;
            mLiveSource.setRotation(next);
            updateTransformButtons();
        });

        mBtnMirrorX.setOnClickListener(v -> {
            if (mLiveSource == null) {
                return;
            }
            mLiveSource.setMirrorX(!mLiveSource.isMirrorX());
            updateTransformButtons();
        });

        mBtnMirrorY.setOnClickListener(v -> {
            if (mLiveSource == null) {
                return;
            }
            mLiveSource.setMirrorY(!mLiveSource.isMirrorY());
            updateTransformButtons();
        });

        mBtnSwitchCamera.setOnClickListener(v -> {
            if (mLiveSource == null || mCameraInfoList == null || mCameraInfoList.isEmpty()) {
                return;
            }
            String currentId = mLiveSource.getCameraId();
            int index = findCameraInfoIndex(currentId);
            if (index != -1) {
                String nextId = mCameraInfoList.get((index + 1) % mCameraInfoList.size()).cameraId;
                boolean ok = mLiveSource.switchCamera(nextId);
                if (!ok) {
                    Toast.makeText(MainActivity.this, "摄像头正在打开，切换失败", Toast.LENGTH_SHORT).show();
                }
            }
        });

        XXPermissions.with(this)
                .permission(PermissionLists.getCameraPermission())
                .permission(PermissionLists.getRecordAudioPermission())
                .request((grantedList, deniedList) -> {
                    if (!deniedList.isEmpty()) {
                        return;
                    }
                    startPreview();
                });

        android.widget.Spinner spVideoEncoder = findViewById(R.id.sp_video_encoder);
        android.widget.Spinner spVideoDecoder = findViewById(R.id.sp_video_decoder);
        android.widget.Spinner spAudioEncoder = findViewById(R.id.sp_audio_encoder);
        android.widget.Spinner spAudioDecoder = findViewById(R.id.sp_audio_decoder);

        setupCodecSpinner(spVideoEncoder, com.github.lkmio.androidavbaselibrary.utils.CodecUtils.getSupportedVideoEncoders());
        setupCodecSpinner(spVideoDecoder, com.github.lkmio.androidavbaselibrary.utils.CodecUtils.getSupportedVideoDecoders());
        setupCodecSpinner(spAudioEncoder, com.github.lkmio.androidavbaselibrary.utils.CodecUtils.getSupportedAudioEncoders());
        setupCodecSpinner(spAudioDecoder, com.github.lkmio.androidavbaselibrary.utils.CodecUtils.getSupportedAudioDecoders());
    }

    private void setupCodecSpinner(android.widget.Spinner spinner, java.util.List<com.github.lkmio.androidavbaselibrary.utils.CodecUtils.CodecInfo> infos) {
        java.util.List<String> names = new java.util.ArrayList<>();
        for (com.github.lkmio.androidavbaselibrary.utils.CodecUtils.CodecInfo info : infos) {
            String hwTag = info.isHardware ? "[硬]" : "[软]";
            names.add(hwTag + " " + info.name);
        }
        android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(this, R.layout.spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuItem item = menu.add(Menu.NONE, 1, Menu.NONE, "控制栏");
        item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        Switch sw = new Switch(this);
        sw.setTextColor(Color.WHITE);
        sw.setText("隐藏控制栏");
        sw.setChecked(true);
        sw.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sw.setText(isChecked ? "隐藏控制栏" : "显示控制栏");
            if (mLlCtrlBar != null) {
                mLlCtrlBar.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            }
        });
        item.setActionView(sw);
        return super.onCreateOptionsMenu(menu);
    }

    private void startPreview() {
        mCameraInfoList = CameraUtils.getCameraInfoList(this);
        if (mCameraInfoList.isEmpty()) {
            return;
        }

        CameraUtils.CameraInfo selected = mCameraInfoList.get(0);
        for (CameraUtils.CameraInfo info : mCameraInfoList) {
            if (info.facing != null && info.facing == CameraCharacteristics.LENS_FACING_BACK) {
                selected = info;
                break;
            }
        }

        boolean front = selected.facing != null && selected.facing == CameraCharacteristics.LENS_FACING_FRONT;
        int rotation = CameraUtils.getDisplayRotation(this, selected.sensorOrientation, front);

        LiveSource.Builder builder = LiveSource.builder(this)
                .setCameraId(selected.cameraId)
                .setVideoWidth(1280)
                .setVideoHeight(720)
                .setVideoCodec(AVCodec.H265)
                .setFPS(25);

        // 如果当前设备型号匹配到手动兼容配置，则设置
        String model = Build.MODEL;
        List<CameraUtils.CameraInfo> compatInfos = sCameraCompatMap.get(Build.MODEL);
        if (compatInfos != null && !compatInfos.isEmpty()) {
            builder.setCameraCompatOverrides(compatInfos.toArray(new CameraUtils.CameraInfo[0]));
        }

        mLiveSource = builder.build();

        mLiveSource.addStaticWatermark(null, CameraCharacteristics.LENS_FACING_BACK, "后摄", 28, Color.WHITE, Gravity.BOTTOM | Gravity.START, new Rect(20, 0, 0, 80));
        mLiveSource.addStaticWatermark(null, CameraCharacteristics.LENS_FACING_FRONT, "前摄", 28, Color.WHITE, Gravity.BOTTOM | Gravity.START, new Rect(20, 0, 0, 80));
        mLiveSource.addStaticWatermark("亮出你的剑 一板一眼", 28, Color.WHITE, Gravity.BOTTOM | Gravity.START, new Rect(20, 0, 0, 40));
        mLiveSource.addStaticWatermark("回归基本功 升级无尽", 28, Color.WHITE, Gravity.BOTTOM | Gravity.START, new Rect(20, 0, 0, 0));
        mLiveSource.addDynamicTextWatermark(new TimeOSD(), 28, Color.WHITE, Gravity.TOP | Gravity.START, new Rect(20, 0, 0, 0));
        mLiveSource.addVideoFrameSink(mPreviewView);
        // 图片水印
//        Drawable drawable = androidx.core.content.ContextCompat.getDrawable(this, R.mipmap.ic_launcher_round);
//        Bitmap logoBitmap = getBitmapFromDrawable(drawable);
//        mLiveSource.addStaticWatermark(logoBitmap, Gravity.TOP | Gravity.END, new Rect(0, 20, 20, 0));

        mLiveSource.setOnCameraOpenListener(new Camera2Session.OnCameraOpenListener() {
            @Override
            public void onCameraOpened(boolean success) {
                if (success) {
                    runOnUiThread(() -> updateTransformButtons());
                }
            }
        });

        mLiveSource.start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mLiveSource != null) {
            mLiveSource.addVideoFrameSink(mPreviewView);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mLiveSource != null) {
            mLiveSource.removeVideoFrameSink(mPreviewView);
        }
    }

    private void takePhoto() {
        if (mLiveSource == null) return;
        mLiveSource.takePhoto(bitmap -> {
            String dir = getExternalCacheDir().getAbsolutePath();
            String path = dir + "/photo_" + System.currentTimeMillis() + ".jpg";
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(path)) {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 100, out);
                runOnUiThread(() -> {
                    Log.i(TAG, "拍照成功: " + path);
                    //android.widget.Toast.makeText(MainActivity.this, "拍照成功: " + path, android.widget.Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> {
                    android.widget.Toast.makeText(MainActivity.this, "拍照失败: " + e.getMessage(), android.widget.Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void startRecording() {
        if (mLiveSource == null) {
            return;
        }
        String dir = getExternalCacheDir().getAbsolutePath();
        final RecordMP4Sink recordSink = new RecordMP4Sink(mLiveSource.getVideoCodec(), new RecordMP4Sink.OnSegmentHandler() {
            @Override
            public String allocPath() {
                return dir + "/record_" + System.currentTimeMillis() + ".mp4";
            }

            @Override
            public void onSegment(String path) {
                Log.i(TAG, "onSegment: " + path);
                new Thread(() -> {
                    boolean failed = com.github.lkmio.androidavbaselibrary.utils.Mp4FastStart.fastStart(path);
                    if (failed) {
                        Log.e(TAG, "FastStart optimization failed for: " + path);
                    } else {
                        Log.i(TAG, "FastStart optimization succeeded for: " + path);
                    }

                    com.github.lkmio.androidavbaselibrary.Mp4Demuxer demuxer = new com.github.lkmio.androidavbaselibrary.Mp4Demuxer();
                    if (demuxer.setDataSource(path)) {
                        java.util.List<android.media.MediaFormat> tracks = demuxer.getTracks();
                        java.io.FileOutputStream[] outs = new java.io.FileOutputStream[tracks.size()];
                        for (int i = 0; i < tracks.size(); i++) {
                            demuxer.selectTrack(i);
                            android.media.MediaFormat format = tracks.get(i);
                            String mime = format.getString(android.media.MediaFormat.KEY_MIME);
                            String ext = mime != null ? mime.replace("/", "_") : "bin";
                            try {
                                outs[i] = new java.io.FileOutputStream(path + "." + ext);
                                // 写入 SPS/PPS 等配置数据 (如果有)
                                if (format.containsKey("csd-0")) {
                                    java.nio.ByteBuffer csd0 = format.getByteBuffer("csd-0");
                                    if (csd0 != null) {
                                        byte[] csd0Data = new byte[csd0.remaining()];
                                        csd0.duplicate().get(csd0Data);
                                        outs[i].write(csd0Data);
                                    }
                                }
                                if (format.containsKey("csd-1")) {
                                    java.nio.ByteBuffer csd1 = format.getByteBuffer("csd-1");
                                    if (csd1 != null) {
                                        byte[] csd1Data = new byte[csd1.remaining()];
                                        csd1.duplicate().get(csd1Data);
                                        outs[i].write(csd1Data);
                                    }
                                }
                                // AAC 的 ADTS 头通常需要手动加，这里直接写出裸流用于验证提取功能
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }

                        Log.i(TAG, "Start demuxing naked streams...");
                        while (true) {
                            int size = demuxer.readSampleData();
                            if (size < 0) {
                                break;
                            }
                            int trackIndex = demuxer.getSampleTrackIndex();
                            if (trackIndex >= 0 && trackIndex < outs.length && outs[trackIndex] != null) {
                                java.nio.ByteBuffer buffer = demuxer.getSampleDataBuffer();
                                byte[] data = new byte[size];
                                buffer.get(data);
                                try {
                                    outs[trackIndex].write(data);
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                            }
                            if (!demuxer.advance()) {
                                break;
                            }
                        }

                        for (java.io.FileOutputStream out : outs) {
                            if (out != null) {
                                try {
                                    out.close();
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                            }
                        }
                        demuxer.release();
                        Log.i(TAG, "Demuxing completed for: " + path);
                    } else {
                        Log.e(TAG, "Mp4Demuxer failed to set data source for: " + path);
                    }
                }, "FastStartThread").start();
            }
        }, 3600);
        recordSink.setPacketDispatcher(new AsyncStreamPacketDispatcher(
                "RecordMP4Sink-PacketDispatcher",
                new AsyncStreamPacketDispatcher.Callback() {
                    @Override
                    public void onPacket(Packet packet) {
                        recordSink.onPacket(packet);
                    }
                }
        ));
        mRecordSink = recordSink;
        mLiveSource.addStreamSink(mRecordSink);
        mIsRecording = true;
        mBtnRecord.setText("停止录像");
    }

    private void startStreaming() {
        if (mLiveSource == null) {
            return;
        }
        String url = mEtRtmpUrl.getText() != null ? mEtRtmpUrl.getText().toString().trim() : "";
        if (url.isEmpty()) {
            Toast.makeText(this, "请输入 RTMP 地址", Toast.LENGTH_SHORT).show();
            return;
        }

        final RTMPStreamSink sink = new RTMPStreamSink(mLiveSource.getVideoCodec(), url);
        sink.setListener(new RTMPStreamSink.Listener() {
            @Override
            public void onConnectionStarted(String url) {
                Log.i(TAG, "RTMP start: " + url);
            }

            @Override
            public void onConnectionSuccess() {
                runOnUiThread(() -> {
                    if (mRtmpStreamSink != sink) {
                        return;
                    }
                    Log.i(TAG, "RTMP connected");
                    Toast.makeText(MainActivity.this, "推流成功", Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onConnectionFailed(String reason) {
                runOnUiThread(() -> {
                    if (mRtmpStreamSink != sink) {
                        return;
                    }
                    Log.w(TAG, "RTMP failed: " + reason);
                    Toast.makeText(MainActivity.this, "推流失败: " + reason, Toast.LENGTH_SHORT).show();
                    stopStreaming();
                });
            }

            @Override
            public void onDisconnect() {
                runOnUiThread(() -> {
                    if (mRtmpStreamSink != sink) {
                        return;
                    }
                    Log.i(TAG, "RTMP disconnected");
                    stopStreaming();
                });
            }

            @Override
            public void onAuthError() {
                runOnUiThread(() -> {
                    if (mRtmpStreamSink != sink) {
                        return;
                    }
                    Toast.makeText(MainActivity.this, "RTMP 鉴权失败", Toast.LENGTH_SHORT).show();
                    stopStreaming();
                });
            }

            @Override
            public void onAuthSuccess() {
                Log.i(TAG, "RTMP auth success");
            }
        });

        mRtmpStreamSink = sink;
        mIsStreaming = true;
        mBtnStream.setText("结束推流");
        mLiveSource.addStreamSink(sink);
    }

    private void stopRecording() {
        if (mLiveSource == null || mRecordSink == null) {
            return;
        }
        mLiveSource.removeStreamSink(mRecordSink);
        mRecordSink.close();
        mRecordSink = null;
        mIsRecording = false;
        mBtnRecord.setText("开始录像");
    }

    private void updateTransformButtons() {
        if (mLiveSource == null) return;
        int rotation = mLiveSource.getRotation();
        boolean mirrorX = mLiveSource.isMirrorX();
        boolean mirrorY = mLiveSource.isMirrorY();
        if (mBtnRotation != null) {
            mBtnRotation.setText("旋转: " + rotation + "°");
        }
        if (mBtnMirrorX != null) {
            mBtnMirrorX.setText("镜像X: " + (mirrorX ? "开" : "关"));
        }
        if (mBtnMirrorY != null) {
            mBtnMirrorY.setText("镜像Y: " + (mirrorY ? "开" : "关"));
        }
        if (mBtnCameraFacing != null && mCameraInfoList != null) {
            String cameraId = mLiveSource.getCameraId();
            for (CameraUtils.CameraInfo info : mCameraInfoList) {
                if (info.cameraId.equals(cameraId)) {
                    boolean isFront = info.facing == CameraCharacteristics.LENS_FACING_FRONT;
                    mBtnCameraFacing.setText(isFront ? "前摄" : "后摄");
                    break;
                }
            }
        }
    }

    private void stopStreaming() {
        RTMPStreamSink sink = mRtmpStreamSink;
        mRtmpStreamSink = null;
        mIsStreaming = false;
        if (mBtnStream != null) {
            mBtnStream.setText("开始推流");
        }
        if (sink == null) {
            return;
        }
        if (mLiveSource != null) {
            mLiveSource.removeStreamSink(sink);
        }
        sink.close();
    }

    public static class TimeOSD extends DynamicOSD {
        private final SimpleDateFormat mDateFormat = new SimpleDateFormat("yyyy年MM月dd日 HH:mm:ss", Locale.getDefault());
        private long mLastSecond = Long.MIN_VALUE;
        private String mCachedText = "";

        @Override
        public String getText() {
            long currentSecond = System.currentTimeMillis() / 1000L;
            if (currentSecond != mLastSecond) {
                mLastSecond = currentSecond;
                mCachedText = mDateFormat.format(new Date(currentSecond * 1000L));
            }
            return mCachedText;
        }
    }

    @Override
    protected void onDestroy() {
        if (mRecordSink != null) {
            mLiveSource.removeStreamSink(mRecordSink);
            mRecordSink.close();
            mRecordSink = null;
        }
        if (mRtmpStreamSink != null) {
            stopStreaming();
        }
        if (mLiveSource != null) {
            mLiveSource.stop();
            mLiveSource = null;
        }
        if (mPreviewView != null) {
            mPreviewView.release();
        }
        super.onDestroy();
    }

    private Bitmap getBitmapFromDrawable(android.graphics.drawable.Drawable drawable) {
        if (drawable == null) {
            return null;
        }
        if (drawable instanceof android.graphics.drawable.BitmapDrawable) {
            return ((android.graphics.drawable.BitmapDrawable) drawable).getBitmap();
        }
        Bitmap bitmap = Bitmap.createBitmap(
                drawable.getIntrinsicWidth() > 0 ? drawable.getIntrinsicWidth() : 100,
                drawable.getIntrinsicHeight() > 0 ? drawable.getIntrinsicHeight() : 100,
                Bitmap.Config.ARGB_8888
        );
        android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);
        return bitmap;
    }

    private int findCameraInfoIndex(String cameraId) {
        if (mCameraInfoList == null) return -1;
        for (int i = 0; i < mCameraInfoList.size(); i++) {
            if (mCameraInfoList.get(i).cameraId.equals(cameraId)) {
                return i;
            }
        }
        return -1;
    }
}
