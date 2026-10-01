package com.hyperduo.trio;

/**
 * Geometry of the "trio" glyph in a 120x120 design space. All units are design
 * units.
 */
final class TrioGeometry {

    private TrioGeometry() {
    }

    /** The SVG viewBox. Everything is drawn inside 0..120 on both axes. */
    static final float BOX = 120f;

    // ---------------------------------------------------------------- battery
    /** Arc centre: the two endpoints (15.5,88.25) and (103.5,88.25) with r=51.5. */
    static final float B_CX = 59.5f;
    static final float B_CY = 61.48715261785473f;
    static final float B_R = 51.5f;
    /** Default ring stroke; the user's {@code ring_stroke} setting overrides it. */
    static final float B_STROKE = 8f;
    /** Start angle (deg) from centre towards (15.5, 88.25). */
    static final float B_START = 148.69008689281117f;
    /** Clockwise sweep (deg) to (103.5, 88.25). The missing part is at the BOTTOM. */
    static final float B_SWEEP = 242.6198262143777f;

    /**
     * The whole open arc is further broken at 12 o'clock by stroke-dasharray over
     * pathLength=100: "35.3 29.4 35.3 4" -> gap covers [0.353, 0.647] of the path.
     *
     * <p>The reference figures are widened here: 29.4% of the path leaves only
     * ~60 design units of chord, which forced the percentage to shrink and left
     * the charging bolt looking pinched. Opening the mouth gives the top
     * indicator the room the ring interior already has.
     */
    static final float GAP_START_IDLE = 0.325f;
    static final float GAP_END_IDLE = 0.675f;
    /** Charging variant: "38.5 22.9 38.6 4" -> gap covers [0.385, 0.614]. */
    static final float GAP_START_CHARGE = 0.345f;
    static final float GAP_END_CHARGE = 0.655f;

    /**
     * Gap used when nothing is drawn in the 12 o'clock slot.
     *
     * <p>A zero-width gap at position 0 makes the ring draw as one unbroken arc:
     * with {@code gapStart == gapEnd == 0} the two halves of
     * {@code batteryRing}'s split collapse into a single 0..to sweep. An empty
     * mouth is not a feature, so the ring simply closes.
     */
    static final float GAP_NONE = 0f;

    /** Path progress t (0..1) -> angle in degrees. */
    static float bAngle(float t) {
        return B_START + t * B_SWEEP;
    }

    // ----------------------------------------------------------------- charging
    /**
     * The charging bolt, scaled up from the reference path.
     *
     * <p>The reference path only spans x 51.3..67.6 and y 2.2..21.6 — a pinched
     * sliver next to a 103-unit ring. {@link #BOLT_SCALE} grows it about
     * {@link #BOLT_CX} and pins its top edge to {@link #BOLT_TOP}, so it fills
     * the opened 12 o'clock gap without leaving the 120x120 box.
     *
     * <p>The renderer draws the path with
     * {@code translate(boltOffsetX(), boltOffsetY())} then
     * {@code scale(BOLT_SCALE)}: a reference point {@code (x, y)} lands on
     * {@code (x * BOLT_SCALE + boltOffsetX(), y * BOLT_SCALE + boltOffsetY())}.
     */
    static final float BOLT_CX = 59.45f;
    static final float BOLT_TOP = 1.5f;
    /** Top edge and horizontal centre of the reference path, before scaling. */
    static final float BOLT_TOP_REF = 2.2f;
    static final float BOLT_CX_REF = 59.45f;
    /**
     * Chosen against the default ring stroke: the reference bolt is sized for a
     * stroke of 8 design units, and the shipping default is 14, so a much larger
     * scale is needed for the two to read as one icon.
     */
    static final float BOLT_SCALE = 1.6f;

    static float boltOffsetX() {
        return BOLT_CX - BOLT_CX_REF * BOLT_SCALE;
    }

    static float boltOffsetY() {
        return BOLT_TOP - BOLT_TOP_REF * BOLT_SCALE;
    }

