package com.hyperduo.trio;

/**
 * The single source of truth for every setting shared between the settings app
 * and the hooked SystemUI process.
 *
 * <p>Both sides talk over the Xposed framework's remote preferences: the
 * settings app writes, the hooked process only reads. Because the framework
 * casts stored values to the requested type without conversion, every key is
 * read and written with exactly one type for the whole lifetime of the module.
 * Keep the {@code DEF_*} defaults here as well so both sides agree when the
 * framework cannot provide remote preferences (embedded mode, or a first run
 * before the user ever opened the settings screen).
 */
public final class Prefs {

    private Prefs() {
    }

    /** Remote-preference group name. Also the settings app's file name. */
    public static final String NAME = "hyperduo_settings";

    /**
     * Explicit broadcast the settings app sends to SystemUI after every write,
     * carrying the whole snapshot as extras.
     *
     * <p>Remote preferences remain the store of record, but the framework's
     * "a value changed" callback has been observed to never reach the hooked
     * process on this device: the daemon database held the new {@code enabled}
     * value while SystemUI kept drawing from the old one. The broadcast is a
     * second, callback-independent path for exactly that case.
     *
     * <p>Lives here rather than in {@code TrioConfig} because the settings app
     * must be able to name it: {@code TrioConfig} references
     * {@code io.github.libxposed.api}, which is {@code compileOnly} and so is
     * not on the app's runtime class path.
     */
    public static final String ACTION_RELOAD = "com.hyperduo.trio.action.RELOAD";

    /**
     * Package the reload broadcast is addressed to. SystemUI registers the
     * receiver, so an explicit package keeps the intent away from every other
     * process on the device.
     */
    public static final String SYSTEMUI_PACKAGE = "com.android.systemui";

    // ------------------------------------------------------------------ keys
    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_SHOW_WIFI = "show_wifi";
    public static final String KEY_SHOW_MOBILE = "show_mobile";
    public static final String KEY_SHOW_VALUE = "show_value";
    public static final String KEY_SHOW_BOLT = "show_bolt";
    /**
     * Where the mobile network type ("5G", "4G"...) is drawn: hidden, inside the
     * ring, or outside it.
     *
     * <p>An {@code int} rather than the boolean it replaced. The framework does
     * no type conversion, so {@link #KEY_SHOW_MOBILE_TYPE} could not simply be
     * re-read as an int: on an existing install the stored boolean would make
     * {@code getInt} throw {@code ClassCastException}. A new key keeps every
     * installed value readable, and the old one is consulted once during
     * migration - see {@code TrioSettings.readMobileTypeMode}.
     */
    public static final String KEY_MOBILE_TYPE_MODE = "mobile_type_mode";
    /**
     * Legacy, read-only for migration.
     *
     * <p>Superseded by {@link #KEY_MOBILE_TYPE_MODE}. Never write it again; the
     * only remaining reader is the one-time migration in
     * {@code TrioSettings.from(SharedPreferences)}, which maps an existing
     * "on" to {@link #MOBILE_TYPE_IN_RING} while the new key is absent.
     */
    public static final String KEY_SHOW_MOBILE_TYPE = "show_mobile_type";
    /** Swaps the Wi-Fi arcs and the battery reading between ring centre and notch. */
    public static final String KEY_SWAP_WIFI_VALUE = "swap_wifi_value";

    public static final String KEY_ROLE_COLORS = "role_colors";
    public static final String KEY_COLOR_CRITICAL_ON_DARK = "color_critical_on_dark";
    public static final String KEY_COLOR_CRITICAL_ON_LIGHT = "color_critical_on_light";
    public static final String KEY_COLOR_CHARGING_ON_DARK = "color_charging_on_dark";
    public static final String KEY_COLOR_CHARGING_ON_LIGHT = "color_charging_on_light";
    public static final String KEY_COLOR_LOW_ON_DARK = "color_low_on_dark";
    public static final String KEY_COLOR_LOW_ON_LIGHT = "color_low_on_light";
    public static final String KEY_LOW_THRESHOLD = "low_threshold";

    public static final String KEY_RING_STROKE = "ring_stroke";
    public static final String KEY_ARC_STROKE = "arc_stroke";
    public static final String KEY_VALUE_SIZE = "value_size";
    public static final String KEY_VALUE_WEIGHT = "value_weight";
    /** Font size of the network type drawn inside the ring. */
    public static final String KEY_TYPE_SIZE = "type_size";
    /**
     * Font size of the network type when it is drawn <em>outside</em> the ring.
     *
     * <p>Deliberately a separate key from {@link #KEY_TYPE_SIZE}: the in-ring
     * label lives in the ring's 120x120 design space and its size is tuned
     * against the ring geometry, whereas the out-of-ring label is a plain
     * status-bar TextView laid out in real pixels next to MIUI's own icons, so
     * it is tuned on its own scale - and its range reaches higher accordingly.
     * Sharing one key between the two spaces is what this key fixes.
     *
     * <p>An {@code int}, like {@link #KEY_TYPE_SIZE}. A brand-new key name and
     * never a reuse of the old one: the framework does no type conversion, so
     * every key has exactly one type for the whole lifetime of the module.
     */
    public static final String KEY_OUT_TYPE_SIZE = "out_type_size";
    public static final String KEY_TYPE_WEIGHT = "type_weight";
    public static final String KEY_TRACK_ALPHA = "track_alpha";

