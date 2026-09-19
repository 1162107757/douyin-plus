package com.example.gesturefeed;

import android.Manifest;
import android.app.Dialog;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
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
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.LinearLayout;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Main control surface for the hands-free short-video controller.
 * The view intentionally keeps the primary action obvious and the permission
 * state explicit, matching the approved product design board.
 */
public class MainActivity extends Activity {
    private static final int REQ_CAMERA = 41;
    private static final int REQ_NOTIFICATIONS = 42;
    private static final int REQ_AUDIO = 43;
    private static final String PREF_GESTURE_MODE = "gesture_mode";
    private static final int MIN_CUSTOM_GESTURE_SAMPLES = 3;
    private static final int MIN_CUSTOM_VOICE_SAMPLES = 1;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private LinearLayout page;
    private TextView cameraValue;
    private TextView accessibilityValue;
    private TextView statusTitle;
    private TextView statusDescription;
    private TextView statusDot;
    private Button startButton;
    private Button setupButton;
    private RadioGroup modeGroup;
    private GestureMode selectedMode = GestureMode.PALM_SWING;
    private BroadcastReceiver stateReceiver;
    private Dialog calibrationDialog;
    private CalibrationPreview calibrationPreview;
    private TextView calibrationHint;
    private boolean calibrationSawUp;
    private boolean calibrationSawDown;
    private TextureView calibrationTexture;
    private Surface calibrationSurface;
    private HandlerThread calibrationCameraThread;
    private Handler calibrationCameraHandler;
    private CameraDevice calibrationCameraDevice;
    private CameraCaptureSession calibrationCaptureSession;
    private ImageReader calibrationImageReader;
    private MediaPipeHandLandmarker calibrationAnalyzer;

    private final int canvas = Color.rgb(248, 250, 252);
    private final int ink = Color.rgb(30, 41, 59);
    private final int muted = Color.rgb(71, 85, 105);
    private final int border = Color.rgb(226, 232, 240);
    private final int blue = Color.rgb(37, 99, 235);
    private final int green = Color.rgb(22, 163, 74);
    private final int orange = Color.rgb(234, 88, 12);
    private final int red = Color.rgb(220, 38, 38);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window window = getWindow();
        window.setStatusBarColor(canvas);
        window.setNavigationBarColor(canvas);
        window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

