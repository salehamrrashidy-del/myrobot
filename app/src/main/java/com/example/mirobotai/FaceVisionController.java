package com.example.mirobotai;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.Image;

import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The phone camera acts as the robot's eyes while the screen stays as the face.
 *
 * v1.0 adds a conservative camera cliff/edge guard for autonomous roaming.
 * It learns the appearance of the lower part of the camera view while the robot
 * is stationary, then watches for a strong lower-image change / horizontal edge.
 * This is a heuristic safety layer, not a real depth sensor.
 */
public class FaceVisionController {
    public interface Listener {
        /** x is -1..+1, where 0 means centered. size is face width/frame width. */
        void onFace(float x, float size, float smileProbability);
        void onNoFace();
        /** Heuristic: same face stayed fairly still for a long time. */
        void onFocusLikeBehavior();
        void onVisionStatus(String message);
        /**
         * Camera cliff guard state. edgeSide: -1 = more risk on left,
         * +1 = more risk on right, 0 = centered/unknown.
         */
        void onGroundSafety(boolean ready, boolean safe, boolean edgeRisk,
                            float confidence, int edgeSide, String message);
    }

    private final Context context;
    private final LifecycleOwner lifecycleOwner;
    private final Listener listener;
    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean processing = new AtomicBoolean(false);

    private final FaceDetector detector;
    private ProcessCameraProvider cameraProvider;
    private boolean running;

    private long lastFaceMs = 0L;
    private float lastX = 0f;
    private long stableSinceMs = 0L;
    private long lastFocusEventMs = 0L;

    // --- Camera cliff guard ---
    private static final int CALIBRATION_FRAMES = 24;
    private int calibrationFrames = 0;
    private float baseBottomMean = 0f;
    private float baseBottomTexture = 0f;
    private float baseLineStrength = 0f;
    private int safeStreak = 0;
    private int riskStreak = 0;
    private long lastGroundCallbackMs = 0L;

    public FaceVisionController(Context context, LifecycleOwner lifecycleOwner, Listener listener) {
        this.context = context;
        this.lifecycleOwner = lifecycleOwner;
        this.listener = listener;

        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .enableTracking()
                .setMinFaceSize(0.16f)
                .build();
        detector = FaceDetection.getClient(options);
    }

