package com.hyperduo.trio;

import android.content.res.Resources;
import android.view.View;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Snapshot of everything the trio glyph needs: battery level / charge state, the
 * Wi-Fi and mobile signal levels, and the tint colours MIUI is currently using
 * for this status bar.
 *
 * <p>All battery/tint values live on {@code MiuiBatteryMeterIconView} as public
 * fields, so they are read reflectively; every read degrades to a sane default.
 *
 * <p>Signal levels are learned from the signal icons the Wi-Fi and mobile binders
 * apply (see {@link #noteSignalIcon}). Those arrive as raw resource ids, which are
 * resolved to entry names once a host view — and therefore a {@code Resources} —
 * is available.
 */
final class TrioState {

    /** Latest resolved signal levels; shared by every trio host. */
    static volatile int sWifiLevel = -1;
    static volatile int sMobileLevel = -1;

    /**
     * Whether the status bar is currently showing a Wi-Fi indicator at all.
     *
     * <p>The level alone is not enough: MIUI only calls
     * {@code MiuiStatusBarIconViewHelper.transformResId} while it is binding a
     * signal icon, so when Wi-Fi is switched off the last level simply stays
     * behind and the arcs would never disappear. Presence is sampled from the
     * live view tree instead (see {@code TrioHooks.settle}), which is the only
     * source that tracks the indicator going away.
     */
    static volatile boolean sWifiPresent = true;

    /**
     * Mobile network type label as MIUI itself renders it: "5G", "4G", "5GA",
     * "3G"... Empty when there is nothing to show.
     *
     * <p>Sampled from {@code MobileTypeDrawable.mMobileType} after MIUI's own
     * normalisation has run, so this is exactly the string the stock status bar
     * would have painted. {@code "5G++"} has already been rewritten to "5G" with
     * a separate double-plus flag by the time the hook reads it, which is why the
     * module never has to know about that special case.
     */
    static volatile String sMobileType = "";

    /** Raw signal icon ids seen before a host existed, newest last. */
    private static final List<Integer> PENDING = new ArrayList<>();
    private static final int PENDING_MAX = 8;

    /** Signal icon id -> classification code. Ids are process-stable. */
    private static final java.util.concurrent.ConcurrentHashMap<Integer, Integer> CLASSIFIED =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Not a signal-level icon: leave the last level alone. */
    private static final int C_IGNORE = -1;
    /** Genuine "no signal" marker: clear the dots. */
    private static final int C_CLEAR = -2;
    /** Wi-Fi levels are stored as this base plus 0..3, to share one int channel. */
    private static final int C_WIFI_BASE = 100;

    private static final int DEFAULT_FOREGROUND = 0xFFFFFFFF;

    private static Field fLevel;
    private static Field fCharging;
    private static Field fQuick;
    private static Field fLow;
    private static Field fPowerSave;
    private static Field fPerformance;
    private static Field fUseTint;
    private static Field fTint;
    private static Field fLight;
    private static Field fDark;
    private static Field fIntensity;
    private static boolean fieldsReady;

    final View host;

    // Battery state of this host.
    int level = -1;
    boolean charging;
    boolean quickCharging;
    boolean low;
    boolean powerSave;
    boolean performanceMode;

    // Tint state of this host.
    boolean useTint;
    int tintColor;
    int lightColor;
    int darkColor;
    float darkIntensity;

    // Signal levels (shared).
    int wifiLevel = -1;
    int mobileLevel = -1;
    boolean wifiPresent = true;
    String mobileType = "";

    TrioState(View host) {
        this.host = host;
    }

    // ------------------------------------------------------------------ fields

    private static synchronized void ensureFields(Class<?> c) {
        if (fieldsReady) {
            return;
        }
        fLevel = Refl.field(c, "mLevel");
        fCharging = Refl.field(c, "mCharging");
        fQuick = Refl.field(c, "mQuickCharging");
        fLow = Refl.field(c, "mLow");
        fPowerSave = Refl.field(c, "mPowerSave");
        fPerformance = Refl.field(c, "mPerformanceMode");
        fUseTint = Refl.field(c, "mUseTint");
        fTint = Refl.field(c, "mTintColor");
        fLight = Refl.field(c, "mLightColor");
        fDark = Refl.field(c, "mDarkColor");
        fIntensity = Refl.field(c, "mDarkIntensity");
        fieldsReady = fLevel != null;
    }

    /** Re-reads every status value from the host. Cheap enough to call in onDraw. */
    void refresh() {
        ensureFields(host.getClass());
        level = Refl.getInt(fLevel, host, level);
        charging = Refl.getBool(fCharging, host, charging);
        quickCharging = Refl.getBool(fQuick, host, quickCharging);
        low = Refl.getBool(fLow, host, low);
        powerSave = Refl.getBool(fPowerSave, host, powerSave);
        performanceMode = Refl.getBool(fPerformance, host, performanceMode);
        useTint = Refl.getBool(fUseTint, host, useTint);
        tintColor = Refl.getInt(fTint, host, tintColor);
        lightColor = Refl.getInt(fLight, host, lightColor);
        darkColor = Refl.getInt(fDark, host, darkColor);
        darkIntensity = Refl.getFloat(fIntensity, host, darkIntensity);

        resolvePending(resourcesOf(host));
        wifiLevel = sWifiLevel;
        mobileLevel = sMobileLevel;
        wifiPresent = sWifiPresent;
        mobileType = sMobileType;
    }

    private static Resources resourcesOf(View v) {
        try {
            return v.getResources();
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ colours

    /** The plain icon colour: what MIUI would paint the battery icon in. */
    int foreground() {
        int c = useTint ? tintColor : (darkIntensity > 0f ? darkColor : lightColor);
        if (c == 0) {
            c = DEFAULT_FOREGROUND;
        }
        return c;
    }

    // ------------------------------------------------------------ signal levels

    /**
     * Records a signal icon resource id — the {@code rawResId} the Wi-Fi and
     * mobile binders pass to
     * {@code MiuiStatusBarIconViewHelper.transformResId(int,boolean,boolean)}.
     *
     * <p>Names look like {@code stat_sys_wifi_signal_2} or
     * {@code stat_sys_signal_3}; decorated variants
     * ({@code stat_sys_signal_0_no_voice_darkmode}) still carry the level as the
     * token right after {@code signal}. Ids seen before a {@code Resources} handle
     * exists are buffered and resolved on the next draw.
     */
    static void noteSignalIcon(Resources res, int resId) {
        if (resId == 0) {
            return;
        }
        if (res == null) {
            synchronized (PENDING) {
                if (PENDING.size() >= PENDING_MAX) {
                    PENDING.remove(0);
                }
                PENDING.add(Integer.valueOf(resId));
            }
            return;
        }
        apply(res, resId);
    }

    /**
     * Records Wi-Fi indicator presence and reports whether it changed.
     *
     * <p>Called from the icon container's layout, from the live children: a
     * {@code slot=wifi} child means the indicator is on screen. Reporting the
     * change lets the caller redraw only on the edge, not on every layout pass.
     */
    static boolean setWifiPresent(boolean present) {
        if (sWifiPresent == present) {
            return false;
        }
        sWifiPresent = present;
        return true;
    }

    /**
     * Records the mobile network type label and reports whether it changed.
     *
     * <p>Fed from {@code MobileTypeDrawable.measure()} after MIUI has already
     * normalised the string, so whatever the stock status bar would show ("5G",
     * "5GA", "4G") is what lands here. A {@code null} is treated as "no type",
     * which is what the view model holds while the radio is still settling.
     */
    static boolean setMobileType(String type) {
        final String next = type == null ? "" : type;
        if (next.equals(sMobileType)) {
            return false;
        }
        sMobileType = next;
        return true;
    }

    private static void resolvePending(Resources res) {
        if (res == null) {
            return;
        }
        synchronized (PENDING) {
            if (PENDING.isEmpty()) {
                return;
            }
            for (int i = 0; i < PENDING.size(); i++) {
                apply(res, PENDING.get(i).intValue());
            }
            PENDING.clear();
        }
    }

    private static void apply(Resources res, int resId) {
        // Classification is a pure function of the resource id, and this runs on
        // the hot transformResId path (20+ call sites, every signal update), so
        // cache it: one getResourceEntryName per distinct id per process.
        final Integer hit = CLASSIFIED.get(Integer.valueOf(resId));
        final int code = hit != null ? hit.intValue() : classify(res, resId);
        if (code == C_IGNORE) {
            return;
        }
        if (code == C_CLEAR) {
            // Not a level icon but a genuine "no signal" marker: clear the dots
            // rather than leaving the last level lit forever.
            sMobileLevel = 0;
            return;
        }
        if (code >= C_WIFI_BASE) {
            sWifiLevel = code - C_WIFI_BASE;
        } else {
            sMobileLevel = code;
        }
    }

    /**
     * Resolves a signal icon id to a classification code, caching the result.
     * Never returns null.
     */
    private static int classify(Resources res, int resId) {
        final int code = computeCode(res, resId);
        CLASSIFIED.put(Integer.valueOf(resId), Integer.valueOf(code));
        return code;
    }

    private static int computeCode(Resources res, int resId) {
        final String name;
        try {
            name = res.getResourceEntryName(resId);
        } catch (Throwable t) {
            return C_IGNORE;
        }
        if (name == null) {
            return C_IGNORE;
        }
        // Match the primary Wi-Fi entry only. A loose "wifi_signal" substring
        // would also swallow stat_sys_slave_wifi_signal_* and
        // ic_no_internet_wifi_signal_*, which are different indicators.
        final boolean wifi = name.startsWith("stat_sys_wifi_signal");
        if (!wifi && !name.startsWith("stat_sys_signal")) {
            return C_IGNORE;
        }
        final int value = parseLevel(name);
        if (value < 0) {
            // Not a level icon (volte, vonr, data_left, satellite_null, ...):
            // decoration rather than a level change, so keep the last level.
            // The two genuine "no signal" markers are matched exactly, because
            // stat_sys_signal_satellite_null also ends in _null and belongs to
            // the separate satellite indicator.
            if ("stat_sys_signal_null".equals(name)
                    || "stat_sys_signal_flightmode".equals(name)) {
                return C_CLEAR;
            }
            return C_IGNORE;
        }
        return wifi ? C_WIFI_BASE + value : value;
    }

    /** {@code stat_sys_wifi_signal_2} → 2, {@code stat_sys_signal_0_no_voice} → 0. */
    private static int parseLevel(String name) {
        final String[] parts = name.split("_");
        for (int i = 0; i < parts.length - 1; i++) {
            if ("signal".equals(parts[i])) {
                final String n = parts[i + 1];
                if (n.length() == 1 && n.charAt(0) >= '0' && n.charAt(0) <= '9') {
                    return n.charAt(0) - '0';
                }
                return -1;
            }
        }
        return -1;
    }
}
