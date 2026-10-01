package com.hyperduo.trio;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * HyperDuo — folds the Wi-Fi signal and the mobile signal into MIUI's battery
 * icon, drawing one trio glyph (battery ring + Wi-Fi arcs + signal dots) in the
 * 28dp x 20dp slot the battery icon already occupies.
 *
 * <p>Entry point declared in {@code META-INF/xposed/java_init.list}; scope in
 * {@code META-INF/xposed/scope.list}.
 */
public final class HyperDuoModule extends XposedModule {

    private static final String TAG = "HyperDuo";
    private static final int LOG_INFO = 4;
    private static final int LOG_WARN = 5;

    private static final String TARGET_PACKAGE = "com.android.systemui";

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        final String pkg = param.getPackageName();
        if (!TARGET_PACKAGE.equals(pkg)) {
            return;
        }
        try {
            TrioHooks.install(this, param.getClassLoader());
        } catch (Throwable t) {
            log(LOG_WARN, "install failed: " + t);
        }
    }

    private void log(int priority, String msg) {
        try {
            log(priority, TAG, msg);
        } catch (Throwable ignored) {
            // never let logging break the module
        }
    }
}
