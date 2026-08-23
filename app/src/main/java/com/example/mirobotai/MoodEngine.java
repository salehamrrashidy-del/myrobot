package com.example.mirobotai;

import android.os.Handler;
import android.os.Looper;

public class MoodEngine {
    public interface Listener {
        void onMoodChanged(int mood, Emotion emotion);
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private int mood = 78;
    private long lastInteractionMs = System.currentTimeMillis();
    private boolean sleeping = false;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            long idleMs = System.currentTimeMillis() - lastInteractionMs;
            if (!sleeping) {
                // Slow decay: the robot gets bored first, then mildly upset.
                if (idleMs > 20 * 60_000L) mood -= 4;
                else if (idleMs > 10 * 60_000L) mood -= 3;
                else if (idleMs > 5 * 60_000L) mood -= 2;
                else if (idleMs > 2 * 60_000L) mood -= 1;
                mood = clamp(mood, 8, 100);
            }
            publish();
            handler.postDelayed(this, 60_000L);
        }
    };

    public MoodEngine(Listener listener) {
        this.listener = listener;
    }

    public void start() {
        handler.removeCallbacks(tick);
        publish();
        handler.postDelayed(tick, 60_000L);
    }

    public void stop() {
        handler.removeCallbacks(tick);
    }

    public void interacted() {
        sleeping = false;
        lastInteractionMs = System.currentTimeMillis();
        mood = clamp(mood + 12, 0, 100);
        publish();
    }

    public void talkedTo() {
        sleeping = false;
        lastInteractionMs = System.currentTimeMillis();
        mood = clamp(mood + 18, 0, 100);
        publish();
    }

    public void setSleeping(boolean value) {
        sleeping = value;
        publish();
    }

    public int getMood() {
        return mood;
    }

    public Emotion currentEmotion() {
        if (sleeping) return Emotion.SLEEPY;
        long idleMs = System.currentTimeMillis() - lastInteractionMs;
        if (idleMs > 20 * 60_000L || mood < 30) return Emotion.UPSET;
        if (idleMs > 8 * 60_000L || mood < 48) return Emotion.BORED;
        if (idleMs > 3 * 60_000L) return Emotion.CURIOUS;
        if (mood >= 88) return Emotion.HAPPY;
        return Emotion.NORMAL;
    }

    private void publish() {
        if (listener != null) listener.onMoodChanged(mood, currentEmotion());
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
