package com.hyperduo.trio;

import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * Installs every hook HyperDuo v1 needs and keeps the per-host {@link TrioState}
 * bookkeeping.
 *
 * <h3>Why the trio glyph is drawn from the battery icon view</h3>
 * {@code MiuiBatteryMeterIconView} is already measured 28dp x 20dp
 * ({@code battery_meter_width} x {@code status_bar_icon_height}) and its native
 * {@code onDraw} clears the canvas itself, so drawing after {@code proceed()}
 * takes the icon over completely without any view-tree surgery.
 *
 * <h3>Why {@code ignoredSlots} alone leaves a ghost, and what fixes it</h3>
 * {@code ModernStatusBarView.isIconVisible()} is driven by the binding and the anim
 * helper, never by {@code View.getVisibility()}, so hiding the native Wi-Fi / mobile
 * icons has to go through {@code ignoredSlots}: {@code MiuiStatusIconContainer.onMeasure}
 * skips every child whose slot is in that list. But the <em>layout</em> pass still
 * touches them: its first pass unconditionally runs
 * {@code childAt.layout(0, y, measuredWidth, ...)} for every child, and the later
 * right-to-left pass only repositions children that are visible, unblocked and not
 * ignored. So an ignored child keeps the left edge 0 of the pass. The status bar
 * hands its icon container exactly half the screen width (see
 * {@code MiuiNotificationStatusContainer.onMeasure}), which parks the orphaned
 * native mobile view - and the "5G" label inside it - on the screen centre line.
 *
 * <p>The fix therefore has to pair the slot suppression with a hard
 * {@code View.GONE} on the same children, re-applied on every layout pass so it
 * survives MIUI re-showing them. Hiding is restricted to containers that really
 * render the trio glyph (one that owns a live host), because a container whose
 * battery view never draws would otherwise lose its signal icons entirely.
 *
 * <h3>Why {@code addIgnoredSlots} itself is not hooked</h3>
 * The callers pass MIUI's shared static block lists
 * ({@code MiuiIconManagerUtils.RIGHT_BLOCK_LIST}); mutating the argument would
 * corrupt those lists process-wide. Instead the container's own
 * {@code addIgnoredSlots} is invoked with a private list, and only when one of our
 * slots is actually missing - {@code addIgnoredSlots} unconditionally ends in
 * {@code requestLayout()}, so calling it on every pass would loop forever.
 */
final class TrioHooks {

    private static final String TAG = "HyperDuo";
    static final int LOG_INFO = 4;
    static final int LOG_WARN = 5;
    static final int LOG_ERROR = 6;

    /**
     * Enables the view-tree dumps used to pin down which container holds a stray
     * native icon. Normally driven by the user's debug-log setting; force it on
     * here while matching a container that has not been identified yet.
     */
    static final boolean DEBUG_DUMP = false;

    /** True when the user asked for verbose logging. */
    static boolean debugLog() {
        return DEBUG_DUMP || TrioConfig.debugLog();
    }

    /**
     * {@code MiuiStatusBatteryContainer} — matched by name while walking the view
     * tree, because resolving it through the host's class loader never worked.
     */
    private static final String BATTERY_CONTAINER_CLASS =
            "com.android.systemui.statusbar.views.MiuiStatusBatteryContainer";

    /** Module handle for logging from helpers that have no callback argument. */
    private static volatile XposedModule sModule;

    /**
     * Slots whose native icons are folded into the trio glyph.
     *
     * <p>"mobile" and "wifi" are the classic {@code ModernStatusBarMobileView} /
     * {@code ModernStatusBarWifiView} slots. HyperOS 4 replaced the visible mobile
     * indicator with a Compose one whose slot is "stacked_mobile"
     * ({@code SingleBindableStatusBarComposeIconView}); on a real device it is the
     * only mobile child left visible and the classic ones stay {@code a=0.0 v=8}.
     * All three are listed so the glyph suppresses whichever the build uses.
     */
    private static final List<String> FOLDED_SLOTS =
            Collections.unmodifiableList(
                    Arrays.asList("wifi", "mobile", "stacked_mobile"));

    /** Hosts currently drawing the trio glyph. */
    private static final List<TrioState> HOSTS =
            Collections.synchronizedList(new ArrayList<TrioState>());

    /** The status bar's icon container, kept for {@link #resources()}. */
    private static volatile Object sStatusIconContainer;

    /** {@code MiuiStatusBatteryContainer}, resolved once. */
    private static volatile Class<?> sBatteryContainerClass;
    /** {@code MiuiStatusBatteryContainer.mStatusIcon}, resolved once. */
    private static volatile Field sStatusIconField;

    /** {@code MiuiBatteryMeterView.mStoreRealStyle}, resolved once. */
    private static volatile Field sStoreRealStyleField;

    /**
     * Live {@code MiuiBatteryMeterView} instances, so a settings change can ask
     * them to re-apply the style MIUI last requested.
     */
    private static final List<WeakReference<View>> METERS = new ArrayList<>();

    /** {@code MiuiStatusIconContainer}, resolved once. */
    private static volatile Class<?> sIconContainerClass;
    /** {@code MiuiStatusIconContainer.ignoredSlots}, resolved once. */
    private static volatile Field sIgnoredSlotsField;

