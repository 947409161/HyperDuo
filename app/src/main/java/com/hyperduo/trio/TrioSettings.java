package com.hyperduo.trio;

import android.content.SharedPreferences;
import android.os.Bundle;

/**
 * One coherent set of user settings, read from a {@link SharedPreferences}
 * file.
 *
 * <p>This type deliberately has no dependency on the Xposed API: the settings
 * app process reuses it to drive the live preview, and pulling in
 * {@code io.github.libxposed.api} there would mean shipping classes the app's
 * class loader cannot resolve.
 *
 * <p>Every field is public and mutable so the renderer can be handed a
 * snapshot cheaply; instances are published as a whole and never mutated after
 * publication.
 */
public final class TrioSettings {

    public boolean enabled;
    public boolean showWifi;
    public boolean showMobile;
    public boolean showValue;
    public boolean showBolt;
    /**
     * Where the mobile network type is drawn: {@link Prefs#MOBILE_TYPE_OFF},
     * {@link Prefs#MOBILE_TYPE_IN_RING} or {@link Prefs#MOBILE_TYPE_OUT_RING}.
     * Clamped to that range wherever it is read.
     */
    public int mobileTypeMode;
    /**
     * Draws the battery percentage enlarged in the middle of the ring, with the
     * Wi-Fi arcs shrunk into the 12 o'clock notch. While charging the bolt owns
     * the middle and the arcs stay in the notch.
     *
     * <p>Stored under the frozen key {@link Prefs#KEY_VALUE_CENTRED}.
     */
    public boolean valueCentred;

    public boolean roleColors;
    public int criticalOnDark;
    public int criticalOnLight;
    public int chargingOnDark;
    public int chargingOnLight;
    public int lowOnDark;
    public int lowOnLight;
    public int lowThreshold;

    public int ringStroke;
    public int arcStroke;
    public int valueSize;
    /** Numeric font weight of the value/type text, 100..900. */
    public int valueWeight;
    /**
     * Size and weight of the centred network type ("5G", "5GA"). Independent of
     * {@link #valueSize}/{@link #valueWeight}: the type is a label of its own,
     * tuned separately from the digits it shares the ring with.
     */
    public int typeSize;
    /**
     * Font size of the network type when it is drawn outside the ring. A field
     * of its own rather than a reuse of {@link #typeSize}: the two labels live
     * in different spaces (ring design space vs. real status-bar pixels) and are
     * tuned separately.
     */
    public int outTypeSize;
    public int typeWeight;
    public int trackAlpha;

    /** Only used by the settings app: the hooked side reads this via {@code TrioConfig}. */
    public boolean debugLog;

    public static TrioSettings defaults() {
        TrioSettings s = new TrioSettings();
        s.enabled = Prefs.DEF_ENABLED;
        s.showWifi = Prefs.DEF_SHOW_WIFI;
        s.showMobile = Prefs.DEF_SHOW_MOBILE;
        s.showValue = Prefs.DEF_SHOW_VALUE;
        s.showBolt = Prefs.DEF_SHOW_BOLT;
        s.mobileTypeMode = Prefs.DEF_MOBILE_TYPE_MODE;
        s.valueCentred = Prefs.DEF_VALUE_CENTRED;

        s.roleColors = Prefs.DEF_ROLE_COLORS;
        s.criticalOnDark = Prefs.DEF_COLOR_CRITICAL_ON_DARK;
        s.criticalOnLight = Prefs.DEF_COLOR_CRITICAL_ON_LIGHT;
        s.chargingOnDark = Prefs.DEF_COLOR_CHARGING_ON_DARK;
        s.chargingOnLight = Prefs.DEF_COLOR_CHARGING_ON_LIGHT;
        s.lowOnDark = Prefs.DEF_COLOR_LOW_ON_DARK;
        s.lowOnLight = Prefs.DEF_COLOR_LOW_ON_LIGHT;
        s.lowThreshold = Prefs.DEF_LOW_THRESHOLD;

        s.ringStroke = Prefs.DEF_RING_STROKE;
        s.arcStroke = Prefs.DEF_ARC_STROKE;
        s.valueSize = Prefs.DEF_VALUE_SIZE;
        s.valueWeight = Prefs.DEF_VALUE_WEIGHT;
        s.typeSize = Prefs.DEF_TYPE_SIZE;
        s.outTypeSize = Prefs.DEF_OUT_TYPE_SIZE;
        s.typeWeight = Prefs.DEF_TYPE_WEIGHT;
        s.trackAlpha = Prefs.DEF_TRACK_ALPHA;
        s.debugLog = Prefs.DEF_DEBUG_LOG;
        return s;
    }

