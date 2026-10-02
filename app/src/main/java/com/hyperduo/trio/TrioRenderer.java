package com.hyperduo.trio;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/**
 * Draws the "trio" glyph (battery ring + Wi-Fi arcs + signal dots + level indicator)
 * into a host view's canvas, in the 120x120 design space of {@link TrioGeometry}.
 */
final class TrioRenderer {

    private TrioRenderer() {
    }

    private static final Paint STROKE = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint FILL = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint TEXT = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Path BOLT = buildBolt();
    private static final Path WIFI_DOT = buildWifiDot();
    private static final RectF ARC = new RectF();

    /**
     * The weight currently installed on {@link #TEXT}; -1 until first applied.
     *
     * <p>Cached against the installed face rather than per text role: the
     * percentage and the network type can carry different weights and both may
     * be drawn in the same frame, so a role-keyed cache would make the second
     * role skip a swap it still needs. With the shared default (700) this is
     * still zero swaps per frame.
     */
    private static int sTextWeight = -1;

    /**
     * Base face the weight is applied to. {@code Typeface.create(String, int)}
     * only takes a style bitmask, so a numeric weight has to go through the
     * {@code (Typeface, int, boolean)} overload with this as its family.
     */
    private static final Typeface TEXT_BASE = Typeface.create("sans-serif", Typeface.NORMAL);

    static {
        STROKE.setStyle(Paint.Style.STROKE);
        STROKE.setStrokeCap(Paint.Cap.ROUND);
        STROKE.setStrokeJoin(Paint.Join.ROUND);
        FILL.setStyle(Paint.Style.FILL);
        TEXT.setStyle(Paint.Style.FILL);
        TEXT.setTextAlign(Paint.Align.CENTER);
        TEXT.setLetterSpacing(TrioGeometry.VALUE_LETTER_SPACING);
    }

    // ------------------------------------------------------------------ public

    static void draw(Canvas canvas, View host, TrioState s) {
        draw(canvas, host, s, TrioConfig.get());
    }

    /**
     * Draws the glyph with the user's current settings. The text size is applied
     * here rather than in the static initialiser so a live settings change is
     * picked up by the very next frame.
     */
    static void draw(Canvas canvas, View host, TrioState s, TrioSettings cfg) {
        final int w = host.getWidth();
        final int h = host.getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        drawInto(canvas, w, h, s.level, s.charging, s.quickCharging, s.powerSave, s.low,
                s.wifiPresent ? s.wifiLevel : -1, s.mobileLevel, s.mobileType,
                s.foreground(), cfg, true);
    }

    /**
     * Renders the glyph into any canvas of the given size. Kept free of
     * {@link View} so the settings app can preview the exact same drawing code
     * instead of a hand-maintained copy of it.
     */
    static void drawInto(Canvas canvas, int w, int h, int level, boolean charging,
                         boolean powerSave, boolean low, int wifiLevel, int mobileLevel,
                         int fg, TrioSettings cfg) {
        drawInto(canvas, w, h, level, charging, false, powerSave, low, wifiLevel, mobileLevel,
                null, fg, cfg, true);
    }

