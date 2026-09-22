package com.example.gesturefeed;

import android.media.Image;
import android.util.Log;

import java.nio.ByteBuffer;

/**
 * Dependency-free first-pass hand motion tracker.
 *
 * The extractor keeps only the largest plausible skin-colour blob, then a
 * temporal state machine requires a stable palm before it can lock a
 * direction. The extractor is deliberately isolated so a MediaPipe
 * 21-landmark implementation can replace it without changing the service or
 * accessibility layers.
 */
public final class HandMotionAnalyzer {
    private static final String TAG = "GestureFeed";
    /** Require the hand to disappear before accepting another trajectory. */
    private static final long HAND_RELEASE_REQUIRED_MS = 550L;
    public interface Listener {
        void onGesture(boolean upward);
        void onState(String state);
    }

    private enum Phase { IDLE, READY, TRACKING, COOLDOWN }

    private final Listener listener;
    private Phase phase = Phase.IDLE;
    private long readyAt;
    private long trackingAt;
    private long cooldownUntil;
    private long lastFeatureAt;
    private long lastNoHandStateAt;
    private long lastMotionAt;
    /** True after a gesture until the hand has really left the camera view. */
    private boolean awaitHandRelease;
    private long handReleaseSince;
    private long visibleStableSince;
    private boolean releaseHasPoint;
    private float releaseLastX;
    private float releaseLastY;
    private float baselineX;
    private float baselineY;
    private float smoothX;
    private float smoothY;
    private boolean directionLocked;
    private boolean lockedUpward;
    private int directionFrames;
    private boolean lastStateReady;
    private boolean hasLastFeature;
    private float lastFeatureX;
    private float lastFeatureY;
    private int stableFrames;
    private int rotationDegrees;

    public HandMotionAnalyzer(Listener listener) {
        this.listener = listener;
    }

    public synchronized void reset() {
        phase = Phase.IDLE;
        directionLocked = false;
        lockedUpward = false;
        directionFrames = 0;
        lastStateReady = false;
        hasLastFeature = false;
        stableFrames = 0;
        lastFeatureAt = 0L;
        lastMotionAt = 0L;
        awaitHandRelease = false;
        handReleaseSince = 0L;
        visibleStableSince = 0L;
        releaseHasPoint = false;
        lastNoHandStateAt = System.currentTimeMillis();
        listener.onState("未检测到手");
    }

    /** Maps sensor-buffer coordinates into the portrait display coordinate space. */
    public synchronized void setRotationDegrees(int degrees) {
        rotationDegrees = ((degrees % 360) + 360) % 360;
    }