    /**
     * Reads every key, falling back to the shared defaults. Numeric keys are
     * clamped here rather than at draw time so the renderer can trust them.
     */
    public static TrioSettings from(SharedPreferences p) {
        TrioSettings s = new TrioSettings();
        s.enabled = p.getBoolean(Prefs.KEY_ENABLED, Prefs.DEF_ENABLED);
        s.showWifi = p.getBoolean(Prefs.KEY_SHOW_WIFI, Prefs.DEF_SHOW_WIFI);
        s.showMobile = p.getBoolean(Prefs.KEY_SHOW_MOBILE, Prefs.DEF_SHOW_MOBILE);
        s.showValue = p.getBoolean(Prefs.KEY_SHOW_VALUE, Prefs.DEF_SHOW_VALUE);
        s.showBolt = p.getBoolean(Prefs.KEY_SHOW_BOLT, Prefs.DEF_SHOW_BOLT);
        s.mobileTypeMode = readMobileTypeMode(p);
        s.valueCentred = p.getBoolean(Prefs.KEY_VALUE_CENTRED, Prefs.DEF_VALUE_CENTRED);

        s.roleColors = p.getBoolean(Prefs.KEY_ROLE_COLORS, Prefs.DEF_ROLE_COLORS);
        s.criticalOnDark = p.getInt(Prefs.KEY_COLOR_CRITICAL_ON_DARK, Prefs.DEF_COLOR_CRITICAL_ON_DARK);
        s.criticalOnLight = p.getInt(Prefs.KEY_COLOR_CRITICAL_ON_LIGHT, Prefs.DEF_COLOR_CRITICAL_ON_LIGHT);
        s.chargingOnDark = p.getInt(Prefs.KEY_COLOR_CHARGING_ON_DARK, Prefs.DEF_COLOR_CHARGING_ON_DARK);
        s.chargingOnLight = p.getInt(Prefs.KEY_COLOR_CHARGING_ON_LIGHT, Prefs.DEF_COLOR_CHARGING_ON_LIGHT);
        s.lowOnDark = p.getInt(Prefs.KEY_COLOR_LOW_ON_DARK, Prefs.DEF_COLOR_LOW_ON_DARK);
        s.lowOnLight = p.getInt(Prefs.KEY_COLOR_LOW_ON_LIGHT, Prefs.DEF_COLOR_LOW_ON_LIGHT);
        s.lowThreshold = Prefs.clamp(
                p.getInt(Prefs.KEY_LOW_THRESHOLD, Prefs.DEF_LOW_THRESHOLD),
                Prefs.MIN_LOW_THRESHOLD, Prefs.MAX_LOW_THRESHOLD);

        s.ringStroke = Prefs.clamp(
                p.getInt(Prefs.KEY_RING_STROKE, Prefs.DEF_RING_STROKE),
                Prefs.MIN_RING_STROKE, Prefs.MAX_RING_STROKE);
        s.arcStroke = Prefs.clamp(
                p.getInt(Prefs.KEY_ARC_STROKE, Prefs.DEF_ARC_STROKE),
                Prefs.MIN_ARC_STROKE, Prefs.MAX_ARC_STROKE);
        s.valueSize = Prefs.clamp(
                p.getInt(Prefs.KEY_VALUE_SIZE, Prefs.DEF_VALUE_SIZE),
                Prefs.MIN_VALUE_SIZE, Prefs.MAX_VALUE_SIZE);
        s.valueWeight = Prefs.clamp(
                p.getInt(Prefs.KEY_VALUE_WEIGHT, Prefs.DEF_VALUE_WEIGHT),
                Prefs.MIN_VALUE_WEIGHT, Prefs.MAX_VALUE_WEIGHT);
        s.typeSize = Prefs.clamp(
                p.getInt(Prefs.KEY_TYPE_SIZE, Prefs.DEF_TYPE_SIZE),
                Prefs.MIN_TYPE_SIZE, Prefs.MAX_TYPE_SIZE);
        s.outTypeSize = Prefs.clamp(
                p.getInt(Prefs.KEY_OUT_TYPE_SIZE, Prefs.DEF_OUT_TYPE_SIZE),
                Prefs.MIN_OUT_TYPE_SIZE, Prefs.MAX_OUT_TYPE_SIZE);
        s.typeWeight = Prefs.clamp(
                p.getInt(Prefs.KEY_TYPE_WEIGHT, Prefs.DEF_TYPE_WEIGHT),
                Prefs.MIN_TYPE_WEIGHT, Prefs.MAX_TYPE_WEIGHT);
        s.trackAlpha = Prefs.clamp(
                p.getInt(Prefs.KEY_TRACK_ALPHA, Prefs.DEF_TRACK_ALPHA),
                Prefs.MIN_TRACK_ALPHA, Prefs.MAX_TRACK_ALPHA);
        s.debugLog = p.getBoolean(Prefs.KEY_DEBUG_LOG, Prefs.DEF_DEBUG_LOG);
        return s;
    }