    /**
     * @param quickCharging {@code true} while the host reports a fast charger;
     *   only the bolt's colour changes, never its shape or size.
     * @param clearWhenDone {@code true} when this glyph owns the whole canvas
     *   (the hooked view case); {@code false} when drawing onto a canvas that
     *   already holds the settings app's background.
     */
    static void drawInto(Canvas canvas, int w, int h, int level, boolean charging,
                         boolean quickCharging, boolean powerSave, boolean low,
                         int wifiLevel, int mobileLevel, String mobileType, int fg,
                         TrioSettings cfg, boolean clearWhenDone) {
        if (w <= 0 || h <= 0 || cfg == null) {
            return;
        }
        final float scale = inkScale(w, h);
        if (scale <= 0f) {
            return;
        }

        if (clearWhenDone) {
            // The native onDraw only clears the canvas on its non-legacy path
            // (MiuiBatteryMeterIconView.onDraw L543-544). On the legacy path it just
            // calls super.onDraw and returns, so the framework's battery drawable
            // would show through underneath the glyph. Clear unconditionally: our
            // glyph is the sole content of this view either way.
            canvas.drawColor(0, PorterDuff.Mode.CLEAR);
        }

        final int role = roleColor(cfg, level, charging, powerSave, low, fg);

        final boolean wifi = wifiInk(cfg, wifiLevel);
        final boolean hasType = mobileType != null && !mobileType.isEmpty();
        final boolean swap = cfg.swapWifiValue;
        // The bolt is drawn by this renderer and only while the percentage is
        // enabled, so it must be gated the same way: otherwise a ring could open
        // its mouth for a bolt that never gets drawn.
        final boolean bolt = charging && cfg.showBolt && cfg.showValue;
        // Two slots, each holding one thing. Which slot a thing lands in is the
        // whole point of the swap: the ring centre, or the 12 o'clock notch.
        final boolean boltInCentre = bolt && swap;
        final boolean boltInGap = bolt && !swap;
        final boolean wifiInCentre = wifi && !swap;
        final boolean wifiInGap = wifi && swap;
        // The network type claims the ring centre only in the in-ring mode; both
        // off and out-of-ring leave the centre to the value, because out-of-ring
        // is drawn by a separate label view outside this canvas. It never
        // competes with the bolt either: a bolt at the centre outranks it.
        final boolean typeInCentre = cfg.mobileTypeMode == Prefs.MOBILE_TYPE_IN_RING
                && hasType && !wifi && !boltInCentre;
        final boolean valueInCentre = cfg.showValue && level >= 0
                && !typeInCentre && !wifiInCentre && !boltInCentre;
        final boolean valueInGap = cfg.showValue && level >= 0
                && !valueInCentre && !wifiInGap && !boltInGap;
        // The top gap is open only while something is actually drawn in it. An
        // empty mouth just breaks the ring, so it closes instead.
        final boolean gapUsed = boltInGap || wifiInGap || valueInGap;
        // Only the bolt wants the narrower charging mouth; the digits and the
        // arcs need all the room the idle mouth gives them.
        final float gapStart = gapUsed
                ? (boltInGap ? TrioGeometry.GAP_START_CHARGE : TrioGeometry.GAP_START_IDLE)
                : TrioGeometry.GAP_NONE;
        final float gapEnd = gapUsed
                ? (boltInGap ? TrioGeometry.GAP_END_CHARGE : TrioGeometry.GAP_END_IDLE)
                : TrioGeometry.GAP_NONE;

        final int save = canvas.save();
        canvas.translate(
                (w - TrioGeometry.INK_W * scale) / 2f - TrioGeometry.INK_X * scale,
                (h - TrioGeometry.INK_H * scale) / 2f - TrioGeometry.INK_Y * scale);
        canvas.scale(scale, scale);

        drawBattery(canvas, level, role, fg, cfg, gapStart, gapEnd);
        if (cfg.showWifi) {
            drawWifi(canvas, wifiLevel, fg, cfg, wifiInGap);
        }
        if (cfg.showMobile) {
            drawLevelDots(canvas, mobileLevel, fg, cfg);
        }
        if (typeInCentre) {
            drawCentreType(canvas, mobileType, fg, cfg);
        }
        if (bolt) {
            drawBolt(canvas, quickCharging ? TrioGeometry.QUICK_CHARGE : fg, boltInCentre);
        }
        if (valueInCentre || valueInGap) {
            drawValue(canvas, level, fg, cfg, valueInCentre);
        }

        canvas.restoreToCount(save);
    }

    /**
     * The colour of the lit part of the ring and the value text.
     *
     * <p>Priority: critical battery, then power-save/low battery, then charging,
     * then the framework's own foreground. {@code isDarkBackground(fg)} tells
     * which variant of a role colour to use: a light icon colour means the status
     * bar is dark, so the light-background variant applies.
     */
    static int roleColor(TrioSettings cfg, int level, boolean charging,
                         boolean powerSave, boolean low, int fg) {
        if (cfg == null || !cfg.roleColors) {
            return fg;
        }
        final boolean dark = isDarkBackground(fg);
        if (level >= 0 && level < TrioGeometry.CRITICAL_LEVEL) {
            return dark ? cfg.criticalOnDark : cfg.criticalOnLight;
        }
        if (powerSave || low || (level >= 0 && level <= cfg.lowThreshold)) {
            return dark ? cfg.lowOnDark : cfg.lowOnLight;
        }
        if (charging) {
            return dark ? cfg.chargingOnDark : cfg.chargingOnLight;
        }
        return fg;
    }

    // ----------------------------------------------------------------- battery