    /**
     * Icon containers that belong to a view actually drawing the trio glyph.
     * Native Wi-Fi / mobile children are only forced GONE in these, so a
     * container whose battery view stays hidden keeps its own signal icons.
     */
    private static final Map<Object, Boolean> OWNED =
            Collections.synchronizedMap(new WeakHashMap<Object, Boolean>());

    /** Containers already dumped to the log, to keep one diagnostic each. */
    private static final Map<Object, Boolean> DIAGNOSED =
            Collections.synchronizedMap(new WeakHashMap<Object, Boolean>());

    /**
     * Last {@code owned} value logged per container.
     *
     * <p>The first layout of a container normally runs before any glyph host has
     * drawn, so the first header legitimately reports {@code owned=false}; a
     * one-shot header would then keep reporting a stale value for the rest of
     * the session. Re-logging on a change keeps the log honest for two extra
     * lines at most per container.
     */
    private static final Map<Object, Boolean> DIAG_OWNED =
            Collections.synchronizedMap(new WeakHashMap<Object, Boolean>());

    /**
     * Folded children already given a hard {@code GONE}. Kept so a firmware pass
     * that re-shows one of them is not answered with a new visibility flip on
     * every layout - the zero-size {@code layout()} in {@link #settle} already
     * keeps the child undrawn.
     */
    private static final Map<View, Boolean> COLLAPSED =
            Collections.synchronizedMap(new WeakHashMap<View, Boolean>());

    /** Last child dump per container, so a steady layout logs at most once. */
    private static final Map<Object, String> DIAG_SIG =
            Collections.synchronizedMap(new WeakHashMap<Object, String>());

    /** Hard cap on total child dumps, so a layout loop cannot flood the log. */
    private static volatile int sDiagDumps;
    /** Hard cap on container headers, including {@code owned} flips. */
    private static volatile int sDiagHeaders;

    private TrioHooks() {
    }

    // ------------------------------------------------------------------ install

    static void install(XposedModule module, ClassLoader cl) {
        sModule = module;
        // Read the user's settings before anything is hooked: the very first hook
        // callback already needs to know whether the module is switched on.
        TrioConfig.install(module);
        TrioConfig.addListener(new TrioConfig.Listener() {
            @Override
            public void onConfigChanged() {
                applyConfigChange();
            }
        });
        int hooked = 0;
        hooked += group(module, cl, 1);
        hooked += group(module, cl, 2);
        hooked += group(module, cl, 3);
        hooked += group(module, cl, 4);
        hooked += group(module, cl, 5);
        hooked += group(module, cl, 6);
        hooked += group(module, cl, 7);
        log(module, "HyperDuo installed, hooks=" + hooked
                + " enabled=" + TrioConfig.get().enabled);
    }

    /**
     * Reacts to the settings app changing a value.
     *
     * <p>Runs on the Binder thread the framework delivers the change on, so
     * everything that touches the view tree is posted to the main looper.
     *
     * <p>Two things need more than a repaint. The battery style was pinned to 0
     * while the module was on, and MIUI only applies a style inside
     * {@code onBatteryStyleChanged}, which is not re-run on its own — so each
     * meter view is asked to re-apply the style it really wants. And when the
     * module is switched off the native signal icons have to be folded back in,
     * which means undoing the ignored slots and the forced {@code GONE}.
     */
    private static void applyConfigChange() {
        final boolean enabled = TrioConfig.get().enabled;
        final TrioSettings cfg = TrioConfig.get();
        final List<TrioState> hosts;
        synchronized (HOSTS) {
            hosts = new ArrayList<TrioState>(HOSTS);
        }
        if (debugLog()) {
            log(LOG_INFO, "config changed, hosts=" + hosts.size()
                    + " enabled=" + enabled
                    + " ring=" + cfg.ringStroke + " arc=" + cfg.arcStroke
                    + " size=" + cfg.valueSize);
        }
        for (int i = 0; i < hosts.size(); i++) {
            final View v = hosts.get(i).host;
            if (v == null) {
                continue;
            }
            v.post(new Runnable() {
                @Override
                public void run() {
                    if (!enabled) {
                        restoreNative();
                    }
                    v.invalidate();
                }
            });
        }
        restyleMeters();
        if (!enabled) {
            restoreNative();
        }
    }

    /** Asks every known {@code MiuiBatteryMeterView} to re-apply its real style. */
    private static void restyleMeters() {
        final List<View> meters = new ArrayList<>();
        synchronized (METERS) {
            for (int i = METERS.size() - 1; i >= 0; i--) {
                final View v = METERS.get(i).get();
                if (v == null) {
                    METERS.remove(i);
                } else {
                    meters.add(v);
                }
            }
        }
        for (int i = 0; i < meters.size(); i++) {
            final View meter = meters.get(i);
            meter.post(new Runnable() {
                @Override
                public void run() {
                    reapplyStyle(meter);
                }
            });
        }
    }

    private static void reapplyStyle(View meter) {
        final Object style = Refl.get(sStoreRealStyleField, meter);
        if (style instanceof Number) {
            Refl.callArgs(meter, "onBatteryStyleChanged",
                    new Class<?>[]{int.class},
                    new Object[]{Integer.valueOf(((Number) style).intValue())});
        }
    }

