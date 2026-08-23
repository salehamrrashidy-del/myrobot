package com.example.mirobotai;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.Image;
import java.nio.ByteBuffer;

import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.lifecycle.LifecycleOwner;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Front-camera vision for face tracking plus an EXPERIMENTAL visual edge guard.
 *
 * Important: the edge guard is a heuristic that looks for a strong horizontal
 * brightness boundary in the lower part of the camera image. It can help on a
 * tabletop if the front camera can see the surface ahead, but it is NOT a real
 * cliff sensor and must not be trusted as the only protection against falls.
 */
public class FaceVisionController {
    public interface Listener {
        /** x is -1..+1, where 0 means centered. size is face width/frame width. */
        void onFace(float x, float size, float smileProbability);
        void onNoFace();
        /** Heuristic: same face stayed fairly still for a long time. */
        void onFocusLikeBehavior();
        /** Experimental visual cliff/edge warning. Confidence is 0..1. */
        void onCliffRisk(float confidence);
        void onCliffClear();
        void onVisionStatus(String message);
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

    private int cliffStreak = 0;
    private int cliffClearStreak = 0;
    private boolean cliffActive = false;
    private long lastCliffCheckMs = 0L;

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
            listener.onVisionStatus("Vision ready 👀 • Edge Guard available");
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

        // Run the inexpensive edge heuristic before ML Kit consumes the frame.
        long now = System.currentTimeMillis();
        if (now - lastCliffCheckMs >= 110L) {
            lastCliffCheckMs = now;
            analyzeVisualCliff(proxy, rotation);
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

    /**
     * Looks for a persistent high-contrast horizontal boundary low in the camera
     * image. This often corresponds to the visible end of a table surface.
     * We require multiple consecutive frames before raising a warning.
     */
    private void analyzeVisualCliff(ImageProxy proxy, int rotation) {
        try {
            Image image = proxy.getImage();
            if (image == null || image.getPlanes().length == 0) return;
            Image.Plane yPlane = image.getPlanes()[0];
            ByteBuffer y = yPlane.getBuffer().duplicate();
            int rowStride = yPlane.getRowStride();
            int pixelStride = yPlane.getPixelStride();
            int rawW = proxy.getWidth();
            int rawH = proxy.getHeight();
            int displayW = (rotation == 90 || rotation == 270) ? rawH : rawW;
            int displayH = (rotation == 90 || rotation == 270) ? rawW : rawH;
            if (displayW < 80 || displayH < 80) return;

            final int rows = 22;
            final int cols = 32;
            float[] avg = new float[rows];
            // Only inspect the lower-middle region. This avoids ceiling/wall edges.
            for (int r = 0; r < rows; r++) {
                float fy = 0.48f + (0.43f * r / (rows - 1f));
                int dy = clampInt(Math.round(fy * (displayH - 1)), 0, displayH - 1);
                long sum = 0;
                int count = 0;
                for (int c = 0; c < cols; c++) {
                    float fx = 0.14f + (0.72f * c / (cols - 1f));
                    int dx = clampInt(Math.round(fx * (displayW - 1)), 0, displayW - 1);
                    int[] raw = displayToRaw(dx, dy, rawW, rawH, rotation);
                    int rx = raw[0], ry = raw[1];
                    int index = ry * rowStride + rx * pixelStride;
                    if (index >= 0 && index < y.limit()) {
                        sum += (y.get(index) & 0xFF);
                        count++;
                    }
                }
                avg[r] = count == 0 ? 0f : (sum / (float) count);
            }

            float maxDiff = 0f;
            int maxAt = -1;
            for (int i = 1; i < rows; i++) {
                float d = Math.abs(avg[i] - avg[i - 1]);
                if (d > maxDiff) {
                    maxDiff = d;
                    maxAt = i;
                }
            }

            // Give more weight to boundaries in the lower half of our inspected band.
            float linePosition = maxAt < 0 ? 0f : (0.48f + 0.43f * maxAt / (rows - 1f));
            float confidence = clamp((maxDiff - 17f) / 38f, 0f, 1f);
            boolean candidate = maxDiff >= 23f && linePosition >= 0.58f && linePosition <= 0.90f;

            if (candidate) {
                cliffStreak++;
                cliffClearStreak = 0;
                if (cliffStreak >= 3) {
                    cliffActive = true;
                    listener.onCliffRisk(confidence);
                }
            } else {
                cliffStreak = 0;
                cliffClearStreak++;
                if (cliffActive && cliffClearStreak >= 5) {
                    cliffActive = false;
                    listener.onCliffClear();
                }
            }
        } catch (Exception ignored) {
            // Edge guard must never crash face tracking.
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
        return new int[]{clampInt(rx, 0, rawW - 1), clampInt(ry, 0, rawH - 1)};
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
        cliffStreak = 0;
        cliffClearStreak = 0;
        if (cliffActive) {
            cliffActive = false;
            listener.onCliffClear();
        }
        if (cameraProvider != null) cameraProvider.unbindAll();
    }

    public void close() {
        stop();
        detector.close();
        cameraExecutor.shutdownNow();
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private static int clampInt(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
