package com.hyperduo.trio.ui

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.hyperduo.trio.HyperDuoApp
import com.hyperduo.trio.Prefs
import com.hyperduo.trio.TrioSettings
import io.github.libxposed.service.XposedService

/**
 * Reads and writes every setting, and mirrors each write to the Xposed
 * framework so the hooked SystemUI process sees it immediately.
 *
 * <p>Two stores are involved and only one of them is authoritative for the
 * hooked process:
 *
 * <ul>
 *   <li>the app's own {@link SharedPreferences} file, which drives this UI and
 *       survives while the framework is absent;
 *   <li>the framework's remote-preference group of the same name, which is what
 *       the hooked process actually reads.
 * </ul>
 *
 * <p>The mirror is therefore not "sync on connect and forget": every single
 * write is pushed, otherwise a value the user changes while the service is
 * bound would never reach SystemUI.
 */
class SettingsRepository(context: Context) {

    private val appContext = context.applicationContext
    private val service: XposedService? get() = HyperDuoApp.xposedService

    private fun preferences() =
        appContext.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)

    /** The current values, as the preview should render them. */
    fun read(): TrioSettings = TrioSettings.from(preferences())

    // ------------------------------------------------------------- appearance

    fun setEnabled(value: Boolean) = writeBoolean(Prefs.KEY_ENABLED, value)
    fun setShowWifi(value: Boolean) = writeBoolean(Prefs.KEY_SHOW_WIFI, value)
    fun setShowMobile(value: Boolean) = writeBoolean(Prefs.KEY_SHOW_MOBILE, value)
    fun setShowValue(value: Boolean) = writeBoolean(Prefs.KEY_SHOW_VALUE, value)
    fun setShowBolt(value: Boolean) = writeBoolean(Prefs.KEY_SHOW_BOLT, value)
    fun setShowMobileType(value: Boolean) = writeBoolean(Prefs.KEY_SHOW_MOBILE_TYPE, value)
    fun setSwapWifiValue(value: Boolean) = writeBoolean(Prefs.KEY_SWAP_WIFI_VALUE, value)

    // ----------------------------------------------------------------- colours

    fun setRoleColors(value: Boolean) = writeBoolean(Prefs.KEY_ROLE_COLORS, value)
    fun setColorCriticalOnDark(value: Int) = writeInt(Prefs.KEY_COLOR_CRITICAL_ON_DARK, value)
    fun setColorCriticalOnLight(value: Int) = writeInt(Prefs.KEY_COLOR_CRITICAL_ON_LIGHT, value)
    fun setColorChargingOnDark(value: Int) = writeInt(Prefs.KEY_COLOR_CHARGING_ON_DARK, value)
    fun setColorChargingOnLight(value: Int) = writeInt(Prefs.KEY_COLOR_CHARGING_ON_LIGHT, value)
    fun setColorLowOnDark(value: Int) = writeInt(Prefs.KEY_COLOR_LOW_ON_DARK, value)
    fun setColorLowOnLight(value: Int) = writeInt(Prefs.KEY_COLOR_LOW_ON_LIGHT, value)

    /** Restores all six role colours to their defaults in one write. */
    fun resetRoleColors() {
        preferences().edit(commit = true) {
            putInt(Prefs.KEY_COLOR_CRITICAL_ON_DARK, Prefs.DEF_COLOR_CRITICAL_ON_DARK)
            putInt(Prefs.KEY_COLOR_CRITICAL_ON_LIGHT, Prefs.DEF_COLOR_CRITICAL_ON_LIGHT)
            putInt(Prefs.KEY_COLOR_CHARGING_ON_DARK, Prefs.DEF_COLOR_CHARGING_ON_DARK)
            putInt(Prefs.KEY_COLOR_CHARGING_ON_LIGHT, Prefs.DEF_COLOR_CHARGING_ON_LIGHT)
            putInt(Prefs.KEY_COLOR_LOW_ON_DARK, Prefs.DEF_COLOR_LOW_ON_DARK)
            putInt(Prefs.KEY_COLOR_LOW_ON_LIGHT, Prefs.DEF_COLOR_LOW_ON_LIGHT)
        }
        push { prefs ->
            prefs.putInt(Prefs.KEY_COLOR_CRITICAL_ON_DARK, Prefs.DEF_COLOR_CRITICAL_ON_DARK)
            prefs.putInt(Prefs.KEY_COLOR_CRITICAL_ON_LIGHT, Prefs.DEF_COLOR_CRITICAL_ON_LIGHT)
            prefs.putInt(Prefs.KEY_COLOR_CHARGING_ON_DARK, Prefs.DEF_COLOR_CHARGING_ON_DARK)
            prefs.putInt(Prefs.KEY_COLOR_CHARGING_ON_LIGHT, Prefs.DEF_COLOR_CHARGING_ON_LIGHT)
            prefs.putInt(Prefs.KEY_COLOR_LOW_ON_DARK, Prefs.DEF_COLOR_LOW_ON_DARK)
            prefs.putInt(Prefs.KEY_COLOR_LOW_ON_LIGHT, Prefs.DEF_COLOR_LOW_ON_LIGHT)
        }
    }

    // ------------------------------------------------------------------ sizing

    fun setLowThreshold(value: Int) = writeInt(Prefs.KEY_LOW_THRESHOLD, value)
    fun setRingStroke(value: Int) = writeInt(Prefs.KEY_RING_STROKE, value)
    fun setArcStroke(value: Int) = writeInt(Prefs.KEY_ARC_STROKE, value)
    fun setValueSize(value: Int) = writeInt(Prefs.KEY_VALUE_SIZE, value)
    fun setValueWeight(value: Int) = writeInt(Prefs.KEY_VALUE_WEIGHT, value)
    fun setTypeSize(value: Int) = writeInt(Prefs.KEY_TYPE_SIZE, value)
    fun setTypeWeight(value: Int) = writeInt(Prefs.KEY_TYPE_WEIGHT, value)
    fun setTrackAlpha(value: Int) = writeInt(Prefs.KEY_TRACK_ALPHA, value)

    // ---------------------------------------------------------------- advanced

    fun setDebugLog(value: Boolean) = writeBoolean(Prefs.KEY_DEBUG_LOG, value)

    // ----------------------------------------------------------- remote mirror

    /**
     * Pushes every value currently in the app's own file to the framework.
     *
     * <p>Called once when the service binds: while the framework is not
     * connected writes only land in the app's file, so a plain "push on write"
     * would leave SystemUI running with stale values from a previous session.
     */
    fun syncAllToFramework() {
        val current = preferences()
        val snapshot = TrioSettings.from(current)
        push { prefs ->
            prefs.putBoolean(Prefs.KEY_ENABLED, snapshot.enabled)
            prefs.putBoolean(Prefs.KEY_SHOW_WIFI, snapshot.showWifi)
            prefs.putBoolean(Prefs.KEY_SHOW_MOBILE, snapshot.showMobile)
            prefs.putBoolean(Prefs.KEY_SHOW_VALUE, snapshot.showValue)
            prefs.putBoolean(Prefs.KEY_SHOW_BOLT, snapshot.showBolt)
            prefs.putBoolean(Prefs.KEY_SHOW_MOBILE_TYPE, snapshot.showMobileType)
            prefs.putBoolean(Prefs.KEY_SWAP_WIFI_VALUE, snapshot.swapWifiValue)

            prefs.putBoolean(Prefs.KEY_ROLE_COLORS, snapshot.roleColors)
            prefs.putInt(Prefs.KEY_COLOR_CRITICAL_ON_DARK, snapshot.criticalOnDark)
            prefs.putInt(Prefs.KEY_COLOR_CRITICAL_ON_LIGHT, snapshot.criticalOnLight)
            prefs.putInt(Prefs.KEY_COLOR_CHARGING_ON_DARK, snapshot.chargingOnDark)
            prefs.putInt(Prefs.KEY_COLOR_CHARGING_ON_LIGHT, snapshot.chargingOnLight)
            prefs.putInt(Prefs.KEY_COLOR_LOW_ON_DARK, snapshot.lowOnDark)
            prefs.putInt(Prefs.KEY_COLOR_LOW_ON_LIGHT, snapshot.lowOnLight)
            prefs.putInt(Prefs.KEY_LOW_THRESHOLD, snapshot.lowThreshold)

            prefs.putInt(Prefs.KEY_RING_STROKE, snapshot.ringStroke)
            prefs.putInt(Prefs.KEY_ARC_STROKE, snapshot.arcStroke)
            prefs.putInt(Prefs.KEY_VALUE_SIZE, snapshot.valueSize)
            prefs.putInt(Prefs.KEY_VALUE_WEIGHT, snapshot.valueWeight)
            prefs.putInt(Prefs.KEY_TYPE_SIZE, snapshot.typeSize)
            prefs.putInt(Prefs.KEY_TYPE_WEIGHT, snapshot.typeWeight)
            prefs.putInt(Prefs.KEY_TRACK_ALPHA, snapshot.trackAlpha)

            prefs.putBoolean(Prefs.KEY_DEBUG_LOG, current.getBoolean(Prefs.KEY_DEBUG_LOG, Prefs.DEF_DEBUG_LOG))
        }
    }

    private fun writeBoolean(key: String, value: Boolean) {
        preferences().edit(commit = true) { putBoolean(key, value) }
        push { it.putBoolean(key, value) }
    }

    private fun writeInt(key: String, value: Int) {
        preferences().edit(commit = true) { putInt(key, value) }
        push { it.putInt(key, value) }
    }

    /** Applies [block] to the framework's remote preferences, if connected. */
    private fun push(block: (SharedPreferences.Editor) -> Unit) {
        val bound = service
        if (bound == null) {
            // Not fatal: onServiceBind replays every value through
            // syncAllToFramework(), so the write is not lost. Logged because a
            // silent return here looks exactly like "live updates are broken".
            Log.w(TAG, "framework not bound, settings will sync on connect")
            return
        }
        runCatching {
            val editor = bound.getRemotePreferences(Prefs.NAME).edit()
            block(editor)
            // commit() rather than apply(): the value has to be visible in the
            // hooked process before the next status-bar frame is drawn.
            editor.commit()
        }.onFailure { Log.e(TAG, "cannot push settings to framework", it) }
    }

    private companion object {
        const val TAG = "HyperDuo"
    }
}