    public void start() {
        if (running) return;
        running = true;
        resetCliffCalibration();
        listener.onVisionStatus("Starting front camera…");
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(context);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                bindCamera();
            } catch (Exception e) {
                running = false;
                listener.onVisionStatus("Camera error: " + e.getClass().getSimpleName());
            }
        }, ContextCompat.getMainExecutor(context));
    }

    /** Re-learn the safe surface. Call while robot is stationary in a safe spot. */
    public synchronized void resetCliffCalibration() {
        calibrationFrames = 0;
        baseBottomMean = 0f;
        baseBottomTexture = 0f;
        baseLineStrength = 0f;
        safeStreak = 0;
        riskStreak = 0;
        lastGroundCallbackMs = 0L;
        listener.onGroundSafety(false, false, false, 0f, 0,
                "Table guard learning… keep robot still");
    }

    @SuppressLint("UnsafeOptInUsageError")
    private void bindCamera() {
        if (cameraProvider == null || !running) return;
        try {
            cameraProvider.unbindAll();
            ImageAnalysis analysis = new ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetResolution(new android.util.Size(640, 480))
                    .build();
            analysis.setAnalyzer(cameraExecutor, this::analyze);
            cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, analysis);
            listener.onVisionStatus("Vision ready 👀 — table guard learning");
        } catch (Exception e) {
            running = false;
            listener.onVisionStatus("Front camera unavailable");
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private void analyze(@NonNull ImageProxy proxy) {
        if (!running) { proxy.close(); return; }
        if (!processing.compareAndSet(false, true)) { proxy.close(); return; }
        Image mediaImage = proxy.getImage();
        if (mediaImage == null) {
            processing.set(false);
            proxy.close();
            return;
        }

        int rotation = proxy.getImageInfo().getRotationDegrees();

        // Do cliff analysis first using the Y (luminance) plane. Absolute buffer reads
        // do not disturb ML Kit's access to the MediaImage below.
        try {
            analyzeGround(proxy, rotation);
        } catch (Exception ignored) {
            listener.onGroundSafety(false, false, false, 0f, 0,
                    "Table guard unavailable");
        }

        InputImage input = InputImage.fromMediaImage(mediaImage, rotation);
        int frameWidth = (rotation == 90 || rotation == 270) ? proxy.getHeight() : proxy.getWidth();

        detector.process(input)
                .addOnSuccessListener(faces -> handleFaces(faces, frameWidth))
                .addOnFailureListener(e -> listener.onVisionStatus("Vision frame error"))
                .addOnCompleteListener(task -> {
                    processing.set(false);
                    proxy.close();
                });
    }

    private void analyzeGround(ImageProxy proxy, int rotation) {
        ImageProxy.PlaneProxy[] planes = proxy.getPlanes();
        if (planes == null || planes.length == 0) return;
        ImageProxy.PlaneProxy yPlane = planes[0];
        ByteBuffer buffer = yPlane.getBuffer();
        int rowStride = yPlane.getRowStride();
        int pixelStride = yPlane.getPixelStride();
        int rawW = proxy.getWidth();
        int rawH = proxy.getHeight();
        int displayW = (rotation == 90 || rotation == 270) ? rawH : rawW;
        int displayH = (rotation == 90 || rotation == 270) ? rawW : rawH;
        if (displayW < 40 || displayH < 40) return;

        final int cols = 24;
        final int rows = 18;
        float[] rowMean = new float[rows];
        float[] leftMean = new float[rows];
        float[] rightMean = new float[rows];
        int[][] sample = new int[rows][cols];

        for (int r = 0; r < rows; r++) {
            // Watch mostly the lower half of the camera image: 48% -> 96%.
            float fy = 0.48f + (0.48f * r / (rows - 1f));
            int dy = clampInt(Math.round(fy * (displayH - 1)), 0, displayH - 1);
            int rowSum = 0, leftSum = 0, rightSum = 0;
            for (int c = 0; c < cols; c++) {
                float fx = 0.06f + (0.88f * c / (cols - 1f));
                int dx = clampInt(Math.round(fx * (displayW - 1)), 0, displayW - 1);
                int[] raw = displayToRaw(dx, dy, rawW, rawH, rotation);
                int index = raw[1] * rowStride + raw[0] * pixelStride;
                int value = (index >= 0 && index < buffer.limit()) ? (buffer.get(index) & 0xFF) : 0;
                sample[r][c] = value;
                rowSum += value;
                if (c < cols / 2) leftSum += value;
                else rightSum += value;
            }
            rowMean[r] = rowSum / (float) cols;
            leftMean[r] = leftSum / (float) (cols / 2);
            rightMean[r] = rightSum / (float) (cols - cols / 2);
        }

        int bottomStart = (int) (rows * 0.55f);
        float bottomMean = 0f;
        int bottomCount = 0;
        float texture = 0f;
        int textureCount = 0;
        for (int r = bottomStart; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                bottomMean += sample[r][c];
                bottomCount++;
                if (c > 0) {
                    texture += Math.abs(sample[r][c] - sample[r][c - 1]);
                    textureCount++;
                }
                if (r > bottomStart) {
                    texture += Math.abs(sample[r][c] - sample[r - 1][c]);
                    textureCount++;
                }
            }
        }
        bottomMean /= Math.max(1, bottomCount);
        float bottomTexture = texture / Math.max(1, textureCount);

        float lineStrength = 0f;
        float lineLeft = 0f;
        float lineRight = 0f;
        for (int r = 1; r < rows; r++) {
            float d = Math.abs(rowMean[r] - rowMean[r - 1]);
            if (d > lineStrength) {
                lineStrength = d;
                lineLeft = Math.abs(leftMean[r] - leftMean[r - 1]);
                lineRight = Math.abs(rightMean[r] - rightMean[r - 1]);
            }
        }

        // Mid/lower difference catches a new large region entering the bottom view.
        float middleMean = 0f;
        int middleRows = 0;
        for (int r = 2; r < bottomStart; r++) {
            middleMean += rowMean[r];
            middleRows++;
        }
        middleMean /= Math.max(1, middleRows);
        float lowerVsMiddle = Math.abs(bottomMean - middleMean);

        synchronized (this) {
            if (calibrationFrames < CALIBRATION_FRAMES) {
                calibrationFrames++;
                float n = calibrationFrames;
                baseBottomMean += (bottomMean - baseBottomMean) / n;
                baseBottomTexture += (bottomTexture - baseBottomTexture) / n;
                baseLineStrength += (lineStrength - baseLineStrength) / n;
                safeStreak = 0;
                riskStreak = 0;
                if (calibrationFrames == CALIBRATION_FRAMES) {
                    listener.onGroundSafety(true, true, false, 0f, 0,
                            "Table guard READY ✅");
                } else if (calibrationFrames == 1 || calibrationFrames % 6 == 0) {
                    listener.onGroundSafety(false, false, false,
                            calibrationFrames / (float) CALIBRATION_FRAMES, 0,
                            "Table guard learning… keep robot still");
                }
                return;
            }

            float appearanceDelta = Math.abs(bottomMean - baseBottomMean);
            float lineDelta = Math.max(0f, lineStrength - baseLineStrength);
            float textureDrop = (baseBottomTexture > 2.0f)
                    ? Math.max(0f, (baseBottomTexture - bottomTexture) / baseBottomTexture)
                    : 0f;

            // Conservative weighted risk. A sudden lower-image appearance change plus
            // a horizontal boundary is typical when a tabletop ends in front of camera.
            float risk = 0f;
            risk += 0.45f * normalize(appearanceDelta, 16f, 48f);
            risk += 0.38f * normalize(lineDelta, 10f, 42f);
            risk += 0.12f * normalize(lowerVsMiddle, 32f, 78f);
            risk += 0.12f * normalize(textureDrop, 0.45f, 0.90f);
            risk = clamp(risk, 0f, 1f);

            boolean strongImmediate = risk >= 0.78f || (appearanceDelta > 45f && lineStrength > 22f);
            boolean frameRisk = risk >= 0.52f;
            boolean frameSafe = risk <= 0.28f;
            if (frameRisk) {
                riskStreak++;
                safeStreak = 0;
            } else if (frameSafe) {
                safeStreak++;
                riskStreak = 0;
            } else {
                safeStreak = Math.max(0, safeStreak - 1);
                riskStreak = Math.max(0, riskStreak - 1);
            }

            boolean edgeRisk = strongImmediate || riskStreak >= 2;
            boolean safe = !edgeRisk && safeStreak >= 3;
            int side = 0;
            if (Math.abs(lineLeft - lineRight) > 5f) side = lineLeft > lineRight ? -1 : +1;

            long now = System.currentTimeMillis();
            if (edgeRisk || now - lastGroundCallbackMs > 180L) {
                lastGroundCallbackMs = now;
                if (edgeRisk) {
                    listener.onGroundSafety(true, false, true, risk, side,
                            "EDGE RISK — STOPPING ⚠️");
                } else if (safe) {
                    listener.onGroundSafety(true, true, false, 1f - risk, side,
                            "Table guard SAFE ✅");
                } else {
                    listener.onGroundSafety(true, false, false, 1f - risk, side,
                            "Table guard checking…");
                }
            }

            // Very slow adaptation only while confidently safe. This handles gradual
            // lighting drift without quickly learning an actual drop as the new normal.
            if (safe && safeStreak > 12) {
                final float a = 0.004f;
                baseBottomMean = baseBottomMean * (1f - a) + bottomMean * a;
                baseBottomTexture = baseBottomTexture * (1f - a) + bottomTexture * a;
                baseLineStrength = baseLineStrength * (1f - a) + lineStrength * a;
            }
        }
    }

    private static int[] displayToRaw(int dx, int dy, int rawW, int rawH, int rotation) {
        int rx, ry;
        switch (rotation) {
            case 90:
                rx = dy;
                ry = rawH - 1 - dx;
                break;
            case 180:
                rx = rawW - 1 - dx;
                ry = rawH - 1 - dy;
                break;
            case 270:
                rx = rawW - 1 - dy;
                ry = dx;
                break;
            case 0:
            default:
                rx = dx;
                ry = dy;
                break;
        }
        return new int[] { clampInt(rx, 0, rawW - 1), clampInt(ry, 0, rawH - 1) };
    }

    private void handleFaces(List<Face> faces, int frameWidth) {
        long now = System.currentTimeMillis();
        if (faces == null || faces.isEmpty()) {
            if (now - lastFaceMs > 450L) listener.onNoFace();
            stableSinceMs = 0L;
            return;
        }

        Face best = faces.get(0);
        int bestArea = best.getBoundingBox().width() * best.getBoundingBox().height();
        for (int i = 1; i < faces.size(); i++) {
            Face f = faces.get(i);
            int area = f.getBoundingBox().width() * f.getBoundingBox().height();
            if (area > bestArea) { best = f; bestArea = area; }
        }

        float centerX = best.getBoundingBox().centerX();
        float normalized = frameWidth <= 0 ? 0f : ((centerX / frameWidth) - 0.5f) * 2f;
        // Front-camera coordinates are mirrored relative to the person. Flip so + means
        // the person is physically to the robot's right.
        normalized = -clamp(normalized, -1f, 1f);
        float size = frameWidth <= 0 ? 0f : best.getBoundingBox().width() / (float) frameWidth;
        Float smile = best.getSmilingProbability();
        float smileValue = smile == null ? -1f : smile;

        if (Math.abs(normalized - lastX) < 0.075f) {
            if (stableSinceMs == 0L) stableSinceMs = now;
        } else {
            stableSinceMs = now;
        }
        lastX = normalized;
        lastFaceMs = now;

        // A cautious "maybe concentrating" heuristic: a person is present and stays
        // quite still for 90 seconds. We do not claim to know their mental state.
        if (stableSinceMs > 0 && now - stableSinceMs > 90_000L
                && now - lastFocusEventMs > 10 * 60_000L
                && (smileValue < 0f || smileValue < 0.45f)) {
            lastFocusEventMs = now;
            listener.onFocusLikeBehavior();
        }

        listener.onFace(normalized, size, smileValue);
    }

    public void stop() {
        running = false;
        if (cameraProvider != null) cameraProvider.unbindAll();
    }

    public void close() {
        stop();
        detector.close();
        cameraExecutor.shutdownNow();
    }

    private static float normalize(float value, float start, float full) {
        if (value <= start) return 0f;
        if (value >= full) return 1f;
        return (value - start) / (full - start);
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private static int clampInt(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