    public static final String KEY_DEBUG_LOG = "debug_log";

    // -------------------------------------------------------------- defaults
    public static final boolean DEF_ENABLED = true;
    public static final boolean DEF_SHOW_WIFI = true;
    public static final boolean DEF_SHOW_MOBILE = true;
    public static final boolean DEF_SHOW_VALUE = true;
    public static final boolean DEF_SHOW_BOLT = true;
    /**
     * Legacy, read-only for migration.
     *
     * <p>Superseded by {@link #DEF_MOBILE_TYPE_MODE}; kept only so the migration
     * can tell what an installed user had before the int key existed.
     */
    public static final boolean DEF_SHOW_MOBILE_TYPE = false;

    /** Off: the network type is not drawn at all. */
    public static final int MOBILE_TYPE_OFF = 0;
    /** Draw the network type inside the ring, in the middle when Wi-Fi is absent. */
    public static final int MOBILE_TYPE_IN_RING = 1;
    /** Draw the network type outside the ring. */
    public static final int MOBILE_TYPE_OUT_RING = 2;
    /**
     * Off by default: the shipment look keeps the battery number in the centre
     * when there is no Wi-Fi, and draws no network type at all.
     */
    public static final int DEF_MOBILE_TYPE_MODE = MOBILE_TYPE_OFF;
    /**
     * Off by default: the shipped look keeps the Wi-Fi arcs in the middle of the
     * ring. Turning this on mirrors the layout - the arcs move up into the 12
     * o'clock notch and the battery reading (or the network type, or the enlarged
     * charging bolt) takes the middle, drawn at
     * {@link TrioGeometry#CENTRE_SIZE_RATIO} scale.
     */
    public static final boolean DEF_SWAP_WIFI_VALUE = false;

    public static final boolean DEF_ROLE_COLORS = true;
    public static final int DEF_COLOR_CRITICAL_ON_DARK = 0xFFFF3B30;
    public static final int DEF_COLOR_CRITICAL_ON_LIGHT = 0xFFFF3B30;
    public static final int DEF_COLOR_CHARGING_ON_DARK = 0xFF34C759;
    public static final int DEF_COLOR_CHARGING_ON_LIGHT = 0xFF1F8F3D;
    public static final int DEF_COLOR_LOW_ON_DARK = 0xFFF2B900;
    public static final int DEF_COLOR_LOW_ON_LIGHT = 0xFFC99700;
    public static final int DEF_LOW_THRESHOLD = 20;

    public static final int DEF_RING_STROKE = 14;
    public static final int DEF_ARC_STROKE = 12;
    public static final int DEF_VALUE_SIZE = 36;
    /**
     * 700 is {@code Typeface.BOLD}, the weight the reference glyph uses. The
     * whole 100..900 range is exposed so thin skinning and heavy digits are both
     * reachable.
     */
    public static final int DEF_VALUE_WEIGHT = 700;
    /**
     * Font size of the centred network type. Deliberately an absolute size rather
     * than a ratio of {@link #DEF_VALUE_SIZE}: the type is a standalone label the
     * user tunes on its own, and 32 is what the old 0.9 x 36 ratio produced, so
     * the default look is unchanged.
     */
    public static final int DEF_TYPE_SIZE = 32;
    /**
     * Font size of the network type when it is drawn outside the ring, at the
     * status bar's own scale. 32 matches {@link #DEF_TYPE_SIZE}, so a user who
     * never touches the new slider keeps exactly the look they had.
     */
    public static final int DEF_OUT_TYPE_SIZE = 32;
    public static final int DEF_TYPE_WEIGHT = 700;
    public static final int DEF_TRACK_ALPHA = 56;

    public static final boolean DEF_DEBUG_LOG = false;

    // --------------------------------------------------------------- bounds
    public static final int MIN_RING_STROKE = 4;
    public static final int MAX_RING_STROKE = 16;
    public static final int MIN_ARC_STROKE = 3;
    public static final int MAX_ARC_STROKE = 16;
    public static final int MIN_VALUE_SIZE = 16;
    public static final int MAX_VALUE_SIZE = 44;
    public static final int MIN_VALUE_WEIGHT = 100;
    public static final int MAX_VALUE_WEIGHT = 900;
    public static final int MIN_TYPE_SIZE = 16;
    public static final int MAX_TYPE_SIZE = 44;
    public static final int MIN_OUT_TYPE_SIZE = 16;
    /**
     * Higher than {@link #MAX_TYPE_SIZE} on purpose: the out-of-ring label sits
     * in the status bar's real pixel space rather than the ring's design space,
     * so it needs headroom the in-ring label does not.
     */
    public static final int MAX_OUT_TYPE_SIZE = 64;
    public static final int MIN_TYPE_WEIGHT = 100;
    public static final int MAX_TYPE_WEIGHT = 900;
    public static final int MIN_TRACK_ALPHA = 0;
    public static final int MAX_TRACK_ALPHA = 255;
    public static final int MIN_LOW_THRESHOLD = 5;
    public static final int MAX_LOW_THRESHOLD = 50;

    public static final int MIN_MOBILE_TYPE_MODE = MOBILE_TYPE_OFF;
    public static final int MAX_MOBILE_TYPE_MODE = MOBILE_TYPE_OUT_RING;

    static int clamp(int value, int min, int max) {
        return value < min ? min : (value > max ? max : value);
    }
}