    /**
     * Reads the three-way network-type placement, migrating the legacy boolean.
     *
     * <p>The new int key wins whenever it is present. Only while it is absent -
     * i.e. on an install that predates it - is the old
     * {@link Prefs#KEY_SHOW_MOBILE_TYPE} boolean consulted, and an existing
     * "on" maps to {@link Prefs#MOBILE_TYPE_IN_RING} so that user keeps the
     * placement they had. The legacy key is never written again, so this runs at
     * most until the user's first write of the new key.
     */
    private static int readMobileTypeMode(SharedPreferences p) {
        if (p.contains(Prefs.KEY_MOBILE_TYPE_MODE)) {
            return Prefs.clamp(
                    p.getInt(Prefs.KEY_MOBILE_TYPE_MODE, Prefs.DEF_MOBILE_TYPE_MODE),
                    Prefs.MIN_MOBILE_TYPE_MODE, Prefs.MAX_MOBILE_TYPE_MODE);
        }
        return p.getBoolean(Prefs.KEY_SHOW_MOBILE_TYPE, Prefs.DEF_SHOW_MOBILE_TYPE)
                ? Prefs.MOBILE_TYPE_IN_RING
                : Prefs.MOBILE_TYPE_OFF;
    }

    /**
     * The broadcast-extras mirror of {@link #readMobileTypeMode(SharedPreferences)}.
     *
     * <p>The legacy fallback cannot actually fire here - the settings app only
     * ever puts the new int key into the bundle - but it is kept for symmetry
     * with the stored read, so a bundle built by an older writer still migrates
     * the same way instead of silently resetting to off.
     */
    private static int readMobileTypeMode(Bundle bundle) {
        if (bundle.containsKey(Prefs.KEY_MOBILE_TYPE_MODE)) {
            return Prefs.clamp(
                    bundle.getInt(Prefs.KEY_MOBILE_TYPE_MODE, Prefs.DEF_MOBILE_TYPE_MODE),
                    Prefs.MIN_MOBILE_TYPE_MODE, Prefs.MAX_MOBILE_TYPE_MODE);
        }
        return bundle.getBoolean(Prefs.KEY_SHOW_MOBILE_TYPE, Prefs.DEF_SHOW_MOBILE_TYPE)
                ? Prefs.MOBILE_TYPE_IN_RING
                : Prefs.MOBILE_TYPE_OFF;
    }

