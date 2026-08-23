package com.example.mirobotai;

import android.media.Image;

import java.nio.ByteBuffer;

/**
 * Lightweight visual cliff / table-edge heuristic using the camera luminance plane.
 *
 * IMPORTANT: this is only a helper. A phone camera is not a true cliff sensor.
 * Forward motion should stay slow and short, and the robot should not be left
 * unattended on a high surface.
 */
public final class SurfaceSafetyDetector {
    public static final class Result {
        public final boolean calibrated;
        public final boolean safe;
        public final float confidence; // 0..1
        public final int edgeSide;      // -1 = left, +1 = right, 0 = center/unknown
        public final String reason;

        Result(boolean calibrated, boolean safe, float confidence, int edgeSide, String reason) {
            this.calibrated = calibrated;
            this.safe = safe;
            this.confidence = confidence;
            this.edgeSide = edgeSide;
            this.reason = reason;
        }
    }

    private static final int CALIBRATION_FRAMES = 18;
    private static final int UNSAFE_CONFIRM_FRAMES = 2;
    private static final int SAFE_CONFIRM_FRAMES = 3;

    private boolean calibrationRequested = false;
    private boolean calibrated = false;
    private int calibrationCount = 0;

    private float baseCenterDiff = 0f;
    private float baseCenterAhead = 0f;
    private float baseCenterNear = 0f;
    private float baseCenterGradient = 0f;
    private float baseLeftDiff = 0f;
    private float baseRightDiff = 0f;

    private int unsafeStreak = 0;
    private int safeStreak = 0;
    private boolean latchedSafe = false;

    public synchronized void requestCalibration() {
        calibrationRequested = true;
        calibrated = false;
        calibrationCount = 0;
        baseCenterDiff = 0f;
        baseCenterAhead = 0f;
        baseCenterNear = 0f;
        baseCenterGradient = 0f;
        baseLeftDiff = 0f;
        baseRightDiff = 0f;
        unsafeStreak = 0;
        safeStreak = 0;
        latchedSafe = false;
    }

    public synchronized boolean isCalibrated() {
        return calibrated;
    }

    /** Analyze one camera frame. Caller should use the result only for short, slow movement. */
    public synchronized Result analyze(Image image, int rotationDegrees) {
        if (image == null || image.getPlanes() == null || image.getPlanes().length == 0) {
            return new Result(calibrated, false, 0f, 0, "no camera luminance");
        }

        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer().duplicate();
        int rawWidth = image.getWidth();
        int rawHeight = image.getHeight();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        if (rawWidth <= 0 || rawHeight <= 0 || rowStride <= 0 || pixelStride <= 0) {
            return new Result(calibrated, false, 0f, 0, "camera geometry unavailable");
        }

        // Features are sampled in DISPLAY coordinates (after CameraX rotation), so
        // the lower part means "toward the bottom of the robot's view" in landscape.
        float centerAhead = mean(buffer, rawWidth, rawHeight, rowStride, pixelStride,
                rotationDegrees, 0.28f, 0.72f, 0.56f, 0.70f);
        float centerNear = mean(buffer, rawWidth, rawHeight, rowStride, pixelStride,
                rotationDegrees, 0.28f, 0.72f, 0.76f, 0.93f);
        float leftAhead = mean(buffer, rawWidth, rawHeight, rowStride, pixelStride,
                rotationDegrees, 0.05f, 0.44f, 0.57f, 0.73f);
        float leftNear = mean(buffer, rawWidth, rawHeight, rowStride, pixelStride,
                rotationDegrees, 0.05f, 0.44f, 0.77f, 0.94f);
        float rightAhead = mean(buffer, rawWidth, rawHeight, rowStride, pixelStride,
                rotationDegrees, 0.56f, 0.95f, 0.57f, 0.73f);
        float rightNear = mean(buffer, rawWidth, rawHeight, rowStride, pixelStride,
                rotationDegrees, 0.56f, 0.95f, 0.77f, 0.94f);

        float centerDiff = Math.abs(centerNear - centerAhead);
        float leftDiff = Math.abs(leftNear - leftAhead);
        float rightDiff = Math.abs(rightNear - rightAhead);
        float centerGradient = verticalGradient(buffer, rawWidth, rawHeight, rowStride, pixelStride,
                rotationDegrees, 0.18f, 0.82f, 0.56f, 0.90f);

        if (calibrationRequested) {
            calibrationCount++;
            baseCenterDiff += centerDiff;
            baseCenterAhead += centerAhead;
            baseCenterNear += centerNear;
            baseCenterGradient += centerGradient;
            baseLeftDiff += leftDiff;
            baseRightDiff += rightDiff;

            if (calibrationCount >= CALIBRATION_FRAMES) {
                float n = calibrationCount;
                baseCenterDiff /= n;
                baseCenterAhead /= n;
                baseCenterNear /= n;
                baseCenterGradient /= n;
                baseLeftDiff /= n;
                baseRightDiff /= n;
                calibrated = true;
                calibrationRequested = false;
                safeStreak = SAFE_CONFIRM_FRAMES;
                latchedSafe = true;
                return new Result(true, true, 1f, 0, "safe surface calibrated");
            }
            return new Result(false, false, 0f, 0,
                    "calibrating " + calibrationCount + "/" + CALIBRATION_FRAMES);
        }

        if (!calibrated) {
            return new Result(false, false, 0f, 0, "tap Calibrate safe surface");
        }

        // Relative-to-baseline scoring is more robust than using one absolute brightness
        // threshold because tables can be white, dark, wood-grain, etc.
        float centerScore =
                Math.abs(centerDiff - baseCenterDiff) * 1.15f
                        + Math.abs(centerAhead - baseCenterAhead) * 0.55f
                        + Math.abs(centerNear - baseCenterNear) * 0.28f
                        + Math.max(0f, centerGradient - baseCenterGradient) * 0.75f;

        float leftScore = Math.abs(leftDiff - baseLeftDiff)
                + Math.abs(centerAhead - baseCenterAhead) * 0.20f;
        float rightScore = Math.abs(rightDiff - baseRightDiff)
                + Math.abs(centerAhead - baseCenterAhead) * 0.20f;

        // These thresholds intentionally prefer false stops over a missed cliff.
        boolean rawUnsafe = centerScore > 34f
                || centerGradient > baseCenterGradient + 28f
                || Math.max(leftScore, rightScore) > 38f;

        int side = 0;
        if (rawUnsafe) {
            if (leftScore > rightScore + 5f) side = -1;
            else if (rightScore > leftScore + 5f) side = +1;
        }

        if (rawUnsafe) {
            unsafeStreak++;
            safeStreak = 0;
            if (unsafeStreak >= UNSAFE_CONFIRM_FRAMES) latchedSafe = false;
        } else {
            safeStreak++;
            unsafeStreak = 0;
            if (safeStreak >= SAFE_CONFIRM_FRAMES) latchedSafe = true;
        }

        float confidence = clamp01(Math.max(centerScore / 55f,
                Math.max(leftScore, rightScore) / 55f));
        if (!rawUnsafe) confidence = 1f - Math.min(0.85f, confidence);

        String reason;
        if (!latchedSafe) {
            reason = side < 0 ? "possible edge on left"
                    : side > 0 ? "possible edge on right"
                    : "possible table edge ahead";
        } else {
            reason = "surface looks safe";
        }

        return new Result(true, latchedSafe, confidence, side, reason);
    }