    public synchronized void analyze(Image image) {
        if (image == null || image.getPlanes().length < 3) return;
        long fastNow = System.currentTimeMillis();
        Feature fastFeature = extract(image);
        if (fastFeature != null) fastFeature = rotate(fastFeature);
        if (fastPathEnabled()) {
            processFast(fastFeature, fastNow);
            return;
        }
        long now = System.currentTimeMillis();
        Feature feature = extract(image);
        if (feature != null) feature = rotate(feature);
        if (feature == null) {
            if (now - lastFeatureAt > 1300L) hasLastFeature = false;
            if (phase != Phase.COOLDOWN || now > cooldownUntil) {
                phase = Phase.IDLE;
                directionLocked = false;
                directionFrames = 0;
                stableFrames = 0;
                if (lastStateReady || now - lastNoHandStateAt >= 1000L) {
                    lastStateReady = false;
                    lastNoHandStateAt = now;
                    listener.onState("未检测到手");
                }
            }
            return;
        }

        lastFeatureAt = now;
        hasLastFeature = true;
        lastFeatureX = feature.x;
        lastFeatureY = feature.y;

        if (phase == Phase.COOLDOWN) {
            if (now < cooldownUntil) return;
            // Re-arm from the current palm position. Requiring the hand to
            // return to the original baseline made the second alternating
            // gesture disappear when the user kept the hand at the endpoint.
            phase = Phase.IDLE;
            directionLocked = false;
            directionFrames = 0;
            stableFrames = 0;
            listener.onState("准备下一次");
        }

        if (phase == Phase.IDLE) {
            smoothX = feature.x;
            smoothY = feature.y;
            baselineX = feature.x;
            baselineY = feature.y;
            readyAt = now;
            stableFrames = 1;
            phase = Phase.READY;
            lastStateReady = true;
            listener.onState("发现手掌 · 稳定中");
            return;
        }

        float previousX = smoothX;
        float previousY = smoothY;
        float alpha = phase == Phase.TRACKING ? 0.24f : 0.18f;
        smoothX = smoothX * (1f - alpha) + feature.x * alpha;
        smoothY = smoothY * (1f - alpha) + feature.y * alpha;

        if (phase == Phase.READY) {
            float jitter = distance(feature.x, feature.y, previousX, previousY);
            if (jitter > 0.105f) {
                readyAt = now;
                stableFrames = 1;
                smoothX = feature.x;
                smoothY = feature.y;
                baselineX = feature.x;
                baselineY = feature.y;
                listener.onState("手掌不稳定 · 请保持");
                return;
            }
            stableFrames++;
            if (now - readyAt >= 320L && stableFrames >= 5) {
                baselineX = smoothX;
                baselineY = smoothY;
                trackingAt = now;
                phase = Phase.TRACKING;
                directionLocked = false;
                directionFrames = 0;
                listener.onState("等待挥动");
            }
            return;
        }

        if (phase != Phase.TRACKING) return;
        long elapsed = now - trackingAt;
        if (elapsed > 1100L) {
            phase = Phase.READY;
            readyAt = now;
            stableFrames = 1;
            baselineX = smoothX;
            baselineY = smoothY;
            directionLocked = false;
            directionFrames = 0;
            listener.onState("准备就绪");
            return;
        }

        float dx = smoothX - baselineX;
        float dy = smoothY - baselineY;
        float vertical = Math.abs(dy);
        float horizontal = Math.abs(dx);
        if (!directionLocked && vertical >= 0.075f && vertical >= horizontal * 1.20f) {
            directionLocked = true;
            lockedUpward = dy < 0;
            directionFrames = 1;
            listener.onState(dy < 0 ? "方向已锁定 · 向上" : "方向已锁定 · 向下");
        }
        if (directionLocked) {
            if ((dy < 0) != lockedUpward) {
                directionLocked = false;
                directionFrames = 0;
                return;
            }
            directionFrames++;
        }
        if (!directionLocked || directionFrames < 3 || vertical < 0.19f || vertical < horizontal * 1.55f) return;

        boolean upward = dy < 0;
        phase = Phase.COOLDOWN;
        cooldownUntil = now + 900L;
        lastStateReady = false;
        listener.onState(upward ? "已识别 · 下一个视频" : "已识别 · 上一个视频");
        listener.onGesture(upward);
    }

