package com.hyperduo.trio;

import android.content.SharedPreferences;

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
    public boolean showMobileType;
    /**
     * Swaps the two slots: the Wi-Fi arcs move up into the 12 o'clock notch and
     * the battery reading takes the middle of the ring, enlarged. While charging
     * the bolt owns the middle and the arcs stay in the notch.
     */
    public boolean swapWifiValue;

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
        s.showMobileType = Prefs.DEF_SHOW_MOBILE_TYPE;
        s.swapWifiValue = Prefs.DEF_SWAP_WIFI_VALUE;

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
        s.showMobileType = p.getBoolean(Prefs.KEY_SHOW_MOBILE_TYPE, Prefs.DEF_SHOW_MOBILE_TYPE);
        s.swapWifiValue = p.getBoolean(Prefs.KEY_SWAP_WIFI_VALUE, Prefs.DEF_SWAP_WIFI_VALUE);

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
        s.typeWeight = Prefs.clamp(
                p.getInt(Prefs.KEY_TYPE_WEIGHT, Prefs.DEF_TYPE_WEIGHT),
                Prefs.MIN_TYPE_WEIGHT, Prefs.MAX_TYPE_WEIGHT);
        s.trackAlpha = Prefs.clamp(
                p.getInt(Prefs.KEY_TRACK_ALPHA, Prefs.DEF_TRACK_ALPHA),
                Prefs.MIN_TRACK_ALPHA, Prefs.MAX_TRACK_ALPHA);
        s.debugLog = p.getBoolean(Prefs.KEY_DEBUG_LOG, Prefs.DEF_DEBUG_LOG);
        return s;
    }
}
