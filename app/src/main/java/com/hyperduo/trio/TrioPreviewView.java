package com.hyperduo.trio;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/**
 * Renders the trio glyph for the settings app's live preview.
 *
 * <p>It exists so the preview reuses {@link TrioRenderer} exactly as the status
 * bar does. A copy of the geometry inside the app would drift the moment either
 * side is tweaked, and the whole point of the preview is that what you see is
 * what the status bar will draw.
 *
 * <p>Nothing here touches the Xposed API: this class is loaded in the app's own
 * process.
 */
public final class TrioPreviewView extends View {

    private final Paint background = new Paint(Paint.ANTI_ALIAS_FLAG);

    private TrioSettings settings = TrioSettings.defaults();
    private int level = 72;
    private boolean charging;
    private boolean quickCharging;
    private boolean powerSave;
    private boolean low;
    private int wifiLevel = 3;
    private int mobileLevel = 4;
    private String mobileType = "";
    private int foreground = 0xFFFFFFFF;
    private int backgroundColor = 0xFF1C1B1F;

    /** Fraction of the shorter edge left empty around the glyph. */
    private float inset = 0.06f;

    public TrioPreviewView(Context context) {
        super(context);
    }

    public TrioPreviewView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public TrioPreviewView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setSettings(TrioSettings value) {
        this.settings = value == null ? TrioSettings.defaults() : value;
        invalidate();
    }

    public void setState(int level, boolean charging, boolean powerSave, boolean low,
                         int wifiLevel, int mobileLevel) {
        setState(level, charging, false, powerSave, low, wifiLevel, mobileLevel, null);
    }

    public void setState(int level, boolean charging, boolean powerSave, boolean low,
                         int wifiLevel, int mobileLevel, String mobileType) {
        setState(level, charging, false, powerSave, low, wifiLevel, mobileLevel, mobileType);
    }

    public void setState(int level, boolean charging, boolean quickCharging, boolean powerSave,
                         boolean low, int wifiLevel, int mobileLevel, String mobileType) {
        this.level = level;
        this.charging = charging;
        this.quickCharging = quickCharging;
        this.powerSave = powerSave;
        this.low = low;
        this.wifiLevel = wifiLevel;
        this.mobileLevel = mobileLevel;
        this.mobileType = mobileType == null ? "" : mobileType;
        invalidate();
    }

    public void setForeground(int color) {
        this.foreground = color;
        invalidate();
    }

    public void setPreviewBackground(int color) {
        this.backgroundColor = color;
        invalidate();
    }

    public void setInset(float fraction) {
        this.inset = Math.max(0f, Math.min(0.4f, fraction));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        final int w = getWidth();
        final int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }

        background.setStyle(Paint.Style.FILL);
        background.setColor(backgroundColor);
        canvas.drawRect(0f, 0f, w, h, background);

        final float pad = Math.min(w, h) * inset;
        final int innerW = Math.round(w - 2f * pad);
        final int innerH = Math.round(h - 2f * pad);
        if (innerW <= 0 || innerH <= 0) {
            return;
        }

        final int save = canvas.save();
        canvas.translate(pad, pad);
        // false: the glyph must not punch a hole through the preview background.
        TrioRenderer.drawInto(canvas, innerW, innerH, level, charging, quickCharging,
                powerSave, low, wifiLevel, mobileLevel, mobileType, foreground, settings, false);
        canvas.restoreToCount(save);
    }
}
