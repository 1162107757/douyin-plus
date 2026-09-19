package com.example.gesturefeed;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.TextUtils;
import android.util.Log;
import android.view.Surface;
import android.view.WindowManager;

import java.util.Arrays;

/** Foreground camera service that owns the local gesture-analysis pipeline. */
public class GestureControlService extends Service {
    public static final String ACTION_START = "com.example.gesturefeed.action.START";
    public static final String ACTION_CALIBRATE = "com.example.gesturefeed.action.CALIBRATE";
    public static final String ACTION_PAUSE = "com.example.gesturefeed.action.PAUSE";
    public static final String ACTION_STOP = "com.example.gesturefeed.action.STOP";
    public static final String ACTION_STATE_CHANGED = "com.example.gesturefeed.action.STATE_CHANGED";
    public static final String EXTRA_GESTURE_MODE = "gesture_mode";
    private static final String CHANNEL_ID = "gesture_control";
    private static final int NOTIFICATION_ID = 9101;
    private static final String TAG = "GestureFeed";

    private static volatile boolean running;
    private static volatile boolean paused;
    private static volatile boolean calibrationMode;
    private boolean calibrationSawUp;
    private boolean calibrationSawDown;
    private GestureMode gestureMode = GestureMode.PALM_SWING;

    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private MediaPipeHandLandmarker analyzer;
    private String cameraId;

    public static boolean isRunning() {
        return running;
    }

    public static boolean isPaused() {
        return paused;
    }