        selectedMode = GestureMode.fromStoredValue(
                getPreferences(MODE_PRIVATE).getString(PREF_GESTURE_MODE, null));
        buildUi();
        registerStateReceiver();
        refreshPermissionState();
        requestInitialPermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPermissionState();
        updateRuntimeState();
    }

    @Override
    protected void onDestroy() {
        stopCalibrationCamera();
        if (stateReceiver != null) {
            unregisterReceiver(stateReceiver);
        }
        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(canvas);

        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(24), dp(20), dp(24), dp(28));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        applySystemBarInsets(scroll, page);
        setContentView(scroll);

        TextView title = text("手势翻页", 34, ink, true);
        page.addView(title, lp(-1, -2, 0, 0, 0, 2));
        TextView subtitle = text("免触碰 · 本地识别", 16, muted, false);
        page.addView(subtitle, lp(-1, -2, 0, 0, 0, 22));

        page.addView(buildStatusCard(), lp(-1, -2, 0, 0, 0, 18));
        page.addView(buildPermissionCard(), lp(-1, -2, 0, 0, 0, 14));
        page.addView(buildModeCard(), lp(-1, -2, 0, 0, 0, 16));
        page.addView(buildDirectionCard(), lp(-1, -2, 0, 0, 0, 20));

        startButton = primaryButton(startActionLabel(false));
        startButton.setOnClickListener(v -> toggleControl());
        page.addView(startButton, lp(-1, dp(56), 0, 0, 0, 10));

        setupButton = outlineButton(setupActionLabel());
        setupButton.setOnClickListener(v -> openSetupForSelectedMode());
        page.addView(setupButton, lp(-1, dp(56), 0, 0, 0, 4));

        Button openVideoApp = textButton("打开视频应用");
        openVideoApp.setOnClickListener(v -> openVideoApp());
        page.addView(openVideoApp, lp(-1, dp(50), 0, 0, 0, 18));

        LinearLayout privacy = new LinearLayout(this);
        privacy.setGravity(Gravity.CENTER);
        privacy.setPadding(0, dp(8), 0, dp(4));
        privacy.addView(new IconView(this, IconView.SHIELD, muted), lp(dp(22), dp(22), 0, 0, 8, 0));
        TextView privacyText = text("画面仅在本机处理", 14, muted, false);
        privacyText.setContentDescription("隐私说明：画面仅在本机处理，不保存");
        privacy.addView(privacyText, lp(-2, -2, 0, 0, 0, 0));
        page.addView(privacy, lp(-1, -2, 0, 0, 0, 8));

        TextView version = text("Android 10+  ·  端侧分析  ·  随时可停止", 12, Color.rgb(100, 116, 139), false);
        version.setGravity(Gravity.CENTER);
        page.addView(version, lp(-1, -2, 0, 0, 0, 0));
    }

    /** Keeps the title and bottom content clear of Android's system bars. */
    private void applySystemBarInsets(ScrollView scroll, LinearLayout page) {
        final int baseTop = dp(20);
        final int baseBottom = dp(28);
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
            page.setPadding(dp(24), baseTop + topInset, dp(24), baseBottom + bottomInset);
            return insets;
        });
        scroll.post(scroll::requestApplyInsets);
    }

    private View buildStatusCard() {
        LinearLayout card = surface();
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        FrameLayout row = new FrameLayout(this);
        row.setMinimumHeight(dp(72));
        statusDot = text("●", 26, green, true);
        statusDot.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams dotParams = new FrameLayout.LayoutParams(dp(34), dp(34));
        dotParams.gravity = Gravity.CENTER_VERTICAL;
        row.addView(statusDot, dotParams);
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        statusTitle = text("准备就绪", 20, green, true);
        statusDescription = text("权限完成后即可开始", 14, muted, false);
        copy.addView(statusTitle, lp(-1, -2, 0, 0, 0, 2));
        copy.addView(statusDescription, lp(-1, -2, 0, 0, 0, 0));
        FrameLayout.LayoutParams copyParams = new FrameLayout.LayoutParams(-1, -2);
        copyParams.leftMargin = dp(48);
        copyParams.gravity = Gravity.CENTER_VERTICAL;
        row.addView(copy, copyParams);
        card.addView(row, lp(-1, dp(72), 0, 0, 0, 0));
        return card;
    }

    private View buildPermissionCard() {
        LinearLayout card = surface();
        card.setPadding(dp(14), dp(8), dp(14), dp(8));
        addPermissionRow(card, IconView.CAMERA, "相机", v -> cameraValue = v, null);
        addDivider(card);
        addPermissionRow(card, IconView.ACCESSIBILITY, "辅助功能", v -> accessibilityValue = v,
                v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        return card;
    }

    private void addPermissionRow(LinearLayout card, int iconType, String label,
                                   java.util.function.Consumer<TextView> valueSink,
                                   View.OnClickListener clickListener) {
        FrameLayout row = new FrameLayout(this);
        row.setMinimumHeight(dp(58));
        if (clickListener != null) {
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(clickListener);
            row.setContentDescription(label + "权限设置");
        }
        FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(dp(30), dp(30));
        iconParams.leftMargin = dp(2);
        iconParams.gravity = Gravity.CENTER_VERTICAL;
        row.addView(new IconView(this, iconType, blue), iconParams);
        TextView name = text(label, 16, ink, true);
        FrameLayout.LayoutParams nameParams = new FrameLayout.LayoutParams(dp(132), -2);
        nameParams.leftMargin = dp(44);
        nameParams.gravity = Gravity.CENTER_VERTICAL;
        row.addView(name, nameParams);
        TextView value = text("检查中", 15, muted, true);
        value.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        value.setContentDescription(label + "状态");
        FrameLayout.LayoutParams valueParams = new FrameLayout.LayoutParams(dp(88), -1);
        valueParams.gravity = Gravity.CENTER_VERTICAL | Gravity.RIGHT;
        row.addView(value, valueParams);
        valueSink.accept(value);
        card.addView(row, lp(-1, dp(58), 0, 0, 0, 0));
    }

    private void addDivider(LinearLayout parent) {
        View divider = new View(this);
        divider.setBackgroundColor(border);
        parent.addView(divider, lp(-1, 1, dp(42), 0, 0, 0));
    }

    private View buildModeCard() {
        LinearLayout card = surface();
        card.setPadding(dp(16), dp(13), dp(16), dp(13));
        TextView heading = text("识别方案", 15, ink, true);
        card.addView(heading, lp(-1, -2, 0, 0, 0, 3));
        TextView hint = text("选择一种方案后开始控制，运行中不可切换", 12, muted, false);
        card.addView(hint, lp(-1, -2, 0, 0, 0, 6));

        modeGroup = new RadioGroup(this);
        modeGroup.setOrientation(RadioGroup.VERTICAL);
        modeGroup.setContentDescription("识别方案选择");
        RadioButton palm = modeOption(GestureMode.PALM_SWING);
        RadioButton twoFinger = modeOption(GestureMode.TWO_FINGER_DIRECTION);
        RadioButton middleFinger = modeOption(GestureMode.MIDDLE_FINGER_DIRECTION);
        RadioButton customGesture = modeOption(GestureMode.CUSTOM_GESTURE);
        RadioButton customVoice = modeOption(GestureMode.CUSTOM_VOICE);
        modeGroup.addView(palm, lp(-1, dp(56), 0, 0, 0, 0));
        modeGroup.addView(twoFinger, lp(-1, dp(56), 0, 0, 0, 0));
        modeGroup.addView(middleFinger, lp(-1, dp(56), 0, 0, 0, 0));
        modeGroup.addView(customGesture, lp(-1, dp(56), 0, 0, 0, 0));
        modeGroup.addView(customVoice, lp(-1, dp(56), 0, 0, 0, 0));
        RadioButton selected = selectedMode == GestureMode.TWO_FINGER_DIRECTION
                ? twoFinger
                : selectedMode == GestureMode.MIDDLE_FINGER_DIRECTION
                ? middleFinger
                : selectedMode == GestureMode.CUSTOM_GESTURE
                ? customGesture
                : selectedMode == GestureMode.CUSTOM_VOICE
                ? customVoice : palm;
        modeGroup.check(selected.getId());
        modeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (GestureControlService.isRunning()) {
                syncModeGroup();
                android.widget.Toast.makeText(this, "请先停止手势翻页，再切换识别方案",
                        android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            View checked = group.findViewById(checkedId);
            if (checked == null || checked.getTag() == null) return;
            selectedMode = (GestureMode) checked.getTag();
            getPreferences(MODE_PRIVATE).edit()
                    .putString(PREF_GESTURE_MODE, selectedMode.name())
                    .apply();
            updateSetupButton();
            refreshPermissionState();
            if ((isCustomGestureMode() || isCustomVoiceMode())
                    && !hasRequiredCustomTraining()) {
                mainHandler.post(() -> {
                    if (!isFinishing() && !GestureControlService.isRunning()) {
                        android.widget.Toast.makeText(this, trainingRequirementMessage(),
                                android.widget.Toast.LENGTH_LONG).show();
                        openSetupForSelectedMode();
                    }
                });
            }
        });
        card.addView(modeGroup, lp(-1, dp(280), 0, 0, 0, 0));
        return card;
    }

    private RadioButton modeOption(GestureMode mode) {
        RadioButton option = new RadioButton(this);
        option.setId(View.generateViewId());
        option.setTag(mode);
        option.setText(mode.getLabel() + "\n" + mode.getDescription());
        option.setTextSize(14);
        option.setTextColor(ink);
        option.setGravity(Gravity.CENTER_VERTICAL);
        option.setLineSpacing(0f, 1.05f);
        option.setPadding(0, 0, 0, 0);
        option.setMinHeight(dp(48));
        option.setContentDescription(mode.getLabel() + "：" + mode.getDescription());
        return option;
    }

    private void syncModeGroup() {
        if (modeGroup == null) return;
        for (int index = 0; index < modeGroup.getChildCount(); index++) {
            View child = modeGroup.getChildAt(index);
            if (child.getTag() == selectedMode) {
                modeGroup.check(child.getId());
                return;
            }
        }
    }

    private void setModeSelectorEnabled(boolean enabled) {
        if (modeGroup == null) return;
        modeGroup.setAlpha(enabled ? 1f : 0.58f);
        for (int index = 0; index < modeGroup.getChildCount(); index++) {
            modeGroup.getChildAt(index).setEnabled(enabled);
        }
    }

    private View buildDirectionCard() {
        LinearLayout card = surface();
        card.setPadding(dp(16), dp(13), dp(16), dp(13));
        TextView heading = text("四向切换", 15, ink, true);
        card.addView(heading, lp(-1, -2, 0, 0, 0, 9));
        addDirectionRow(card, ControlDirection.UP, "向上动作", "上滑  ·  下一个视频", blue);
        addDirectionRow(card, ControlDirection.DOWN, "向下动作", "下滑  ·  上一个视频", orange);
        addDirectionRow(card, ControlDirection.LEFT, "向左动作", "左滑  ·  左侧操作", blue);
        addDirectionRow(card, ControlDirection.RIGHT, "向右动作", "右滑  ·  右侧操作", orange);
        return card;
    }

    private void addDirectionRow(LinearLayout parent, ControlDirection direction,
                                 String title, String detail, int accent) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(42));
        View stripe = new View(this);
        stripe.setBackgroundColor(accent);
        row.addView(stripe, lp(dp(4), dp(28), 0, 0, 10, 0));
        int icon = direction == ControlDirection.UP ? IconView.ARROW_UP
                : direction == ControlDirection.DOWN ? IconView.ARROW_DOWN
                : direction == ControlDirection.LEFT ? IconView.ARROW_LEFT : IconView.ARROW_RIGHT;
        row.addView(new IconView(this, icon, accent),
                lp(dp(22), dp(22), 0, 0, 10, 0));
        TextView label = text(title, 14, ink, true);
        row.addView(label, lp(dp(92), -2, 0, 0, 8, 0));
        TextView desc = text(detail, 14, muted, false);
        row.addView(desc, lp(dp(170), -2, 0, 0, 0, 0));
        row.setContentDescription(title + "，" + detail);
        parent.addView(row, lp(-1, dp(42), 0, 0, 0, 0));
    }

    private LinearLayout surface() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(22));
        // Keep the card edge soft and explicit instead of relying on the
        // platform's dark ambient shadow, which reads as a black outline on
        // some OEM skins.
        bg.setStroke(dp(1), border);
        card.setBackground(bg);
        card.setElevation(0f);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            card.setStateListAnimator(null);
        }
        return card;
    }

    private Button primaryButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(16);
        button.setTextColor(Color.WHITE);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setAllCaps(false);
        button.setBackground(round(blue, 18));
        button.setElevation(0f);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            button.setStateListAnimator(null);
        }
        button.setMinHeight(dp(52));
        button.setMinimumHeight(dp(52));
        return button;
    }

    private Button outlineButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(16);
        button.setTextColor(blue);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setAllCaps(false);
        android.graphics.drawable.GradientDrawable background = round(Color.rgb(248, 250, 255), 18);
        background.setStroke(dp(1), Color.rgb(203, 216, 247));
        button.setBackground(background);
        button.setElevation(0f);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            button.setStateListAnimator(null);
        }
        button.setMinHeight(dp(52));
        button.setMinimumHeight(dp(52));
        return button;
    }

    private Button textButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(16);
        button.setTextColor(blue);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setAllCaps(false);
        button.setBackground(round(Color.TRANSPARENT, 16));
        button.setElevation(0f);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            button.setStateListAnimator(null);
        }
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        return button;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        return view;
    }

    private LinearLayout.LayoutParams lp(int width, int height, float weight,
                                         int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height, weight);
        params.setMargins(left, top, right, bottom);
        return params;
    }

    private LinearLayout.LayoutParams lp(int width, int height, int left, int top, int right, int bottom) {
        return lp(width, height, 0, left, top, right, bottom);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void requestInitialPermissions() {
        if (!isCustomVoiceMode() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        } else if (isCustomVoiceMode() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
        } else if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refreshPermissionState();
        if (requestCode == REQ_CAMERA && Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        }
    }

    private void refreshPermissionState() {
        if (cameraValue == null || accessibilityValue == null) return;
        boolean runningState = GestureControlService.isRunning();
        startButton.setText(startActionLabel(runningState));
        boolean camera = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        boolean microphone = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        boolean accessibility = isAccessibilityEnabled();
        setModeSelectorEnabled(!runningState);
        if (isCustomVoiceMode()) {
            cameraValue.setText("非必需");
            cameraValue.setTextColor(muted);
        } else {
            cameraValue.setText(camera ? "已开启" : "未开启");
            cameraValue.setTextColor(camera ? green : orange);
        }
        accessibilityValue.setText(accessibility ? "已开启" : "去开启");
        accessibilityValue.setTextColor(accessibility ? green : orange);
        if (runningState) {
            startButton.setAlpha(1f);
            return;
        }
        boolean trainingReady = hasRequiredCustomTraining();
        boolean ready = (isCustomVoiceMode() ? microphone : camera) && accessibility && trainingReady;
        startButton.setEnabled(true);
        startButton.setAlpha(ready ? 1f : 0.72f);
        if (!trainingReady) {
            statusTitle.setText("需要完成录入");
            statusTitle.setTextColor(orange);
            statusDescription.setText(trainingRequirementMessage());
            statusDot.setTextColor(orange);
        } else if (!ready && !GestureControlService.isRunning()) {
            statusTitle.setText("完成权限设置");
            statusTitle.setTextColor(orange);
            statusDescription.setText(isCustomVoiceMode()
                    ? "开启麦克风和辅助功能后即可开始"
                    : "开启相机和辅助功能后即可开始");
            statusDot.setTextColor(orange);
        } else if (!GestureControlService.isRunning()) {
            statusTitle.setText("准备就绪");
            statusTitle.setTextColor(green);
            statusDescription.setText("权限已完成，可以开始");
            statusDot.setTextColor(green);
        }
    }

    private boolean isAccessibilityEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabled)) return false;
        String expected = getPackageName() + "/" + GestureAccessibilityService.class.getName();
        for (String service : enabled.split(":")) {
            if (expected.equalsIgnoreCase(service)) return true;
        }
        return false;
    }

    private void startControl() {
        if (!hasRequiredCustomTraining()) {
            android.widget.Toast.makeText(this, trainingRequirementMessage(),
                    android.widget.Toast.LENGTH_LONG).show();
            openSetupForSelectedMode();
            return;
        }
        boolean camera = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        boolean microphone = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        if (!isCustomVoiceMode() && !camera) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            return;
        }
        if (isCustomVoiceMode() && !microphone) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            return;
        }
        if (!isAccessibilityEnabled()) {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            android.widget.Toast.makeText(this, "请开启“手势翻页辅助功能”后返回", android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        Intent service = new Intent(this, GestureControlService.class);
        service.setAction(GestureControlService.ACTION_START);
        service.putExtra(GestureControlService.EXTRA_GESTURE_MODE, selectedMode.name());
        startForegroundService(service);
        mainHandler.postDelayed(this::openVideoApp, 650);
        statusTitle.setText("启动中");
        statusTitle.setTextColor(blue);
        statusDescription.setText(isCustomVoiceMode()
                ? "正在准备麦克风与声音识别"
                : "正在准备前摄与手势识别");
        statusDot.setTextColor(blue);
    }

    private void toggleControl() {
        if (GestureControlService.isRunning()) {
            Intent stop = new Intent(this, GestureControlService.class);
            stop.setAction(GestureControlService.ACTION_STOP);
            stopService(stop);
            android.app.NotificationManager notificationManager = getSystemService(android.app.NotificationManager.class);
            if (notificationManager != null) notificationManager.cancelAll();
            statusTitle.setText("已停止");
            statusTitle.setTextColor(muted);
            statusDescription.setText(isCustomVoiceMode()
                    ? "麦克风和声音识别已释放"
                    : "前摄和手势识别已释放");
            statusDot.setTextColor(muted);
            startButton.setText(startActionLabel(false));
            setModeSelectorEnabled(true);
            return;
        }
        startControl();
    }

    private boolean isCustomGestureMode() {
        return selectedMode == GestureMode.CUSTOM_GESTURE;
    }

    private boolean isCustomVoiceMode() {
        return selectedMode == GestureMode.CUSTOM_VOICE;
    }

    private String startActionLabel(boolean running) {
        if (running) return isCustomVoiceMode() ? "停止声音翻页" : "停止手势翻页";
        return isCustomVoiceMode() ? "开始声音翻页" : "开始手势翻页";
    }

    private boolean hasRequiredCustomTraining() {
        if (!isCustomGestureMode() && !isCustomVoiceMode()) return true;
        FeatureTemplateStore store = new FeatureTemplateStore(this,
                isCustomGestureMode() ? "custom_gesture_templates" : "custom_voice_templates");
        if (isCustomVoiceMode()) {
            int total = 0;
            for (ControlDirection direction : ControlDirection.values()) total += store.count(direction);
            return total >= MIN_CUSTOM_VOICE_SAMPLES;
        }
        for (ControlDirection direction : ControlDirection.values()) {
            if (store.count(direction) < MIN_CUSTOM_GESTURE_SAMPLES) return false;
        }
        return true;
    }

    private String trainingRequirementMessage() {
        return isCustomVoiceMode()
                ? "自定义声音至少录入一个方向 1 次，请先完成录入"
                : "自定义手势要求每个方向至少录入 3 次，请先完成录入";
    }

    private String setupActionLabel() {
        if (isCustomGestureMode()) return "录入自定义手势";
        if (isCustomVoiceMode()) return "录入自定义声音";
        return "手势校准";
    }

    private void updateSetupButton() {
        if (setupButton != null) setupButton.setText(setupActionLabel());
    }

    private void openSetupForSelectedMode() {
        if (GestureControlService.isRunning()) {
            android.widget.Toast.makeText(this, "请先停止手势翻页，再进行设置",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if (isCustomGestureMode() || isCustomVoiceMode()) {
            Intent intent = new Intent(this, CustomTrainingActivity.class);
            intent.putExtra(CustomTrainingActivity.EXTRA_MODE, selectedMode.name());
            startActivity(intent);
        } else {
            showCalibrationDialog();
        }
    }

    private void openVideoApp() {
        for (String packageName : GestureAccessibilityService.VIDEO_APP_PACKAGES) {
            Intent launch = getPackageManager().getLaunchIntentForPackage(packageName);
            if (launch == null) continue;
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(launch);
                return;
            } catch (android.content.ActivityNotFoundException ignored) {
                // Try the next known video app.
            }
        }
        android.widget.Toast.makeText(this,
                "未找到已安装的视频应用，请手动打开抖音、快手或其他视频 App",
                android.widget.Toast.LENGTH_LONG).show();
    }

    private void updateRuntimeState() {
        if (GestureControlService.isRunning()) {
            startButton.setText(startActionLabel(true));
            setModeSelectorEnabled(false);
            startButton.setAlpha(1f);
            statusTitle.setText(GestureControlService.isPaused() ? "已暂停" : "正在运行");
            statusTitle.setTextColor(GestureControlService.isPaused() ? muted : green);
            statusDescription.setText(GestureControlService.isPaused()
                    ? "识别已暂停，可从通知继续"
                    : selectedMode == GestureMode.CUSTOM_GESTURE
                    ? "前摄识别已学习手势"
                    : selectedMode == GestureMode.CUSTOM_VOICE
                    ? "麦克风识别已学习口令"
                    : selectedMode == GestureMode.TWO_FINGER_DIRECTION
                    ? "伸出食指和中指，指向上方或下方"
                    : selectedMode == GestureMode.MIDDLE_FINGER_DIRECTION
                    ? "只伸出中指，指向上方或下方"
                    : "张开手掌，向上或向下挥动");
            statusDot.setTextColor(GestureControlService.isPaused() ? muted : green);
        } else if (startButton != null) {
            startButton.setText(startActionLabel(false));
            setModeSelectorEnabled(true);
        }
    }

    private void updateRuntimeEvent(String state) {
        if (GestureControlService.isCalibrationMode() || !GestureControlService.isRunning()
                || TextUtils.isEmpty(state) || statusDescription == null) return;
        if (state.contains("滑动已完成")) {
            statusTitle.setText("已完成");
            statusTitle.setTextColor(green);
            statusDescription.setText(state);
            statusDot.setTextColor(green);
        } else if (state.contains("取消") || state.contains("失败") || state.contains("请先打开")
                || state.contains("未匹配") || state.contains("请先完成")) {
            statusTitle.setText("需要注意");
            statusTitle.setTextColor(orange);
            statusDescription.setText(state);
            statusDot.setTextColor(orange);
        } else if (state.contains("未检测到手") || state.contains("未检测到中指")) {
            statusDescription.setText(selectedMode == GestureMode.CUSTOM_GESTURE
                    ? "未匹配到手势，请做已学习动作"
                    : selectedMode == GestureMode.CUSTOM_VOICE
                    ? "未匹配到声音，请说已学习口令"
                    : selectedMode == GestureMode.TWO_FINGER_DIRECTION
                    ? "未检测到双指，请同时伸出食指和中指"
                    : selectedMode == GestureMode.MIDDLE_FINGER_DIRECTION
                    ? "未检测到中指，请竖起中指"
                    : "未检测到手，请张开手掌再挥动");
        }
    }

    private void registerStateReceiver() {
        stateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String state = intent == null ? null : intent.getStringExtra("state");
                updateCalibrationState(state);
                updateRuntimeState();
                updateRuntimeEvent(state);
                refreshPermissionState();
            }
        };
        IntentFilter filter = new IntentFilter(GestureControlService.ACTION_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stateReceiver, filter);
        }
    }

    private void showCalibrationDialog() {
        if (GestureControlService.isRunning()) {
            android.widget.Toast.makeText(this, "请先停止手势翻页，再进行校准", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            android.widget.Toast.makeText(this, "请先开启相机权限", android.widget.Toast.LENGTH_SHORT).show();
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            return;
        }

        final Dialog dialog = new Dialog(this);
        calibrationDialog = dialog;
        calibrationSawUp = false;
        calibrationSawDown = false;
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(dp(22), dp(18), dp(22), dp(20));
        shell.setBackground(round(Color.WHITE, 26));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView heading = text("手势校准", 22, ink, true);
        header.addView(heading, lp(0, dp(44), 1, 0, 0, 0, 0));
        Button close = textButton("关闭");
        close.setTextSize(14);
        close.setOnClickListener(v -> dialog.dismiss());
        header.addView(close, lp(dp(72), dp(44), 0, 0, 0, 0));
        shell.addView(header, lp(-1, dp(44), 0, 0, 0, 12));

        shell.addView(buildCalibrationPreview(), lp(-1, dp(260), 0, 0, 0, 12));
        calibrationHint = text("正在启动前摄…", 16, ink, true);
        calibrationHint.setGravity(Gravity.CENTER);
        shell.addView(calibrationHint, lp(-1, dp(30), 0, 0, 0, 10));

        LinearLayout metrics = new LinearLayout(this);
        metrics.setGravity(Gravity.CENTER);
        metrics.addView(metric("距离", "合适", green), lp(0, dp(58), 1, 0, 0, 6, 0));
        metrics.addView(metric("光线", "良好", green), lp(0, dp(58), 1, 6, 0, 0, 0));
        shell.addView(metrics, lp(-1, dp(58), 0, 0, 0, 12));

        TextView sensitivity = text("灵敏度    低        中        高", 14, muted, false);
        sensitivity.setGravity(Gravity.CENTER);
        shell.addView(sensitivity, lp(-1, dp(30), 0, 0, 0, 8));

        Button done = primaryButton("完成校准");
        done.setOnClickListener(v -> dialog.dismiss());
        shell.addView(done, lp(-1, dp(54), 0, 0, 0, 0));

        dialog.setOnDismissListener(d -> {
            stopCalibrationCamera();
            calibrationDialog = null;
            calibrationPreview = null;
            calibrationHint = null;
        });

        dialog.setContentView(shell);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attrs = window.getAttributes();
            attrs.dimAmount = 0.42f;
            window.setAttributes(attrs);
            window.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.90f), -2);
        }
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.90f), -2);
        }
        startCalibrationCamera();
    }

    private FrameLayout buildCalibrationPreview() {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackground(round(Color.rgb(23, 33, 43), 24));
        frame.setClipToOutline(true);

        calibrationTexture = new TextureView(this);
        calibrationTexture.setContentDescription("前置摄像头实时画面");
        frame.addView(calibrationTexture, new FrameLayout.LayoutParams(-1, -1));

        calibrationPreview = new CalibrationPreview(this);
        calibrationPreview.setLiveMode(true);
        calibrationPreview.setContentDescription("前置摄像头实时画面：" + calibrationInstruction());
        frame.addView(calibrationPreview, new FrameLayout.LayoutParams(-1, -1));
        return frame;
    }

    private void startCalibrationCamera() {
        calibrationAnalyzer = new MediaPipeHandLandmarker(this, new MediaPipeHandLandmarker.Listener() {
            @Override
            public void onGesture(boolean upward) {
                String state = selectedMode == GestureMode.TWO_FINGER_DIRECTION
                        ? (upward ? "校准成功 · 双指向上" : "校准成功 · 双指向下")
                        : selectedMode == GestureMode.MIDDLE_FINGER_DIRECTION
                        ? (upward ? "校准成功 · 中指向上" : "校准成功 · 中指向下")
                        : (upward ? "校准成功 · 上挥" : "校准成功 · 下挥");
                runOnUiThread(() -> updateCalibrationState(state));
            }

            @Override
            public void onState(String state) {
                runOnUiThread(() -> updateCalibrationState(state));
            }
        });
        calibrationAnalyzer.setMode(selectedMode);
        calibrationCameraThread = new HandlerThread("calibration-camera");
        calibrationCameraThread.start();
        calibrationCameraHandler = new Handler(calibrationCameraThread.getLooper());
        if (calibrationTexture == null) {
            updateCalibrationState("相机启动失败");
        } else {
            calibrationTexture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                    surface.setDefaultBufferSize(640, 480);
                    calibrationSurface = new Surface(surface);
                    openCalibrationCamera();
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                    surface.setDefaultBufferSize(640, 480);
                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                    calibrationSurface = null;
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture surface) {
                    // Preview frames are intentionally not persisted.
                }
            });
            if (calibrationTexture.isAvailable()) {
                SurfaceTexture surface = calibrationTexture.getSurfaceTexture();
                surface.setDefaultBufferSize(640, 480);
                calibrationSurface = new Surface(surface);
                openCalibrationCamera();
            }
        }
    }

    private void openCalibrationCamera() {
        if (calibrationCameraHandler == null || calibrationSurface == null) return;
        calibrationCameraHandler.post(() -> {
            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            if (manager == null) {
                runOnUiThread(() -> updateCalibrationState("相机不可用"));
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
                    runOnUiThread(() -> updateCalibrationState("未找到前置摄像头"));
                    return;
                }
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
                Integer sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
                int displayDegrees = getWindowManager().getDefaultDisplay().getRotation() * 90;
                int frameRotation = ((sensorOrientation == null ? 0 : sensorOrientation) - displayDegrees + 360) % 360;
                if (calibrationAnalyzer != null) calibrationAnalyzer.setRotationDegrees(frameRotation);
                calibrationImageReader = ImageReader.newInstance(640, 480, android.graphics.ImageFormat.YUV_420_888, 2);
                calibrationImageReader.setOnImageAvailableListener(reader -> {
                    Image image = null;
                    try {
                        image = reader.acquireLatestImage();
                        if (image != null && calibrationAnalyzer != null) calibrationAnalyzer.analyze(image);
                    } catch (Throwable t) {
                        runOnUiThread(() -> updateCalibrationState("相机分析失败"));
                    } finally {
                        if (image != null) image.close();
                    }
                }, calibrationCameraHandler);
                manager.openCamera(cameraId, calibrationStateCallback, calibrationCameraHandler);
            } catch (SecurityException | CameraAccessException | IllegalArgumentException e) {
                runOnUiThread(() -> updateCalibrationState("相机启动失败"));
            }
        });
    }

    private final CameraDevice.StateCallback calibrationStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            calibrationCameraDevice = camera;
            createCalibrationCaptureSession();
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            camera.close();
            calibrationCameraDevice = null;
            runOnUiThread(() -> updateCalibrationState("相机已断开"));
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            camera.close();
            calibrationCameraDevice = null;
            runOnUiThread(() -> updateCalibrationState("相机被占用或启动失败"));
        }
    };

    private void createCalibrationCaptureSession() {
        if (calibrationCameraDevice == null || calibrationImageReader == null || calibrationSurface == null) return;
        try {
            calibrationCameraDevice.createCaptureSession(
                    java.util.Arrays.asList(calibrationImageReader.getSurface(), calibrationSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            calibrationCaptureSession = session;
                            try {
                                CaptureRequest.Builder request = calibrationCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                request.addTarget(calibrationImageReader.getSurface());
                                request.addTarget(calibrationSurface);
                                request.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                                request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                                session.setRepeatingRequest(request.build(), null, calibrationCameraHandler);
                                runOnUiThread(() -> updateCalibrationState(calibrationState()));
                            } catch (CameraAccessException e) {
                                runOnUiThread(() -> updateCalibrationState("相机分析启动失败"));
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            runOnUiThread(() -> updateCalibrationState("相机配置失败"));
                        }
                    }, calibrationCameraHandler);
        } catch (CameraAccessException | IllegalStateException e) {
            runOnUiThread(() -> updateCalibrationState("相机配置失败"));
        }
    }

    private void stopCalibrationCamera() {
        if (calibrationCameraHandler != null) {
            calibrationCameraHandler.post(() -> {
                if (calibrationCaptureSession != null) {
                    calibrationCaptureSession.close();
                    calibrationCaptureSession = null;
                }
                if (calibrationCameraDevice != null) {
                    calibrationCameraDevice.close();
                    calibrationCameraDevice = null;
                }
                if (calibrationImageReader != null) {
                    calibrationImageReader.close();
                    calibrationImageReader = null;
                }
                if (calibrationSurface != null) {
                    calibrationSurface.release();
                    calibrationSurface = null;
                }
            });
        }
        if (calibrationCameraThread != null) {
            calibrationCameraThread.quitSafely();
            calibrationCameraThread = null;
            calibrationCameraHandler = null;
        }
        if (calibrationAnalyzer != null) {
            calibrationAnalyzer.close();
            calibrationAnalyzer = null;
        }
    }

    private void updateCalibrationState(String state) {
        if (calibrationDialog == null || TextUtils.isEmpty(state)) return;
        if (calibrationPreview != null) calibrationPreview.setState(state);
        if (state.contains("下一个视频") || state.contains("上挥") || state.contains("双指向上")
                || state.contains("中指向上")) {
            calibrationSawUp = true;
        }
        if (state.contains("上一个视频") || state.contains("下挥") || state.contains("双指向下")
                || state.contains("中指向下")) {
            calibrationSawDown = true;
        }

        String copy;
        if (state.contains("相机启动失败") || state.contains("相机不可用") || state.contains("相机配置失败")) {
            copy = state + "，请检查相机是否被占用";
        } else if (state.contains("已识别") && calibrationSawUp && calibrationSawDown) {
            copy = "上下方向都已完成，可以结束校准";
        } else if (state.contains("校准成功")) {
            copy = (calibrationSawUp && calibrationSawDown)
                    ? "上下方向都已完成，可以结束校准"
                    : state + "，请再完成另一个方向";
        } else if (state.contains("校准完成")) {
            copy = "上下方向都已完成，可以结束校准";
        } else if (state.contains("已识别")) {
            copy = state + "，请再完成另一个方向";
        } else if (state.contains("方向已锁定")) {
            copy = state + "，继续保持动作幅度";
        } else if (state.contains("双指方向稳定中")) {
            copy = state + "，保持双指方向稳定";
        } else if (state.contains("双指模式") && state.contains("准备下一次")) {
            copy = "收回双指后，再指向另一个方向";
        } else if (state.contains("双指模式") && state.contains("未检测到")) {
            copy = "请同时伸出食指和中指，并指向上方或下方";
        } else if (state.contains("中指方向稳定中")) {
            copy = state + "，保持中指方向稳定";
        } else if (state.contains("中指模式") && state.contains("准备下一次")) {
            copy = "收回中指后，再指向另一个方向";
        } else if (state.contains("中指模式") && state.contains("未检测到")) {
            copy = "请竖起中指，并指向上方或下方";
        } else if (state.contains("等待挥动") || state.contains("准备就绪")) {
            copy = selectedMode == GestureMode.TWO_FINGER_DIRECTION
                    ? "食指和中指同时指向上方，再指向下方"
                    : selectedMode == GestureMode.MIDDLE_FINGER_DIRECTION
                    ? "中指先指向上方，再指向下方"
                    : "张开手掌，先向上挥动，再向下挥动";
        } else if (state.contains("未检测到手")) {
            copy = selectedMode == GestureMode.TWO_FINGER_DIRECTION
                    ? "请将食指和中指同时放入框内"
                    : selectedMode == GestureMode.MIDDLE_FINGER_DIRECTION
                    ? "请将中指竖起放入框内"
                    : "请把张开的手掌放入框内";
        } else if (state.contains("运行中")) {
            copy = selectedMode == GestureMode.TWO_FINGER_DIRECTION
                    ? "前摄已连接，等待双指方向"
                    : selectedMode == GestureMode.MIDDLE_FINGER_DIRECTION
                    ? "前摄已连接，等待中指方向"
                    : "前摄已连接，等待手掌";
        } else {
            copy = state;
        }
        if (calibrationHint != null) calibrationHint.setText(copy);
    }

    private String calibrationState() {
        return selectedMode == GestureMode.TWO_FINGER_DIRECTION
                ? "校准中 · 等待双指指向"
                : selectedMode == GestureMode.MIDDLE_FINGER_DIRECTION
                ? "校准中 · 等待中指指向"
                : "校准中 · 等待手掌";
    }

    private String calibrationInstruction() {
        return selectedMode == GestureMode.TWO_FINGER_DIRECTION
                ? "请将食指和中指同时指向上方或下方"
                : selectedMode == GestureMode.MIDDLE_FINGER_DIRECTION
                ? "请将中指竖起并指向上方或下方"
                : "将张开的手掌向上或向下挥动";
    }

    private LinearLayout metric(String label, String value, int color) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackground(round(Color.rgb(248, 250, 252), 16));
        box.addView(text(label, 12, muted, false), lp(-1, 0, 1, 0, 4, 0, 0));
        box.addView(text(value, 15, color, true), lp(-1, 0, 1, 0, 0, 0, 4));
        return box;
    }

    private android.graphics.drawable.GradientDrawable round(int color, int radiusDp) {
        android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    /** Lightweight vector icon, avoiding emoji or platform-dependent glyphs. */
    static class IconView extends View {
        static final int CAMERA = 1;
        static final int ACCESSIBILITY = 2;
        static final int SHIELD = 3;
        static final int ARROW_UP = 4;
        static final int ARROW_DOWN = 5;
        static final int ARROW_LEFT = 6;
        static final int ARROW_RIGHT = 7;
        private final int type;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int color;

        IconView(Context context, int type, int color) {
            super(context);
            this.type = type;
            this.color = color;
            setContentDescription(type == CAMERA ? "相机图标" : type == ACCESSIBILITY ? "辅助功能图标" : "状态图标");
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(getWidth() * 0.075f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            if (type == CAMERA) {
                RectF body = new RectF(getWidth() * .12f, getHeight() * .28f, getWidth() * .88f, getHeight() * .84f);
                canvas.drawRoundRect(body, getWidth() * .12f, getWidth() * .12f, paint);
                canvas.drawCircle(cx, getHeight() * .56f, getWidth() * .18f, paint);
                canvas.drawLine(getWidth() * .30f, getHeight() * .28f, getWidth() * .40f, getHeight() * .12f, paint);
                canvas.drawLine(getWidth() * .40f, getHeight() * .12f, getWidth() * .60f, getHeight() * .12f, paint);
                canvas.drawLine(getWidth() * .60f, getHeight() * .12f, getWidth() * .70f, getHeight() * .28f, paint);
            } else if (type == ACCESSIBILITY) {
                canvas.drawCircle(cx, getHeight() * .16f, getWidth() * .10f, paint);
                canvas.drawLine(cx, getHeight() * .28f, cx, getHeight() * .63f, paint);
                canvas.drawLine(getWidth() * .20f, getHeight() * .35f, getWidth() * .80f, getHeight() * .35f, paint);
                canvas.drawLine(cx, getHeight() * .63f, getWidth() * .29f, getHeight() * .92f, paint);
                canvas.drawLine(cx, getHeight() * .63f, getWidth() * .71f, getHeight() * .92f, paint);
            } else if (type == SHIELD) {
                Path shield = new Path();
                shield.moveTo(cx, getHeight() * .08f);
                shield.lineTo(getWidth() * .84f, getHeight() * .25f);
                shield.lineTo(getWidth() * .74f, getHeight() * .68f);
                shield.lineTo(cx, getHeight() * .92f);
                shield.lineTo(getWidth() * .26f, getHeight() * .68f);
                shield.lineTo(getWidth() * .16f, getHeight() * .25f);
                shield.close();
                canvas.drawPath(shield, paint);
                canvas.drawLine(cx, getHeight() * .25f, cx, getHeight() * .72f, paint);
            } else if (type == ARROW_UP || type == ARROW_DOWN) {
                float y1 = type == ARROW_UP ? getHeight() * .78f : getHeight() * .22f;
                float y2 = type == ARROW_UP ? getHeight() * .22f : getHeight() * .78f;
                canvas.drawLine(cx, y1, cx, y2, paint);
                canvas.drawLine(cx, y2, cx - getWidth() * .22f, y2 + (type == ARROW_UP ? getHeight() * .20f : -getHeight() * .20f), paint);
                canvas.drawLine(cx, y2, cx + getWidth() * .22f, y2 + (type == ARROW_UP ? getHeight() * .20f : -getHeight() * .20f), paint);
            } else {
                float x1 = type == ARROW_RIGHT ? getWidth() * .22f : getWidth() * .78f;
                float x2 = type == ARROW_RIGHT ? getWidth() * .78f : getWidth() * .22f;
                canvas.drawLine(x1, cy, x2, cy, paint);
                canvas.drawLine(x2, cy, x2 + (type == ARROW_RIGHT ? -getWidth() * .20f : getWidth() * .20f),
                        cy - getHeight() * .22f, paint);
                canvas.drawLine(x2, cy, x2 + (type == ARROW_RIGHT ? -getWidth() * .20f : getWidth() * .20f),
                        cy + getHeight() * .22f, paint);
            }
        }
    }

    static class CalibrationPreview extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private String state = "正在启动前摄…";
        private boolean liveMode;

        CalibrationPreview(Context context) {
            super(context);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            setContentDescription("手势校准预览");
        }

        void setState(String nextState) {
            state = nextState == null ? "等待手掌" : nextState;
            invalidate();
        }

        void setLiveMode(boolean enabled) {
            liveMode = enabled;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth();
            float h = getHeight();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(liveMode ? Color.argb(52, 10, 20, 30) : Color.rgb(23, 33, 43));
            c.drawRoundRect(new RectF(0, 0, w, h), 24, 24, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(3);
            paint.setColor(Color.argb(210, 255, 255, 255));
            c.drawRoundRect(new RectF(24, 24, w - 24, h - 24), 20, 20, paint);

            boolean down = state.contains("向下") || state.contains("下挥") || state.contains("上一个");
            boolean locked = state.contains("方向已锁定") || state.contains("已识别");
            int arrowColor = down ? Color.rgb(234, 88, 12) : (locked ? Color.rgb(37, 99, 235) : Color.rgb(34, 197, 94));
            paint.setStrokeWidth(4);
            paint.setColor(arrowColor);
            float arrowTop = down ? h * .78f : h * .22f;
            float arrowBottom = down ? h * .22f : h * .78f;
            c.drawLine(w / 2, arrowTop, w / 2, arrowBottom, paint);
            c.drawLine(w / 2, arrowBottom, w / 2 - 10, arrowBottom + (down ? -10 : 10), paint);
            c.drawLine(w / 2, arrowBottom, w / 2 + 10, arrowBottom + (down ? -10 : 10), paint);

            if (state.contains("未检测到手") || state.contains("未检测到中指")
                    || state.contains("等待手掌") || state.contains("等待中指")) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(3);
                paint.setColor(Color.rgb(148, 163, 184));
                c.drawCircle(w / 2, h * .53f, 30, paint);
            }

            paint.setStrokeWidth(2.5f);
            paint.setColor(Color.rgb(96, 165, 250));
            float cx = w / 2;
            float base = h * .72f;
            float[] points = {
                    cx, base, cx - 34, base - 16, cx - 52, base - 48,
                    cx - 67, base - 94, cx - 62, base - 132,
                    cx - 32, base - 82, cx - 18, base - 142, cx - 4, base - 178,
                    cx + 7, base - 144, cx + 12, base - 84, cx + 34, base - 124,
                    cx + 48, base - 165, cx + 60, base - 136, cx + 48, base - 74,
                    cx + 66, base - 95, cx + 78, base - 65, cx + 62, base - 36
            };
            for (int i = 0; i < points.length; i += 2) c.drawCircle(points[i], points[i + 1], 4, paint);
            paint.setStrokeWidth(2);
            c.drawLine(cx, base, cx - 32, base - 82, paint);
            c.drawLine(cx, base, cx + 12, base - 84, paint);
            c.drawLine(cx - 32, base - 82, cx - 18, base - 142, paint);
            c.drawLine(cx + 12, base - 84, cx + 48, base - 124, paint);

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.WHITE);
            paint.setTextSize(15);
            paint.setTypeface(Typeface.DEFAULT_BOLD);
            String display = state;
            if (display.length() > 18) display = display.substring(0, 18) + "…";
            c.drawText(display, 24, h - 28, paint);
        }
    }
}
