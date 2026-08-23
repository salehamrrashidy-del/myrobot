package com.example.mirobotai;

import android.os.Handler;
import android.os.Looper;

/**
 * Hidden personality-state engine.
 *
 * There is intentionally NO single visible "mood" score anymore.
 * The character quietly keeps three independent internal meters:
 *   - happiness
 *   - curiosity
 *   - boredom
 *
 * They move slowly so the robot feels stable instead of changing personality
 * every few minutes. None of these numbers are shown on the robot face or in
 * the debug panel.
 */
public class MoodEngine {
    public interface Listener {
        void onEmotionChanged(Emotion emotion);
    }

    private static final long TICK_MS = 60_000L;          // check once a minute
    private static final long STATE_STEP_MS = 10 * 60_000L; // actual meter movement every 10 min

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Listener listener;

    // Hidden internal meters. 0..100.
    private int happiness = 72;
    private int curiosity = 38;
    private int boredom = 8;

    private long lastInteractionMs = System.currentTimeMillis();
    private long lastStateStepMs = System.currentTimeMillis();
    private boolean sleeping = false;
    private Emotion lastPublishedEmotion = Emotion.NORMAL;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            long now = System.currentTimeMillis();
            long idleMs = now - lastInteractionMs;

            if (!sleeping && now - lastStateStepMs >= STATE_STEP_MS) {
                // Work out how many 10-minute steps passed. This also behaves
                // sensibly if Android paused the app for a while.
                int steps = (int) ((now - lastStateStepMs) / STATE_STEP_MS);
                steps = Math.min(steps, 12); // never jump dramatically after a long pause

                for (int i = 0; i < steps; i++) {
                    applySlowIdleStep(idleMs);
                }
                lastStateStepMs += (long) steps * STATE_STEP_MS;
            }

            publishIfNeeded();
            handler.postDelayed(this, TICK_MS);
        }
    };

    public MoodEngine(Listener listener) {
        this.listener = listener;
    }

    public void start() {
        handler.removeCallbacks(tick);
        publishIfNeeded(true);
        handler.postDelayed(tick, TICK_MS);
    }

    public void stop() {
        handler.removeCallbacks(tick);
    }

    /** A tap/pet/normal interaction. Changes are deliberately small. */
    public void interacted() {
        sleeping = false;
        lastInteractionMs = System.currentTimeMillis();
        lastStateStepMs = lastInteractionMs;

        happiness = clamp(happiness + 4);
        curiosity = clamp(curiosity + 2);
        boredom = clamp(boredom - 6);
        publishIfNeeded();
    }

    /** A real conversation is a stronger positive interaction, but still gentle. */
    public void talkedTo() {
        sleeping = false;
        lastInteractionMs = System.currentTimeMillis();
        lastStateStepMs = lastInteractionMs;

        happiness = clamp(happiness + 6);
        curiosity = clamp(curiosity + 4);
        boredom = clamp(boredom - 10);
        publishIfNeeded();
    }

    /** Call later when the camera notices something new or interesting. */
    public void noticedSomethingInteresting() {
        curiosity = clamp(curiosity + 5);
        boredom = clamp(boredom - 2);
        publishIfNeeded();
    }

    public void setSleeping(boolean value) {
        sleeping = value;
        if (!value) {
            lastInteractionMs = System.currentTimeMillis();
            lastStateStepMs = lastInteractionMs;
        }
        publishIfNeeded(true);
    }

    /** Hidden values for the future AI personality logic. Not displayed in UI. */
    public int getHappiness() { return happiness; }
    public int getCuriosity() { return curiosity; }
    public int getBoredom() { return boredom; }

    public Emotion currentEmotion() {
        if (sleeping) return Emotion.SLEEPY;

        // Strong states need strong meter values, so expressions don't flip often.
        if (boredom >= 82 && happiness <= 32) return Emotion.UPSET;
        if (boredom >= 62) return Emotion.BORED;
        if (curiosity >= 68 && boredom < 55) return Emotion.CURIOUS;
        if (happiness >= 84 && boredom < 45) return Emotion.HAPPY;
        return Emotion.NORMAL;
    }

    private void applySlowIdleStep(long idleMs) {
        // First 10 minutes: essentially no personality penalty.
        if (idleMs < 10 * 60_000L) return;

        if (idleMs < 30 * 60_000L) {
            boredom = clamp(boredom + 1);
            curiosity = clamp(curiosity + 1);
            // happiness stays stable
        } else if (idleMs < 90 * 60_000L) {
            boredom = clamp(boredom + 2);
            curiosity = clamp(curiosity + 1);
            happiness = clamp(happiness - 1);
        } else {
            // Only after being ignored for a long time does he slowly become upset.
            boredom = clamp(boredom + 2);
            curiosity = clamp(curiosity - 1);
            happiness = clamp(happiness - 2);
        }
    }

    private void publishIfNeeded() {
        publishIfNeeded(false);
    }

    private void publishIfNeeded(boolean force) {
        Emotion emotion = currentEmotion();
        if (force || emotion != lastPublishedEmotion) {
            lastPublishedEmotion = emotion;
            if (listener != null) listener.onEmotionChanged(emotion);
        }
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }
}
