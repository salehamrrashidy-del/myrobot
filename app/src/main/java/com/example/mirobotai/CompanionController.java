package com.example.mirobotai;

import android.os.Handler;
import android.os.Looper;

import java.util.Random;

/**
 * Autonomous behaviour layer. It never sends raw BLE packets itself.
 *
 * v1.0: roaming is camera-guarded. It only drives forward after the vision
 * controller reports a stable safe surface. Random reverse movement is removed
 * because a front camera cannot see a rear cliff.
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

    // Camera cliff guard state.
    private boolean groundReady = false;
    private boolean groundSafe = false;
    private boolean edgeRisk = false;
    private int edgeSide = 0;
    private float edgeConfidence = 0f;

    // Edge escape sequence: stop -> tiny reverse -> turn away -> wait for safe view.
    private boolean edgeRecoveryActive = false;
    private int edgeRecoveryStep = 0;
    private long edgeRecoveryNextMs = 0L;
    private int recoveryTurn = 1;

    private final Runnable loop = new Runnable() {
        @Override public void run() {
            tick();
            handler.postDelayed(this, 80L);
        }
    };

    public CompanionController(MotionSink sink) {
        this.sink = sink;
        scheduleNextRoam(700L);
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
        edgeRecoveryActive = false;
        edgeRecoveryStep = 0;
        scheduleNextRoam(600L);
        if (!enabled) {
            if (!companionMode) sink.autoStop();
            sink.onCompanionStatus("Roam mode OFF");
        } else if (!groundReady) {
            sink.autoStop();
            sink.onCompanionStatus("Safe roam waiting for camera table guard…");
        } else {
            sink.onCompanionStatus("Safe roam ON — camera edge guard active");
        }
    }

    /** Receives the conservative table/floor safety estimate from the camera. */
    public void groundSafety(boolean ready, boolean safe, boolean risk,
                             float confidence, int side) {
        boolean wasRisk = edgeRisk;
        groundReady = ready;
        groundSafe = safe;
        edgeRisk = risk;
        edgeConfidence = confidence;
        edgeSide = side;

        if (roamMode && risk && !wasRisk && !edgeRecoveryActive) {
            // Risk is in front of the robot because this guard uses the front camera.
            sink.autoStop();
            sink.showTemporaryEmotion(Emotion.SURPRISED, 1100L);
            sink.onCompanionStatus("Edge seen! Backing away safely ⚠️");
            edgeRecoveryActive = true;
            edgeRecoveryStep = 0;
            edgeRecoveryNextMs = System.currentTimeMillis() + 80L;
            // Turn away from the stronger side of the detected edge. If centered,
            // choose a direction randomly.
            if (side < 0) recoveryTurn = +1;
            else if (side > 0) recoveryTurn = -1;
            else recoveryTurn = random.nextBoolean() ? +1 : -1;
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

        if (roamMode && edgeRecoveryActive) {
            runEdgeRecovery(now);
            return;
        }

        // If the camera sees an edge and recovery has not started for any reason,
        // stop immediately. No autonomous forward movement is allowed.
        if (roamMode && edgeRisk) {
            sink.autoStop();
            return;
        }

        // Companion mode has first priority: face-center the body. These are turns in
        // place only, so it never drives toward a person or toward a table edge.
        if (facePresent && companionMode) {
            float error = Math.abs(faceX);
            if (error > 0.09f && now - lastTrackMoveMs > 70L) {
                int turn = faceX > 0 ? +1 : -1;
                int delta;
                long pulseMs;
                if (error > 0.55f) {
                    delta = 22;
                    pulseMs = 215L;
                } else if (error > 0.30f) {
                    delta = 18;
                    pulseMs = 190L;
                } else if (error > 0.17f) {
                    delta = 15;
                    pulseMs = 165L;
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

                // If roam is also on, only a tiny look-around turn is allowed while a
                // face is centered. Never nudge forward toward a person.
                if (roamMode && groundReady && !edgeRisk && now >= nextRoamMs
                        && now - faceCenteredSinceMs > 2500L) {
                    int turn = random.nextBoolean() ? +1 : -1;
                    sink.autoPulse(0, turn, 9, 120L);
                    scheduleNextRoam(2200L);
                }
                return;
            }
        }

        if (!roamMode || now < nextRoamMs) return;

        // Safe-roam gate: the robot stays still while the camera is learning or unsure.
        if (!groundReady) {
            sink.autoStop();
            sink.onCompanionStatus("Safe roam waiting for table guard…");
            scheduleNextRoam(450L);
            return;
        }
        if (!groundSafe) {
            sink.autoStop();
            sink.onCompanionStatus("Safe roam paused — checking surface 👀");
            scheduleNextRoam(350L);
            return;
        }

        int pick = random.nextInt(100);
        boolean faceLooksClose = facePresent && faceSize > 0.16f;

        // More natural but conservative roaming: look around, then make very short
        // forward pulses only while camera safety is green. No random reverse motion.
        if (faceLooksClose || pick < 58) {
            int turn = random.nextBoolean() ? +1 : -1;
            sink.autoPulse(0, turn, 10 + random.nextInt(4), 150L + random.nextInt(90));
            scheduleNextRoam(950L);
        } else {
            // Short/slower forward step so camera gets another chance to inspect the
            // next patch before any further movement.
            sink.autoPulse(+1, 0, 8 + random.nextInt(3), 115L + random.nextInt(55));
            scheduleNextRoam(1050L);
        }
        sink.showTemporaryEmotion(Emotion.CURIOUS, 550L);
    }

    private void runEdgeRecovery(long now) {
        if (now < edgeRecoveryNextMs) return;

        if (edgeRecoveryStep == 0) {
            // We only reverse after a *front* edge detection and only by a tiny amount.
            // This is not used as random roaming because the rear is not visible.
            sink.autoPulse(-1, 0, 7, 105L);
            edgeRecoveryStep = 1;
            edgeRecoveryNextMs = now + 300L;
            return;
        }

        if (edgeRecoveryStep == 1) {
            sink.autoPulse(0, recoveryTurn, 10, 230L);
            edgeRecoveryStep = 2;
            edgeRecoveryNextMs = now + 520L;
            return;
        }

        // Finish the physical escape. Then wait until the camera reports a stable safe
        // surface again before any forward motion.
        sink.autoStop();
        edgeRecoveryActive = false;
        edgeRecoveryStep = 0;
        scheduleNextRoam(1200L);
        if (groundSafe && !edgeRisk) {
            sink.onCompanionStatus("Edge avoided ✅");
        } else {
            sink.onCompanionStatus("Edge avoided — waiting for safe view");
        }
    }

    private void scheduleNextRoam(long minimumDelayMs) {
        long extra = 550L + random.nextInt(1050);
        nextRoamMs = System.currentTimeMillis() + minimumDelayMs + extra;
    }
}
