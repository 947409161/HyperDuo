package io.github.yixing233.hyperduo;

import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;

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

/** Flyme AIOS 2.0 SystemUI adapter, kept separate from the MIUI implementation. */
final class FlymeHooks {

    private static final String TAG = "HyperDuo";
    private static final String BATTERY_VIEW =
            "com.flyme.statusbar.battery.FlymeBatteryMeterView";
    private static final String PHONE_STATUS_BAR_VIEW =
            "com.android.systemui.statusbar.phone.PhoneStatusBarView";
    private static final String ICON_CONTAINER =
            "com.android.systemui.statusbar.phone.StatusIconContainer";
    private static final String WIFI_VIEW =
            "com.flyme.systemui.statusbar.net.wifi.FlymeStatusBarWifiView";
    private static final String WIFI_STATE =
            "com.flyme.systemui.statusbar.net.wifi.WifiIconState";
    private static final String MOBILE_SIGNAL_DRAWABLE =
            "com.android.settingslib.graph.SignalDrawable";
    private static final List<String> MANAGED_SLOTS = Collections.unmodifiableList(
            Arrays.asList("wifi", "mobile", "stacked_mobile"));

    private static final Map<View, TrioState> STATES =
            Collections.synchronizedMap(new WeakHashMap<View, TrioState>());
    /** Original visibility for children changed by this adapter, keyed weakly. */
    private static final Map<View, Integer> SAVED_VISIBILITY =
            Collections.synchronizedMap(new WeakHashMap<View, Integer>());
    /** The exact slot names this adapter inserted into StatusIconContainer. */
    private static final Map<Object, List<String>> ADDED_SLOTS =
            Collections.synchronizedMap(new WeakHashMap<Object, List<String>>());

    private static volatile boolean sActive;
    private static volatile XposedModule sModule;
    private static volatile ViewGroup sStatusIconContainer;
    private static volatile Field sSystemIconAreaField;
    private static volatile Field sIgnoredSlotsField;
    private static volatile Field sWifiViewStateField;
    private static volatile Field sWifiStateResIdField;
    private static volatile Field sBatteryPercentField;

    private FlymeHooks() {
    }

    static boolean isFlyme(ClassLoader cl) {
        return Refl.cls(BATTERY_VIEW, cl) != null
                && Refl.cls(WIFI_VIEW, cl) != null;
    }

    static boolean isActive() {
        return sActive;
    }

    static int install(XposedModule module, ClassLoader cl) {
        sModule = module;
        sActive = true;
        int count = 0;
        count += hookBatteryView(module, cl);
        count += hookStatusBarCapture(module, cl);
        count += hookIconContainer(module, cl);
        count += hookWifiState(module, cl);
        count += hookMobileSignal(module, cl);
        return count;
    }

    static void onConfigChanged() {
        final ViewGroup container = sStatusIconContainer;
        if (container != null) {
            container.post(new Runnable() {
                @Override
                public void run() {
                    applySlots(container);
                }
            });
        }
        final List<View> hosts = new ArrayList<View>();
        synchronized (STATES) {
            hosts.addAll(STATES.keySet());
        }
        for (int i = 0; i < hosts.size(); i++) {
            final View host = hosts.get(i);
            if (host != null) {
                host.post(new Runnable() {
                    @Override
                    public void run() {
                        applyBatteryPercent(host);
                        host.invalidate();
                    }
                });
            }
        }
    }