    /** Feeds a normalized palm point from a landmark model into the same state machine. */
    public synchronized void analyzeLandmarks(float x, float y, float confidence, long timestamp) {
        long now = timestamp > 0L ? timestamp : System.currentTimeMillis();
        Feature feature = confidence >= 0.45f ? new Feature(clamp01(x), clamp01(y)) : null;
        if (fastPathEnabled()) {
            processFast(feature, now);
            return;
        }
        if (feature == null) {
            if (now - lastFeatureAt > 1300L) hasLastFeature = false;
            if (phase != Phase.COOLDOWN || now > cooldownUntil) {
                phase = Phase.IDLE;
                directionLocked = false;
                directionFrames = 0;
                stableFrames = 0;
                if (lastStateReady || now - lastNoHandStateAt >= 1000L) {
                    lastStateReady = false;
                    lastNoHandStateAt = now;
                    listener.onState("未检测到手");
                }
            }
            return;
        }
        lastFeatureAt = now;
        hasLastFeature = true;
        lastFeatureX = feature.x;
        lastFeatureY = feature.y;
        if (phase == Phase.COOLDOWN) {
            if (now < cooldownUntil) return;
            // Re-arm from the current palm position; do not wait for a
            // return to the first gesture's baseline.
            phase = Phase.IDLE;
            directionLocked = false;
            directionFrames = 0;
            stableFrames = 0;
            listener.onState("准备下一次");
        }
        if (phase == Phase.IDLE) {
            smoothX = feature.x;
            smoothY = feature.y;
            baselineX = feature.x;
            baselineY = feature.y;
            readyAt = now;
            stableFrames = 1;
            phase = Phase.READY;
            lastStateReady = true;
            listener.onState("发现手掌 · 稳定中");
            return;
        }
        float previousX = smoothX;
        float previousY = smoothY;
        float alpha = phase == Phase.TRACKING ? 0.24f : 0.18f;
        smoothX = smoothX * (1f - alpha) + feature.x * alpha;
        smoothY = smoothY * (1f - alpha) + feature.y * alpha;
        if (phase == Phase.READY) {
            float jitter = distance(feature.x, feature.y, previousX, previousY);
            if (jitter > 0.105f) {
                readyAt = now;
                stableFrames = 1;
                smoothX = feature.x;
                smoothY = feature.y;
                baselineX = feature.x;
                baselineY = feature.y;
                listener.onState("手掌不稳定 · 请保持");
                return;
            }
            stableFrames++;
            if (now - readyAt >= 320L && stableFrames >= 5) {
                baselineX = smoothX;
                baselineY = smoothY;
                trackingAt = now;
                phase = Phase.TRACKING;
                directionLocked = false;
                directionFrames = 0;
                listener.onState("等待挥动");
            }
            return;
        }
        if (phase != Phase.TRACKING) return;
        long elapsed = now - trackingAt;
        if (elapsed > 1100L) {
            phase = Phase.READY;
            readyAt = now;
            stableFrames = 1;
            baselineX = smoothX;
            baselineY = smoothY;
            directionLocked = false;
            directionFrames = 0;
            listener.onState("准备就绪");
            return;
        }
        float dx = smoothX - baselineX;
        float dy = smoothY - baselineY;
        float vertical = Math.abs(dy);
        float horizontal = Math.abs(dx);
        if (!directionLocked && vertical >= 0.075f && vertical >= horizontal * 1.20f) {
            directionLocked = true;
            lockedUpward = dy < 0;
            directionFrames = 1;
            listener.onState(dy < 0 ? "方向已锁定 · 向上" : "方向已锁定 · 向下");
        }
        if (directionLocked) {
            if ((dy < 0) != lockedUpward) {
                directionLocked = false;
                directionFrames = 0;
                return;
            }
            directionFrames++;
        }
        if (!directionLocked || directionFrames < 3 || vertical < 0.19f || vertical < horizontal * 1.55f) return;
        boolean upward = dy < 0;
        phase = Phase.COOLDOWN;
        cooldownUntil = now + 900L;
        lastStateReady = false;
        listener.onState(upward ? "已识别 · 下一个视频" : "已识别 · 上一个视频");
        listener.onGesture(upward);
    }