    /**
     * Puts the native icons back: drops our slots from {@code ignoredSlots} and
     * clears the forced {@code GONE}, so switching the module off leaves the
     * status bar exactly as MIUI would have drawn it.
     */
    private static void restoreNative() {
        List<Object> containers;
        synchronized (OWNED) {
            containers = new ArrayList<Object>(OWNED.keySet());
        }
        for (int i = 0; i < containers.size(); i++) {
            final Object container = containers.get(i);
            unfoldSlots(container);
            if (!(container instanceof ViewGroup)) {
                continue;
            }
            final ViewGroup group = (ViewGroup) container;
            final int count = group.getChildCount();
            for (int c = 0; c < count; c++) {
                final View child;
                try {
                    child = group.getChildAt(c);
                } catch (Throwable t) {
                    continue;
                }
                if (child == null || !FOLDED_SLOTS.contains(slotOf(child))) {
                    continue;
                }
                try {
                    if (child.getVisibility() != View.VISIBLE) {
                        child.setVisibility(View.VISIBLE);
                    }
                } catch (Throwable ignored) {
                    // never let one child abort the pass
                }
            }
        }
        synchronized (COLLAPSED) {
            COLLAPSED.clear();
        }
        synchronized (METERS) {
            METERS.clear();
        }
    }

    /**
     * Runs one hook group, isolating any failure. Hook groups are independent:
     * a method that a given firmware build renamed must not take the others
     * down with it.
     */
    private static int group(XposedModule module, ClassLoader cl, int which) {
        try {
            switch (which) {
                case 1: return hookStatusBarViewCapture(module, cl);
                case 2: return hookBatteryIconView(module, cl);
                case 3: return hookBatteryMeterView(module, cl);
                case 4: return hookStatusBarView(module, cl);
                case 5: return hookSignalIcons(module, cl);
                case 6: return hookIconContainerLayout(module, cl);
                default: return hookMobileType(module, cl);
            }
        } catch (Throwable t) {
            log(module, "hook group " + which + " failed: " + t);
            return 0;
        }
    }

    /**
     * Hooks one executable, tolerating a missing method (null) or a rejected
     * hook. Returns 1 when the hook was installed, 0 otherwise.
     */
    private static int hook(XposedModule module, Method m, String id,
                            XposedInterface.Hooker hooker) {
        if (m == null) {
            log(module, "skip " + id + ": method not found");
            return 0;
        }
        try {
            module.hook(m).setId(id).intercept(hooker);
            return 1;
        } catch (Throwable t) {
            log(module, "skip " + id + ": " + t);
            return 0;
        }
    }