    /**
     * An independent copy. A snapshot is published as a whole and never mutated
     * afterwards, so updating a single field means copying and republishing.
     */
    public TrioSettings copy() {
        TrioSettings s = new TrioSettings();
        s.enabled = enabled;
        s.showWifi = showWifi;
        s.showMobile = showMobile;
        s.showValue = showValue;
        s.showBolt = showBolt;
        s.mobileTypeMode = mobileTypeMode;
        s.valueCentred = valueCentred;

        s.roleColors = roleColors;
        s.criticalOnDark = criticalOnDark;
        s.criticalOnLight = criticalOnLight;
        s.chargingOnDark = chargingOnDark;
        s.chargingOnLight = chargingOnLight;
        s.lowOnDark = lowOnDark;
        s.lowOnLight = lowOnLight;
        s.lowThreshold = lowThreshold;

        s.ringStroke = ringStroke;
        s.arcStroke = arcStroke;
        s.valueSize = valueSize;
        s.valueWeight = valueWeight;
        s.typeSize = typeSize;
        s.outTypeSize = outTypeSize;
        s.typeWeight = typeWeight;
        s.trackAlpha = trackAlpha;

        s.debugLog = debugLog;
        return s;
    }

    /**
     * Copies the one field named by {@code key} out of {@code src}.
     *
     * <p>The framework's remote map is only guaranteed to be fresh for the key it
     * just reported: the daemon merges a diff into the map and then fires one
     * change callback per key in that diff, so a full re-read here would take
     * stale values for every key whose diff never arrived. That is exactly how a
     * value published by another path can be silently reverted by the next
     * unrelated setting change, which is why only the reported key is applied and
     * the rest of an already-correct snapshot is left alone.
     *
     * @return true when the key maps to a field of this type
     */
    public boolean applyKeyFrom(TrioSettings src, String key) {
        if (src == null || key == null) {
            return false;
        }
        switch (key) {
            case Prefs.KEY_ENABLED: enabled = src.enabled; return true;
            case Prefs.KEY_SHOW_WIFI: showWifi = src.showWifi; return true;
            case Prefs.KEY_SHOW_MOBILE: showMobile = src.showMobile; return true;
            case Prefs.KEY_SHOW_VALUE: showValue = src.showValue; return true;
            case Prefs.KEY_SHOW_BOLT: showBolt = src.showBolt; return true;
            case Prefs.KEY_MOBILE_TYPE_MODE: mobileTypeMode = src.mobileTypeMode; return true;
            case Prefs.KEY_VALUE_CENTRED: valueCentred = src.valueCentred; return true;
            case Prefs.KEY_ROLE_COLORS: roleColors = src.roleColors; return true;
            case Prefs.KEY_COLOR_CRITICAL_ON_DARK: criticalOnDark = src.criticalOnDark; return true;
            case Prefs.KEY_COLOR_CRITICAL_ON_LIGHT: criticalOnLight = src.criticalOnLight; return true;
            case Prefs.KEY_COLOR_CHARGING_ON_DARK: chargingOnDark = src.chargingOnDark; return true;
            case Prefs.KEY_COLOR_CHARGING_ON_LIGHT: chargingOnLight = src.chargingOnLight; return true;
            case Prefs.KEY_COLOR_LOW_ON_DARK: lowOnDark = src.lowOnDark; return true;
            case Prefs.KEY_COLOR_LOW_ON_LIGHT: lowOnLight = src.lowOnLight; return true;
            case Prefs.KEY_LOW_THRESHOLD: lowThreshold = src.lowThreshold; return true;
            case Prefs.KEY_RING_STROKE: ringStroke = src.ringStroke; return true;
            case Prefs.KEY_ARC_STROKE: arcStroke = src.arcStroke; return true;
            case Prefs.KEY_VALUE_SIZE: valueSize = src.valueSize; return true;
            case Prefs.KEY_VALUE_WEIGHT: valueWeight = src.valueWeight; return true;
            case Prefs.KEY_TYPE_SIZE: typeSize = src.typeSize; return true;
            case Prefs.KEY_OUT_TYPE_SIZE: outTypeSize = src.outTypeSize; return true;
            case Prefs.KEY_TYPE_WEIGHT: typeWeight = src.typeWeight; return true;
            case Prefs.KEY_TRACK_ALPHA: trackAlpha = src.trackAlpha; return true;
            case Prefs.KEY_DEBUG_LOG: debugLog = src.debugLog; return true;
            default: return false;
        }
    }

