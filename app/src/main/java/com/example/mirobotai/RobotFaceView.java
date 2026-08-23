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

public class RobotFaceView extends View {
    private final Paint white = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dark = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint accent = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();

    private Emotion emotion = Emotion.NORMAL;
    private float blink = 1f;
    private float lookX = 0f;
    private float mouthPulse = 0f;
    private boolean talking = false;
    private ValueAnimator talkingAnimator;

    private final Runnable blinkRunnable = new Runnable() {
        @Override public void run() {
            blinkOnce();
            handler.postDelayed(this, 2600L + random.nextInt(3000));
        }
    };

    public RobotFaceView(Context context) { super(context); init(); }
    public RobotFaceView(Context context, AttributeSet attrs) { super(context, attrs); init(); }
    public RobotFaceView(Context context, AttributeSet attrs, int style) { super(context, attrs, style); init(); }

    private void init() {
        setBackgroundColor(Color.rgb(7, 11, 16));
        white.setColor(Color.WHITE);
        dark.setColor(Color.rgb(7, 11, 16));
        accent.setColor(Color.rgb(137, 222, 255));
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        handler.postDelayed(blinkRunnable, 1800L);
    }

    public void setEmotion(Emotion emotion) {
        if (emotion == null) emotion = Emotion.NORMAL;
        this.emotion = emotion;
        invalidate();
    }

    public Emotion getEmotion() { return emotion; }

    public void look(float x) {
        lookX = Math.max(-1f, Math.min(1f, x));
        invalidate();
    }

    public void setTalking(boolean value) {
        talking = value;
        if (talkingAnimator != null) talkingAnimator.cancel();
        if (value) {
            talkingAnimator = ValueAnimator.ofFloat(0f, 1f);
            talkingAnimator.setDuration(320L);
            talkingAnimator.setRepeatMode(ValueAnimator.REVERSE);
            talkingAnimator.setRepeatCount(ValueAnimator.INFINITE);
            talkingAnimator.addUpdateListener(a -> {
                mouthPulse = (float) a.getAnimatedValue();
                invalidate();
            });
            talkingAnimator.start();
        } else {
            mouthPulse = 0f;
            invalidate();
        }
    }

    private void blinkOnce() {
        ValueAnimator a = ValueAnimator.ofFloat(1f, 0.05f, 1f);
        a.setDuration(220L);
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

        float cx1 = w * 0.34f;
        float cx2 = w * 0.66f;
        float cy = h * 0.46f;
        float eyeW = Math.min(w * 0.16f, h * 0.25f);
        float eyeH = Math.min(w * 0.12f, h * 0.34f);

        float emotionHeight = 1f;
        switch (emotion) {
            case SLEEPY: emotionHeight = 0.28f; break;
            case BORED: emotionHeight = 0.55f; break;
            case UPSET: emotionHeight = 0.58f; break;
            case SURPRISED: emotionHeight = 1.18f; break;
            case EXCITED: emotionHeight = 1.08f; break;
            default: break;
        }
        float actualH = eyeH * emotionHeight * blink;
        actualH = Math.max(5f, actualH);

        drawEye(canvas, cx1, cy, eyeW, actualH, true);
        drawEye(canvas, cx2, cy, eyeW, actualH, false);

        if (emotion == Emotion.UPSET) drawUpsetBrows(canvas, cx1, cx2, cy, eyeW, eyeH);
        if (emotion == Emotion.CURIOUS) drawCuriousBrows(canvas, cx1, cx2, cy, eyeW, eyeH);

        if (talking || emotion == Emotion.TALKING) {
            float mouthW = w * (0.045f + 0.018f * mouthPulse);
            float mouthH = h * (0.035f + 0.030f * mouthPulse);
            RectF mouth = new RectF(w/2f-mouthW, h*0.68f-mouthH, w/2f+mouthW, h*0.68f+mouthH);
            canvas.drawOval(mouth, accent);
        } else if (emotion == Emotion.HAPPY || emotion == Emotion.EXCITED) {
            Paint p = new Paint(accent);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(7f, h * 0.012f));
            p.setStrokeCap(Paint.Cap.ROUND);
            RectF smile = new RectF(w*0.45f, h*0.61f, w*0.55f, h*0.72f);
            canvas.drawArc(smile, 20, 140, false, p);
        }
    }

    private void drawEye(Canvas canvas, float cx, float cy, float eyeW, float eyeH, boolean left) {
        RectF r = new RectF(cx-eyeW/2f, cy-eyeH/2f, cx+eyeW/2f, cy+eyeH/2f);
        float radius = Math.min(eyeW, eyeH) * 0.48f;

        if (emotion == Emotion.HAPPY || emotion == Emotion.EXCITED) {
            Paint p = new Paint(white);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(14f, eyeH * 0.24f));
            p.setStrokeCap(Paint.Cap.ROUND);
            RectF arc = new RectF(cx-eyeW/2f, cy-eyeH*0.58f, cx+eyeW/2f, cy+eyeH*0.65f);
            canvas.drawArc(arc, 205, 130, false, p);
            return;
        }

        canvas.drawRoundRect(r, radius, radius, white);

        float pupilR = Math.min(eyeW, eyeH) * (emotion == Emotion.SURPRISED ? 0.14f : 0.18f);
        float px = cx + lookX * eyeW * 0.16f;
        float py = cy;
        canvas.drawCircle(px, py, pupilR, dark);

        // tiny highlight gives the eyes a softer character feel
        canvas.drawCircle(px - pupilR*0.28f, py - pupilR*0.28f, pupilR*0.18f, accent);
    }

    private void drawUpsetBrows(Canvas canvas, float cx1, float cx2, float cy, float eyeW, float eyeH) {
        Paint p = new Paint(accent);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(7f, getHeight()*0.012f));
        p.setStrokeCap(Paint.Cap.ROUND);
        float y = cy - eyeH*0.72f;
        canvas.drawLine(cx1-eyeW*0.38f, y-eyeH*0.12f, cx1+eyeW*0.32f, y+eyeH*0.08f, p);
        canvas.drawLine(cx2-eyeW*0.32f, y+eyeH*0.08f, cx2+eyeW*0.38f, y-eyeH*0.12f, p);
    }

    private void drawCuriousBrows(Canvas canvas, float cx1, float cx2, float cy, float eyeW, float eyeH) {
        Paint p = new Paint(accent);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(7f, getHeight()*0.012f));
        p.setStrokeCap(Paint.Cap.ROUND);
        float y = cy - eyeH*0.75f;
        canvas.drawLine(cx1-eyeW*0.35f, y+eyeH*0.05f, cx1+eyeW*0.35f, y-eyeH*0.12f, p);
        canvas.drawLine(cx2-eyeW*0.35f, y-eyeH*0.05f, cx2+eyeW*0.35f, y+eyeH*0.05f, p);
    }

    @Override protected void onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null);
        if (talkingAnimator != null) talkingAnimator.cancel();
        super.onDetachedFromWindow();
    }
}