    private static int hookBatteryView(XposedModule module, ClassLoader cl) {
        final Class<?> viewClass = Refl.cls(BATTERY_VIEW, cl);
        if (viewClass == null) {
            log(module, "FlymeBatteryMeterView missing");
            return 0;
        }
        sBatteryPercentField = Refl.field(viewClass, "mBatteryPercentView");
        final int draw = hook(module, Refl.method(viewClass, "onDraw", Canvas.class),
                "hyperduo-flyme-draw", new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        final Object result = chain.proceed();
                        final Object self = chain.getThisObject();
                        final Object arg = chain.getArg(0);
                        if (!(self instanceof View) || !(arg instanceof Canvas)) {
                            return result;
                        }
                        final View host = (View) self;
                        TrioConfig.installReceiver(host.getContext());
                        TrioState.attachContext(host.getContext());
                        applyBatteryPercent(host);
                        if (!TrioConfig.get().enabled) {
                            return result;
                        }
                        final TrioState state = stateFor(host);
                        state.refresh();
                        TrioRenderer.draw((Canvas) arg, host, state);
                        return result;
                    }
                });
        final int dark = hook(module, Refl.method(viewClass, "onDarkChanged",
                        ArrayList.class, float.class, int.class),
                "hyperduo-flyme-dark", new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        final Object result = chain.proceed();
                        final Object self = chain.getThisObject();
                        if (self instanceof View) {
                            final TrioState state = stateFor((View) self);
                            final Object intensity = chain.getArg(1);
                            final Object tint = chain.getArg(2);
                            if (intensity instanceof Number) {
                                state.darkIntensity = ((Number) intensity).floatValue();
                            }
                            if (tint instanceof Number) {
                                final int color = ((Number) tint).intValue();
                                state.useTint = color != 0;
                                state.tintColor = color;
                                state.lightColor = color;
                                state.darkColor = color;
                            }
                            ((View) self).invalidate();
                        }
                        return result;
                    }
                });
        final int detached = hook(module,
                Refl.method(viewClass, "onDetachedFromWindow"),
                "hyperduo-flyme-detach", new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        final Object result = chain.proceed();
                        final Object self = chain.getThisObject();
                        if (self instanceof View) {
                            restoreBatteryPercent((View) self);
                            STATES.remove((View) self);
                        }
                        return result;
                    }
                });
        return draw + dark + detached;
    }

    private static int hookStatusBarCapture(XposedModule module, ClassLoader cl) {
        final Class<?> barClass = Refl.cls(PHONE_STATUS_BAR_VIEW, cl);
        if (barClass == null) {
            log(module, "PhoneStatusBarView missing");
            return 0;
        }
        sSystemIconAreaField = Refl.field(barClass, "mSystemIconArea");
        return hook(module, Refl.method(barClass, "onFinishInflate"),
                "hyperduo-flyme-statusbar", new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        final Object result = chain.proceed();
                        final Object self = chain.getThisObject();
                        final Object area = Refl.get(sSystemIconAreaField, self);
                        if (area instanceof View) {
                            final ViewGroup found = findIconContainer((View) area);
                            if (found != null) {
                                sStatusIconContainer = found;
                                applySlots(found);
                                log(sModule, "Flyme status icon container captured: "
                                        + found.getClass().getName());
                            } else {
                                log(sModule, "StatusIconContainer not found under mSystemIconArea");
                            }
                        }
                        if (self instanceof View) {
                            TrioConfig.installReceiver(((View) self).getContext());
                            TrioState.attachContext(((View) self).getContext());
                        }
                        return result;
                    }
                });
    }

    private static int hookIconContainer(XposedModule module, ClassLoader cl) {
        final Class<?> container = Refl.cls(ICON_CONTAINER, cl);
        if (container == null) {
            log(module, "StatusIconContainer missing");
            return 0;
        }
        sIgnoredSlotsField = Refl.field(container, "mIgnoredSlots");
        return hook(module, Refl.method(container, "onLayout",
                        boolean.class, int.class, int.class, int.class, int.class),
                "hyperduo-flyme-icon-layout", new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        final Object result = chain.proceed();
                        final Object self = chain.getThisObject();
                        if (self == sStatusIconContainer && self instanceof ViewGroup) {
                            applySlots((ViewGroup) self);
                            updateWifiPresence((ViewGroup) self);
                        }
                        return result;
                    }
                });
    }

    private static int hookWifiState(XposedModule module, ClassLoader cl) {
        final Class<?> wifiClass = Refl.cls(WIFI_VIEW, cl);
        final Class<?> stateClass = Refl.cls(WIFI_STATE, cl);
        if (wifiClass == null || stateClass == null) {
            log(module, "Flyme Wi-Fi view/state missing");
            return 0;
        }
        sWifiViewStateField = Refl.field(wifiClass, "mState");
        sWifiStateResIdField = Refl.field(stateClass, "resId");
        return hook(module, Refl.method(wifiClass, "updateState", stateClass),
                "hyperduo-flyme-wifi-state", new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        final Object result = chain.proceed();
                        final Object self = chain.getThisObject();
                        if (self instanceof View) {
                            final Object state = Refl.get(sWifiViewStateField, self);
                            final int resId = Refl.getInt(sWifiStateResIdField, state, 0);
                            if (resId != 0) {
                                TrioState.noteSignalIcon(((View) self).getResources(), resId);
                            }
                            final Object visible = Refl.callByName(self, "isIconVisible");
                            TrioState.setWifiPresent(Boolean.TRUE.equals(visible));
                            invalidateHosts();
                        }
                        return result;
                    }
                });
    }

    /** The Flyme mobile binder uses SignalDrawable for the per-SIM signal view. */
    private static int hookMobileSignal(XposedModule module, ClassLoader cl) {
        final Class<?> signal = Refl.cls(MOBILE_SIGNAL_DRAWABLE, cl);
        if (signal == null) {
            log(module, "SignalDrawable missing");
            return 0;
        }
        return hook(module, Refl.method(signal, "onLevelChange", int.class),
                "hyperduo-flyme-mobile-signal", new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        final Object result = chain.proceed();
                        if (TrioState.pollSimsNow()) {
                            invalidateHosts();
                        }
                        return result;
                    }
                });
    }

    private static void applySlots(ViewGroup container) {
        if (container != sStatusIconContainer) {
            return;
        }
        final TrioAppearance appearance = TrioConfig.appearance();
        final boolean enabled = appearance.glyph;
        final List<String> wanted = new ArrayList<String>(2);
        if (enabled && appearance.wifi) {
            wanted.add("wifi");
        }
        // The Flyme adapter has no out-of-ring replacement view or network-type
        // label yet; keep the native mobile view for either of those settings.
        if (enabled && appearance.signalDots() && !appearance.typeOutOfRing) {
            wanted.add("mobile");
            wanted.add("stacked_mobile");
        }

        final List<String> ignored = ignoredSlots(container);
        final List<String> owned;
        synchronized (ADDED_SLOTS) {
            List<String> current = ADDED_SLOTS.get(container);
            if (current == null) {
                current = new ArrayList<String>();
                ADDED_SLOTS.put(container, current);
            }
            owned = current;
        }

        boolean slotsChanged = false;
        for (int i = 0; i < wanted.size(); i++) {
            final String slot = wanted.get(i);
            if (ignored != null && !ignored.contains(slot)) {
                ignored.add(slot);
                if (!owned.contains(slot)) {
                    owned.add(slot);
                }
                slotsChanged = true;
            }
        }
        for (int i = owned.size() - 1; i >= 0; i--) {
            final String slot = owned.get(i);
            if (!wanted.contains(slot)) {
                if (ignored != null) {
                    ignored.remove(slot);
                }
                owned.remove(i);
                slotsChanged = true;
            }
        }

        final int count = container.getChildCount();
        for (int i = 0; i < count; i++) {
            final View child = container.getChildAt(i);
            final String slot = slotOf(child);
            if (slot == null || !MANAGED_SLOTS.contains(slot)) {
                continue;
            }
            if (wanted.contains(slot)) {
                hideChild(child);
            } else {
                restoreChild(child);
            }
        }
        if (slotsChanged) {
            container.requestLayout();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> ignoredSlots(Object container) {
        final Object value = Refl.get(sIgnoredSlotsField, container);
        return (value instanceof List) ? (List<String>) value : null;
    }

    private static void hideChild(View child) {
        if (!SAVED_VISIBILITY.containsKey(child)) {
            SAVED_VISIBILITY.put(child, Integer.valueOf(child.getVisibility()));
        }
        if (child.getVisibility() != View.GONE) {
            child.setVisibility(View.GONE);
        }
    }

    private static void restoreChild(View child) {
        final Integer saved = SAVED_VISIBILITY.remove(child);
        if (saved != null && child.getVisibility() != saved.intValue()) {
            child.setVisibility(saved.intValue());
        }
    }

    private static void applyBatteryPercent(View host) {
        final Object value = Refl.get(sBatteryPercentField, host);
        if (!(value instanceof View)) {
            return;
        }
        final View percent = (View) value;
        if (TrioConfig.get().enabled) {
            hideChild(percent);
        } else {
            restoreChild(percent);
        }
    }

    private static void restoreBatteryPercent(View host) {
        final Object value = Refl.get(sBatteryPercentField, host);
        if (value instanceof View) {
            restoreChild((View) value);
        }
    }

    private static TrioState stateFor(View host) {
        synchronized (STATES) {
            TrioState state = STATES.get(host);
            if (state == null) {
                state = new TrioState(host);
                STATES.put(host, state);
            }
            return state;
        }
    }

    private static String slotOf(View child) {
        if (child == null) {
            return null;
        }
        final Object slot = Refl.callByName(child, "getSlot");
        if (slot instanceof String) {
            return (String) slot;
        }
        return null;
    }

    private static ViewGroup findIconContainer(View root) {
        if (ICON_CONTAINER.equals(root.getClass().getName()) && root instanceof ViewGroup) {
            return (ViewGroup) root;
        }
        if (!(root instanceof ViewGroup)) {
            return null;
        }
        final ViewGroup group = (ViewGroup) root;
        for (int i = 0; i < group.getChildCount(); i++) {
            final View found = findIconContainer(group.getChildAt(i));
            if (found instanceof ViewGroup) {
                return (ViewGroup) found;
            }
        }
        return null;
    }

    private static void updateWifiPresence(ViewGroup container) {
        boolean present = false;
        for (int i = 0; i < container.getChildCount(); i++) {
            final View child = container.getChildAt(i);
            if (!"wifi".equals(slotOf(child))) {
                continue;
            }
            final Object visible = Refl.callByName(child, "isIconVisible");
            present = (visible instanceof Boolean)
                    ? ((Boolean) visible).booleanValue()
                    : child.getVisibility() == View.VISIBLE;
            break;
        }
        if (TrioState.setWifiPresent(present)) {
            invalidateHosts();
        }
    }

    private static void invalidateHosts() {
        final List<View> hosts = new ArrayList<View>();
        synchronized (STATES) {
            hosts.addAll(STATES.keySet());
        }
        for (int i = 0; i < hosts.size(); i++) {
            final View host = hosts.get(i);
            if (host != null) {
                host.postInvalidate();
            }
        }
    }

    private static int hook(XposedModule module, Method method, String id,
                            XposedInterface.Hooker hooker) {
        if (method == null) {
            log(module, "skip " + id + ": method not found");
            return 0;
        }
        try {
            module.hook(method).setId(id).intercept(hooker);
            return 1;
        } catch (Throwable t) {
            log(module, "skip " + id + ": " + t);
            return 0;
        }
    }

    private static void log(XposedModule module, String message) {
        if (module == null) {
            return;
        }
        try {
            module.log(TrioHooks.LOG_INFO, TAG, message);
        } catch (Throwable ignored) {
            // Logging must never interrupt SystemUI.
        }
    }
}