    /**
     * Reads a snapshot delivered as broadcast extras, falling back to the same
     * defaults as {@link #from(SharedPreferences)}. Numeric values are clamped
     * identically, because the extras come from another process and are just as
     * untrusted as the stored file.
     *
     * <p>{@code bundle} may be null or partial; every key falls back on its own.
     */
    public static TrioSettings fromBundle(Bundle bundle) {
        TrioSettings s = new TrioSettings();
        if (bundle == null) {
            return defaults();
        }
        s.enabled = bundle.getBoolean(Prefs.KEY_ENABLED, Prefs.DEF_ENABLED);
        s.showWifi = bundle.getBoolean(Prefs.KEY_SHOW_WIFI, Prefs.DEF_SHOW_WIFI);
        s.showMobile = bundle.getBoolean(Prefs.KEY_SHOW_MOBILE, Prefs.DEF_SHOW_MOBILE);
        s.showValue = bundle.getBoolean(Prefs.KEY_SHOW_VALUE, Prefs.DEF_SHOW_VALUE);
        s.showBolt = bundle.getBoolean(Prefs.KEY_SHOW_BOLT, Prefs.DEF_SHOW_BOLT);
        s.mobileTypeMode = readMobileTypeMode(bundle);
        s.valueCentred = bundle.getBoolean(Prefs.KEY_VALUE_CENTRED, Prefs.DEF_VALUE_CENTRED);

        s.roleColors = bundle.getBoolean(Prefs.KEY_ROLE_COLORS, Prefs.DEF_ROLE_COLORS);
        s.criticalOnDark = bundle.getInt(Prefs.KEY_COLOR_CRITICAL_ON_DARK, Prefs.DEF_COLOR_CRITICAL_ON_DARK);
        s.criticalOnLight = bundle.getInt(Prefs.KEY_COLOR_CRITICAL_ON_LIGHT, Prefs.DEF_COLOR_CRITICAL_ON_LIGHT);
        s.chargingOnDark = bundle.getInt(Prefs.KEY_COLOR_CHARGING_ON_DARK, Prefs.DEF_COLOR_CHARGING_ON_DARK);
        s.chargingOnLight = bundle.getInt(Prefs.KEY_COLOR_CHARGING_ON_LIGHT, Prefs.DEF_COLOR_CHARGING_ON_LIGHT);
        s.lowOnDark = bundle.getInt(Prefs.KEY_COLOR_LOW_ON_DARK, Prefs.DEF_COLOR_LOW_ON_DARK);
        s.lowOnLight = bundle.getInt(Prefs.KEY_COLOR_LOW_ON_LIGHT, Prefs.DEF_COLOR_LOW_ON_LIGHT);
        s.lowThreshold = Prefs.clamp(
                bundle.getInt(Prefs.KEY_LOW_THRESHOLD, Prefs.DEF_LOW_THRESHOLD),
                Prefs.MIN_LOW_THRESHOLD, Prefs.MAX_LOW_THRESHOLD);

        s.ringStroke = Prefs.clamp(
                bundle.getInt(Prefs.KEY_RING_STROKE, Prefs.DEF_RING_STROKE),
                Prefs.MIN_RING_STROKE, Prefs.MAX_RING_STROKE);
        s.arcStroke = Prefs.clamp(
                bundle.getInt(Prefs.KEY_ARC_STROKE, Prefs.DEF_ARC_STROKE),
                Prefs.MIN_ARC_STROKE, Prefs.MAX_ARC_STROKE);
        s.valueSize = Prefs.clamp(
                bundle.getInt(Prefs.KEY_VALUE_SIZE, Prefs.DEF_VALUE_SIZE),
                Prefs.MIN_VALUE_SIZE, Prefs.MAX_VALUE_SIZE);
        s.valueWeight = Prefs.clamp(
                bundle.getInt(Prefs.KEY_VALUE_WEIGHT, Prefs.DEF_VALUE_WEIGHT),
                Prefs.MIN_VALUE_WEIGHT, Prefs.MAX_VALUE_WEIGHT);
        s.typeSize = Prefs.clamp(
                bundle.getInt(Prefs.KEY_TYPE_SIZE, Prefs.DEF_TYPE_SIZE),
                Prefs.MIN_TYPE_SIZE, Prefs.MAX_TYPE_SIZE);
        s.outTypeSize = Prefs.clamp(
                bundle.getInt(Prefs.KEY_OUT_TYPE_SIZE, Prefs.DEF_OUT_TYPE_SIZE),
                Prefs.MIN_OUT_TYPE_SIZE, Prefs.MAX_OUT_TYPE_SIZE);
        s.typeWeight = Prefs.clamp(
                bundle.getInt(Prefs.KEY_TYPE_WEIGHT, Prefs.DEF_TYPE_WEIGHT),
                Prefs.MIN_TYPE_WEIGHT, Prefs.MAX_TYPE_WEIGHT);
        s.trackAlpha = Prefs.clamp(
                bundle.getInt(Prefs.KEY_TRACK_ALPHA, Prefs.DEF_TRACK_ALPHA),
                Prefs.MIN_TRACK_ALPHA, Prefs.MAX_TRACK_ALPHA);
        s.debugLog = bundle.getBoolean(Prefs.KEY_DEBUG_LOG, Prefs.DEF_DEBUG_LOG);
        return s;
    }

