package com.example.gesturefeed;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.Image;
import android.util.Log;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult;

import java.util.List;

/** MediaPipe Hand Landmarker adapter feeding the shared gesture state machine. */
public final class MediaPipeHandLandmarker implements AutoCloseable {
    private static final String TAG = "GestureFeed";
    public interface Listener {
        void onGesture(boolean upward);
        void onState(String state);
    }

    private final HandMotionAnalyzer motion;
    private final TwoFingerDirectionAnalyzer twoFinger;
    private final MiddleFingerDirectionAnalyzer middleFinger;
    private final Listener listener;
    private HandLandmarker landmarker;
    private long lastTimestamp;
    private int rotationDegrees;
    private GestureMode mode = GestureMode.PALM_SWING;

    public MediaPipeHandLandmarker(Context context, Listener listener) {
        this.listener = listener;
        motion = new HandMotionAnalyzer(new HandMotionAnalyzer.Listener() {
            @Override
            public void onGesture(boolean upward) {
                listener.onGesture(upward);
            }

            @Override
            public void onState(String state) {
                listener.onState(state);
            }
        });
        twoFinger = new TwoFingerDirectionAnalyzer(new TwoFingerDirectionAnalyzer.Listener() {
            @Override
            public void onGesture(boolean upward) {
                listener.onGesture(upward);
            }

            @Override
            public void onState(String state) {
                listener.onState(state);
            }
        });
        middleFinger = new MiddleFingerDirectionAnalyzer(new MiddleFingerDirectionAnalyzer.Listener() {
            @Override
            public void onGesture(boolean upward) {
                listener.onGesture(upward);
            }

            @Override
            public void onState(String state) {
                listener.onState(state);
            }
        });
        try {
            BaseOptions baseOptions = BaseOptions.builder()
                    .setModelAssetPath("hand_landmarker.task")
                    .build();
            HandLandmarker.HandLandmarkerOptions options = HandLandmarker.HandLandmarkerOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setRunningMode(RunningMode.VIDEO)
                    .setNumHands(1)
                    // Keep recall high enough for a quick hand entry/exit;
                    // the trajectory state machine and release guard handle
                    // the resulting occasional low-confidence frame.
                    .setMinHandDetectionConfidence(0.55f)
                    .setMinHandPresenceConfidence(0.50f)
                    .setMinTrackingConfidence(0.50f)
                    .build();
            landmarker = HandLandmarker.createFromOptions(context, options);
            listener.onState("模型已就绪 · 等待手掌");
        } catch (RuntimeException error) {
            landmarker = null;
            Log.e(TAG, "MediaPipe model initialization failed", error);
            listener.onState("模型初始化失败");
        }
    }

    public void setRotationDegrees(int degrees) {
        rotationDegrees = ((degrees % 360) + 360) % 360;
        twoFinger.setRotationDegrees(rotationDegrees);
        middleFinger.setRotationDegrees(rotationDegrees);
    }

    public void setMode(GestureMode mode) {
        this.mode = mode == null ? GestureMode.PALM_SWING : mode;
        motion.reset();
        twoFinger.reset();
        middleFinger.reset();
    }

