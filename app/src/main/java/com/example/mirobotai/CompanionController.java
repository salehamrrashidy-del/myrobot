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
        boolean forwardSafe();
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
    private boolean cliffRisk = false;
    private boolean facePresent = false;
    private float faceX = 0f;
    private float faceSize = 0f;
    private long lastFaceMs = 0L;
    private long nextRoamMs = 0L;
    private long manualPauseUntilMs = 0L;
    private long lastTrackMoveMs = 0L;
    private long faceCenteredSinceMs = 0L;
    private long lastCliffTurnMs = 0L;

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
        sink.onCompanionStatus(enabled ? "Roam mode ON — slow + edge-aware" : "Roam mode OFF");
    }

    public boolean isRoamMode() { return roamMode; }
    public boolean isCompanionMode() { return companionMode; }

    public void setCliffRisk(boolean risky) {
        cliffRisk = risky;
        if (risky) {
            sink.autoStop();
            sink.showTemporaryEmotion(Emotion.SURPRISED, 900L);
            sink.onCompanionStatus("EDGE AHEAD ⚠️ — forward blocked");
        } else {
            scheduleNextRoam(800L);
        }
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

        // Local safety always wins over companion/AI/roam behaviour.
        if (cliffRisk || !sink.forwardSafe()) {
            sink.autoStop();
            // A tiny in-place turn may help the camera look away from the table edge.
            // Keep it very short because the camera cannot see behind the robot.
            if (roamMode && now - lastCliffTurnMs > 1600L) {
                int turn = random.nextBoolean() ? +1 : -1;
                sink.autoPulse(0, turn, 8, 105L);
                lastCliffTurnMs = now;
            }
            return;
        }

        // Companion mode has first priority: face-center the body.
        if (facePresent && companionMode) {
            float error = Math.abs(faceX);
            if (error > 0.09f && now - lastTrackMoveMs > 70L) {
                int turn = faceX > 0 ? +1 : -1;
                int delta;
                long pulseMs;
                if (error > 0.55f) {
                    delta = 22;
                    pulseMs = 210L;
                } else if (error > 0.30f) {
                    delta = 18;
                    pulseMs = 185L;
                } else if (error > 0.17f) {
                    delta = 15;
                    pulseMs = 160L;
                } else {
                    delta = 12;
                    pulseMs = 135L;
                }
                sink.autoPulse(0, turn, delta, pulseMs);
                lastTrackMoveMs = now;
                faceCenteredSinceMs = 0L;
                return;
            }

            if (error <= 0.09f) {
                if (faceCenteredSinceMs == 0L) faceCenteredSinceMs = now;
                sink.autoStop();

                if (roamMode && now >= nextRoamMs && now - faceCenteredSinceMs > 3000L) {
                    int turn = random.nextBoolean() ? +1 : -1;
                    sink.autoPulse(0, turn, 9, 110L);
                    scheduleNextRoam(2800L);
                }
                return;
            }
        }

        // Edge-aware gentle roaming. Forward movement is deliberately rarer than turning.
        if (roamMode && now >= nextRoamMs) {
            int pick = random.nextInt(100);
            boolean faceLooksClose = facePresent && faceSize > 0.16f;

            if (faceLooksClose || pick < 70) {
                int turn = random.nextBoolean() ? +1 : -1;
                sink.autoPulse(0, turn, 10 + random.nextInt(4), 170L + random.nextInt(100));
            } else if (pick < 92 && sink.forwardSafe()) {
                // Only short forward nudges. Never a long continuous drive.
                sink.autoPulse(+1, 0, 9 + random.nextInt(3), 150L + random.nextInt(85));
            } else {
                // No automatic reverse: the front camera cannot see the rear edge.
                sink.autoStop();
            }
            sink.showTemporaryEmotion(Emotion.CURIOUS, 650L);
            scheduleNextRoam(2000L);
        }
    }

    private void scheduleNextRoam(long minimumDelayMs) {
        long extra = 1100L + random.nextInt(2600);
        nextRoamMs = System.currentTimeMillis() + minimumDelayMs + extra;
    }
}
