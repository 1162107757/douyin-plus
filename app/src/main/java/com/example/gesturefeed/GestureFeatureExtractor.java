package com.example.gesturefeed;

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;

import java.util.ArrayList;
import java.util.List;

/** Converts MediaPipe landmarks into translation/scale-normalized sequences. */
public final class GestureFeatureExtractor {
    public static final int FEATURE_SIZE = 44;
    public static final int RESAMPLED_FRAMES = 24;

    private GestureFeatureExtractor() {
    }

    public static float[] fromLandmarks(List<NormalizedLandmark> landmarks, int rotationDegrees) {
        if (landmarks == null || landmarks.size() < 21) return null;
        Point[] points = new Point[21];
        for (int index = 0; index < points.length; index++) {
            points[index] = rotate(landmarks.get(index).x(), landmarks.get(index).y(), rotationDegrees);
        }
        Point wrist = points[0];
        float scale = 0f;
        int[] palm = {5, 9, 13, 17};
        for (int index : palm) scale += distance(wrist, points[index]);
        scale = Math.max(0.08f, scale / palm.length);
        float[] feature = new float[FEATURE_SIZE];
        feature[0] = wrist.x;
        feature[1] = wrist.y;
        for (int index = 0; index < points.length; index++) {
            int offset = 2 + index * 2;
            feature[offset] = (points[index].x - wrist.x) / scale;
            feature[offset + 1] = (points[index].y - wrist.y) / scale;
        }
        return feature;
    }

    /** Resamples a recorded sequence and normalizes its start position. */
    public static float[] prepareSequence(List<float[]> frames) {
        if (frames == null || frames.size() < 5) return null;
        List<float[]> valid = new ArrayList<>();
        for (float[] frame : frames) {
            if (frame != null && frame.length == FEATURE_SIZE) valid.add(frame);
        }
        if (valid.size() < 5) return null;
        float[] result = new float[RESAMPLED_FRAMES * FEATURE_SIZE];
        float startX = valid.get(0)[0];
        float startY = valid.get(0)[1];
        float minX = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        for (int frame = 0; frame < RESAMPLED_FRAMES; frame++) {
            float position = valid.size() == 1
                    ? 0f
                    : frame * (valid.size() - 1f) / (RESAMPLED_FRAMES - 1f);
            int left = Math.min(valid.size() - 1, (int) Math.floor(position));
            int right = Math.min(valid.size() - 1, left + 1);
            float fraction = position - left;
            int offset = frame * FEATURE_SIZE;
            for (int index = 0; index < FEATURE_SIZE; index++) {
                float value = valid.get(left)[index]
                        + (valid.get(right)[index] - valid.get(left)[index]) * fraction;
                result[offset + index] = value;
            }
            result[offset] -= startX;
            result[offset + 1] -= startY;
            minX = Math.min(minX, result[offset]);
            maxX = Math.max(maxX, result[offset]);
            minY = Math.min(minY, result[offset + 1]);
            maxY = Math.max(maxY, result[offset + 1]);
        }
        // Normalize movement amplitude while retaining static hand shape.
        float movementScale = Math.max(maxX - minX, maxY - minY);
        if (movementScale > 0.25f) {
            for (int frame = 0; frame < RESAMPLED_FRAMES; frame++) {
                int offset = frame * FEATURE_SIZE;
                result[offset] /= movementScale;
                result[offset + 1] /= movementScale;
            }
        }
        return result;
    }

    public static float distance(float[] first, float[] second) {
        if (first == null || second == null || first.length != second.length) return Float.MAX_VALUE;
        int frames = first.length / FEATURE_SIZE;
        if (frames == 0 || second.length % FEATURE_SIZE != 0) return Float.MAX_VALUE;
        float total = 0f;
        for (int frame = 0; frame < frames; frame++) {
            int offset = frame * FEATURE_SIZE;
            float frameTotal = 0f;
            for (int index = 0; index < FEATURE_SIZE; index += 2) {
                float dx = first[offset + index] - second[offset + index];
                float dy = first[offset + index + 1] - second[offset + index + 1];
                frameTotal += (float) Math.hypot(dx, dy);
            }
            total += frameTotal / (FEATURE_SIZE / 2f);
        }
        return total / frames;
    }

    private static Point rotate(float x, float y, int degrees) {
        switch (((degrees % 360) + 360) % 360) {
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

    private static final class Point {
        final float x;
        final float y;

        Point(float x, float y) {
            this.x = x;
            this.y = y;
        }
    }
}
