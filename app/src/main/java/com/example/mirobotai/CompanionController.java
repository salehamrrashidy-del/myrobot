package com.example.mirobotai;

import android.os.Handler;
import android.os.Looper;

import java.util.Random;

/**
 * Autonomous behaviour layer. It never sends raw BLE packets itself.
 * Movement stays pulse-based so MainActivity can apply the visual edge guard.
 */
public class CompanionController {
    public interface MotionSink {
        boolean robotReady();
        /** True when the visual safety gate is calibrated/current, or when it is disabled. */
        boolean safetyKnown();
        /** True when a short FORWARD pulse is allowed by the current safety policy. */
        boolean forwardSafe();
        /** -1 = possible edge on left, +1 = right, 0 = center/unknown. */
        int unsafeEdgeSide();
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
    private long nextSearchMs = 0L;
    private long manualPauseUntilMs = 0L;
    private long lastTrackMoveMs = 0L;
    private long lastApproachMs = 0L;
    private long faceCenteredSinceMs = 0L;

    private final Runnable loop = new Runnable() {
        @Override public void run() {
            tick();
            handler.postDelayed(this, 90L);
        }
    };

    public CompanionController(MotionSink sink) {
        this.sink = sink;
        scheduleNextRoam(450L);
        scheduleNextSearch(650L);
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
        faceCenteredSinceMs = 0L;
        scheduleNextSearch(250L);
        if (!enabled && !roamMode) sink.autoStop();
        sink.onCompanionStatus(enabled
                ? "Companion ON — I’ll look for you and follow gently"
                : "Companion mode OFF");
    }

    public void setRoamMode(boolean enabled) {
        roamMode = enabled;
        scheduleNextRoam(250L);
        if (!enabled && !companionMode) sink.autoStop();
        sink.onCompanionStatus(enabled
                ? "Free roam ON — short cautious moves"
                : "Free roam OFF");
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
        if (System.currentTimeMillis() - lastFaceMs > 700L) {
            facePresent = false;
            faceCenteredSinceMs = 0L;
        }
    }

    public void manualOverride() {
        pauseFor(2200L);
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

        if (facePresent && now - lastFaceMs > 900L) {
            facePresent = false;
            faceCenteredSinceMs = 0L;
        }

        // When the edge guard is ON but not calibrated/current, do not move at all.
        if ((companionMode || roamMode) && !sink.safetyKnown()) {
            sink.autoStop();
            sink.onCompanionStatus("Autonomy waiting — calibrate the table edge guard");
            return;
        }

        // If an edge is actually detected, companion mode stops. Free roam may make
        // one tiny turn-away pulse, but never a forward/reverse pulse.
        if ((companionMode || roamMode) && !sink.forwardSafe()) {
            if (roamMode && now >= nextRoamMs) roamStep();
            else sink.autoStop();
            return;
        }

        // Companion mode: actively search for a face, turn toward it, then approach
        // only while the camera safety gate says forward motion is okay.
        if (companionMode) {
            if (facePresent) {
                if (trackAndApproachFace(now)) return;
            } else if (!roamMode && now >= nextSearchMs) {
                // No face: visibly scan instead of looking "dead".
                int turn = random.nextBoolean() ? +1 : -1;
                sink.autoPulse(0, turn, 14 + random.nextInt(4), 230L + random.nextInt(90));
                sink.onCompanionStatus("Companion — looking for you 👀");
                scheduleNextSearch(650L);
                return;
            }
        }

        // If both modes are on and no face is visible, roam can explore while searching.
        if (roamMode && now >= nextRoamMs) {
            roamStep();
        }
    }

    /** Returns true when companion mode consumed this tick. */
    private boolean trackAndApproachFace(long now) {
        float error = Math.abs(faceX);

        if (error > 0.10f && now - lastTrackMoveMs > 110L) {
            int turn = faceX > 0 ? +1 : -1;
            int delta;
            long pulseMs;
            if (error > 0.55f) {
                delta = 23;
                pulseMs = 250L;
            } else if (error > 0.30f) {
                delta = 20;
                pulseMs = 220L;
            } else if (error > 0.18f) {
                delta = 17;
                pulseMs = 190L;
            } else {
                delta = 14;
                pulseMs = 155L;
            }
            sink.autoPulse(0, turn, delta, pulseMs);
            sink.onCompanionStatus("Companion — turning toward you");
            lastTrackMoveMs = now;
            faceCenteredSinceMs = 0L;
            return true;
        }

        if (error <= 0.10f) {
            if (faceCenteredSinceMs == 0L) faceCenteredSinceMs = now;

            // Face width is a rough distance cue. If the face is small, move closer.
            // Never reverse automatically on a table because the front camera cannot
            // protect the robot from a cliff behind it.
            if (faceSize > 0f && faceSize < 0.17f && now - lastApproachMs > 650L) {
                if (sink.forwardSafe()) {
                    sink.autoPulse(+1, 0, 13, 270L);
                    sink.onCompanionStatus("Companion — coming a little closer");
                    lastApproachMs = now;
                } else {
                    sink.autoStop();
                    sink.onCompanionStatus("Companion — edge guard blocked forward movement");
                }
                return true;
            }

            sink.autoStop();
            sink.onCompanionStatus(faceSize >= 0.24f
                    ? "Companion — close enough 🙂"
                    : "Companion — with you 👀");
            return true;
        }

        return false;
    }

    private void roamStep() {
        // When forward is unsafe/unknown, do not keep nudging toward the edge.
        if (!sink.forwardSafe()) {
            int side = sink.unsafeEdgeSide();
            int turn;
            if (side < 0) turn = +1;       // possible edge left -> turn right
            else if (side > 0) turn = -1;  // possible edge right -> turn left
            else turn = random.nextBoolean() ? +1 : -1;

            sink.autoPulse(0, turn, 14, 180L);
            sink.showTemporaryEmotion(Emotion.SURPRISED, 700L);
            sink.onCompanionStatus("Edge guard — turning away, no forward move");
            scheduleNextRoam(750L);
            return;
        }

        int pick = random.nextInt(100);
        if (pick < 48) {
            // Forward exploration is deliberately short and slow.
            sink.autoPulse(+1, 0, 13 + random.nextInt(4), 330L + random.nextInt(150));
            sink.onCompanionStatus("Free roam — exploring");
        } else if (pick < 90) {
            int turn = random.nextBoolean() ? +1 : -1;
            sink.autoPulse(0, turn, 14 + random.nextInt(5), 230L + random.nextInt(130));
            sink.onCompanionStatus("Free roam — looking around");
        } else {
            sink.autoStop();
            sink.onCompanionStatus("Free roam — checking the room 👀");
        }
        sink.showTemporaryEmotion(Emotion.CURIOUS, 650L);
        scheduleNextRoam(650L);
    }

    private void scheduleNextRoam(long minimumDelayMs) {
        long extra = 650L + random.nextInt(1200);
        nextRoamMs = System.currentTimeMillis() + minimumDelayMs + extra;
    }

    private void scheduleNextSearch(long minimumDelayMs) {
        nextSearchMs = System.currentTimeMillis() + minimumDelayMs + 600L + random.nextInt(1000);
    }
}
