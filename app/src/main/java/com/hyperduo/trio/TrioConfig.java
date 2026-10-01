package com.hyperduo.trio;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedInterface;

/**
 * Hook-side snapshot of the user's settings.
 *
 * <p>The remote {@link SharedPreferences} handed to a hooked process is
 * read-only, but it is live: the framework pushes {@code put}/{@code delete}
 * bundles over Binder and re-fires the change listeners. A single listener is
 * registered once and every change simply re-reads the handful of values into
 * this object, then pokes the rendered hosts so the next frame uses the new
 * values. The listener is invoked on a Binder thread; {@code invalidateHosts}
 * always posts to the main looper, so that is safe.
 *
 * <p>Every getter falls back to the {@link Prefs} default, which keeps the
 * module working when the framework is embedded and cannot serve remote
 * preferences at all.
 */
public final class TrioConfig {

    /** Notified (on whatever thread the framework used) after a value changed. */
    public interface Listener {
        void onConfigChanged();
    }

    private static final Object LOCK = new Object();

    /**
     * Registered listeners, held strongly and on purpose.
     *
     * <p>A weak list is tempting but silently wrong here: the only listeners are
     * anonymous inner classes created inside {@code TrioHooks.install}, so
     * nothing else keeps them reachable. They were collected on the first GC
     * after install, after which a settings change updated the snapshot but
     * repainted nothing - the module appeared unable to update the status bar
     * live. The module lives in the hooked process for its whole lifetime, so a
     * strong reference cannot outlive its usefulness.
     */
    private static final List<Listener> LISTENERS = new ArrayList<>();

    private static volatile SharedPreferences sPrefs;
    private static volatile boolean sDebugLog = Prefs.DEF_DEBUG_LOG;

    // Values are published together, so a reader never sees a mixed snapshot.
    private static volatile TrioSettings sSnapshot = TrioSettings.defaults();

    private TrioConfig() {
    }

    /**
     * Binds to the framework's remote preferences. Safe to call repeatedly; only
     * the first successful call registers a listener.
     */
    static void install(XposedInterface xposed) {
        if (sPrefs != null) {
            return;
        }
        SharedPreferences prefs;
        try {
            prefs = xposed.getRemotePreferences(Prefs.NAME);
        } catch (Throwable t) {
            // Embedded framework (or a very old one): run on defaults.
            TrioHooks.log(TrioHooks.LOG_WARN, "remote preferences unavailable: " + t);
            return;
        }
        if (prefs == null) {
            return;
        }
        sPrefs = prefs;
        reload();
        try {
            prefs.registerOnSharedPreferenceChangeListener(LISTENER);
        } catch (Throwable t) {
            TrioHooks.log(TrioHooks.LOG_WARN, "cannot watch preferences: " + t);
        }
    }

    /** Immutable view of every setting the renderer cares about. */
    static TrioSettings get() {
        return sSnapshot;
    }

    static boolean debugLog() {
        return sDebugLog;
    }

    static void addListener(Listener listener) {
        if (listener == null) {
            return;
        }
        synchronized (LOCK) {
            LISTENERS.add(listener);
        }
    }

    /**
     * The framework fires once per changed key; each fires its own
     * onSharedPreferenceChanged, so a debounce would only add latency. Re-read
     * the small snapshot directly instead.
     */
    private static final SharedPreferences.OnSharedPreferenceChangeListener LISTENER =
            new SharedPreferences.OnSharedPreferenceChangeListener() {
                @Override
                public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
                    reload();
                    if (sDebugLog) {
                        TrioHooks.log(TrioHooks.LOG_INFO,
                                "remote pref changed: " + key
                                        + " -> enabled=" + sSnapshot.enabled
                                        + " ring=" + sSnapshot.ringStroke
                                        + " arc=" + sSnapshot.arcStroke
                                        + " size=" + sSnapshot.valueSize);
                    }
                    notifyChanged();
                }
            };

    private static void reload() {
        SharedPreferences prefs = sPrefs;
        if (prefs == null) {
            return;
        }
        try {
            sSnapshot = TrioSettings.from(prefs);
            sDebugLog = prefs.getBoolean(Prefs.KEY_DEBUG_LOG, Prefs.DEF_DEBUG_LOG);
        } catch (Throwable t) {
            // A type mismatch in the framework's cast must never break drawing.
            TrioHooks.log(TrioHooks.LOG_WARN, "cannot read preferences: " + t);
        }
    }

    private static void notifyChanged() {
        List<Listener> alive;
        synchronized (LOCK) {
            alive = new ArrayList<>(LISTENERS);
        }
        for (Listener l : alive) {
            try {
                l.onConfigChanged();
            } catch (Throwable t) {
                // One bad listener must not stop the others.
                TrioHooks.log(TrioHooks.LOG_WARN, "config listener failed: " + t);
            }
        }
    }

}