    /**
     * The same read on top of an existing snapshot: keys the bundle carries are
     * taken from it, keys it leaves out keep the value {@code base} already had.
     *
     * <p>Seeding matters because the bundle is untrusted input. If an absent key
     * fell back to the shared default instead, a single missing extra would wipe
     * the user's configuration - including {@code enabled}, which would flip back
     * to true and draw the trio glyph over an otherwise native status bar.
     */
    public static TrioSettings fromBundle(Bundle bundle, TrioSettings base) {
        TrioSettings s = (base == null) ? defaults() : base.copy();
        if (bundle == null) {
            return s;
        }
        TrioSettings delivered = fromBundle(bundle);
        for (String key : bundle.keySet()) {
            s.applyKeyFrom(delivered, key);
        }
        return s;
    }

    /**
     * The inverse of {@link #fromBundle(Bundle)}, using the same pref key names
     * so the two sides cannot drift apart. The settings app sends this to the
     * hooked process after every write.
     */
    public Bundle toBundle() {
        Bundle b = new Bundle();
        b.putBoolean(Prefs.KEY_ENABLED, enabled);
        b.putBoolean(Prefs.KEY_SHOW_WIFI, showWifi);
        b.putBoolean(Prefs.KEY_SHOW_MOBILE, showMobile);
        b.putBoolean(Prefs.KEY_SHOW_VALUE, showValue);
        b.putBoolean(Prefs.KEY_SHOW_BOLT, showBolt);
        b.putInt(Prefs.KEY_MOBILE_TYPE_MODE, mobileTypeMode);
        b.putBoolean(Prefs.KEY_VALUE_CENTRED, valueCentred);

        b.putBoolean(Prefs.KEY_ROLE_COLORS, roleColors);
        b.putInt(Prefs.KEY_COLOR_CRITICAL_ON_DARK, criticalOnDark);
        b.putInt(Prefs.KEY_COLOR_CRITICAL_ON_LIGHT, criticalOnLight);
        b.putInt(Prefs.KEY_COLOR_CHARGING_ON_DARK, chargingOnDark);
        b.putInt(Prefs.KEY_COLOR_CHARGING_ON_LIGHT, chargingOnLight);
        b.putInt(Prefs.KEY_COLOR_LOW_ON_DARK, lowOnDark);
        b.putInt(Prefs.KEY_COLOR_LOW_ON_LIGHT, lowOnLight);
        b.putInt(Prefs.KEY_LOW_THRESHOLD, lowThreshold);

        b.putInt(Prefs.KEY_RING_STROKE, ringStroke);
        b.putInt(Prefs.KEY_ARC_STROKE, arcStroke);
        b.putInt(Prefs.KEY_VALUE_SIZE, valueSize);
        b.putInt(Prefs.KEY_VALUE_WEIGHT, valueWeight);
        b.putInt(Prefs.KEY_TYPE_SIZE, typeSize);
        b.putInt(Prefs.KEY_OUT_TYPE_SIZE, outTypeSize);
        b.putInt(Prefs.KEY_TYPE_WEIGHT, typeWeight);
        b.putInt(Prefs.KEY_TRACK_ALPHA, trackAlpha);

        b.putBoolean(Prefs.KEY_DEBUG_LOG, debugLog);
        return b;
    }
}
