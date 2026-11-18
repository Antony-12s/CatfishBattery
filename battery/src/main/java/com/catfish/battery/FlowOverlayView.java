package com.catfish.battery;

import android.animation.ValueAnimator;
import android.content.Context;
import android.database.ContentObserver;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.animation.LinearInterpolator;

import org.json.JSONObject;

/** Moving dots over the static pack schematic. */
final class FlowOverlayView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] xy = new float[2];
    private final ValueAnimator animator = ValueAnimator.ofFloat(0, 1);
    private final ContentObserver durationScale = new ContentObserver(new Handler(Looper.getMainLooper())) {
        @Override public void onChange(boolean selfChange) {
            readDurationScale();
        }
    };
    private UiPalette palette;
    private boolean allowMotion;
    private boolean running, reverseHv;
    private int routes;
    private float phase;

    FlowOverlayView(Context context) {
        super(context);
        palette = UiPalette.of(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        animator.setDuration(1400);
        animator.setInterpolator(new LinearInterpolator());
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.addUpdateListener(animation -> {
            phase = animation.getAnimatedFraction();
            invalidate();
        });
    }

    void setData(JSONObject batteryData) {
        int active = 0;
        boolean reverse = false;
        if (batteryData != null && Boolean.TRUE.equals(batteryData.opt("streamConnected"))) {
            JSONObject flow = batteryData.optJSONObject("flow");
            double amps = number(batteryData, "battCurrentA");
            boolean plugged = FlowGeometry.plugged(number(flow, "obcHandle"), number(flow, "dcHandle"));
            active = FlowGeometry.activeRoutes(amps, plugged, Boolean.TRUE.equals(batteryData.opt("dcdcOn")));
            reverse = (active & (1 << FlowGeometry.HV)) != 0 && amps < 0;
        }
        if (active == routes && reverse == reverseHv) return;
        routes = active;
        reverseHv = reverse;
        updateAnimation();
    }

    private static double number(JSONObject object, String key) {
        Object value = object == null ? null : object.opt(key);
        return value instanceof Number ? ((Number) value).doubleValue() : Double.NaN;
    }

    void setRunning(boolean running) {
        if (this.running == running) return;
        this.running = running;
        updateAnimation();
    }

    void setNight(boolean night) {
        palette = UiPalette.of(night);
        invalidate();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getContext().getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, durationScale);
        readDurationScale();
    }

    @Override protected void onDetachedFromWindow() {
        getContext().getContentResolver().unregisterContentObserver(durationScale);
        animator.cancel();
        super.onDetachedFromWindow();
    }

    private void readDurationScale() {
        allowMotion = Settings.Global.getFloat(getContext().getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 1) > 0;
        updateAnimation();
    }

    private void updateAnimation() {
        if (running && routes != 0 && isAttachedToWindow() && allowMotion) {
            if (!animator.isStarted()) animator.start();
        } else {
            animator.cancel();
        }
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        if (!running || routes == 0) return;
        float scale = FlowGeometry.scale(getWidth(), getHeight());
        canvas.save();
        canvas.translate(FlowGeometry.originX(getWidth(), scale), FlowGeometry.originY(getHeight(), scale));
        canvas.scale(scale, scale);
        boolean moving = animator.isStarted();
        for (int route = 0; route <= FlowGeometry.CHARGER; route++) {
            if ((routes & (1 << route)) == 0) continue;
            boolean reverse = route == FlowGeometry.HV && reverseHv;
            paint.setColor(route == FlowGeometry.CHARGER || reverse ? palette.good : palette.accent);
            if (moving) {
                dot(canvas, route, reverse ? 1 - phase : phase);
                float second = (phase + 0.5f) % 1;
                dot(canvas, route, reverse ? 1 - second : second);
            } else {
                dot(canvas, route, 0.5f);
            }
        }
        canvas.restore();
    }

    private void dot(Canvas canvas, int route, float fraction) {
        FlowGeometry.position(route, fraction, xy);
        canvas.drawCircle(xy[0], xy[1], 6, paint);
    }
}