    /**
     * Captures the status bar's icon container.
     *
     * <p>This deliberately hooks {@code MiuiPhoneStatusBarView}, not
     * {@code MiuiStatusBatteryContainer}: {@code system_icons.xml} is included by
     * seven layouts (status bar, keyguard, control center, both QS headers, ...),
     * so a container-level hook fires for every one of those instances and the
     * last to inflate would win, leaving us suppressing slots on the keyguard or
     * control-center container instead of the status bar's. Only
     * {@code status_bar.xml}'s root is a {@code MiuiPhoneStatusBarView}, and it
     * holds the authoritative {@code mStatusBarStatusIcons}.
     */
    private static int hookStatusBarViewCapture(XposedModule module, ClassLoader cl) {
        final Class<?> bar = Refl.cls(
                "com.android.systemui.statusbar.phone.MiuiPhoneStatusBarView", cl);
        if (bar == null) {
            log(module, "MiuiPhoneStatusBarView missing (capture)");
            return 0;
        }
        final Field iconsField = Refl.field(bar, "mStatusBarStatusIcons");
        return hook(module, Refl.method(bar, "onFinishInflate"),
                "hyperduo-container", new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        final Object result = chain.proceed();
                        // mStatusBarStatusIcons is assigned inside proceed(), so it
                        // is only readable now.
                        final Object self = chain.getThisObject();
                        final Object icons = Refl.get(iconsField, self);
                        if (icons instanceof View) {
                            sStatusIconContainer = icons;
                            foldSlots(icons);
                        }
                        return result;
                    }
                });
    }

    private static int hookBatteryIconView(XposedModule module, ClassLoader cl) {
        final Class<?> icon = Refl.cls(
                "com.android.systemui.statusbar.views.MiuiBatteryMeterIconView", cl);
        if (icon == null) {
            log(module, "MiuiBatteryMeterIconView missing");
            return 0;
        }
        final int a = hook(module, Refl.method(icon, "onDraw", Canvas.class),
                "hyperduo-draw", new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        final Object result = chain.proceed();
                        final Object self = chain.getThisObject();
                        final Object canvasArg = chain.getArg(0);
                        if (self instanceof View && canvasArg instanceof Canvas
                                && TrioConfig.get().enabled) {
                            final View host = (View) self;
                            TrioState state = stateFor(host);
                            if (state == null) {
                                state = registerHost(host);
                            }
                            state.refresh();
                            TrioRenderer.draw((Canvas) canvasArg, host, state);
                        }
                        return result;
                    }
                });
        final int b = hook(module, Refl.method(icon, "onDetachedFromWindow"),
                "hyperduo-detach", new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        final Object result = chain.proceed();
                        final Object self = chain.getThisObject();
                        if (self instanceof View) {
                            unregisterHost((View) self);
                        }
                        return result;
                    }
                });
        return a + b;
    }

    private static int hookBatteryMeterView(XposedModule module, ClassLoader cl) {
        final Class<?> meter = Refl.cls(
                "com.android.systemui.statusbar.views.MiuiBatteryMeterView", cl);
        if (meter == null) {
            log(module, "MiuiBatteryMeterView missing");
            return 0;
        }
        // Pin the style to 0: MIUI then shows the digital battery view and hides
        // its hollow variant, and never measures its own charging icon or
        // percentage container. While the module is off the call is passed
        // through untouched, so MIUI keeps whatever the user configured.
        final Field storeRealStyle = Refl.field(meter, "mStoreRealStyle");
        sStoreRealStyleField = storeRealStyle;
        final int a = hook(module, Refl.method(meter, "onBatteryStyleChanged", int.class),
                "hyperduo-style", new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        final Object self = chain.getThisObject();
                        final Object requested = chain.getArg(0);
                        if (self instanceof View) {
                            rememberMeter((View) self);
                        }
                        if (!TrioConfig.get().enabled) {
                            return chain.proceed();
                        }
                        final Object result = chain.proceed(new Object[]{Integer.valueOf(0)});
                        // L574 assigns mStoreRealStyle = i *before* the style guard,
                        // so our forced 0 clobbers the real style. Keyguard reads it
                        // (KeyguardStatusBarViewControllerInject: "mStoreRealStyle != 3")
                        // to choose between its icon-container and battery alpha
                        // animations, so restore the caller's value.
                        Refl.set(storeRealStyle, self, requested);
                        return result;
                    }
                });
        // updateChargeAndText() re-shows those two views on every state change.
        final int b = hook(module, Refl.method(meter, "updateChargeAndText"),
                "hyperduo-charge-text", new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        final Object result = chain.proceed();
                        final Object self = chain.getThisObject();
                        if (self instanceof View) {
                            rememberMeter((View) self);
                        }
                        if (TrioConfig.get().enabled) {
                            hide(Refl.get(Refl.field(meter, "mBatteryChargingView"), self));
                            hide(Refl.get(Refl.field(meter, "mBatteryPercentContainer"), self));
                        }
                        return result;
                    }
                });
        return a + b;
    }

    /** Records a meter view so a later settings change can re-apply its style. */
    private static void rememberMeter(View meter) {
        synchronized (METERS) {
            for (int i = 0; i < METERS.size(); i++) {
                if (METERS.get(i).get() == meter) {
                    return;
                }
            }
            METERS.add(new WeakReference<>(meter));
        }
    }

    /**
     * Re-applies our slots after the one call that clears them:
     * {@code MiuiPhoneStatusBarView.updateCutoutLocation} invokes
     * {@code setIgnoredSlots(RIGHT_BLOCK_LIST)}, which clears before adding.
     */
    private static int hookStatusBarView(XposedModule module, ClassLoader cl) {
        final Class<?> bar = Refl.cls(
                "com.android.systemui.statusbar.phone.MiuiPhoneStatusBarView", cl);
        if (bar == null) {
            log(module, "MiuiPhoneStatusBarView missing");
            return 0;
        }
        return hook(module, Refl.method(bar, "updateCutoutLocation"),
                "hyperduo-cutout", new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        final Object result = chain.proceed();
                        foldSlots(sStatusIconContainer);
                        return result;
                    }
                });
    }

    /**
     * Learns the current Wi-Fi / mobile signal levels. Both binders call
     * {@code imageView.setTag(rawResId)} immediately before passing that same raw
     * id to {@code transformResId}, so the first argument is the signal icon being
     * applied right now.
     */
    private static int hookSignalIcons(XposedModule module, ClassLoader cl) {
        final Class<?> helper = Refl.cls(
                "com.android.systemui.statusbar.MiuiStatusBarIconViewHelper", cl);
        if (helper == null) {
            log(module, "MiuiStatusBarIconViewHelper missing");
            return 0;
        }
        return hook(module, Refl.method(helper, "transformResId",
                        int.class, boolean.class, boolean.class),
                "hyperduo-signal", new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        final Object raw = chain.getArg(0);
                        final int beforeWifi = TrioState.sWifiLevel;
                        final int beforeMobile = TrioState.sMobileLevel;
                        final Object result = chain.proceed();
                        if (raw instanceof Number) {
                            TrioState.noteSignalIcon(
                                    resources(), ((Number) raw).intValue());
                            if (beforeWifi != TrioState.sWifiLevel
                                    || beforeMobile != TrioState.sMobileLevel) {
                                invalidateHosts();
                            }
                        }
                        return result;
                    }
                });
    }

    /**
     * Samples the mobile network type label ("5G", "5GA", "4G") that MIUI is
     * about to draw.
     *
     * <p>{@code MobileTypeDrawable.mMobileType} is owned by the
     * {@code R.id.mobile_type} image view, which has no stable global entry
     * point, so the string is sampled where it is used instead:
     * {@code measure()} runs in lockstep with MIUI's own refresh and is called
     * only when the label actually changed
     * ({@code MiuiMobileIconBinder} L1366-1373).
     *
     * <p>The read happens after {@code chain.proceed()} because {@code measure()}
     * is what normalises the label: it rewrites {@code "5G++"} into {@code "5G"}
     * plus a separate double-plus flag, so reading beforehand would surface a
     * string MIUI never paints.
     */
    private static int hookMobileType(XposedModule module, ClassLoader cl) {
        final Class<?> drawable = Refl.cls(
                "com.miui.systemui.statusbar.views.MobileTypeDrawable", cl);
        if (drawable == null) {
            log(module, "MobileTypeDrawable missing");
            return 0;
        }
        final Field type = Refl.field(drawable, "mMobileType");
        if (type == null) {
            log(module, "MobileTypeDrawable.mMobileType missing");
            return 0;
        }
        return hook(module, Refl.method(drawable, "measure"),
                "hyperduo-mobile-type", new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        final String before = TrioState.sMobileType;
                        final Object result = chain.proceed();
                        final Object raw = Refl.get(type, chain.getThisObject());
                        if (raw instanceof String
                                && TrioState.setMobileType((String) raw)
                                && !before.equals(TrioState.sMobileType)) {
                            invalidateHosts();
                        }
                        return result;
                    }
                });
    }

    // ------------------------------------------------------------- registrations

    /**
     * Hides the native Wi-Fi / mobile views inside every icon container that
     * renders the trio glyph.
     *
     * <p>{@code ignoredSlots} alone only removes a child from measurement; the
     * layout pass still parks it at the container's left edge, which - in the
     * status bar - is the middle of the screen, so the orphaned "5G" label stays
     * visible next to the clock. Forcing the child {@code GONE} removes it from
     * the pass (and from drawing) entirely. Re-applying on every layout keeps it
     * gone when MIUI re-shows it.
     *
     * <p>Hiding is limited to containers that own a live host: a container whose
     * battery view is hidden (island, minimalism, control-center collapse) never
     * draws the glyph, and stripping its signal icons would leave it empty.
     */
    private static int hookIconContainerLayout(XposedModule module, ClassLoader cl) {
        final Class<?> container = Refl.cls(
                "com.android.systemui.statusbar.views.MiuiStatusIconContainer", cl);
        if (container == null) {
            log(module, "MiuiStatusIconContainer missing");
            return 0;
        }
        sIconContainerClass = container;
        sIgnoredSlotsField = Refl.field(container, "ignoredSlots");
        return hook(module, Refl.method(container, "onLayout",
                        boolean.class, int.class, int.class, int.class, int.class),
                "hyperduo-icon-layout", new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        final Object result = chain.proceed();
                        final Object self = chain.getThisObject();
                        if (self instanceof ViewGroup) {
                            settle((ViewGroup) self);
                        }
                        return result;
                    }
                });
    }

    /**
     * Brings one icon container into its steady state: our slots suppressed, the
     * folded native children collapsed out of the layout. Runs inside
     * {@code onLayout}, after the container has finished positioning children.
     */
    private static void settle(ViewGroup container) {
        if (!TrioConfig.get().enabled) {
            return;
        }
        ensureFolded(container);
        sampleSignalPresence(container);
        final boolean owned = isOwned(container);
        diagnose(container, owned);
        if (!owned) {
            return;
        }
        final int count = container.getChildCount();
        for (int i = 0; i < count; i++) {
            final View child;
            try {
                child = container.getChildAt(i);
            } catch (Throwable t) {
                continue;
            }
            if (child == null || !FOLDED_SLOTS.contains(slotOf(child))) {
                continue;
            }
            // Collapse the child out of the layout. MIUI's own onLayout lays every
            // child out at container-local x=0 in its first pass and its later
            // passes only move the ones it does *not* ignore, so an ignored
            // "mobile" stays stranded at the container's left edge - which for the
            // status bar is the middle of the screen. layout() does not schedule
            // another pass, so this cannot loop.
            try {
                if (child.getWidth() != 0 || child.getHeight() != 0) {
                    child.layout(0, 0, 0, 0);
                }
            } catch (Throwable ignored) {
                // never let one child abort the pass
            }
            // Also take it out of drawing, but only once: if MIUI re-shows the
            // child on a later pass, flipping visibility back and forth would
            // schedule a new layout every frame. The zero-size layout above has
            // already made it invisible, so a single GONE is enough.
            if (child.getVisibility() != View.GONE && markCollapsed(child)) {
                try {
                    child.setVisibility(View.GONE);
                } catch (Throwable ignored) {
                    // never let one child abort the pass
                }
            }
        }
    }

    /**
     * Records {@code child} as already hidden. Returns false when it was recorded
     * before, so the caller skips a repeat {@code setVisibility} - the redundant
     * call is a no-op for the framework but would re-enter layout if MIUI keeps
     * restoring the child.
     */
    private static boolean markCollapsed(View child) {
        synchronized (COLLAPSED) {
            return COLLAPSED.put(child, Boolean.TRUE) == null;
        }
    }

    /**
     * Samples which signal indicators the status bar is currently showing, from
     * the live view tree.
     *
     * <p>This is the only reliable source for the Wi-Fi indicator going away.
     * MIUI stops calling {@code MiuiStatusBarIconViewHelper.transformResId} as
     * soon as an indicator is not visible, so the level learned there goes stale:
     * switching Wi-Fi off left the last level behind and the arcs stayed on
     * screen forever.
     *
     * <p>Note what "present" means here. When Wi-Fi is switched off MIUI does
     * <em>not</em> remove the {@code slot=wifi} child — it keeps the view and
     * merely stops binding it, so the child count stays at one. The live answer
     * is {@code ModernStatusBarView.isIconVisible()}, which reads the binding.
     * Counting children instead would never see the indicator leave.
     *
     * <p>Only the Wi-Fi indicator is sampled. {@code isIconVisible()} stays true
     * on a mobile view that has already been faded out, so it cannot tell mobile
     * off from mobile on; the level dots keep being driven by the last signal
     * resource seen instead.
     *
     * <p>Only the authoritative status-bar container is sampled. The control
     * centre, the QS headers and the keyguard each inflate their own container
     * from the same layout, and those must never drive the shared state.
     */
    private static void sampleSignalPresence(ViewGroup container) {
        if (container == null || !isStatusBarContainer(container)) {
            return;
        }
        boolean wifiVisible = false;
        int count;
        try {
            count = container.getChildCount();
            for (int i = 0; i < count; i++) {
                final View child = container.getChildAt(i);
                if (child == null || !"wifi".equals(slotOf(child))) {
                    continue;
                }
                // Presence means "MIUI is actually showing it". A turned-off
                // indicator is not removed from the container: MIUI keeps the
                // view and just stops binding it, so counting children alone
                // left the arcs on screen forever. isIconVisible() is the
                // binding-driven answer and is independent of the View.GONE this
                // module applies to the folded slots.
                final Object visible = Refl.callByName(child, "isIconVisible");
                // Fall back to mere presence when the method is not there, so a
                // firmware that renames it does not lose the arcs altogether.
                if (!(visible instanceof Boolean) || ((Boolean) visible).booleanValue()) {
                    wifiVisible = true;
                    break;
                }
            }
        } catch (Throwable ignored) {
            // keep the previous state
        }
        if (TrioState.setWifiPresent(wifiVisible)) {
            invalidateHosts();
        }
    }

    /**
     * True for the status bar's own icon container, as opposed to the ones the
     * control centre, its fake header and the QS headers inflate from the same
     * {@code system_icons.xml}.
     *
     * <p>Matched either against the container captured from
     * {@code MiuiPhoneStatusBarView.mStatusBarStatusIcons}, or - in case that
     * capture has not run yet - by looking for the {@code MiuiPhoneStatusBarView}
     * ancestor that only the status bar (and the keyguard sharing it) has.
     */
    private static boolean isStatusBarContainer(View container) {
        if (container == sStatusIconContainer) {
            return true;
        }
        for (ViewParent p = container.getParent(); p != null;
             p = (p instanceof View) ? ((View) p).getParent() : null) {
            if (p.getClass().getSimpleName().equals("MiuiPhoneStatusBarView")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Makes sure both folded slots are registered in {@code ignoredSlots}.
     *
     * <p>Called only when something is actually missing: {@code addIgnoredSlots}
     * ends in an unconditional {@code requestLayout()}, so calling it on every
     * layout pass would schedule a new layout forever.
     */
    private static void ensureFolded(Object container) {
        final Field f = sIgnoredSlotsField;
        if (f == null || container == null) {
            return;
        }
        final Object value = Refl.get(f, container);
        if (!(value instanceof List)) {
            return;
        }
        final List<?> slots = (List<?>) value;
        boolean missing = false;
        for (int i = 0; i < FOLDED_SLOTS.size(); i++) {
            if (!slots.contains(FOLDED_SLOTS.get(i))) {
                missing = true;
                break;
            }
        }
        if (missing) {
            foldSlots(container);
        }
    }

    /**
     * Reads {@code ModernStatusBarView.getSlot()} (or the displayable one).
     */
    private static String slotOf(View child) {
        final Object slot = Refl.callByName(child, "getSlot");
        return (slot instanceof String) ? (String) slot : null;
    }

    /**
     * True when {@code container} belongs to a {@code MiuiStatusBatteryContainer}
     * that also holds a battery host currently drawing the trio glyph.
     *
     * <p>The icon container is a <em>sibling</em> of the battery view inside
     * {@code MiuiStatusBatteryContainer} (see {@code system_icons.xml}), so
     * walking up from the host never reaches it; the two are bound by the
     * battery container's own {@code mStatusIcon} field plus the registered
     * drawing hosts instead.
     *
     * <p>Every look-up walks the live view tree, so a container that exists
     * before any host has drawn simply stays native until the next layout pass:
     * {@code onDraw} and {@code onLayout} legitimately race on the first frame.
     */
    private static boolean isOwned(Object container) {
        synchronized (OWNED) {
            if (OWNED.containsKey(container)) {
                return true;
            }
        }
        if (!(container instanceof View)) {
            return false;
        }
        final View owner = batteryContainerOf((View) container);
        if (owner == null) {
            return false;
        }
        // Guard against a mis-walk: the container must really be this parent's.
        final Object declared = Refl.get(sStatusIconField, owner);
        if (declared != null && declared != container) {
            return false;
        }
        if (!holdsLiveHost(owner)) {
            return false;
        }
        own(container);
        return true;
    }

    /** True when some registered glyph host lives under {@code container}. */
    private static boolean holdsLiveHost(View container) {
        synchronized (HOSTS) {
            for (int i = 0; i < HOSTS.size(); i++) {
                if (isDescendant(HOSTS.get(i).host, container)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isDescendant(View child, View ancestor) {
        for (ViewParent p = child.getParent(); p != null;
             p = (p instanceof View) ? ((View) p).getParent() : null) {
            if (p == ancestor) {
                return true;
            }
        }
        return false;
    }

    /**
     * The {@code MiuiStatusBatteryContainer} enclosing {@code v}, or null.
     *
     * <p>Matched by class name rather than by {@code Class.forName} on the host's
     * class loader: the host is inflated through a Compose/Factory wrapper whose
     * loader chain does not expose SystemUI classes, which made every earlier
     * look-up fail ({@code owned=false} for all four containers on device).
     * The concrete class of the parent view is always available directly.
     */
    private static View batteryContainerOf(View v) {
        Class<?> known = sBatteryContainerClass;
        for (ViewParent p = v.getParent(); p != null;
             p = (p instanceof View) ? ((View) p).getParent() : null) {
            if (!(p instanceof View)) {
                return null;
            }
            final View parent = (View) p;
            final Class<?> cls = parent.getClass();
            final boolean match = (known != null)
                    ? known.isInstance(parent)
                    : BATTERY_CONTAINER_CLASS.equals(cls.getName());
            if (match) {
                if (known == null) {
                    sBatteryContainerClass = cls;
                    sStatusIconField = Refl.field(cls, "mStatusIcon");
                }
                return parent;
            }
        }
        return null;
    }

    private static void own(Object container) {
        synchronized (OWNED) {
            OWNED.put(container, Boolean.TRUE);
        }
    }

    /**
     * Logs a container's geometry and children once, so the on-device report
     * pins down which container a stray icon belongs to.
     */
    private static void diagnose(ViewGroup container, boolean owned) {
        if (!debugLog()) {
            return;
        }
        final int[] location = new int[2];
        try {
            container.getLocationOnScreen(location);
        } catch (Throwable ignored) {
            // keep the relative values
        }
        final String children = childrenOf(container);
        final boolean log;
        synchronized (DIAGNOSED) {
            final boolean first = !DIAGNOSED.containsKey(container);
            // Record every container seen, not just the first dozen: the map is
            // weak, so this bounds nothing but the log. Without the record a
            // container past the log cap would look "first" on every pass.
            if (first) {
                DIAGNOSED.put(container, Boolean.TRUE);
            }
            final Boolean previous = DIAG_OWNED.get(container);
            // Log the first sighting and any later flip of `owned`. The first
            // layout normally runs before any glyph host has drawn, so that
            // first header legitimately reads owned=false; a one-shot header
            // would keep reporting that stale value for the whole session.
            final boolean changed = (previous != null) && previous.booleanValue() != owned;
            log = (first || changed) && sDiagHeaders < 48;
            if (log) {
                DIAG_OWNED.put(container, Boolean.valueOf(owned));
                sDiagHeaders++;
            }
        }
        if (log) {
            // The first layout usually runs before MIUI populates the container,
            // so the header alone is not enough: the children are dumped below by
            // signature as soon as they exist.
            final XposedModule module = sModule;
            if (module != null) {
                log(module, "container " + container.getClass().getSimpleName()
                        + " owned=" + owned
                        + " screen=" + location[0] + "," + location[1]
                        + " size=" + container.getWidth() + "x" + container.getHeight()
                        + " measured=" + container.getMeasuredWidth()
                        + " left=" + container.getLeft()
                        + " padStart=" + container.getPaddingStart()
                        + " padEnd=" + container.getPaddingEnd()
                        + " parent=" + (container.getParent() == null ? "null"
                        : container.getParent().getClass().getSimpleName())
                        + " path=" + pathOf(container));
            }
        }
        // Children are dumped until one dump shows the container populated; the
        // signature check keeps a repeated layout from logging the same content.
        if (children.length() == 0 || sDiagDumps >= 24) {
            return;
        }
        synchronized (DIAG_SIG) {
            if (children.equals(DIAG_SIG.get(container))) {
                return;
            }
            DIAG_SIG.put(container, children);
            sDiagDumps++;
        }
        final XposedModule module = sModule;
        if (module != null) {
            log(module, "children of " + container.getClass().getSimpleName()
                    + " at " + location[0] + "," + location[1] + ":" + children);
        }
    }

    /** One compact token per child, used both for logging and for change detection. */
    private static String childrenOf(ViewGroup container) {
        final StringBuilder sb = new StringBuilder();
        final int count;
        try {
            count = container.getChildCount();
        } catch (Throwable t) {
            return sb.toString();
        }
        for (int i = 0; i < count; i++) {
            final View child;
            try {
                child = container.getChildAt(i);
            } catch (Throwable t) {
                continue;
            }
            if (child == null) {
                continue;
            }
            sb.append(" | ").append(i)
                    .append(' ').append(child.getClass().getSimpleName())
                    .append(" slot=").append(slotOf(child))
                    .append(" l=").append(child.getLeft())
                    .append(" r=").append(child.getRight())
                    .append(" w=").append(child.getWidth())
                    .append(" mw=").append(child.getMeasuredWidth())
                    .append(" tx=").append(child.getTranslationX())
                    .append(" a=").append(child.getAlpha())
                    .append(" v=").append(child.getVisibility());
        }
        return sb.toString();
    }

    private static String pathOf(View v) {
        final StringBuilder sb = new StringBuilder();
        for (ViewParent p = v.getParent(); p != null;
             p = (p instanceof View) ? ((View) p).getParent() : null) {
            sb.append(p.getClass().getSimpleName()).append('<');
        }
        return sb.toString();
    }

    private static TrioState registerHost(View host) {
        synchronized (HOSTS) {
            for (int i = 0; i < HOSTS.size(); i++) {
                if (HOSTS.get(i).host == host) {
                    return HOSTS.get(i);
                }
            }
            final TrioState s = new TrioState(host);
            HOSTS.add(s);
            // Each host hides the native signal icons of the container it lives in
            // (status bar, keyguard, control center, QS headers each have their own).
            // registerHost runs from onDraw, and addIgnoredSlots ends in
            // requestLayout(), so defer it out of the draw pass.
            host.post(new Runnable() {
                @Override
                public void run() {
                    foldHostContainer(host);
                }
            });
            return s;
        }
    }

    private static TrioState stateFor(View host) {
        synchronized (HOSTS) {
            for (int i = 0; i < HOSTS.size(); i++) {
                final TrioState s = HOSTS.get(i);
                if (s.host == host) {
                    return s;
                }
            }
        }
        return null;
    }

    private static void unregisterHost(View host) {
        synchronized (HOSTS) {
            for (int i = HOSTS.size() - 1; i >= 0; i--) {
                if (HOSTS.get(i).host == host) {
                    HOSTS.remove(i);
                }
            }
        }
    }

    /** Repaints every trio host. Signal updates arrive off the UI thread. */
    private static void invalidateHosts() {
        final List<TrioState> copy;
        synchronized (HOSTS) {
            if (HOSTS.isEmpty()) {
                return;
            }
            copy = new ArrayList<TrioState>(HOSTS);
        }
        for (int i = 0; i < copy.size(); i++) {
            final View v = copy.get(i).host;
            v.post(new Runnable() {
                @Override
                public void run() {
                    v.invalidate();
                }
            });
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Appends the folded slots to a container's ignored-slot list. */
    private static void foldSlots(Object container) {
        if (container == null || !TrioConfig.get().enabled) {
            return;
        }
        Refl.callArgs(container, "addIgnoredSlots",
                new Class<?>[]{List.class},
                new Object[]{new ArrayList<String>(FOLDED_SLOTS)});
    }

    /** Removes the folded slots again, so MIUI measures and lays them out. */
    private static void unfoldSlots(Object container) {
        if (container == null) {
            return;
        }
        final Field f = sIgnoredSlotsField;
        if (f == null) {
            return;
        }
        final Object value = Refl.get(f, container);
        if (!(value instanceof List)) {
            return;
        }
        try {
            ((List<?>) value).removeAll(FOLDED_SLOTS);
        } catch (Throwable ignored) {
            // an immutable list is MIUI's problem, not ours
        }
        Refl.callArgs(container, "requestLayout", new Class<?>[0], new Object[0]);
    }

    /**
     * Suppresses the native Wi-Fi / mobile icons in the
     * {@code MiuiStatusBatteryContainer} that owns {@code host}.
     *
     * <p>There are seven containers (status bar, keyguard, control center, both
     * QS headers, ...) and each one that renders the trio glyph must hide its own
     * native signal icons. Walking up from the drawing host is the only way to
     * bind the two together without a hard-coded per-host lookup.
     */
    private static void foldHostContainer(View host) {
        final Object container = batteryContainerOf(host);
        if (container == null) {
            return;
        }
        foldSlots(Refl.get(sStatusIconField, container));
    }

    private static void hide(Object view) {
        if (view instanceof View) {
            ((View) view).setVisibility(View.GONE);
        }
    }

    /** Resources handle for resolving signal icon entry names. */
    private static android.content.res.Resources resources() {
        final Object container = sStatusIconContainer;
        if (container instanceof View) {
            try {
                return ((View) container).getResources();
            } catch (Throwable ignored) {
                // fall through
            }
        }
        synchronized (HOSTS) {
            if (!HOSTS.isEmpty()) {
                try {
                    return HOSTS.get(0).host.getResources();
                } catch (Throwable ignored) {
                    // fall through
                }
            }
        }
        return null;
    }

    private static void log(XposedModule module, String msg) {
        try {
            module.log(LOG_INFO, TAG, msg);
        } catch (Throwable ignored) {
            // logging must never break hook installation
        }
    }

    /**
     * Logs through the handle captured at install time. Used by callers that run
     * outside the hook callbacks (the settings channel) and so cannot pass the
     * module instance along.
     */
    static void log(int priority, String msg) {
        final XposedModule module = sModule;
        if (module == null) {
            return;
        }
        try {
            module.log(priority, TAG, msg);
        } catch (Throwable ignored) {
            // logging must never break a config change
        }
    }
}