    /** Vertical middle of the reference bolt, used to centre it in the ring. */
    static final float BOLT_MID_REF = 11.9f;
    /**
     * Bolt scale for the swapped layout, where the bolt no longer shares the
     * notch with anything and can fill the ring. Matches the mass of the
     * percentage drawn at {@link #centreSize} for the default value size.
     */
    static final float BOLT_CENTRE_SCALE = 2.6f;

    static float boltCentreOffsetX() {
        return BOLT_CX - BOLT_CX_REF * BOLT_CENTRE_SCALE;
    }

    static float boltCentreOffsetY() {
        return CENTER_CY - BOLT_MID_REF * BOLT_CENTRE_SCALE;
    }

    // ------------------------------------------------------------------- value
    static final float VALUE_X = 59.5f;
    static final float VALUE_BASELINE = 24f;
    static final float VALUE_SIZE = 32f;
    /** SVG letter-spacing="-0.04em". */
    static final float VALUE_LETTER_SPACING = -0.04f;

    /**
     * Vertical centre of the ring interior. The level is drawn here once the
     * Wi-Fi area is empty, so the hole left by a missing Wi-Fi glyph carries the
     * battery percentage instead of staying blank.
     */
    static final float CENTER_CY = 64f;
    /** Cap height of the digits, in em. Roboto's is ~0.71em. */
    static final float CAP_HEIGHT_RATIO = 0.71f;
    /**
     * Height of a digit's visual centre above its baseline, in em. Digits fill
     * the cap, so their centre sits half of the cap height above the baseline.
     */
    static final float CAP_CENTRE_RATIO = CAP_HEIGHT_RATIO / 2f;

    /**
     * Smallest y a value's cap may start at when it is drawn in the top gap.
     * Anything above this leaves the 120x120 box and gets clipped by the host.
     *
     * <p>Not 0: the cap sits on a curve, and font metrics differ by a hair
     * between Roboto and the measurement used here, so a cap exactly on the ink
     * edge loses its top row to antialiasing. Two and a half units of headroom
     * costs nothing visually and keeps every digit whole.
     */
    static final float GAP_TOP = 2.5f;

    /**
     * Baseline for a value drawn in the 12 o'clock gap. {@link #VALUE_BASELINE}
     * is the design baseline, but a larger font would push the cap above
     * {@link #GAP_TOP}, so the line is dropped just enough to keep it inside.
     */
    static float gapBaseline(float size) {
        return Math.max(VALUE_BASELINE, GAP_TOP + size * CAP_HEIGHT_RATIO);
    }

    /**
     * The centre of the ring gives the value far more room than the narrow top
     * gap it normally lives in, so a centred value is drawn this much larger.
     *
     * <p>1.5 crowded the ring: the digits ran into the stroke on both sides once
     * the ring was thickened. 1.3 still reads as "the big number" while keeping
     * a visible margin against the inner edge.
     */
    static final float CENTRE_SIZE_RATIO = 1.4f;
    /** Upper bound for {@link #CENTRE_SIZE_RATIO}: "100" must stay inside the ring. */
    static final float CENTRE_MAX_SIZE = 52f;

    /** Font size actually used for a centred value of configured {@code size}. */
    static float centreSize(float size) {
        return Math.min(size * CENTRE_SIZE_RATIO, CENTRE_MAX_SIZE);
    }

    /** Baseline that centres digits of {@code size} on {@link #CENTER_CY}. */
    static float centerBaseline(float size) {
        return CENTER_CY + size * CAP_CENTRE_RATIO;
    }

    /**
     * Clear horizontal room for text drawn inside the ring. The ring's inner
     * edge is one stroke width inside its centre line; the text's cap corners
     * must stay within that circle, so the room narrows as it gets taller.
     *
     * <p>Both centre slots go through here: the percentage when Wi-Fi is off,
     * and the mobile network type ({@code Prefs.KEY_TYPE_SIZE}) when it replaces
     * the percentage. Sizing them independently is safe because neither can
     * overrun the ring.
     */
    static float centreClearWidth(float size, float stroke) {
        final float inner = B_R - stroke * 0.5f - 1f;
        final float dy = size * CAP_HEIGHT_RATIO * 0.5f;
        final float dx = (float) Math.sqrt(Math.max(0f, inner * inner - dy * dy));
        return dx * 2f;
    }