    public void analyze(Image image) {
        if (image == null || landmarker == null) return;
        // Keep the timestamp clock consistent with HandMotionAnalyzer's
        // fallback path and with the calibration camera. MediaPipe only
        // requires monotonically increasing VIDEO timestamps.
        long timestamp = System.currentTimeMillis();
        if (timestamp <= lastTimestamp) timestamp = lastTimestamp + 1L;
        lastTimestamp = timestamp;
        MPImage mpImage = null;
        Bitmap bitmap = null;
        try {
            // MediaPipe's Android packet path requires ARGB_8888. The camera
            // stream is YUV_420_888, so convert a half-resolution copy and
            // never retain the source frame after this call.
            bitmap = yuvToBitmap(image);
            mpImage = new BitmapImageBuilder(bitmap).build();
            HandLandmarkerResult result = landmarker.detectForVideo(mpImage, timestamp);
            List<List<NormalizedLandmark>> hands = result.landmarks();
            if (hands == null || hands.isEmpty() || hands.get(0) == null || hands.get(0).size() < 21) {
                if (mode == GestureMode.TWO_FINGER_DIRECTION) {
                    twoFinger.analyze(null, timestamp);
                } else if (mode == GestureMode.MIDDLE_FINGER_DIRECTION) {
                    middleFinger.analyze(null, timestamp);
                } else {
                    motion.analyzeLandmarks(0f, 0f, 0f, timestamp);
                }
                return;
            }
            List<NormalizedLandmark> landmarks = hands.get(0);
            if (mode == GestureMode.TWO_FINGER_DIRECTION) {
                twoFinger.analyze(landmarks, timestamp);
                return;
            }
            if (mode == GestureMode.MIDDLE_FINGER_DIRECTION) {
                middleFinger.analyze(landmarks, timestamp);
                return;
            }
            float x = 0f;
            float y = 0f;
            int[] palm = {0, 5, 9, 13, 17};
            for (int index : palm) {
                x += landmarks.get(index).x();
                y += landmarks.get(index).y();
            }
            x /= palm.length;
            y /= palm.length;
            float[] displayPoint = rotate(x, y);
            motion.analyzeLandmarks(displayPoint[0], displayPoint[1], 1f, timestamp);
        } catch (RuntimeException error) {
            Log.e(TAG, "MediaPipe inference failed", error);
            listener.onState("模型推理失败");
        } finally {
            if (mpImage != null) mpImage.close();
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        }
    }

    private Bitmap yuvToBitmap(Image image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int targetWidth = Math.max(1, width / 2);
        int targetHeight = Math.max(1, height / 2);
        int[] pixels = new int[targetWidth * targetHeight];
        Image.Plane yPlane = image.getPlanes()[0];
        Image.Plane uPlane = image.getPlanes()[1];
        Image.Plane vPlane = image.getPlanes()[2];
        java.nio.ByteBuffer yBuffer = yPlane.getBuffer();
        java.nio.ByteBuffer uBuffer = uPlane.getBuffer();
        java.nio.ByteBuffer vBuffer = vPlane.getBuffer();
        int yRow = yPlane.getRowStride();
        int yPixel = yPlane.getPixelStride();
        int uRow = uPlane.getRowStride();
        int uPixel = uPlane.getPixelStride();
        int vRow = vPlane.getRowStride();
        int vPixel = vPlane.getPixelStride();
        for (int ty = 0; ty < targetHeight; ty++) {
            int y = Math.min(height - 1, ty * 2);
            for (int tx = 0; tx < targetWidth; tx++) {
                int x = Math.min(width - 1, tx * 2);
                int yi = y * yRow + x * yPixel;
                int ui = (y / 2) * uRow + (x / 2) * uPixel;
                int vi = (y / 2) * vRow + (x / 2) * vPixel;
                int yv = yi < yBuffer.limit() ? (yBuffer.get(yi) & 0xff) : 0;
                int u = ui < uBuffer.limit() ? (uBuffer.get(ui) & 0xff) - 128 : 0;
                int v = vi < vBuffer.limit() ? (vBuffer.get(vi) & 0xff) - 128 : 0;
                float yy = Math.max(0, yv - 16) * 1.164f;
                int r = clamp((int) (yy + 1.596f * v));
                int g = clamp((int) (yy - 0.392f * u - 0.813f * v));
                int b = clamp((int) (yy + 2.017f * u));
                pixels[ty * targetWidth + tx] = Color.rgb(r, g, b);
            }
        }
        return Bitmap.createBitmap(pixels, targetWidth, targetHeight, Bitmap.Config.ARGB_8888);
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    public void reset() {
        motion.reset();
        twoFinger.reset();
        middleFinger.reset();
    }

    private float[] rotate(float x, float y) {
        switch (rotationDegrees) {
            case 90:
                return new float[]{1f - y, x};
            case 180:
                return new float[]{1f - x, 1f - y};
            case 270:
                return new float[]{y, 1f - x};
            default:
                return new float[]{x, y};
        }
    }

    @Override
    public void close() {
        if (landmarker != null) {
            landmarker.close();
            landmarker = null;
        }
    }
}