    private static void drawBattery(Canvas c, int level, int role, int fg,
                                    TrioSettings cfg, float gapStart, float gapEnd) {
        STROKE.setStrokeWidth(cfg.ringStroke);

        STROKE.setColor(withAlpha(fg, cfg.trackAlpha));
        batteryRing(c, 0f, 1f, gapStart, gapEnd);

        if (level > 0) {
            STROKE.setColor(role);
            batteryRing(c, 0f, Math.min(1f, level / 100f), gapStart, gapEnd);
        }
    }

    /**
     * Draws the open battery arc from progress {@code from} to {@code to},
     * skipping the segment hidden behind the top gap.
     *
     * <p>Both ends are progress of the arc that is <em>actually drawn</em>, not
     * of the full path: the two segments the mouth leaves behind divide the
     * level between them, so 50% ends exactly where the left segment does and
     * the mouth never consumes any of it. Measuring over the whole path instead
     * made the level stand still across the entire width of the mouth — the
     * right half did not start filling until the level had climbed past 67%.
     */
    private static void batteryRing(Canvas c, float from, float to, float gapStart, float gapEnd) {
        from = clamp01(from);
        to = clamp01(to);
        if (to <= from) {
            return;
        }
        final float mouthStart = clamp01(gapStart);
        final float mouthEnd = clamp01(gapEnd);
        // Path length that actually carries ink; the level is spread over it.
        final float drawn = mouthStart + (1f - mouthEnd);
        if (drawn <= 0f) {
            return;
        }
        final float visibleFrom = from * drawn;
        final float visibleTo = to * drawn;
        // First segment: visible progress [0, mouthStart] is path [0, mouthStart].
        final float firstEnd = Math.min(visibleTo, mouthStart);
        if (firstEnd > visibleFrom) {
            arcByProgress(c, visibleFrom, firstEnd, TrioGeometry.B_CX, TrioGeometry.B_CY,
                    TrioGeometry.B_R);
        }
        // Second segment: visible progress (mouthStart, drawn] is path (mouthEnd, 1].
        final float secondFrom = Math.max(visibleFrom, mouthStart);
        if (visibleTo > secondFrom) {
            arcByProgress(c, mouthEnd + secondFrom - mouthStart, mouthEnd + visibleTo - mouthStart,
                    TrioGeometry.B_CX, TrioGeometry.B_CY, TrioGeometry.B_R);
        }
    }

    private static void arcByProgress(Canvas c, float from, float to, float cx, float cy, float r) {
        arc(c, cx, cy, r,
                TrioGeometry.B_START + from * TrioGeometry.B_SWEEP,
                (to - from) * TrioGeometry.B_SWEEP);
    }

    private static void arc(Canvas c, float cx, float cy, float r, float startDeg, float sweepDeg) {
        ARC.set(cx - r, cy - r, cx + r, cy + r);
        c.drawArc(ARC, startDeg, sweepDeg, false, STROKE);
    }

    // -------------------------------------------------------------------- wifi

    /**
     * @param gap {@code true} when the swap setting has moved the arcs up into
     *   the 12 o'clock notch. The whole group is then translated and scaled on
     *   the canvas, which keeps one set of reference coordinates for both slots -
     *   the same trick the bolt uses.
     */
    private static void drawWifi(Canvas c, int level, int fg, TrioSettings cfg, boolean gap) {
        final int save = gap ? c.save() : 0;
        if (gap) {
            c.translate(TrioGeometry.gapWifiOffsetX(), TrioGeometry.gapWifiOffsetY(cfg.arcStroke));
            c.scale(TrioGeometry.GAP_WIFI_SCALE, TrioGeometry.GAP_WIFI_SCALE);
        }
        STROKE.setStrokeWidth(cfg.arcStroke);
        STROKE.setColor(fg);
        if (level >= 3) {
            arc(c, TrioGeometry.W_CX, TrioGeometry.W1_CY, TrioGeometry.W1_R,
                    TrioGeometry.W1_START, TrioGeometry.W1_SWEEP);
            arc(c, TrioGeometry.W_CX, TrioGeometry.W2_CY, TrioGeometry.W2_R,
                    TrioGeometry.W2_START, TrioGeometry.W2_SWEEP);
        } else if (level == 2) {
            arc(c, TrioGeometry.W_CX, TrioGeometry.W2_CY, TrioGeometry.W2_R,
                    TrioGeometry.W2_START, TrioGeometry.W2_SWEEP);
        }
        if (level >= 1) {
            FILL.setColor(fg);
            c.drawPath(WIFI_DOT, FILL);
        }
        if (gap) {
            c.restoreToCount(save);
        }
    }

