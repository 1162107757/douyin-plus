package com.example.gesturefeed;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
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
    /** Extra guard for recognizer jitter and the app's swipe animation settling. */
    private static volatile long nextDispatchAllowedAt;
    private static final long MIN_DISPATCH_INTERVAL_MS = 1100L;
    private static final String TAG = "GestureFeed";
    private volatile String activePackageName;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        activePackageName = null;
        gestureInFlight = false;
        nextDispatchAllowedAt = 0L;
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
        nextDispatchAllowedAt = 0L;
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        if (instance == this) instance = null;
        activePackageName = null;
        gestureInFlight = false;
        nextDispatchAllowedAt = 0L;
        return super.onUnbind(intent);
    }

    public static boolean isConnected() {
        return instance != null;
    }

    /**
     * @param upward true for screen-up (next video), false for screen-down (previous video)
     */
    public static boolean performSwipe(boolean upward) {
        return performSwipe(upward ? ControlDirection.UP : ControlDirection.DOWN);
    }

    public static boolean performSwipe(ControlDirection direction) {
        GestureAccessibilityService service = instance;
        // dispatchGesture injects into the current foreground surface. Do not
        // gate it on a package-name lookup: several OEMs report the active
        // accessibility window as SystemUI while a video app is transitioning,
        // which used to drop an otherwise valid recognition.
        if (service == null || direction == null) return false;
        return service.dispatchDirectionalSwipe(direction);
    }

    private boolean dispatchDirectionalSwipe(ControlDirection direction) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;
        long now = SystemClock.uptimeMillis();
        if (gestureInFlight || now < nextDispatchAllowedAt) {
            Log.d(TAG, "ignore swipe while previous gesture is settling"
                    + " inFlight=" + gestureInFlight
                    + " remainingMs=" + Math.max(0L, nextDispatchAllowedAt - now));
            return false;
        }
        float width = getResources().getDisplayMetrics().widthPixels;
        float height = getResources().getDisplayMetrics().heightPixels;
        boolean vertical = direction == ControlDirection.UP || direction == ControlDirection.DOWN;
        boolean positive = direction == ControlDirection.UP || direction == ControlDirection.RIGHT;
        float startX = vertical ? width * 0.50f : (positive ? width * 0.25f : width * 0.75f);
        float endX = vertical ? startX : (positive ? width * 0.75f : width * 0.25f);
        float startY = vertical ? (positive ? height * 0.78f : height * 0.28f) : height * 0.50f;
        float endY = vertical ? (positive ? height * 0.28f : height * 0.78f) : startY;
        Path path = new Path();
        path.moveTo(startX, startY);
        path.lineTo(endX, endY);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, 380);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        gestureInFlight = true;
        nextDispatchAllowedAt = now + MIN_DISPATCH_INTERVAL_MS;
        boolean dispatched = dispatchGesture(gesture, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                gestureInFlight = false;
                Log.d(TAG, "swipe " + direction.name().toLowerCase() + " completed");
                sendGestureState("滑动已完成 · " + direction.getAction());
                super.onCompleted(gestureDescription);
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                gestureInFlight = false;
                Log.w(TAG, "swipe " + direction.name().toLowerCase() + " cancelled");
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
        Log.d(TAG, "dispatch swipe=" + direction + " accepted=" + dispatched
                + " target=" + activePackageName);
        if (!dispatched) {
            gestureInFlight = false;
            nextDispatchAllowedAt = 0L;
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
