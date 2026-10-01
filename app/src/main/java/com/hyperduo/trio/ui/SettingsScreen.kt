package com.hyperduo.trio.ui

import android.content.Context
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.pm.PackageInfoCompat
import com.hyperduo.trio.HyperDuoApp
import com.hyperduo.trio.Prefs
import com.hyperduo.trio.R
import com.hyperduo.trio.TrioPreviewView
import com.hyperduo.trio.TrioSettings
import io.github.libxposed.service.XposedService
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.ColorPalette
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Inner padding of a preference row inside its Card. Miuix's Card carries no
 * outer margin and its preferences default to a uniform 16.dp box; the MIUI look
 * wants 18.dp against the card edge and a slightly tighter 14.dp vertical rhythm.
 */
private val SettingsItemMargin = PaddingValues(horizontal = 18.dp, vertical = 14.dp)

/** Which role colour the picker dialog is editing. */
private enum class RoleColor(val labelRes: Int) {
    Low(R.string.color_low_title),
    Charging(R.string.color_charging_title),
    Critical(R.string.color_critical_title),
}

@Composable
fun SettingsScreen(repository: SettingsRepository) {
    var settings by remember { mutableStateOf(repository.read()) }
    var service by remember { mutableStateOf(HyperDuoApp.xposedService) }
    var editing by remember { mutableStateOf<RoleColor?>(null) }

    // The framework service can bind or die at any moment; both the status row
    // and every remote write have to follow it.
    DisposableEffect(repository) {
        val listener: (XposedService?) -> Unit = { service = it }
        HyperDuoApp.addServiceListener(listener)
        onDispose { HyperDuoApp.removeServiceListener(listener) }
    }

    // Single funnel: apply the write, then re-read so the preview never shows a
    // value the repository did not accept (clamping happens on read).
    fun update(block: (SettingsRepository) -> Unit) {
        block(repository)
        settings = repository.read()
    }

    val gated = settings.enabled

    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())

    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.app_name),
                largeTitle = stringResource(R.string.app_name),
                scrollBehavior = scrollBehavior,
            )
        },
        contentWindowInsets = WindowInsets.statusBars,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = padding.calculateTopPadding() + 12.dp,
                end = 16.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { PreviewCard(settings) }

            item { SmallTitle(stringResource(R.string.group_appearance)) }
            item {
                Card {
                    SwitchPreference(
                        checked = settings.enabled,
                        onCheckedChange = { value -> update { it.setEnabled(value) } },
                        title = stringResource(R.string.master_title),
                        summary = stringResource(R.string.master_summary),
                        insideMargin = SettingsItemMargin,
                    )
                    SwitchPreference(
                        checked = settings.showWifi,
                        onCheckedChange = { value -> update { it.setShowWifi(value) } },
                        title = stringResource(R.string.show_wifi_title),
                        summary = stringResource(R.string.show_wifi_summary),
                        insideMargin = SettingsItemMargin,
                        enabled = gated,
                    )
                    SwitchPreference(
                        checked = settings.showMobile,
                        onCheckedChange = { value -> update { it.setShowMobile(value) } },
                        title = stringResource(R.string.show_mobile_title),
                        summary = stringResource(R.string.show_mobile_summary),
                        insideMargin = SettingsItemMargin,
                        enabled = gated,
                    )
                    SwitchPreference(
                        checked = settings.showValue,
                        onCheckedChange = { value -> update { it.setShowValue(value) } },
                        title = stringResource(R.string.show_value_title),
                        summary = stringResource(R.string.show_value_summary),
                        insideMargin = SettingsItemMargin,
                        enabled = gated,
                    )
                    SwitchPreference(
                        checked = settings.showBolt,
                        onCheckedChange = { value -> update { it.setShowBolt(value) } },
                        title = stringResource(R.string.show_bolt_title),
                        summary = stringResource(R.string.show_bolt_summary),
                        insideMargin = SettingsItemMargin,
                        enabled = gated && settings.showValue,
                    )
                    SwitchPreference(
                        checked = settings.showMobileType,
                        onCheckedChange = { value -> update { it.setShowMobileType(value) } },
                        title = stringResource(R.string.show_mobile_type_title),
                        summary = stringResource(R.string.show_mobile_type_summary),
                        insideMargin = SettingsItemMargin,
                        enabled = gated && settings.showValue,
                    )
                }
            }

            item { SmallTitle(stringResource(R.string.group_geometry)) }
            item {
                Card {
                    IntSlider(
                        value = settings.ringStroke,
                        min = Prefs.MIN_RING_STROKE,
                        max = Prefs.MAX_RING_STROKE,
                        title = stringResource(R.string.stroke_title),
                        summary = stringResource(R.string.stroke_summary),
                        enabled = gated,
                        onValueChange = { v -> update { it.setRingStroke(v) } },
                    )
                    IntSlider(
                        value = settings.arcStroke,
                        min = Prefs.MIN_ARC_STROKE,
                        max = Prefs.MAX_ARC_STROKE,
                        title = stringResource(R.string.arc_stroke_title),
                        summary = stringResource(R.string.arc_stroke_summary),
                        enabled = gated,
                        onValueChange = { v -> update { it.setArcStroke(v) } },
                    )
                    IntSlider(
                        value = settings.valueSize,
                        min = Prefs.MIN_VALUE_SIZE,
                        max = Prefs.MAX_VALUE_SIZE,
                        title = stringResource(R.string.value_size_title),
                        summary = stringResource(R.string.value_size_summary),
                        enabled = gated,
                        onValueChange = { v -> update { it.setValueSize(v) } },
                    )
                    IntSlider(
                        value = settings.valueWeight,
                        min = Prefs.MIN_VALUE_WEIGHT,
                        max = Prefs.MAX_VALUE_WEIGHT,
                        title = stringResource(R.string.value_weight_title),
                        summary = stringResource(R.string.value_weight_summary),
                        // A 100..900 range would give the slider 799 steps, so
                        // it snaps in hundreds: the nine weights the platform
                        // actually ships distinct faces for.
                        step = WEIGHT_STEP,
                        enabled = gated && settings.showValue,
                        onValueChange = { v -> update { it.setValueWeight(v) } },
                    )
                    IntSlider(
                        value = settings.typeSize,
                        min = Prefs.MIN_TYPE_SIZE,
                        max = Prefs.MAX_TYPE_SIZE,
                        title = stringResource(R.string.type_size_title),
                        summary = stringResource(R.string.type_size_summary),
                        // Only the centred type honours these, and it only
                        // appears when the network type is enabled at all.
                        enabled = gated && settings.showMobileType,
                        onValueChange = { v -> update { it.setTypeSize(v) } },
                    )
                    IntSlider(
                        value = settings.typeWeight,
                        min = Prefs.MIN_TYPE_WEIGHT,
                        max = Prefs.MAX_TYPE_WEIGHT,
                        title = stringResource(R.string.type_weight_title),
                        summary = stringResource(R.string.type_weight_summary),
                        step = WEIGHT_STEP,
                        enabled = gated && settings.showMobileType,
                        onValueChange = { v -> update { it.setTypeWeight(v) } },
                    )
                    IntSlider(
                        value = settings.trackAlpha,
                        min = Prefs.MIN_TRACK_ALPHA,
                        max = Prefs.MAX_TRACK_ALPHA,
                        title = stringResource(R.string.track_alpha_title),
                        summary = stringResource(R.string.track_alpha_summary),
                        enabled = gated,
                        onValueChange = { v -> update { it.setTrackAlpha(v) } },
                    )
                }
            }

            item { SmallTitle(stringResource(R.string.group_colors)) }
            item {
                Card {
                    SwitchPreference(
                        checked = settings.roleColors,
                        onCheckedChange = { value -> update { it.setRoleColors(value) } },
                        title = stringResource(R.string.color_role_title),
                        summary = stringResource(R.string.color_role_summary),
                        insideMargin = SettingsItemMargin,
                        enabled = gated,
                    )
                    IntSlider(
                        value = settings.lowThreshold,
                        min = Prefs.MIN_LOW_THRESHOLD,
                        max = Prefs.MAX_LOW_THRESHOLD,
                        title = stringResource(R.string.low_threshold_title),
                        summary = stringResource(R.string.low_threshold_summary),
                        enabled = gated && settings.roleColors,
                        onValueChange = { v -> update { it.setLowThreshold(v) } },
                    )
                    RoleColor.entries.forEach { role ->
                        ArrowPreference(
                            title = stringResource(role.labelRes),
                            summary = stringResource(role.colorFieldLabelRes),
                            insideMargin = SettingsItemMargin,
                            startAction = {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Swatch(colorFor(settings, role, onDark = true))
                                    Swatch(colorFor(settings, role, onDark = false))
                                }
                            },
                            enabled = gated && settings.roleColors,
                            onClick = { editing = role },
                        )
                    }
                }
            }

            item { SmallTitle(stringResource(R.string.group_advanced)) }
            item {
                Card {
                    SwitchPreference(
                        checked = settings.debugLog,
                        onCheckedChange = { value -> update { it.setDebugLog(value) } },
                        title = stringResource(R.string.debug_title),
                        summary = stringResource(R.string.debug_summary),
                        insideMargin = SettingsItemMargin,
                    )
                    ArrowPreference(
                        title = stringResource(R.string.color_reset),
                        summary = stringResource(R.string.color_reset_summary),
                        insideMargin = SettingsItemMargin,
                        onClick = { update { it.resetRoleColors() } },
                    )
                }
            }

            item { SmallTitle(stringResource(R.string.about_title)) }
            item { AboutCard(service = service) }
        }

        // Must stay inside the Scaffold: MiuixPopupHost reads LocalRootDialogStates,
        // which Scaffold provides. Composed outside this lambda the dialog would
        // register itself on a list nothing ever renders, and silently never appear.
        val current = editing
        if (current != null) {
            ColorDialog(
                role = current,
                settings = settings,
                onPick = { onDark, argb ->
                    update { repo ->
                        when (current) {
                            RoleColor.Critical ->
                                if (onDark) repo.setColorCriticalOnDark(argb)
                                else repo.setColorCriticalOnLight(argb)

                            RoleColor.Low ->
                                if (onDark) repo.setColorLowOnDark(argb)
                                else repo.setColorLowOnLight(argb)

                            RoleColor.Charging ->
                                if (onDark) repo.setColorChargingOnDark(argb)
                                else repo.setColorChargingOnLight(argb)
                        }
                    }
                },
                onDismiss = { editing = null },
            )
        }
    }
}

