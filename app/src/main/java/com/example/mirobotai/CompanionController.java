package com.example.mirobotai;

import android.os.Handler;
import android.os.Looper;

import java.util.Random;

/**
 * Small autonomous-behaviour layer. It never sends raw BLE packets itself.
 * It only asks MainActivity for short, low-speed, automatically-stopping moves.
 */
public class CompanionController {
    public interface MotionSink {
        boolean robotReady();
        void autoPulse(int forward, int turn, int delta, long durationMs);
        void autoStop();
        void showTemporaryEmotion(Emotion emotion, long durationMs);
        void onCompanionStatus(String text);
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final MotionSink sink;

    private boolean companionMode = false;
    private boolean roamMode = false;
    private boolean facePresent = false;
    private float faceX = 0f;
    private float faceSize = 0f;
    private long lastFaceMs = 0L;
    private long nextRoamMs = 0L;
    private long manualPauseUntilMs = 0L;
    private long lastTrackMoveMs = 0L;

    private final Runnable loop = new Runnable() {
        @Override public void run() {
            tick();
            handler.postDelayed(this, 300L);
        }
    };

    public CompanionController(MotionSink sink) {
        this.sink = sink;
        scheduleNextRoam();
    }

    public void start() {
        handler.removeCallbacks(loop);
        handler.post(loop);
    }

    public void stop() {
        handler.removeCallbacks(loop);
        sink.autoStop();
    }

    public void setCompanionMode(boolean enabled) {
        companionMode = enabled;
        if (!enabled) sink.autoStop();
        sink.onCompanionStatus(enabled ? "Companion mode ON" : "Companion mode OFF");
    }

    public void setRoamMode(boolean enabled) {
        roamMode = enabled;
        scheduleNextRoam();
        if (!enabled && !companionMode) sink.autoStop();
        sink.onCompanionStatus(enabled ? "Gentle roam ON" : "Gentle roam OFF");
    }

    public void faceSeen(float x, float size) {
        boolean wasMissing = System.currentTimeMillis() - lastFaceMs > 4_000L;
        facePresent = true;
        faceX = x;
        faceSize = size;
        lastFaceMs = System.currentTimeMillis();
        if (wasMissing) sink.showTemporaryEmotion(Emotion.CURIOUS, 900L);
    }

    public void noFace() {
        if (System.currentTimeMillis() - lastFaceMs > 700L) facePresent = false;
    }

    public void manualOverride() {
        manualPauseUntilMs = System.currentTimeMillis() + 2500L;
    }

    public void focusLikeBehavior() {
        sink.showTemporaryEmotion(Emotion.CURIOUS, 2500L);
        sink.onCompanionStatus("You seem busy — curious 👀");
        // No automatic approach yet: without distance/obstacle sensing it would be unsafe.
        // The future voice/owner-aware version can ask to sit with the user first.
    }

    private void tick() {
        long now = System.currentTimeMillis();
        if (!sink.robotReady() || now < manualPauseUntilMs) return;

        if (facePresent && companionMode) {
            // Center the person using short turn pulses. Dead-zone prevents jitter.
            if (Math.abs(faceX) > 0.20f && now - lastTrackMoveMs > 520L) {
                int turn = faceX > 0 ? +1 : -1;
                sink.autoPulse(0, turn, 10, 120L);
                lastTrackMoveMs = now;
            }
            return;
        }

        if (roamMode && !facePresent && now >= nextRoamMs) {
            // Gentle exploration: mostly look around, with an occasional tiny forward nudge.
            // Every pulse stops automatically.
            int pick = random.nextInt(10);
            if (pick < 7) {
                int turn = random.nextBoolean() ? +1 : -1;
                sink.autoPulse(0, turn, 9, 180L + random.nextInt(120));
            } else {
                sink.autoPulse(+1, 0, 8, 160L);
            }
            scheduleNextRoam();
        }
    }

    private void scheduleNextRoam() {
        nextRoamMs = System.currentTimeMillis() + 3500L + random.nextInt(4500);
    }
}
