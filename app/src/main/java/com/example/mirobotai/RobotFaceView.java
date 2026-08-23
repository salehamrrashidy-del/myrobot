package com.example.mirobotai;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;

import java.util.Random;

/**
 * Minimal two-eye companion face inspired by the clean LOOI look.
 * Emotions are expressed with only eye shape, position, size and motion.
 */
public class RobotFaceView extends View {
    private static final int BG = Color.rgb(3, 5, 7);
    private static final int CYAN = Color.rgb(127, 224, 239);
    private static final int CYAN_BRIGHT = Color.rgb(157, 239, 249);
    private static final int BLUE_SHADOW = Color.rgb(66, 67, 218);

    private final Paint eyePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();

    private Emotion emotion = Emotion.NORMAL;
    private float blink = 1f;
    private float lookX = 0f;
    private float lookY = 0f;
    private float talkPulse = 0f;
    private boolean talking = false;
    private ValueAnimator talkingAnimator;
    private ValueAnimator glanceAnimator;

    private final Runnable blinkRunnable = new Runnable() {
        @Override public void run() {
            blinkOnce();
            handler.postDelayed(this, 2500L + random.nextInt(3200));
        }
    };

    private final Runnable glanceRunnable = new Runnable() {
        @Override public void run() {
            if (!talking && emotion != Emotion.SLEEPY) {
                float targetX = (random.nextFloat() * 2f - 1f) * 0.65f;
                float targetY = (random.nextFloat() * 2f - 1f) * 0.18f;
                animateGlance(targetX, targetY);
            }
            handler.postDelayed(this, 1800L + random.nextInt(2600));
        }
    };

    public RobotFaceView(Context context) { super(context); init(); }
    public RobotFaceView(Context context, AttributeSet attrs) { super(context, attrs); init(); }
    public RobotFaceView(Context context, AttributeSet attrs, int style) { super(context, attrs, style); init(); }

    private void init() {
        setBackgroundColor(BG);
        eyePaint.setColor(CYAN);
        shadowPaint.setColor(BLUE_SHADOW);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        handler.postDelayed(blinkRunnable, 1500L);
        handler.postDelayed(glanceRunnable, 1200L);
    }

    public void setEmotion(Emotion emotion) {
        if (emotion == null) emotion = Emotion.NORMAL;
        this.emotion = emotion;
        invalidate();
    }

    public Emotion getEmotion() { return emotion; }

    public void look(float x) {
        animateGlance(Math.max(-1f, Math.min(1f, x)), 0f);
    }

    public void setTalking(boolean value) {
        talking = value;
        if (talkingAnimator != null) talkingAnimator.cancel();
        if (value) {
            talkingAnimator = ValueAnimator.ofFloat(0f, 1f);
            talkingAnimator.setDuration(420L);
            talkingAnimator.setRepeatMode(ValueAnimator.REVERSE);
            talkingAnimator.setRepeatCount(ValueAnimator.INFINITE);
            talkingAnimator.addUpdateListener(a -> {
                talkPulse = (float) a.getAnimatedValue();
                invalidate();
            });
            talkingAnimator.start();
        } else {
            talkPulse = 0f;
            invalidate();
        }
    }

