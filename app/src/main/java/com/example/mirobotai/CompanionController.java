package com.example.mirobotai;

import android.os.Handler;
import android.os.Looper;

import java.util.Random;

/**
 * Autonomous behaviour layer. It never sends raw BLE packets itself.
 * All movement is short, low-speed and automatically stopped by MainActivity.
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
    private long faceCenteredSinceMs = 0L;

    private final Runnable loop = new Runnable() {
        @Override public void run() {
            tick();
            handler.postDelayed(this, 90L);
        }
    };

    public CompanionController(MotionSink sink) {
        this.sink = sink;
        scheduleNextRoam(600L);
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
        if (!enabled && !roamMode) sink.autoStop();
        sink.onCompanionStatus(enabled ? "Companion mode ON" : "Companion mode OFF");
    }

    public void setRoamMode(boolean enabled) {
        roamMode = enabled;
        scheduleNextRoam(350L);
        if (!enabled && !companionMode) sink.autoStop();
        sink.onCompanionStatus(enabled ? "Roam mode ON — exploring" : "Roam mode OFF");
    }

    public void faceSeen(float x, float size) {
        long now = System.currentTimeMillis();
        boolean wasMissing = now - lastFaceMs > 4_000L;
        facePresent = true;
        faceX = x;
        faceSize = size;
        lastFaceMs = now;
        if (wasMissing) sink.showTemporaryEmotion(Emotion.CURIOUS, 900L);
    }

    public void noFace() {
        if (System.currentTimeMillis() - lastFaceMs > 750L) {
            facePresent = false;
            faceCenteredSinceMs = 0L;
        }
    }

    public void manualOverride() {
        pauseFor(2500L);
    }

    public void pauseFor(long ms) {
        manualPauseUntilMs = Math.max(manualPauseUntilMs, System.currentTimeMillis() + ms);
        sink.autoStop();
    }

    public void focusLikeBehavior() {
        sink.showTemporaryEmotion(Emotion.CURIOUS, 2500L);
        sink.onCompanionStatus("You seem focused — curious 👀");
    }

    private void tick() {
        long now = System.currentTimeMillis();
        if (!sink.robotReady() || now < manualPauseUntilMs) return;

        // Companion mode has first priority: face-center the body.
        if (facePresent && companionMode) {
            float error = Math.abs(faceX);
            if (error > 0.09f && now - lastTrackMoveMs > 70L) {
                int turn = faceX > 0 ? +1 : -1;
                int delta;
                long pulseMs;
                if (error > 0.55f) {
                    delta = 24;
                    pulseMs = 230L;
                } else if (error > 0.30f) {
                    delta = 20;
                    pulseMs = 205L;
                } else if (error > 0.17f) {
                    delta = 16;
                    pulseMs = 175L;
                } else {
                    delta = 13;
                    pulseMs = 145L;
                }
                sink.autoPulse(0, turn, delta, pulseMs);
                lastTrackMoveMs = now;
                faceCenteredSinceMs = 0L;
                return;
            }

            if (error <= 0.09f) {
                if (faceCenteredSinceMs == 0L) faceCenteredSinceMs = now;
                sink.autoStop();

                // Roam can still make the robot feel alive while a face is centered,
                // but only with a tiny occasional look-around turn, never a forward nudge.
                if (roamMode && now >= nextRoamMs && now - faceCenteredSinceMs > 2500L) {
                    int turn = random.nextBoolean() ? +1 : -1;
                    sink.autoPulse(0, turn, 10, 130L);
                    scheduleNextRoam(2500L);
                }
                return;
            }
        }

        // v0.8 fix: roam is no longer blocked just because the camera sees a face.
        // If Companion mode is OFF, the robot can still explore the room.
        if (roamMode && now >= nextRoamMs) {
            int pick = random.nextInt(100);

            // When a face looks close, avoid forward nudges and mostly turn/look.
            boolean faceLooksClose = facePresent && faceSize > 0.16f;
            if (faceLooksClose || pick < 62) {
                int turn = random.nextBoolean() ? +1 : -1;
                sink.autoPulse(0, turn, 12 + random.nextInt(5), 210L + random.nextInt(130));
            } else if (pick < 88) {
                // A noticeable but still gentle forward exploration pulse.
                sink.autoPulse(+1, 0, 11 + random.nextInt(4), 230L + random.nextInt(120));
            } else {
                // Tiny playful back-up, useful for breaking repetitive paths.
                sink.autoPulse(-1, 0, 9, 150L);
            }
            sink.showTemporaryEmotion(Emotion.CURIOUS, 650L);
            scheduleNextRoam(1500L);
        }
    }

    private void scheduleNextRoam(long minimumDelayMs) {
        long extra = 900L + random.nextInt(2200);
        nextRoamMs = System.currentTimeMillis() + minimumDelayMs + extra;
    }
}
