package com.example.gesturefeed;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

/** Enrollment screen for the four user-defined gesture or voice commands. */
public final class CustomTrainingActivity extends Activity {
    public static final String EXTRA_MODE = "training_mode";
    private static final int REQUEST_CAMERA = 81;
    private static final int REQUEST_AUDIO = 82;
    private static final int MIN_GESTURE_SAMPLES = 3;
    private static final int MIN_VOICE_SAMPLES = 1;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final EnumMap<ControlDirection, TextView> sampleLabels =
            new EnumMap<>(ControlDirection.class);
    private final int background = Color.rgb(248, 250, 252);
    private final int ink = Color.rgb(30, 41, 59);
    private final int muted = Color.rgb(71, 85, 105);
    private final int blue = Color.rgb(37, 99, 235);
    private final int green = Color.rgb(22, 163, 74);
    private final int orange = Color.rgb(234, 88, 12);

    private GestureMode mode;
    private FeatureTemplateStore store;
    private TextView status;
    private TextView previewState;
    private TextureView texture;
    private Button[] recordButtons;
    private boolean recording;
    private ControlDirection recordingDirection;
    private final List<float[]> gestureFrames = new ArrayList<>();
    private final List<Short> voiceSamples = new ArrayList<>();

    private MediaPipeHandLandmarker analyzer;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private Surface previewSurface;
    private VoiceRecorder voiceRecorder;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(background);
        getWindow().setNavigationBarColor(background);
        getWindow().getDecorView().setSystemUiVisibility(
                android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                        | android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        mode = GestureMode.fromStoredValue(getIntent().getStringExtra(EXTRA_MODE));
        if (mode != GestureMode.CUSTOM_GESTURE && mode != GestureMode.CUSTOM_VOICE) {
            finish();
            return;
        }
        store = new FeatureTemplateStore(this,
                mode == GestureMode.CUSTOM_GESTURE
                        ? "custom_gesture_templates" : "custom_voice_templates");
        buildUi();
        if (mode == GestureMode.CUSTOM_GESTURE) {
            if (hasPermission(Manifest.permission.CAMERA)) initGestureLearning();
            else requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA);
        } else {
            if (hasPermission(Manifest.permission.RECORD_AUDIO)) initVoiceLearning();
            else requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_AUDIO);
        }
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(background);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(24), dp(20), dp(24), dp(28));
        applyInsets(scroll, page);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(mode.getLabel(), 28, ink, true);
        header.addView(title, lp(0, dp(52), 1, 0, 0, 0, 0));
        Button close = textButton("关闭");
        close.setOnClickListener(v -> finish());
        header.addView(close, lp(dp(72), dp(48), 0, 0, 0, 0));
        page.addView(header, lp(-1, dp(52), 0, 0, 0, 10));

        TextView intro = text(mode == GestureMode.CUSTOM_GESTURE
                ? "最低要求：每个方向至少录入 3 次，建议录入 5 次"
                : "最低要求：至少录入一个方向 1 次，建议每个方向录入 5 次", 14, muted, false);
        page.addView(intro, lp(-1, -2, 0, 0, 0, 12));

        if (mode == GestureMode.CUSTOM_GESTURE) {
            page.addView(buildPreview(), lp(-1, dp(220), 0, 0, 0, 12));
        } else {
            LinearLayout voiceCard = surface();
            voiceCard.setGravity(Gravity.CENTER);
            TextView voiceHint = text("麦克风待命\n点击方向按钮后，说出你的自定义口令",
                    18, ink, true);
            voiceHint.setGravity(Gravity.CENTER);
            voiceCard.addView(voiceHint, lp(-1, dp(130), 0, 0, 0, 0));
            page.addView(voiceCard, lp(-1, dp(150), 0, 0, 0, 12));
        }

        status = text("正在准备…", 15, muted, true);
        status.setGravity(Gravity.CENTER);
        page.addView(status, lp(-1, dp(34), 0, 0, 0, 8));

        recordButtons = new Button[ControlDirection.values().length];
        for (ControlDirection direction : ControlDirection.values()) {
            page.addView(buildDirectionRow(direction), lp(-1, dp(64), 0, 0, 0, 8));
        }

        TextView privacy = text("原始画面和录音不会保存，仅在本机提取特征", 13, muted, false);
        privacy.setGravity(Gravity.CENTER);
        page.addView(privacy, lp(-1, -2, 0, 0, 0, 4));
        Button clear = outlineButton("清空本模式学习");
        clear.setOnClickListener(v -> confirmClear());
        page.addView(clear, lp(-1, dp(48), 0, 0, 0, 4));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        setContentView(scroll);
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle("清空学习数据")
                .setMessage("只会删除当前模式的四方向特征模板，原始画面和录音本来就不会保存。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空", (dialog, which) -> {
                    for (ControlDirection direction : ControlDirection.values()) store.clear(direction);
                    refreshCounts();
                    setStatus("当前模式学习数据已清空", green);
                })
                .show();
    }

    private FrameLayout buildPreview() {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackground(round(Color.rgb(23, 33, 43), 24));
        frame.setClipToOutline(true);
        texture = new TextureView(this);
        texture.setContentDescription("自定义手势学习前置摄像头画面");
        frame.addView(texture, new FrameLayout.LayoutParams(-1, -1));
        previewState = text("等待前摄启动…", 15, Color.WHITE, true);
        previewState.setGravity(Gravity.CENTER);
        previewState.setBackgroundColor(Color.argb(90, 0, 0, 0));
        frame.addView(previewState, new FrameLayout.LayoutParams(-1, dp(48), Gravity.BOTTOM));
        return frame;
    }

    private LinearLayout buildDirectionRow(ControlDirection direction) {
        LinearLayout row = surface();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), 0, dp(10), 0);
        TextView name = text(direction.getLabel() + "方向", 17, ink, true);
        name.setGravity(Gravity.CENTER);
        row.addView(name, lp(dp(90), -1, 0, 0, 0, 0));
        TextView samples = text("未学习", 13, muted, false);
        samples.setGravity(Gravity.CENTER_VERTICAL);
        sampleLabels.put(direction, samples);
        row.addView(samples, lp(0, -1, 1, 0, 0, 0, 0));
        Button button = outlineButton("录入");
        button.setContentDescription("录入" + direction.getLabel() + "方向");
        button.setOnClickListener(v -> startEnrollment(direction));
        recordButtons[direction.ordinal()] = button;
        row.addView(button, lp(dp(86), dp(48), 0, 0, 0, 0));
        return row;
    }

    private void initGestureLearning() {
        analyzer = new MediaPipeHandLandmarker(this, new MediaPipeHandLandmarker.Listener() {
            @Override
            public void onGesture(boolean upward) {
                // Fixed-mode callbacks are intentionally ignored in enrollment.
            }

            @Override
            public void onState(String nextState) {
                runOnUiThread(() -> {
                    if (!recording && previewState != null && nextState != null) previewState.setText(nextState);
                });
            }
        });
        analyzer.setMode(GestureMode.PALM_SWING);
        analyzer.setFrameListener(this::onGestureFrame);
        cameraThread = new HandlerThread("training-camera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                surface.setDefaultBufferSize(640, 480);
                previewSurface = new Surface(surface);
                openCamera();
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                surface.setDefaultBufferSize(640, 480);
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                previewSurface = null;
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {
            }
        });
        if (texture.isAvailable()) {
            SurfaceTexture surface = texture.getSurfaceTexture();
            surface.setDefaultBufferSize(640, 480);
            previewSurface = new Surface(surface);
            openCamera();
        }
        refreshCounts();
        setStatus("前摄已启动，选择一个方向开始录入", green);
    }

    private void initVoiceLearning() {
        voiceRecorder = new VoiceRecorder(new VoiceRecorder.Listener() {
            @Override
            public void onSamples(short[] samples) {
                if (!recording || samples == null) return;
                synchronized (voiceSamples) {
                    for (short sample : samples) voiceSamples.add(sample);
                }
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> setStatus(message, orange));
            }
        });
        if (voiceRecorder.start()) {
            refreshCounts();
            setStatus("麦克风已启动，选择一个方向开始录入", green);
        }
    }

    private void startEnrollment(ControlDirection direction) {
        if (recording) return;
        recording = false;
        recordingDirection = direction;
        setButtonsEnabled(false);
        setStatus("准备录入" + direction.getLabel() + "方向 · 3", blue);
        mainHandler.postDelayed(() -> {
            if (isFinishing()) return;
            setStatus("准备录入" + direction.getLabel() + "方向 · 2", blue);
        }, 700L);
        mainHandler.postDelayed(() -> {
            if (isFinishing()) return;
            setStatus("准备录入" + direction.getLabel() + "方向 · 1", blue);
        }, 1400L);
        mainHandler.postDelayed(this::beginRecording, 2100L);
    }

    private void beginRecording() {
        if (recordingDirection == null || isFinishing()) return;
        recording = true;
        if (mode == GestureMode.CUSTOM_GESTURE) {
            synchronized (gestureFrames) {
                gestureFrames.clear();
            }
            setStatus("录入中 · 请完成" + recordingDirection.getLabel() + "方向动作", orange);
            if (previewState != null) previewState.setText("录入中 · 完成动作后自然收手");
        } else {
            synchronized (voiceSamples) {
                voiceSamples.clear();
            }
            setStatus("录入中 · 请说出你的" + recordingDirection.getLabel() + "方向口令", orange);
        }
        mainHandler.postDelayed(this::finishRecording, 1500L);
    }

    private void onGestureFrame(List<NormalizedLandmark> landmarks, long timestamp, int rotationDegrees) {
        if (!recording || mode != GestureMode.CUSTOM_GESTURE) return;
        float[] feature = GestureFeatureExtractor.fromLandmarks(landmarks, rotationDegrees);
        if (feature == null) return;
        synchronized (gestureFrames) {
            gestureFrames.add(feature);
        }
    }

    private void finishRecording() {
        if (!recording) return;
        recording = false;
        ControlDirection direction = recordingDirection;
        recordingDirection = null;
        if (mode == GestureMode.CUSTOM_GESTURE) {
            List<float[]> copy;
            synchronized (gestureFrames) {
                copy = new ArrayList<>(gestureFrames);
                gestureFrames.clear();
            }
            float[] sequence = GestureFeatureExtractor.prepareSequence(copy);
            if (sequence == null) {
                setStatus("没有采集到完整动作，请重新录入", orange);
            } else {
                store.addSample(direction, sequence);
                setStatus("已保存" + direction.getLabel() + "方向手势", green);
            }
        } else {
            short[] samples;
            synchronized (voiceSamples) {
                samples = new short[voiceSamples.size()];
                for (int index = 0; index < samples.length; index++) samples[index] = voiceSamples.get(index);
                voiceSamples.clear();
            }
            float[] sequence = VoiceFeatureExtractor.prepareSequence(VoiceFeatureExtractor.extract(samples));
            if (sequence == null) {
                setStatus("没有采集到清晰声音，请重新录入", orange);
            } else {
                store.addSample(direction, sequence);
                setStatus("已保存" + direction.getLabel() + "方向口令", green);
            }
        }
        refreshCounts();
        setButtonsEnabled(true);
    }

    private void refreshCounts() {
        if (store == null) return;
        int minimum = mode == GestureMode.CUSTOM_GESTURE
                ? MIN_GESTURE_SAMPLES : MIN_VOICE_SAMPLES;
        for (ControlDirection direction : ControlDirection.values()) {
            TextView label = sampleLabels.get(direction);
            if (label == null) continue;
            int count = store.count(direction);
            label.setText(count == 0
                    ? "未学习 · 最少 " + minimum + " 次"
                    : "已学习 " + count + " 次 · 最少 " + minimum + " 次");
            label.setTextColor(count >= minimum ? green : muted);
        }
    }

    private void setButtonsEnabled(boolean enabled) {
        if (recordButtons == null) return;
        for (Button button : recordButtons) {
            if (button != null) {
                button.setEnabled(enabled);
                button.setAlpha(enabled ? 1f : 0.55f);
            }
        }
    }

    private void setStatus(String message, int color) {
        if (status != null) {
            status.setText(message);
            status.setTextColor(color);
        }
    }

    private void openCamera() {
        if (cameraHandler == null || previewSurface == null) return;
        cameraHandler.post(() -> {
            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            if (manager == null) {
                runOnUiThread(() -> setStatus("相机不可用", orange));
                return;
            }
            try {
                String cameraId = null;
                for (String id : manager.getCameraIdList()) {
                    CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
                    Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                    if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                        cameraId = id;
                        break;
                    }
                }
                if (cameraId == null) {
                    runOnUiThread(() -> setStatus("未找到前置摄像头", orange));
                    return;
                }
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
                Integer sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
                int displayDegrees = getWindowManager().getDefaultDisplay().getRotation() * 90;
                int frameRotation = ((sensorOrientation == null ? 0 : sensorOrientation)
                        - displayDegrees + 360) % 360;
                analyzer.setRotationDegrees(frameRotation);
                imageReader = ImageReader.newInstance(640, 480, android.graphics.ImageFormat.YUV_420_888, 2);
                imageReader.setOnImageAvailableListener(reader -> {
                    Image image = null;
                    try {
                        image = reader.acquireLatestImage();
                        if (image != null && analyzer != null) analyzer.analyze(image);
                    } finally {
                        if (image != null) image.close();
                    }
                }, cameraHandler);
                manager.openCamera(cameraId, cameraCallback, cameraHandler);
            } catch (SecurityException | CameraAccessException | IllegalArgumentException error) {
                runOnUiThread(() -> setStatus("相机启动失败", orange));
            }
        });
    }

    private final CameraDevice.StateCallback cameraCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            cameraDevice = camera;
            try {
                camera.createCaptureSession(java.util.Collections.singletonList(imageReader.getSurface()),
                        new CameraCaptureSession.StateCallback() {
                            @Override
                            public void onConfigured(CameraCaptureSession session) {
                                captureSession = session;
                                try {
                                    CaptureRequest.Builder request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                    request.addTarget(imageReader.getSurface());
                                    request.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                                    request.set(CaptureRequest.CONTROL_AF_MODE,
                                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                                    session.setRepeatingRequest(request.build(), null, cameraHandler);
                                } catch (CameraAccessException error) {
                                    runOnUiThread(() -> setStatus("相机分析启动失败", orange));
                                }
                            }

                            @Override
                            public void onConfigureFailed(CameraCaptureSession session) {
                                runOnUiThread(() -> setStatus("相机配置失败", orange));
                            }
                        }, cameraHandler);
            } catch (CameraAccessException error) {
                runOnUiThread(() -> setStatus("相机配置失败", orange));
            }
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            camera.close();
            cameraDevice = null;
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            camera.close();
            cameraDevice = null;
            runOnUiThread(() -> setStatus("相机被占用或启动失败", orange));
        }
    };

    private void stopCamera() {
        if (cameraHandler != null) {
            cameraHandler.post(() -> {
                if (captureSession != null) {
                    captureSession.close();
                    captureSession = null;
                }
                if (cameraDevice != null) {
                    cameraDevice.close();
                    cameraDevice = null;
                }
                if (imageReader != null) {
                    imageReader.close();
                    imageReader = null;
                }
                if (previewSurface != null) {
                    previewSurface.release();
                    previewSurface = null;
                }
            });
        }
        if (cameraThread != null) {
            cameraThread.quitSafely();
            cameraThread = null;
            cameraHandler = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            setStatus("权限未开启，无法学习", orange);
            return;
        }
        if (requestCode == REQUEST_CAMERA) initGestureLearning();
        else if (requestCode == REQUEST_AUDIO) initVoiceLearning();
    }

    private boolean hasPermission(String permission) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        recording = false;
        stopCamera();
        if (analyzer != null) {
            analyzer.close();
            analyzer = null;
        }
        if (voiceRecorder != null) {
            voiceRecorder.stop();
            voiceRecorder = null;
        }
        super.onDestroy();
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        return view;
    }

    private Button outlineButton(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextSize(14);
        button.setTextColor(blue);
        button.setMinHeight(dp(48));
        button.setPadding(dp(6), 0, dp(6), 0);
        return button;
    }

    private Button textButton(String value) {
        Button button = outlineButton(value);
        button.setTextColor(muted);
        return button;
    }

    private LinearLayout surface() {
        LinearLayout layout = new LinearLayout(this);
        layout.setBackground(round(Color.WHITE, 18));
        return layout;
    }

    private android.graphics.drawable.GradientDrawable round(int color, int radiusDp) {
        android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        drawable.setStroke(dp(1), Color.rgb(226, 232, 240));
        return drawable;
    }

    private LinearLayout.LayoutParams lp(int width, int height, float weight,
                                         int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height, weight);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    private LinearLayout.LayoutParams lp(int width, int height, int left, int top, int right, int bottom) {
        return lp(width, height, 0f, left, top, right, bottom);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void applyInsets(ScrollView scroll, LinearLayout page) {
        final int top = dp(20);
        final int bottom = dp(28);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            int topInset;
            int bottomInset;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                topInset = bars.top;
                bottomInset = bars.bottom;
            } else {
                topInset = insets.getSystemWindowInsetTop();
                bottomInset = insets.getSystemWindowInsetBottom();
            }
            page.setPadding(dp(24), top + topInset, dp(24), bottom + bottomInset);
            return insets;
        });
        scroll.post(scroll::requestApplyInsets);
    }
}