    public static boolean isCalibrationMode() {
        return calibrationMode;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        analyzer = new MediaPipeHandLandmarker(this, new MediaPipeHandLandmarker.Listener() {
            @Override
            public void onGesture(boolean upward) {
                if (calibrationMode) {
                    handleCalibrationGesture(upward);
                } else {
                    handleRecognizedGesture(upward);
                }
            }

            @Override
            public void onState(String state) {
                if (!paused) sendState(state);
            }
        });
        cameraThread = new HandlerThread("gesture-camera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (intent != null) {
            String modeValue = intent.getStringExtra(EXTRA_GESTURE_MODE);
            if (!TextUtils.isEmpty(modeValue)) {
                setGestureMode(GestureMode.fromStoredValue(modeValue));
            }
        }
        if (ACTION_STOP.equals(action)) {
            running = false;
            paused = false;
            calibrationMode = false;
            calibrationSawUp = false;
            calibrationSawDown = false;
            closeCamera();
            if (Build.VERSION.SDK_INT >= 24) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.cancelAll();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_PAUSE.equals(action) && running) {
            togglePause();
            return START_NOT_STICKY;
        }
        if (!running) {
            running = true;
            paused = false;
            calibrationMode = ACTION_CALIBRATE.equals(action);
            calibrationSawUp = false;
            calibrationSawDown = false;
            createNotificationChannel();
            Notification notification = buildNotification();
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            sendState(calibrationMode ? "校准中 · 启动前摄" : runningState());
            openFrontCamera();
        } else if (ACTION_CALIBRATE.equals(action)) {
            calibrationMode = true;
            calibrationSawUp = false;
            calibrationSawDown = false;
            analyzer.reset();
            sendState(calibrationState());
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification());
        }
        return START_NOT_STICKY;
    }

    private void setGestureMode(GestureMode mode) {
        gestureMode = mode == null ? GestureMode.PALM_SWING : mode;
        if (analyzer != null) analyzer.setMode(gestureMode);
    }

    private String runningState() {
        return gestureMode == GestureMode.TWO_FINGER_DIRECTION
                ? "运行中 · 等待双指指向"
                : gestureMode == GestureMode.MIDDLE_FINGER_DIRECTION
                ? "运行中 · 等待中指指向"
                : "运行中 · 等待挥动";
    }

    private void togglePause() {
        paused = !paused;
        analyzer.reset();
        if (paused) {
            closeCamera();
            sendState("已暂停");
        } else {
            sendState(runningState());
            openFrontCamera();
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification());
    }

    private void handleRecognizedGesture(boolean upward) {
        if (!running || paused) return;
        // AccessibilityService resolves whichever third-party app is currently
        // in the foreground; no video-app package is hard-coded here.
        boolean dispatched = GestureAccessibilityService.performSwipe(upward);
        Log.d(TAG, "recognized upward=" + upward + " dispatched=" + dispatched
                + " accessibilityConnected=" + GestureAccessibilityService.isConnected());
        if (dispatched) {
            Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= 26) {
                    vibrator.vibrate(VibrationEffect.createOneShot(28, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    vibrator.vibrate(28);
                }
            }
            sendState(upward ? "已识别 · 下一个视频" : "已识别 · 上一个视频");
        } else {
            sendState(gestureMode == GestureMode.TWO_FINGER_DIRECTION
                    ? "请先打开视频应用，再用双指指向"
                    : gestureMode == GestureMode.MIDDLE_FINGER_DIRECTION
                    ? "请先打开视频应用，再用中指指向"
                    : "请先打开视频应用");
        }
    }

    private void handleCalibrationGesture(boolean upward) {
        if (!running || paused) return;
        if (upward) {
            calibrationSawUp = true;
            sendState(gestureMode == GestureMode.TWO_FINGER_DIRECTION
                    ? "校准成功 · 双指向上"
                    : gestureMode == GestureMode.MIDDLE_FINGER_DIRECTION
                    ? "校准成功 · 中指向上" : "校准成功 · 上挥");
        } else {
            calibrationSawDown = true;
            sendState(gestureMode == GestureMode.TWO_FINGER_DIRECTION
                    ? "校准成功 · 双指向下"
                    : gestureMode == GestureMode.MIDDLE_FINGER_DIRECTION
                    ? "校准成功 · 中指向下" : "校准成功 · 下挥");
        }
        if (calibrationSawUp && calibrationSawDown) {
            sendState("校准完成");
        }
    }

    private void openFrontCamera() {
        if (cameraHandler == null || !running || paused) return;
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            sendState("相机权限未开启");
            stopSelf();
            return;
        }
        cameraHandler.post(() -> {
            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            if (manager == null) {
                sendState("相机不可用");
                return;
            }
            try {
                cameraId = findFrontCamera(manager);
                if (cameraId == null) {
                    sendState("未找到前置摄像头");
                    return;
                }
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
                Integer sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
                WindowManager windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
                int displayDegrees = windowManager == null ? 0 : windowManager.getDefaultDisplay().getRotation() * 90;
                int frameRotation = ((sensorOrientation == null ? 0 : sensorOrientation) - displayDegrees + 360) % 360;
                analyzer.setRotationDegrees(frameRotation);
                imageReader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 2);
                imageReader.setOnImageAvailableListener(reader -> {
                    Image image = null;
                    try {
                        image = reader.acquireLatestImage();
                        if (image != null && running && !paused) analyzer.analyze(image);
            } catch (Throwable t) {
                Log.w(TAG, "frame analysis failed", t);
                    } finally {
                        if (image != null) image.close();
                    }
                }, cameraHandler);
                manager.openCamera(cameraId, stateCallback, cameraHandler);
            } catch (SecurityException | CameraAccessException | IllegalArgumentException e) {
                Log.e(TAG, "Unable to open front camera", e);
                sendState("相机启动失败，请重试");
            }
        });
    }

    private String findFrontCamera(CameraManager manager) throws CameraAccessException {
        for (String id : manager.getCameraIdList()) {
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) return id;
        }
        return null;
    }

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            cameraDevice = camera;
            createCaptureSession();
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            camera.close();
            cameraDevice = null;
            sendState("相机已断开");
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            camera.close();
            cameraDevice = null;
            sendState("相机被占用或启动失败");
        }
    };

    private void createCaptureSession() {
        if (cameraDevice == null || imageReader == null) return;
        try {
            cameraDevice.createCaptureSession(Arrays.asList(imageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            captureSession = session;
                            try {
                                CaptureRequest.Builder request = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                request.addTarget(imageReader.getSurface());
                                request.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                                request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                                session.setRepeatingRequest(request.build(), null, cameraHandler);
                                sendState(calibrationMode ? calibrationState() : runningState());
                            } catch (CameraAccessException e) {
                                sendState("相机分析启动失败");
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            sendState("相机配置失败");
                        }
                    }, cameraHandler);
        } catch (CameraAccessException | IllegalStateException e) {
            Log.e(TAG, "create camera session failed", e);
            sendState("相机配置失败");
        }
    }

    private void closeCamera() {
        if (cameraHandler == null) return;
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
        });
    }

    private Notification buildNotification() {
        Intent pause = new Intent(this, GestureControlService.class).setAction(ACTION_PAUSE);
        Intent stop = new Intent(this, GestureControlService.class).setAction(ACTION_STOP);
        PendingIntent pausePending = PendingIntent.getService(this, 9102, pause,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stopPending = PendingIntent.getService(this, 9103, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String title;
        String text;
        if (calibrationMode) {
            title = paused ? "手势校准已暂停" : "手势校准中";
            text = paused ? "点击继续校准，或停止服务" : "前摄识别中 · 画面不保存";
        } else {
            title = paused ? "手势翻页已暂停" : "手势翻页运行中";
            text = paused ? "点击继续识别，或停止服务"
                    : gestureMode == GestureMode.TWO_FINGER_DIRECTION
                    ? "前摄运行中 · 双指指向"
                    : gestureMode == GestureMode.MIDDLE_FINGER_DIRECTION
                    ? "前摄运行中 · 中指指向"
                    : "前摄运行中 · 手掌挥动";
        }
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(null, paused ? "继续" : "暂停", pausePending).build())
                .addAction(new Notification.Action.Builder(null, "停止", stopPending).build())
                .build();
    }

    private String calibrationState() {
        return gestureMode == GestureMode.TWO_FINGER_DIRECTION
                ? "校准中 · 等待双指指向"
                : gestureMode == GestureMode.MIDDLE_FINGER_DIRECTION
                ? "校准中 · 等待中指指向"
                : "校准中 · 等待手掌";
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "手势翻页", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("前摄手势识别运行状态");
        manager.createNotificationChannel(channel);
    }

    private void sendState(String state) {
        Intent intent = new Intent(ACTION_STATE_CHANGED);
        intent.setPackage(getPackageName());
        intent.putExtra("state", state);
        sendBroadcast(intent);
    }

    @Override
    public void onDestroy() {
        running = false;
        paused = false;
        calibrationMode = false;
        calibrationSawUp = false;
        calibrationSawDown = false;
        if (analyzer != null) {
            analyzer.reset();
            analyzer.close();
            analyzer = null;
        }
        closeCamera();
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.cancelAll();
        if (cameraThread != null) {
            cameraThread.quitSafely();
            cameraThread = null;
        }
        sendState("已停止");
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // The user can still stop the service from the persistent notification.
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
