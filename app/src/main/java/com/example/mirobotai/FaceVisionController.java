package com.example.mirobotai;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.Image;

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
 * Runs the front camera in the background. There is intentionally no camera preview:
 * the screen stays as the robot's face while the camera acts as its eyes.
 *
 * This version does FACE DETECTION + TRACKING only. Identity/owner recognition will
 * use a separate on-device face-embedding model in the next step.
 */
public class FaceVisionController {
    public interface Listener {
        /** x is -1..+1, where 0 means centered. size is face width/frame width. */
        void onFace(float x, float size, float smileProbability);
        void onNoFace();
        /** Heuristic: same face stayed fairly still for a long time. */
        void onFocusLikeBehavior();
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
            listener.onVisionStatus("Vision ready 👀");
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

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}