/**
 * Previews the glyph in five states, drawn by the very renderer the status bar
 * uses, so the two can never disagree about the geometry.
 *
 * <p>The last-but-one state is the one worth watching: with no Wi-Fi ink the
 * value moves down into the ring centre, which is where the percentage lives
 * whenever Wi-Fi is off.
 */
@Composable
private fun PreviewCard(settings: TrioSettings) {
    Card {
        Column(modifier = Modifier.padding(vertical = 16.dp)) {
            Text(
                text = stringResource(R.string.preview_title),
                style = MiuixTheme.textStyles.subtitle,
                modifier = Modifier.padding(horizontal = 18.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.Top,
            ) {
                PreviewCell(settings, 88, charging = true, caption = stringResource(R.string.preview_charging))
                PreviewCell(
                    settings, 88,
                    charging = true,
                    quickCharging = true,
                    caption = stringResource(R.string.preview_quick_charge),
                )
                PreviewCell(settings, 64, caption = stringResource(R.string.preview_normal))
                PreviewCell(
                    settings, 79,
                    wifiLevel = NO_WIFI_LEVEL,
                    mobileType = MOBILE_TYPE_PREVIEW,
                    caption = stringResource(R.string.preview_no_wifi),
                )
                PreviewCell(settings, 24, powerSave = true, low = true, caption = stringResource(R.string.preview_low))
                PreviewCell(settings, 12, low = true, caption = stringResource(R.string.preview_critical))
            }
        }
    }
}

@Composable
private fun RowScope.PreviewCell(
    settings: TrioSettings,
    level: Int,
    charging: Boolean = false,
    quickCharging: Boolean = false,
    powerSave: Boolean = false,
    low: Boolean = false,
    wifiLevel: Int = WIFI_LEVEL,
    mobileType: String = "",
    caption: String,
) {
    // The status bar sits on the wallpaper, so the preview follows the app's own
    // light/dark mode rather than trying to guess the wallpaper.
    val dark = MiuixTheme.colorSchemeMode == ColorSchemeMode.Dark ||
        (MiuixTheme.colorSchemeMode == ColorSchemeMode.System &&
            androidx.compose.foundation.isSystemInDarkTheme())

    Column(
        modifier = Modifier.weight(1f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AndroidView(
            modifier = Modifier.size(PREVIEW_SIZE),
            factory = { context -> TrioPreviewView(context) },
            update = { view ->
                view.setSettings(settings)
                view.setPreviewBackground(if (dark) 0xFF1C1B1F.toInt() else 0xFFF2F2F7.toInt())
                view.setForeground(if (dark) 0xFFFFFFFF.toInt() else 0xFF000000.toInt())
                view.setState(level, charging, quickCharging, powerSave, low, wifiLevel, MOBILE_LEVEL, mobileType)
            },
        )
        Text(
            text = caption,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/**
 * One label/value pair of the about card. The label sits above the value the way
 * HyperCopy's info card does, which keeps long values (the framework string, the
 * scope list) free to wrap without a second column squeezing them.
 */
@Composable
private fun InfoRow(label: String, value: String) {
    Text(
        text = label,
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
    Text(
        text = value,
        style = MiuixTheme.textStyles.body2,
        color = MiuixTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 2.dp, bottom = 14.dp),
    )
}

@Composable
private fun AboutCard(service: XposedService?) {
    val context = LocalContext.current
    val connected = service != null

    // Both of these are binder calls into the framework daemon: bind them to the
    // service identity so they run on bind rather than on every recomposition.
    val unknown = stringResource(R.string.info_unknown)
    val module = remember(context) { readModuleInfo(context) }
    val framework = remember(service, unknown) { readFrameworkInfo(service, unknown) }
    val scope = remember(service, unknown) { readScope(service, unknown) }

    // fillMaxWidth is required on both the card and its content column: Miuix's Card
    // wraps to its content, and this card's text is narrower than the sibling cards,
    // so without it the About card renders visibly inset on the right.
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(18.dp)) {
            Text(
                text = stringResource(
                    if (connected) R.string.status_connected else R.string.status_disconnected,
                ),
                style = MiuixTheme.textStyles.headline1,
            )
            Text(
                text = stringResource(
                    if (connected) R.string.status_connected_summary else R.string.status_disconnected_summary,
                ),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 2.dp, bottom = 16.dp),
            )

            InfoRow(stringResource(R.string.info_module_version), module.moduleVersion)
            InfoRow(stringResource(R.string.info_framework), framework)
            InfoRow(stringResource(R.string.info_scope), scope)
            InfoRow(stringResource(R.string.info_system_version), module.systemVersion)
            InfoRow(stringResource(R.string.info_android_version), module.androidVersion)
            InfoRow(stringResource(R.string.info_device_model), module.deviceModel)

            Text(
                text = stringResource(R.string.about_summary),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun ColorDialog(
    role: RoleColor,
    settings: TrioSettings,
    onPick: (onDark: Boolean, argb: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var onDark by remember { mutableStateOf(true) }

    OverlayDialog(
        show = true,
        title = stringResource(role.labelRes),
        summary = stringResource(R.string.color_dialog_summary),
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.padding(horizontal = 4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BackgroundChip(
                    label = stringResource(R.string.color_on_dark),
                    selected = onDark,
                    modifier = Modifier.weight(1f),
                ) { onDark = true }
                BackgroundChip(
                    label = stringResource(R.string.color_on_light),
                    selected = !onDark,
                    modifier = Modifier.weight(1f),
                ) { onDark = false }
            }
            ColorPalette(
                color = Color(colorFor(settings, role, onDark)),
                onColorChanged = { picked -> onPick(onDark, picked.toArgb()) },
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun BackgroundChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = if (selected) {
        ButtonDefaults.buttonColors()
    } else {
        ButtonDefaults.buttonColors(
            color = MiuixTheme.colorScheme.surfaceContainerHigh,
            contentColor = MiuixTheme.colorScheme.onSurfaceContainerHigh,
        )
    }
    Button(onClick = onClick, modifier = modifier, colors = colors) {
        Text(text = label, style = MiuixTheme.textStyles.button)
    }
}

@Composable
private fun Swatch(color: Int) {
    Box(
        modifier = Modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(Color(color)),
    )
}

/**
 * Integer slider over a closed range, showing the current value at the end.
 *
 * @param step increment between selectable values; the range must divide by it.
 */
@Composable
private fun IntSlider(
    value: Int,
    min: Int,
    max: Int,
    title: String,
    summary: String,
    enabled: Boolean,
    onValueChange: (Int) -> Unit,
    step: Int = 1,
) {
    SliderPreference(
        value = value.toFloat(),
        // Rounded here rather than stored rounded: the slider reports floats,
        // and every preset in this screen is a whole number. Snapping through
        // [step] keeps a wide range (100..900) from producing values no font
        // actually ships.
        onValueChange = { raw ->
            val snapped = Math.round(raw / step) * step
            onValueChange(snapped.coerceIn(min, max))
        },
        title = title,
        summary = summary,
        valueText = value.toString(),
        enabled = enabled,
        valueRange = min.toFloat()..max.toFloat(),
        steps = ((max - min) / step - 1).coerceAtLeast(0),
        insideMargin = SettingsItemMargin,
    )
}

private val RoleColor.colorFieldLabelRes: Int
    get() = R.string.color_field_summary

private fun colorFor(settings: TrioSettings, role: RoleColor, onDark: Boolean): Int =
    when (role) {
        RoleColor.Critical -> if (onDark) settings.criticalOnDark else settings.criticalOnLight
        RoleColor.Low -> if (onDark) settings.lowOnDark else settings.lowOnLight
        RoleColor.Charging -> if (onDark) settings.chargingOnDark else settings.chargingOnLight
    }

/** Signal levels the preview shows; the middle of each range. */
private const val WIFI_LEVEL = 3
private const val MOBILE_LEVEL = 4

/** Font weights snap in hundreds: 100, 200, ... 900. */
private const val WEIGHT_STEP = 100

/** What the renderer treats as "no Wi-Fi ink": the value drops to the centre. */
private const val NO_WIFI_LEVEL = -1

/**
 * Preview glyph edge. Sized so the six cells still fit one row inside a 368.dp
 * card: six of these plus five 4.dp gutters stay under the content width.
 */
private val PREVIEW_SIZE = 52.dp

/** Stand-in label for the no-Wi-Fi preview cell; the real one comes from SystemUI. */
private const val MOBILE_TYPE_PREVIEW = "5G"

/** Everything the about card renders about this installation. */
private data class ModuleInfo(
    val moduleVersion: String,
    val systemVersion: String,
    val androidVersion: String,
    val deviceModel: String,
)

/**
 * Reads the values that never change while the process lives.
 *
 * [Build.VERSION.INCREMENTAL] and [Build.DISPLAY] are both shown when they differ:
 * on MIUI the former carries the ROM build (OS4.0.0.27.XNCCNXM) and the latter the
 * underlying AOSP build id, and either one alone leaves the user guessing.
 */
private fun readModuleInfo(context: Context): ModuleInfo {
    val packageInfo = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0)
    }.getOrNull()
    val versionName = packageInfo?.versionName ?: "?"
    val versionCode = packageInfo?.let { PackageInfoCompat.getLongVersionCode(it) } ?: 0L
    val systemVersion = listOf(Build.VERSION.INCREMENTAL, Build.DISPLAY)
        .filter { !it.isNullOrBlank() }
        .distinct()
        .joinToString(" · ")
    return ModuleInfo(
        moduleVersion = "$versionName ($versionCode)",
        systemVersion = systemVersion.ifBlank { "?" },
        androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        deviceModel = listOf(Build.MANUFACTURER, Build.MODEL)
            .filter { !it.isNullOrBlank() }
            .joinToString(" "),
    )
}

/**
 * Every getter here is a binder call into the framework daemon, so all of them are
 * wrapped: a service that died between binding and this call must degrade to
 * [unknown] rather than take the settings screen down with it.
 */
private fun readFrameworkInfo(service: XposedService?, unknown: String): String {
    if (service == null) return unknown
    return runCatching {
        val name = service.frameworkName
        val version = service.frameworkVersion
        val code = service.frameworkVersionCode
        "$name $version ($code), API ${service.apiVersion}"
    }.getOrDefault(unknown)
}

private fun readScope(service: XposedService?, unknown: String): String {
    if (service == null) return unknown
    val packages = runCatching { service.scope }.getOrNull()
    if (packages.isNullOrEmpty()) return unknown
    return packages.joinToString("\n")
}