    // ------------------------------------------------------------- level dots

    private static void drawLevelDots(Canvas c, int level, int fg, TrioSettings cfg) {
        final int active = level < 0 ? 0 : Math.min(4, level);
        final int inactive = withAlpha(fg, cfg.trackAlpha);
        for (int i = 0; i < TrioGeometry.DOTS.length; i++) {
            FILL.setColor(i < active ? fg : inactive);
            c.drawCircle(TrioGeometry.DOTS[i][0], TrioGeometry.DOTS[i][1],
                    TrioGeometry.DOT_R, FILL);
        }
    }

    // ------------------------------------------------------------ top gap fill

    /**
     * Whether the Wi-Fi arcs actually put ink in the middle of the ring. Wi-Fi
     * off ({@code level < 0}), unknown, or disabled all leave that area blank,
     * and a level of 0 draws no arc or dot either.
     */
    private static boolean wifiInk(TrioSettings cfg, int wifiLevel) {
        return cfg.showWifi && wifiLevel >= 1;
    }

    /**
     * Installs {@code weight} on {@link #TEXT}, skipping the work when that
     * weight is already the installed one. Every draw runs inside onDraw, so the
     * cached comparison keeps a per-frame {@code Typeface} allocation off the hot
     * path. The percentage and the network type share this cache because they
     * share the paint; when they differ both pay one swap per frame, which
     * {@link Typeface#create(Typeface, int, boolean)} answers from its own cache.
     */
    private static void applyWeight(int weight) {
        if (weight == sTextWeight) {
            return;
        }
        TEXT.setTypeface(typefaceFor(weight));
        sTextWeight = weight;
    }

    /**
     * Draws the value in whichever slot it belongs to.
     *
     * @param centre {@code true} to put the percentage in the middle of the ring
     *   (the Wi-Fi arcs and the network type are both absent), {@code false} to
     *   keep it in the 12 o'clock gap next to the charging bolt.
     */
    private static void drawValue(Canvas c, int level, int fg, TrioSettings cfg, boolean centre) {
        final String text = String.valueOf(level);
        final float requested = centre ? TrioGeometry.centreSize(cfg.valueSize) : cfg.valueSize;
        final float clear = centre ? TrioGeometry.centreClearWidth(requested, cfg.ringStroke)
                                   : TrioGeometry.gapClearWidth(cfg.ringStroke);
        // The typeface has to be current before measuring: a heavier weight is
        // wider, and fitSize decides whether the text still clears the gap.
        applyWeight(cfg.valueWeight);
        final float size = fitSize(text, requested, clear);
        TEXT.setTextSize(size);
        TEXT.setColor(fg);
        c.drawText(text, TrioGeometry.VALUE_X,
                centre ? TrioGeometry.centerBaseline(size)
                       : TrioGeometry.gapBaseline(size),
                TEXT);
    }

    /**
     * Draws the mobile network type ("5G", "5GA", ...) in the middle of the ring.
     *
     * <p>Only reached when the top gap already carries the percentage, so this
     * never competes with the value for the same space.
     */
    private static void drawCentreType(Canvas c, String type, int fg, TrioSettings cfg) {
        final float requested = cfg.typeSize;
        final float clear = TrioGeometry.centreClearWidth(requested, cfg.ringStroke);
        applyWeight(cfg.typeWeight);
        final float size = fitSize(type, requested, clear);
        TEXT.setTextSize(size);
        TEXT.setColor(fg);
        c.drawText(type, TrioGeometry.VALUE_X, TrioGeometry.centerBaseline(size), TEXT);
    }

    /**
     * Draws the charging bolt scaled up from its reference path. The transform is
     * applied to the canvas rather than baked into the path so the reference
     * coordinates stay readable, and it is saved/restored so nothing else moves.
     *
     * @param color bolt fill; amber while the battery reports quick charge.
     * @param centre {@code true} to fill the middle of the ring (the swap
     *   setting, and while charging there), {@code false} to sit in the
     *   12 o'clock gap at the size the reference path implies.
     */
    private static void drawBolt(Canvas c, int color, boolean centre) {
        final int save = c.save();
        if (centre) {
            c.translate(TrioGeometry.boltCentreOffsetX(), TrioGeometry.boltCentreOffsetY());
            c.scale(TrioGeometry.BOLT_CENTRE_SCALE, TrioGeometry.BOLT_CENTRE_SCALE);
        } else {
            c.translate(TrioGeometry.boltOffsetX(), TrioGeometry.boltOffsetY());
            c.scale(TrioGeometry.BOLT_SCALE, TrioGeometry.BOLT_SCALE);
        }
        FILL.setColor(color);
        c.drawPath(BOLT, FILL);
        c.restoreToCount(save);
    }