    /**
     * Fast trajectory path used by the landmark model. It deliberately does
     * not require a stationary "定位" phase: the first visible palm becomes
     * the baseline and a short, clearly vertical trajectory triggers directly.
     */
    private void processFast(Feature feature, long now) {
        // The downward/upward movement that follows a completed swipe is
        // usually just the user's hand leaving the camera. Do not re-arm on
        // that return path: require a genuine no-hand interval first.
        if (awaitHandRelease) {
            if (feature == null) {
                if (handReleaseSince == 0L) handReleaseSince = now;
                if (now >= cooldownUntil && now - handReleaseSince >= HAND_RELEASE_REQUIRED_MS) {
                    rearmAfterRelease(now);
                }
            } else {
                // Any visible hand cancels the release timer. Movement while
                // waiting is intentionally ignored, including the opposite
                // direction caused by releasing the first gesture.
                handReleaseSince = 0L;
                lastFeatureAt = now;
                lastFeatureX = feature.x;
                lastFeatureY = feature.y;
                float releaseMotion = releaseHasPoint
                        ? distance(feature.x, feature.y, releaseLastX, releaseLastY) : 0f;
                releaseLastX = feature.x;
                releaseLastY = feature.y;
                releaseHasPoint = true;
                if (releaseMotion <= 0.045f) {
                    if (visibleStableSince == 0L) visibleStableSince = now;
                    // Keep the detector locked while the hand is visible.
                    // A stable endpoint is not a release: re-arming here can
                    // interpret the user's hand drop/return as the opposite
                    // swipe and make the video jump back immediately.
                } else {
                    visibleStableSince = 0L;
                }
            }
            return;
        }

        if (feature == null) {
            if (now - lastFeatureAt > 450L) {
                phase = Phase.IDLE;
                directionLocked = false;
                directionFrames = 0;
                if (lastStateReady || now - lastNoHandStateAt >= 700L) {
                    lastStateReady = false;
                    lastNoHandStateAt = now;
                    listener.onState("未检测到手");
                }
            }
            return;
        }

        lastFeatureAt = now;
        hasLastFeature = true;
        lastFeatureX = feature.x;
        lastFeatureY = feature.y;

        if (phase == Phase.COOLDOWN) {
            if (now < cooldownUntil) return;
            phase = Phase.IDLE;
            directionLocked = false;
            directionFrames = 0;
            stableFrames = 0;
            lastMotionAt = now;
            listener.onState("准备下一次");
        }

        if (phase == Phase.IDLE) {
            smoothX = feature.x;
            smoothY = feature.y;
            baselineX = feature.x;
            baselineY = feature.y;
            lastMotionAt = now;
            phase = Phase.TRACKING;
            directionLocked = false;
            directionFrames = 0;
            lastStateReady = true;
            listener.onState("发现手掌 · 可直接挥动");
            return;
        }

        float previousX = smoothX;
        float previousY = smoothY;
        // Higher response keeps a quick hand flash from being averaged away.
        float alpha = 0.40f;
        smoothX = smoothX * (1f - alpha) + feature.x * alpha;
        smoothY = smoothY * (1f - alpha) + feature.y * alpha;

        float dx = smoothX - baselineX;
        float dy = smoothY - baselineY;
        float vertical = Math.abs(dy);
        float horizontal = Math.abs(dx);
        float rawDx = feature.x - previousX;
        float rawDy = feature.y - previousY;
        float rawVertical = Math.abs(rawDy);
        float rawHorizontal = Math.abs(rawDx);
        float frameMotion = distance(feature.x, feature.y, previousX, previousY);
        // MediaPipe palm points are normalized to the half-resolution frame;
        // a deliberate but smooth wave can move less than 3.5% per frame.
        // Keep the motion clock alive for smaller steps so the re-anchor
        // timer cannot erase a slow, valid swipe.
        if (frameMotion >= 0.012f) lastMotionAt = now;

        // A stationary palm can remain in view indefinitely. Re-anchor it
        // quietly after a pause so the next flash is measured from its latest
        // position instead of expiring into the old READY state.
        if (now - lastMotionAt >= 750L) {
            baselineX = smoothX;
            baselineY = smoothY;
            directionLocked = false;
            directionFrames = 0;
            return;
        }

        if (!directionLocked && vertical >= 0.055f && vertical >= horizontal * 1.20f) {
            directionLocked = true;
            lockedUpward = dy < 0;
            directionFrames = 1;
            listener.onState(dy < 0 ? "方向已锁定 · 向上" : "方向已锁定 · 向下");
        } else if (directionLocked) {
            if ((dy < 0) != lockedUpward) {
                directionLocked = false;
                directionFrames = 0;
                return;
            }
            directionFrames++;
        }

        // A very quick flash may only be visible in one tracked frame. Allow
        // that strong vertical impulse through immediately; ordinary motion
        // still needs two consistent frames to reject camera noise.
        boolean strongImpulse = rawVertical >= 0.08f
                && rawVertical >= rawHorizontal * 1.25f;
        float requiredVertical = strongImpulse ? 0.055f : 0.085f;
        float requiredAxisRatio = strongImpulse ? 1.10f : 1.20f;
        if (!directionLocked || (!strongImpulse && directionFrames < 2)
                || vertical < requiredVertical || vertical < horizontal * requiredAxisRatio) return;

        boolean upward = lockedUpward;
        phase = Phase.COOLDOWN;
        cooldownUntil = now + 700L;
        awaitHandRelease = true;
        handReleaseSince = 0L;
        lastStateReady = false;
        Log.d(TAG, "gesture detected upward=" + upward + " vertical=" + vertical
                + " rawVertical=" + rawVertical + " strongImpulse=" + strongImpulse);
        listener.onState(upward ? "已识别 · 下一个视频 · 等待放手" : "已识别 · 上一个视频 · 等待放手");
        listener.onGesture(upward);
    }