    /**
     * Clear horizontal room for a value drawn in the 12 o'clock gap: the chord
     * between the two rounded arc ends, less one stroke width for those caps.
     */
    static float gapClearWidth(float stroke) {
        final double half = Math.toRadians((GAP_END_IDLE - GAP_START_IDLE) * B_SWEEP * 0.5f);
        return (float) (2.0 * B_R * Math.sin(half)) - stroke;
    }

    // ------------------------------------------------------------------- wi-fi

    static final float W_CX = 59.5f;
    static final float W_STROKE = 7f;
    /** Outer arc: chord (38.5,55.5)-(80.5,55.5), r=31 -> centre below the chord. */
    static final float W1_CY = 78.3035085f;
    static final float W1_R = 31f;
    static final float W1_START = 227.3423f;
    static final float W1_SWEEP = 85.3154f;
    /** Middle arc: chord (47,65.25)-(72,65.25), r=18.5. */
    static final float W2_CY = 78.8881817f;
    static final float W2_R = 18.5f;
    static final float W2_START = 227.5048f;
    static final float W2_SWEEP = 84.9904f;

    /** Topmost ink of the outer arc, at its apex. */
    static final float W_TOP = W1_CY - W1_R;
    /**
     * Scale of the Wi-Fi group when {@code Prefs.KEY_SWAP_WIFI_VALUE} moves it
     * into the 12 o'clock notch. The notch is 69.6 units of chord at the mouth
     * and the group is 49 wide at full size, so it has to shrink to read as a
     * notch ornament rather than spill over the arc ends.
     */
    static final float GAP_WIFI_SCALE = 0.8f;

    /** Keeps the group horizontally centred on the icon axis. */
    static float gapWifiOffsetX() {
        return W_CX * (1f - GAP_WIFI_SCALE);
    }

    /**
     * Puts the topmost ink - the outer arc's apex, half a stroke above its
     * centre line - at {@link #GAP_TOP}, the same clearance the digits get when
     * they sit in the notch.
     */
    static float gapWifiOffsetY(float stroke) {
        return GAP_TOP - (W_TOP - stroke * 0.5f) * GAP_WIFI_SCALE;
    }

    // ------------------------------------------------------- four level dots
    static final float DOT_R = 5.5f;
    static final float[][] DOTS = {
            {33f, 104.2f},
            {50.5f, 111.2f},
            {68.5f, 111.7f},
            {86f, 105.8f},
    };

    /** Opacity of the battery track / inactive level dots. */
    static final int TRACK_ALPHA = 56; // 0.22 * 255

    /** Below this battery level the ring turns the "critical" colour. */
    static final int CRITICAL_LEVEL = 20;

    // ------------------------------------------------------------------- frame
    /**
     * Tight ink box of the whole glyph (battery arc + stroke, Wi-Fi arcs + dot,
     * level dots, top indicator) in design units. Used to fit 120x120 artwork
     * into the host view without non-uniform scaling.
     */
    static final float INK_X = 1.0f;
    static final float INK_Y = 0.5f;
    static final float INK_W = 117.0f;
    static final float INK_H = 117.5f;

    // ------------------------------------------------------------------ colours
    static final int CRITICAL = 0xFFFF3B30;
    static final int CHARGING_LIGHT = 0xFF34C759;
    static final int CHARGING_DARK = 0xFF1F8F3D;
    static final int LOW_POWER_LIGHT = 0xFFF2B900;
    static final int LOW_POWER_DARK = 0xFFC99700;

    /**
     * Amber used for the charging bolt while the battery reports quick charge.
     * At the real 60px status-bar size colour is the only channel that stays
     * instantly legible, so quick charge is told apart by hue rather than by
     * extra glyphs (two bolts merge into an unreadable blob at that size).
     */
    static final int QUICK_CHARGE = 0xFFFFBA28;
}