    /**
     * Largest font size at which {@code text} fits in {@code clearWidth} design
     * units, capped by {@code requested}. "100" is far wider than "9", so a
     * long value shrinks instead of overrunning the ring.
     */
    private static float fitSize(String text, float requested, float clearWidth) {
        if (clearWidth <= 0f) {
            return requested;
        }
        TEXT.setTextSize(requested);
        final float width = TEXT.measureText(text);
        if (width <= clearWidth || width <= 0f) {
            return requested;
        }
        return requested * (clearWidth / width);
    }

    // ----------------------------------------------------------------- helpers

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    /**
     * Pixels per design unit for a host of this size: the uniform scale that
     * fits the 120x120 design box into the view, i.e. the same factor
     * {@link #drawInto} applies to its canvas. Anything drawn outside the
     * canvas (the out-of-ring network type label) has to convert its design-unit
     * size with this, or it will not match the glyph next to it.
     */
    static float inkScale(int w, int h) {
        if (w <= 0 || h <= 0) {
            return 0f;
        }
        return Math.min(w / TrioGeometry.INK_W, h / TrioGeometry.INK_H);
    }

    /**
     * The shared text face at a numeric weight. The settings app and the hooked
     * side have to agree on it, and so do the in-canvas glyph and the label view
     * drawn beside it.
     */
    static Typeface typefaceFor(int weight) {
        return Typeface.create(TEXT_BASE, weight, false);
    }

    // ----------------------------------------------------------- path builders

    /** The charging bolt outline, in 120x120 design units. */
    private static Path buildBolt() {
        final Path p = new Path();
        p.moveTo(62.1f, 2.2f);
        p.quadTo(62.8f, 2.5f, 62.6f, 3.3f);
        p.lineTo(61.2f, 7.8f);
        p.lineTo(65.9f, 7.8f);
        p.quadTo(66.9f, 7.8f, 67.3f, 8.6f);
        p.quadTo(67.6f, 9.3f, 67.0f, 10.0f);
        p.lineTo(57.0f, 21.3f);
        p.quadTo(56.4f, 22.0f, 55.6f, 21.6f);
        p.quadTo(55.0f, 21.3f, 55.3f, 20.5f);
        p.lineTo(57.4f, 14.1f);
        p.lineTo(52.9f, 14.1f);
        p.quadTo(52.0f, 14.1f, 51.6f, 13.3f);
        p.quadTo(51.3f, 12.6f, 51.9f, 12.0f);
        p.lineTo(61.1f, 2.7f);
        p.quadTo(61.6f, 2.1f, 62.1f, 2.2f);
        p.close();
        return p;
    }

    /** The innermost Wi-Fi dot, in 120x120 design units. */
    private static Path buildWifiDot() {
        final Path p = new Path();
        p.moveTo(59.5f, 69.9f);
        p.cubicTo(61.0f, 69.9f, 65.2f, 70.8f, 66.5f, 73.0f);
        p.cubicTo(66.7f, 73.8f, 66.7f, 74.3f, 66.5f, 75.0f);
        p.cubicTo(63.8f, 78.8f, 61.15f, 80.95f, 59.5f, 80.95f);
        p.cubicTo(57.85f, 80.95f, 55.2f, 78.8f, 52.5f, 75.0f);
        p.cubicTo(52.3f, 74.3f, 52.3f, 73.8f, 52.5f, 73.0f);
        p.cubicTo(53.8f, 70.8f, 58.0f, 69.9f, 59.5f, 69.9f);
        p.close();
        return p;
    }

    /** sRGB luminance test: a light icon colour implies a dark status bar. */
    static boolean isDarkBackground(int color) {
        final float lum = (0.2126f * Color.red(color)
                + 0.7152f * Color.green(color)
                + 0.0722f * Color.blue(color)) / 255f;
        return lum > 0.5f;
    }
}