    private void rearmAfterRelease(long now) {
        awaitHandRelease = false;
        handReleaseSince = 0L;
        visibleStableSince = 0L;
        releaseHasPoint = false;
        phase = Phase.IDLE;
        directionLocked = false;
        directionFrames = 0;
        stableFrames = 0;
        lastMotionAt = now;
        lastStateReady = false;
        listener.onState("已重新准备 · 请再次挥动");
    }

    // Kept as a method (rather than a compile-time constant) so the old
    // chroma fallback remains available for diagnosis without affecting the
    // MediaPipe path used in production.
    private boolean fastPathEnabled() {
        return true;
    }

    private Feature extract(Image image) {
        int width = image.getWidth();
        int height = image.getHeight();
        Image.Plane yPlane = image.getPlanes()[0];
        Image.Plane uPlane = image.getPlanes()[1];
        Image.Plane vPlane = image.getPlanes()[2];
        ByteBuffer yBuffer = yPlane.getBuffer();
        ByteBuffer uBuffer = uPlane.getBuffer();
        ByteBuffer vBuffer = vPlane.getBuffer();
        int yRowStride = yPlane.getRowStride();
        int yPixelStride = yPlane.getPixelStride();
        int uRowStride = uPlane.getRowStride();
        int uPixelStride = uPlane.getPixelStride();
        int vRowStride = vPlane.getRowStride();
        int vPixelStride = vPlane.getPixelStride();

        int step = Math.max(7, Math.min(width, height) / 64);
        int gridW = (width + step - 1) / step;
        int gridH = (height + step - 1) / step;
        int gridSize = gridW * gridH;
        boolean[] skin = new boolean[gridSize];
        float[] weights = new float[gridSize];

        for (int gy = 0; gy < gridH; gy++) {
            int y = Math.min(height - 1, gy * step + step / 2);
            for (int gx = 0; gx < gridW; gx++) {
                int x = Math.min(width - 1, gx * step + step / 2);
                int yi = y * yRowStride + x * yPixelStride;
                int uvX = x / 2;
                int uvY = y / 2;
                int ui = uvY * uRowStride + uvX * uPixelStride;
                int vi = uvY * vRowStride + uvX * vPixelStride;
                if (yi < 0 || yi >= yBuffer.limit() || ui < 0 || ui >= uBuffer.limit()
                        || vi < 0 || vi >= vBuffer.limit()) continue;
                int yv = yBuffer.get(yi) & 0xff;
                int u = (uBuffer.get(ui) & 0xff) - 128;
                int v = (vBuffer.get(vi) & 0xff) - 128;
                float yy = Math.max(0, yv - 16) * 1.164f;
                int r = clamp((int) (yy + 1.596f * v));
                int g = clamp((int) (yy - 0.392f * u - 0.813f * v));
                int b = clamp((int) (yy + 2.017f * u));

                // Chroma and channel ordering reject most beige/grey room
                // backgrounds while still accepting darker skin tones.
                if (r < 45 || g < 20 || b < 12 || r <= g + 7 || g <= b + 3
                        || r - b < 20 || r - g > 105 || g - b > 85) continue;
                int index = gy * gridW + gx;
                skin[index] = true;
                weights[index] = Math.max(0.2f, (r - g) / 45f + (g - b) / 35f);
            }
        }

        boolean[] visited = new boolean[gridSize];
        int[] queue = new int[gridSize];
        Component best = null;
        for (int start = 0; start < gridSize; start++) {
            if (!skin[start] || visited[start]) continue;
            Component component = new Component();
            int head = 0;
            int tail = 0;
            queue[tail++] = start;
            visited[start] = true;
            while (head < tail) {
                int index = queue[head++];
                int gx = index % gridW;
                int gy = index / gridW;
                component.count++;
                component.weight += weights[index];
                component.sumX += (gx * step + step / 2f) / width * weights[index];
                component.sumY += (gy * step + step / 2f) / height * weights[index];
                component.minX = Math.min(component.minX, gx);
                component.maxX = Math.max(component.maxX, gx);
                component.minY = Math.min(component.minY, gy);
                component.maxY = Math.max(component.maxY, gy);
                if (gx > 0) {
                    int ni = index - 1;
                    if (skin[ni] && !visited[ni]) {
                        visited[ni] = true;
                        queue[tail++] = ni;
                    }
                }
                if (gx + 1 < gridW) {
                    int ni = index + 1;
                    if (skin[ni] && !visited[ni]) {
                        visited[ni] = true;
                        queue[tail++] = ni;
                    }
                }
                if (gy > 0) {
                    int ni = index - gridW;
                    if (skin[ni] && !visited[ni]) {
                        visited[ni] = true;
                        queue[tail++] = ni;
                    }
                }
                if (gy + 1 < gridH) {
                    int ni = index + gridW;
                    if (skin[ni] && !visited[ni]) {
                        visited[ni] = true;
                        queue[tail++] = ni;
                    }
                }
            }

            float boxW = (component.maxX - component.minX + 1f) * step / width;
            float boxH = (component.maxY - component.minY + 1f) * step / height;
            float boxArea = boxW * boxH;
            float occupied = component.count * step * step / (float) (width * height);
            float ratio = boxW / Math.max(0.001f, boxH);
            if (component.count < 8 || boxArea < 0.006f || boxArea > 0.55f
                    || occupied < 0.003f || ratio < 0.22f || ratio > 3.8f) continue;
            float centerX = component.sumX / component.weight;
            float centerY = component.sumY / component.weight;
            float compactness = occupied / Math.max(0.001f, boxArea);
            float proximity = hasLastFeature
                    ? 1f / (1f + distance(centerX, centerY, lastFeatureX, lastFeatureY) * 4f)
                    : 1f;
            component.score = component.weight * (0.55f + compactness) * proximity;
            component.centerX = centerX;
            component.centerY = centerY;
            if (best == null || component.score > best.score) best = component;
        }

        if (best == null || best.centerX < 0.06f || best.centerX > 0.94f
                || best.centerY < 0.04f || best.centerY > 0.96f) return null;
        return new Feature(best.centerX, best.centerY);
    }

    private Feature rotate(Feature feature) {
        switch (rotationDegrees) {
            case 90:
                return new Feature(1f - feature.y, feature.x);
            case 180:
                return new Feature(1f - feature.x, 1f - feature.y);
            case 270:
                return new Feature(feature.y, 1f - feature.x);
            default:
                return feature;
        }
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static float distance(float x1, float y1, float x2, float y2) {
        return (float) Math.hypot(x1 - x2, y1 - y2);
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static final class Feature {
        final float x;
        final float y;

        Feature(float x, float y) {
            this.x = x;
            this.y = y;
        }
    }

    private static final class Component {
        int count;
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        float weight;
        float sumX;
        float sumY;
        float score;
        float centerX;
        float centerY;
    }
}
