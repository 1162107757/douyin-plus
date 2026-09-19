package com.example.gesturefeed;

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;

import java.util.List;

/**
 * Recognizes a single middle-finger pointing pose. The index, ring and little
 * fingers must stay folded, while the middle finger points clearly up or
 * down. A short stability window and release guard prevent duplicate turns
 * while the finger is held in place.
 */
public final class MiddleFingerDirectionAnalyzer {
    public interface Listener {
        void onGesture(boolean upward);
        void onState(String state);
    }

    private final Listener listener;
    private int rotationDegrees;
    private int candidateDirection;
    private int stableFrames;
    private long lastPoseAt;
    private long lastNoPoseStateAt;
    private long noPoseSince;
    private long cooldownUntil;
    private boolean waitingRelease;

    public MiddleFingerDirectionAnalyzer(Listener listener) {
        this.listener = listener;
    }

    public synchronized void setRotationDegrees(int degrees) {
        rotationDegrees = ((degrees % 360) + 360) % 360;
    }

    public synchronized void reset() {
        candidateDirection = 0;
        stableFrames = 0;
        lastPoseAt = 0L;
        lastNoPoseStateAt = 0L;
        noPoseSince = 0L;
        cooldownUntil = 0L;
        waitingRelease = false;
        listener.onState("中指模式 · 未检测到中指");
    }

    public synchronized void analyze(List<NormalizedLandmark> landmarks, long timestamp) {
        long now = timestamp > 0L ? timestamp : System.currentTimeMillis();
        Pose pose = detectPose(landmarks);
        if (pose == null) {
            handlePoseMissing(now);
            return;
        }

        lastPoseAt = now;
        noPoseSince = 0L;
        if (waitingRelease) return;

        if (pose.direction != candidateDirection) {
            candidateDirection = pose.direction;
            stableFrames = 1;
            listener.onState(pose.upward ? "中指方向稳定中 · 向上" : "中指方向稳定中 · 向下");
        } else {
            stableFrames++;
        }

        if (stableFrames < 4 || now < cooldownUntil) return;
        waitingRelease = true;
        cooldownUntil = now + 700L;
        candidateDirection = 0;
        stableFrames = 0;
        listener.onState(pose.upward ? "已识别 · 中指向上" : "已识别 · 中指向下");
        listener.onGesture(pose.upward);
    }

    private void handlePoseMissing(long now) {
        candidateDirection = 0;
        stableFrames = 0;
        if (!waitingRelease) {
            if (now - lastPoseAt >= 650L && now - lastNoPoseStateAt >= 900L) {
                lastNoPoseStateAt = now;
                listener.onState("中指模式 · 未检测到中指");
            }
            return;
        }
        if (noPoseSince == 0L) noPoseSince = now;
        if (now >= cooldownUntil && now - noPoseSince >= 380L) {
            waitingRelease = false;
            noPoseSince = 0L;
            listener.onState("中指模式 · 准备下一次");
        }
    }

    private Pose detectPose(List<NormalizedLandmark> landmarks) {
        if (landmarks == null || landmarks.size() < 21) return null;

        // The other three long fingers must be folded. The thumb is ignored so
        // a natural thumb position does not make the single-finger pose fail.
        boolean indexExtended = isExtended(landmarks, 5, 6, 7, 8);
        boolean middleExtended = isExtended(landmarks, 9, 10, 11, 12);
        boolean ringExtended = isExtended(landmarks, 13, 14, 15, 16);
        boolean littleExtended = isExtended(landmarks, 17, 18, 19, 20);
        if (indexExtended || !middleExtended || ringExtended || littleExtended) return null;

        Point mcp = point(landmarks.get(9));
        Point tip = point(landmarks.get(12));
        float dx = tip.x - mcp.x;
        float dy = tip.y - mcp.y;
        float length = (float) Math.hypot(dx, dy);
        if (length < 0.075f || Math.abs(dy) < length * 0.55f
                || Math.abs(dy) < Math.abs(dx) * 0.95f) return null;
        return new Pose(dy < 0f ? -1 : 1, dy < 0f);
    }

    private boolean isExtended(List<NormalizedLandmark> landmarks, int mcpIndex,
                               int pipIndex, int dipIndex, int tipIndex) {
        Point mcp = point(landmarks.get(mcpIndex));
        Point pip = point(landmarks.get(pipIndex));
        Point dip = point(landmarks.get(dipIndex));
        Point tip = point(landmarks.get(tipIndex));
        Point wrist = point(landmarks.get(0));
        float path = distance(mcp, pip) + distance(pip, dip) + distance(dip, tip);
        float direct = distance(mcp, tip);
        float tipFromWrist = distance(tip, wrist);
        float pipFromWrist = distance(pip, wrist);
        return direct > 0.075f && direct / Math.max(0.001f, path) > 0.66f
                && tipFromWrist > pipFromWrist * 1.03f;
    }

    private Point point(NormalizedLandmark landmark) {
        float x = landmark.x();
        float y = landmark.y();
        switch (rotationDegrees) {
            case 90:
                return new Point(1f - y, x);
            case 180:
                return new Point(1f - x, 1f - y);
            case 270:
                return new Point(y, 1f - x);
            default:
                return new Point(x, y);
        }
    }

    private static float distance(Point first, Point second) {
        return (float) Math.hypot(first.x - second.x, first.y - second.y);
    }

    private static final class Pose {
        final int direction;
        final boolean upward;

        Pose(int direction, boolean upward) {
            this.direction = direction;
            this.upward = upward;
        }
    }

    private static final class Point {
        final float x;
        final float y;

        Point(float x, float y) {
            this.x = x;
            this.y = y;
        }
    }
}