    private void animateGlance(float targetX, float targetY) {
        if (glanceAnimator != null) glanceAnimator.cancel();
        final float startX = lookX;
        final float startY = lookY;
        glanceAnimator = ValueAnimator.ofFloat(0f, 1f);
        glanceAnimator.setDuration(430L);
        glanceAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        glanceAnimator.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            lookX = startX + (targetX - startX) * t;
            lookY = startY + (targetY - startY) * t;
            invalidate();
        });
        glanceAnimator.start();
    }

    private void blinkOnce() {
        ValueAnimator a = ValueAnimator.ofFloat(1f, 0.08f, 1f);
        a.setDuration(190L);
        a.setInterpolator(new AccelerateDecelerateInterpolator());
        a.addUpdateListener(v -> {
            blink = (float) v.getAnimatedValue();
            invalidate();
        });
        a.start();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;

        // LOOI-like composition: two simple floating eyes with lots of empty black space.
        float baseY = h * 0.49f;
        float leftX = w * 0.37f;
        float rightX = w * 0.63f;
        float eyeW = Math.min(w * 0.135f, h * 0.27f);
        float eyeH = eyeW * 0.92f;
        float gapMoveX = lookX * w * 0.026f;
        float gapMoveY = lookY * h * 0.025f;

        float sizeL = 1f;
        float sizeR = 1f;
        float yL = baseY;
        float yR = baseY;

        switch (emotion) {
            case CURIOUS:
                sizeL = 1.09f;
                sizeR = 0.91f;
                yL -= h * 0.012f;
                yR += h * 0.006f;
                break;
            case SURPRISED:
                sizeL = sizeR = 1.18f;
                break;
            case BORED:
                yL += h * 0.020f;
                yR += h * 0.020f;
                break;
            case UPSET:
                yL += h * 0.012f;
                yR += h * 0.012f;
                break;
            case EXCITED:
                sizeL = sizeR = 1.08f + talkPulse * 0.03f;
                break;
            case TALKING:
                sizeL = sizeR = 1.0f + talkPulse * 0.045f;
                yL -= talkPulse * h * 0.008f;
                yR -= talkPulse * h * 0.008f;
                break;
            default:
                break;
        }

        drawEye(canvas, leftX + gapMoveX, yL + gapMoveY, eyeW * sizeL, eyeH * sizeL, true);
        drawEye(canvas, rightX + gapMoveX, yR + gapMoveY, eyeW * sizeR, eyeH * sizeR, false);
    }

    private void drawEye(Canvas canvas, float cx, float cy, float eyeW, float eyeH, boolean left) {
        float eyeBlink = blink;

        // Happy / excited = soft closed domes, using eyes only (no mouth).
        if (emotion == Emotion.HAPPY || emotion == Emotion.EXCITED) {
            drawHappyEye(canvas, cx, cy, eyeW, eyeH, eyeBlink);
            return;
        }

        // Sleepy = tiny rounded horizontal cyan bars.
        if (emotion == Emotion.SLEEPY) {
            drawPillEye(canvas, cx, cy, eyeW * 0.92f, Math.max(8f, eyeH * 0.14f * eyeBlink));
            return;
        }

        float heightFactor = 1f;
        if (emotion == Emotion.BORED) heightFactor = 0.46f;
        else if (emotion == Emotion.UPSET) heightFactor = 0.58f;
        else if (emotion == Emotion.TALKING) heightFactor = 0.88f + 0.12f * talkPulse;

        float actualH = Math.max(7f, eyeH * heightFactor * eyeBlink);
        float y = cy;

        if (emotion == Emotion.BORED) y += eyeH * 0.17f;
        if (emotion == Emotion.UPSET) y += eyeH * 0.09f;

        drawOvalWithShadow(canvas, cx, y, eyeW, actualH);

        // A very small asymmetric tilt for "upset" without adding eyebrows or a mouth.
        if (emotion == Emotion.UPSET && blink > 0.3f) {
            Paint lid = new Paint(Paint.ANTI_ALIAS_FLAG);
            lid.setColor(BG);
            Path p = new Path();
            float top = y - actualH / 2f;
            if (left) {
                p.moveTo(cx - eyeW * 0.58f, top - eyeH * 0.08f);
                p.lineTo(cx + eyeW * 0.58f, top + eyeH * 0.16f);
            } else {
                p.moveTo(cx - eyeW * 0.58f, top + eyeH * 0.16f);
                p.lineTo(cx + eyeW * 0.58f, top - eyeH * 0.08f);
            }
            p.lineTo(cx + eyeW * 0.60f, top - eyeH * 0.30f);
            p.lineTo(cx - eyeW * 0.60f, top - eyeH * 0.30f);
            p.close();
            canvas.drawPath(p, lid);
        }
    }

    private void drawOvalWithShadow(Canvas canvas, float cx, float cy, float w, float h) {
        float radius = Math.min(w, h) * 0.50f;
        float shadowY = Math.max(5f, getHeight() * 0.010f);
        float shadowX = Math.max(2f, getWidth() * 0.003f);

        RectF shadow = new RectF(cx - w/2f + shadowX, cy - h/2f + shadowY,
                cx + w/2f + shadowX, cy + h/2f + shadowY);
        canvas.drawRoundRect(shadow, radius, radius, shadowPaint);

        eyePaint.setColor(emotion == Emotion.EXCITED ? CYAN_BRIGHT : CYAN);
        RectF eye = new RectF(cx - w/2f, cy - h/2f, cx + w/2f, cy + h/2f);
        canvas.drawRoundRect(eye, radius, radius, eyePaint);
    }

    private void drawPillEye(Canvas canvas, float cx, float cy, float w, float h) {
        drawOvalWithShadow(canvas, cx, cy, w, h);
    }

    private void drawHappyEye(Canvas canvas, float cx, float cy, float w, float h, float eyeBlink) {
        float activeH = Math.max(7f, h * 0.52f * eyeBlink);
        float bottom = cy + h * 0.22f;
        float top = bottom - activeH;
        float shadowY = Math.max(5f, getHeight() * 0.010f);
        float shadowX = Math.max(2f, getWidth() * 0.003f);

        // Rounded dome with a flat-ish lower edge, matching the minimal companion aesthetic.
        RectF shadowRect = new RectF(cx - w/2f + shadowX, top + shadowY,
                cx + w/2f + shadowX, bottom + shadowY);
        canvas.drawRoundRect(shadowRect, w * 0.48f, w * 0.48f, shadowPaint);

        eyePaint.setColor(CYAN_BRIGHT);
        RectF rect = new RectF(cx - w/2f, top, cx + w/2f, bottom);
        canvas.drawRoundRect(rect, w * 0.48f, w * 0.48f, eyePaint);

        // Mask the very bottom to create a calm half-eye silhouette.
        Paint mask = new Paint(Paint.ANTI_ALIAS_FLAG);
        mask.setColor(BG);
        canvas.drawRect(cx - w*0.56f, bottom - activeH*0.18f, cx + w*0.56f, bottom + h*0.25f, mask);
    }

    @Override protected void onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null);
        if (talkingAnimator != null) talkingAnimator.cancel();
        if (glanceAnimator != null) glanceAnimator.cancel();
        super.onDetachedFromWindow();
    }
}