    private static float mean(ByteBuffer b, int rawW, int rawH, int rowStride, int pixelStride,
                              int rotation, float u0, float u1, float v0, float v1) {
        final int sx = 14;
        final int sy = 8;
        long sum = 0L;
        int count = 0;
        for (int iy = 0; iy < sy; iy++) {
            float v = v0 + (v1 - v0) * (iy + 0.5f) / sy;
            for (int ix = 0; ix < sx; ix++) {
                float u = u0 + (u1 - u0) * (ix + 0.5f) / sx;
                int[] xy = displayToRaw(u, v, rawW, rawH, rotation);
                int index = xy[1] * rowStride + xy[0] * pixelStride;
                if (index >= 0 && index < b.limit()) {
                    sum += (b.get(index) & 0xFF);
                    count++;
                }
            }
        }
        return count == 0 ? 0f : sum / (float) count;
    }

    private static float verticalGradient(ByteBuffer b, int rawW, int rawH,
                                          int rowStride, int pixelStride, int rotation,
                                          float u0, float u1, float v0, float v1) {
        final int sx = 16;
        final int sy = 10;
        float sum = 0f;
        int count = 0;
        float dv = (v1 - v0) / (sy + 1f);
        for (int iy = 0; iy < sy; iy++) {
            float v = v0 + dv * (iy + 1f);
            float v2 = Math.min(v1, v + 0.022f);
            for (int ix = 0; ix < sx; ix++) {
                float u = u0 + (u1 - u0) * (ix + 0.5f) / sx;
                int[] a = displayToRaw(u, v, rawW, rawH, rotation);
                int[] c = displayToRaw(u, v2, rawW, rawH, rotation);
                int ia = a[1] * rowStride + a[0] * pixelStride;
                int ic = c[1] * rowStride + c[0] * pixelStride;
                if (ia >= 0 && ia < b.limit() && ic >= 0 && ic < b.limit()) {
                    sum += Math.abs((b.get(ia) & 0xFF) - (b.get(ic) & 0xFF));
                    count++;
                }
            }
        }
        return count == 0 ? 0f : sum / count;
    }

    /** Map normalized display-space coordinates to raw Y-plane coordinates. */
    private static int[] displayToRaw(float u, float v, int w, int h, int rotation) {
        u = clamp01(u);
        v = clamp01(v);
        int x;
        int y;
        int r = ((rotation % 360) + 360) % 360;
        if (r == 90) {
            x = Math.round(v * (w - 1));
            y = Math.round((1f - u) * (h - 1));
        } else if (r == 180) {
            x = Math.round((1f - u) * (w - 1));
            y = Math.round((1f - v) * (h - 1));
        } else if (r == 270) {
            x = Math.round((1f - v) * (w - 1));
            y = Math.round(u * (h - 1));
        } else {
            x = Math.round(u * (w - 1));
            y = Math.round(v * (h - 1));
        }
        x = Math.max(0, Math.min(w - 1, x));
        y = Math.max(0, Math.min(h - 1, y));
        return new int[]{x, y};
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
