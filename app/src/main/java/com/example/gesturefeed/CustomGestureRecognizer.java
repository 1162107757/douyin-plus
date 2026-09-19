package com.example.gesturefeed;

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/** Matches a completed hand-motion sequence against the user's four templates. */
public final class CustomGestureRecognizer implements MediaPipeHandLandmarker.FrameListener {
    public interface Listener {
        void onGesture(ControlDirection direction);
        void onState(String state);
    }

    private static final String TAG = "GestureFeed";
    private static final long RELEASE_MS = 140L;
    private static final long COOLDOWN_MS = 650L;
    private static final long MAX_SEQUENCE_MS = 1400L;
    private static final int MIN_TEMPLATE_COUNT = 1;
    private static final int MIN_MOTION_FRAMES = 6;
    private static final float MOTION_THRESHOLD = 0.12f;
    private static final float DIRECTION_DOMINANCE = 1.12f;
    private static final float MATCH_THRESHOLD = 0.90f;

    private final FeatureTemplateStore store;
    private final Listener listener;
    private final List<float[]> frames = new ArrayList<>();
    private long sequenceStartedAt;
    private long noHandSince;
    private long cooldownUntil;
    private boolean waitingRelease;

    public CustomGestureRecognizer(FeatureTemplateStore store, Listener listener) {
        this.store = store;
        this.listener = listener;
    }

    public synchronized void reset() {
        frames.clear();
        sequenceStartedAt = 0L;
        noHandSince = 0L;
        cooldownUntil = 0L;
        waitingRelease = false;
    }

    @Override
    public synchronized void onFrame(List<NormalizedLandmark> landmarks, long timestamp,
                                     int rotationDegrees) {
        long now = timestamp > 0L ? timestamp : System.currentTimeMillis();
        float[] feature = GestureFeatureExtractor.fromLandmarks(landmarks, rotationDegrees);
        if (feature != null) {
            noHandSince = 0L;
            if (waitingRelease) return;
            if (frames.isEmpty()) {
                sequenceStartedAt = now;
                listener.onState("自定义手势 · 采集中");
            }
            frames.add(feature);
            // Do not wait for the hand to disappear. A short, decisive
            // trajectory should fire while the hand is still visible.
            if (frames.size() >= MIN_MOTION_FRAMES && now - sequenceStartedAt >= 120L) {
                ControlDirection motionDirection = detectMotionDirection();
                if (motionDirection != null) {
                    finish(now, motionDirection);
                    return;
                }
            }
            if (now - sequenceStartedAt >= MAX_SEQUENCE_MS) finish(now, detectMotionDirection());
            return;
        }

        if (frames.isEmpty()) {
            if (waitingRelease && now >= cooldownUntil) {
                waitingRelease = false;
                listener.onState("自定义手势 · 准备下一次");
            }
            return;
        }
        if (noHandSince == 0L) noHandSince = now;
        if (now - noHandSince >= RELEASE_MS) finish(now, detectMotionDirection());
    }

    private void finish(long now) {
        finish(now, null);
    }

    private void finish(long now, ControlDirection motionDirection) {
        if (frames.isEmpty()) return;
        List<float[]> copy = new ArrayList<>(frames);
        frames.clear();
        sequenceStartedAt = 0L;
        float[] sequence = GestureFeatureExtractor.prepareSequence(copy);
        if (sequence == null) {
            listener.onState("自定义手势 · 动作太短，请重新录入");
            return;
        }
        ControlDirection bestDirection = null;
        float bestDistance = Float.MAX_VALUE;
        int templateCount = 0;
        for (ControlDirection direction : ControlDirection.values()) {
            for (float[] template : store.load(direction)) {
                templateCount++;
                float distance = GestureFeatureExtractor.distance(sequence, template);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    bestDirection = direction;
                }
            }
        }
        if (templateCount < MIN_TEMPLATE_COUNT) {
            listener.onState("自定义手势 · 请先完成至少一次方向学习");
            return;
        }
        // Prefer a learned template when it is a close match. If the user
        // performs the same direction with a different speed/amplitude,
        // fall back to the clear trajectory direction instead of dropping it.
        ControlDirection direction = bestDistance <= MATCH_THRESHOLD
                ? bestDirection : motionDirection;
        if (direction == null || now < cooldownUntil) {
            listener.onState("自定义手势 · 未匹配，请动作更明显");
            waitingRelease = false;
            return;
        }
        waitingRelease = true;
        cooldownUntil = now + COOLDOWN_MS;
        noHandSince = 0L;
        Log.d(TAG, "custom gesture matched direction=" + direction
                + " templateDistance=" + bestDistance + " frames=" + copy.size());
        listener.onState("已识别 · 自定义手势" + direction.getLabel());
        listener.onGesture(direction);
    }

    private ControlDirection detectMotionDirection() {
        if (frames.size() < MIN_MOTION_FRAMES) return null;
        float[] first = frames.get(0);
        float[] latest = frames.get(frames.size() - 1);
        float dx = latest[0] - first[0];
        float dy = latest[1] - first[1];
        float absX = Math.abs(dx);
        float absY = Math.abs(dy);
        if (Math.max(absX, absY) < MOTION_THRESHOLD) return null;
        if (absY >= absX * DIRECTION_DOMINANCE) {
            return dy < 0f ? ControlDirection.UP : ControlDirection.DOWN;
        }
        if (absX >= absY * DIRECTION_DOMINANCE) {
            return dx < 0f ? ControlDirection.LEFT : ControlDirection.RIGHT;
        }
        return null;
    }
}
