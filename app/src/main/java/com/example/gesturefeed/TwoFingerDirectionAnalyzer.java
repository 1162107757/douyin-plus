package com.example.gesturefeed;

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;

import java.util.List;

/**
 * Recognizes a deliberate two-finger pointing pose. Index and middle fingers
 * must both be extended, while the ring and little fingers remain folded. A
 * direction is emitted only after several stable frames and only once until
 * the pose is released, so a held pose cannot repeatedly turn pages.
 */
public final class TwoFingerDirectionAnalyzer {
    public interface Listener {
        void onGesture(boolean upward);
        void onState(String state);
    }

    private static final int INDEX = 1;
    private static final int MIDDLE = 2;
    private static final int RING = 3;
    private static final int LITTLE = 4;

    private final Listener listener;
    private int rotationDegrees;
    private int candidateDirection;
    private int stableFrames;
    private long lastPoseAt;
    private long lastNoPoseStateAt;
    private long noPoseSince;
    private long cooldownUntil;
    private boolean waitingRelease;

    public TwoFingerDirectionAnalyzer(Listener listener) {
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
        listener.onState("双指模式 · 未检测到手");
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
        if (waitingRelease) {
            // One action per pointing pose. The hand must briefly leave the
            // pose before the same direction can be recognized again.
            return;
        }

        if (pose.direction != candidateDirection) {
            candidateDirection = pose.direction;
            stableFrames = 1;
            listener.onState(pose.upward ? "双指方向稳定中 · 向上" : "双指方向稳定中 · 向下");
        } else {
            stableFrames++;
        }

        if (stableFrames < 4 || now < cooldownUntil) return;
        waitingRelease = true;
        cooldownUntil = now + 700L;
        candidateDirection = 0;
        stableFrames = 0;
        listener.onState(pose.upward ? "已识别 · 双指向上" : "已识别 · 双指向下");
        listener.onGesture(pose.upward);
    }

    private void handlePoseMissing(long now) {
        candidateDirection = 0;
        stableFrames = 0;
        if (!waitingRelease) {
            if (now - lastPoseAt >= 650L && now - lastNoPoseStateAt >= 900L) {
                lastNoPoseStateAt = now;
                listener.onState("双指模式 · 未检测到双指");
            }
            return;
        }
        if (noPoseSince == 0L) noPoseSince = now;
        if (now >= cooldownUntil && now - noPoseSince >= 380L) {
            waitingRelease = false;
            noPoseSince = 0L;
            listener.onState("双指模式 · 准备下一次");
        }
    }

    private Pose detectPose(List<NormalizedLandmark> landmarks) {
        if (landmarks == null || landmarks.size() < 21) return null;
        boolean indexExtended = isExtended(landmarks, 5, 6, 7, 8);
        boolean middleExtended = isExtended(landmarks, 9, 10, 11, 12);
        boolean ringExtended = isExtended(landmarks, 13, 14, 15, 16);
        boolean littleExtended = isExtended(landmarks, 17, 18, 19, 20);
        if (!indexExtended || !middleExtended || ringExtended || littleExtended) return null;

        Point indexMcp = point(landmarks.get(5));
        Point middleMcp = point(landmarks.get(9));
        Point indexTip = point(landmarks.get(8));
        Point middleTip = point(landmarks.get(12));
        float indexDy = indexTip.y - indexMcp.y;
        float middleDy = middleTip.y - middleMcp.y;
        float fingerLength = Math.max(distance(indexTip, indexMcp), distance(middleTip, middleMcp));
        float avgDx = ((indexTip.x + middleTip.x) - (indexMcp.x + middleMcp.x)) * 0.5f;
        float avgDy = ((indexTip.y + middleTip.y) - (indexMcp.y + middleMcp.y)) * 0.5f;
        if (fingerLength < 0.085f || Math.abs(avgDy) < fingerLength * 0.55f
                || Math.abs(avgDx) > Math.abs(avgDy) * 0.95f) return null;
        if (Math.abs(indexDy) < 0.04f || Math.abs(middleDy) < 0.04f
                || (indexDy < 0f) != (middleDy < 0f)) return null;
        return new Pose(avgDy < 0f ? -1 : 1, avgDy < 0f);
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
