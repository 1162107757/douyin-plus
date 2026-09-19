package com.example.gesturefeed;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * The only cross-app control surface. It follows the current foreground
 * window, so the same gesture path works across video apps without coupling
 * the controller to a single package name.
 */
public class GestureAccessibilityService extends AccessibilityService {
    /** Known launchable video apps used by the optional shortcut in the UI. */
    public static final Set<String> VIDEO_APP_PACKAGES;
    static {
        Set<String> packages = new HashSet<>();
        packages.add("com.ss.android.ugc.aweme");       // 抖音
        packages.add("com.ss.android.ugc.aweme.lite");  // 抖音极速版
        packages.add("com.ss.android.article.video");  // 西瓜视频
        packages.add("com.smile.gifmaker");             // 快手
        packages.add("com.kuaishou.nebula");            // 快手极速版
        VIDEO_APP_PACKAGES = Collections.unmodifiableSet(packages);
    }

    private static volatile GestureAccessibilityService instance;
    /** Prevents a second dispatch while the previous swipe is still settling. */
    private static volatile boolean gestureInFlight;
    private static final String TAG = "GestureFeed";
    private volatile String activePackageName;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        activePackageName = null;
        gestureInFlight = false;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        activePackageName = String.valueOf(event.getPackageName());
    }

    @Override
    public void onInterrupt() {
        activePackageName = null;
        gestureInFlight = false;
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        if (instance == this) instance = null;
        activePackageName = null;
        gestureInFlight = false;
        return super.onUnbind(intent);
    }

    public static boolean isConnected() {
        return instance != null;
    }

    /**
     * @param upward true for screen-up (next video), false for screen-down (previous video)
     */
    public static boolean performSwipe(boolean upward) {
        GestureAccessibilityService service = instance;
        // dispatchGesture injects into the current foreground surface. Do not
        // gate it on a package-name lookup: several OEMs report the active
        // accessibility window as SystemUI while a video app is transitioning,
        // which used to drop an otherwise valid recognition.
        if (service == null) return false;
        return service.dispatchDirectionalSwipe(upward);
    }

    private boolean dispatchDirectionalSwipe(boolean upward) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;
        if (gestureInFlight) {
            Log.d(TAG, "ignore swipe while previous gesture is in flight");
            return false;
        }
        float width = getResources().getDisplayMetrics().widthPixels;
        float height = getResources().getDisplayMetrics().heightPixels;
        float x = width * 0.50f;
        float startY = upward ? height * 0.78f : height * 0.28f;
        float endY = upward ? height * 0.28f : height * 0.78f;
        Path path = new Path();
        path.moveTo(x, startY);
        path.lineTo(x, endY);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, 380);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        gestureInFlight = true;
        boolean dispatched = dispatchGesture(gesture, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                gestureInFlight = false;
                Log.d(TAG, upward ? "swipe up completed" : "swipe down completed");
                sendGestureState(upward ? "滑动已完成 · 下一个视频" : "滑动已完成 · 上一个视频");
                super.onCompleted(gestureDescription);
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                gestureInFlight = false;
                Log.w(TAG, upward ? "swipe up cancelled" : "swipe down cancelled");
                sendGestureState("滑动被系统取消，请保持视频应用在前台");
                super.onCancelled(gestureDescription);
            }
        }, mainHandler);
        // Some OEM accessibility implementations occasionally omit the
        // completion callback during an app transition. Do not let that leave
        // the one-shot guard permanently locked.
        mainHandler.postDelayed(() -> {
            if (gestureInFlight) {
                gestureInFlight = false;
                Log.w(TAG, "swipe callback timeout; re-arming gesture dispatch");
            }
        }, 1100L);
        Log.d(TAG, "dispatch swipe=" + upward + " accepted=" + dispatched
                + " target=" + activePackageName);
        if (!dispatched) {
            gestureInFlight = false;
            sendGestureState("滑动派发失败，请检查辅助功能服务");
        }
        return dispatched;
    }

    private void sendGestureState(String state) {
        android.content.Intent intent = new android.content.Intent(GestureControlService.ACTION_STATE_CHANGED);
        intent.setPackage(getPackageName());
        intent.putExtra("state", state);
        sendBroadcast(intent);
    }
}
