package com.hyperduo.trio.ui

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.ColorPalette
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TabRow
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

/**
 * Group heading. Miuix's [SmallTitle] defaults to a 28.dp vertical margin, which
 * reads as a stray indent once the page itself only insets 12.dp; the reference
 * page narrows it to 18.dp so the heading lines up with the card padding instead
 * of the card edge.
 */
@Composable
private fun SectionTitle(title: String) {
    SmallTitle(
        text = title,
        modifier = Modifier.padding(top = 4.dp),
        insideMargin = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
    )
}

/** Which role colour the picker dialog is editing. */
private enum class RoleColor(val labelRes: Int) {
    Low(R.string.color_low_title),
    Charging(R.string.color_charging_title),
    Critical(R.string.color_critical_title),
}

/**
 * The page's top-level sections, in strip order. The label reuses the section
 * heading the group already had, so the strip reads as a table of contents for
 * the page rather than a second vocabulary for the same four things.
 */
private enum class SettingsTab(val labelRes: Int) {
    General(R.string.tab_general),
    Geometry(R.string.group_geometry),
    Colors(R.string.group_colors),
    About(R.string.about_title),
}

@Composable
fun SettingsScreen(repository: SettingsRepository) {
    var settings by remember { mutableStateOf(repository.read()) }
    var service by remember { mutableStateOf(HyperDuoApp.xposedService) }
    var editing by remember { mutableStateOf<RoleColor?>(null) }
    // The ordinal rather than the enum itself, so a configuration change simply
    // restores an Int instead of routing an enum through the saveable bundle.
    var tabIndex by rememberSaveable { mutableIntStateOf(SettingsTab.General.ordinal) }
    val listState = rememberLazyListState()

    // The framework service can bind or die at any moment; both the status row
    // and every remote write have to follow it.
    DisposableEffect(repository) {
        val listener: (XposedService?) -> Unit = { service = it }
        HyperDuoApp.addServiceListener(listener)
        onDispose { HyperDuoApp.removeServiceListener(listener) }
    }

    // Held here rather than inside the About tab: a LazyColumn disposes items
    // that scroll out of view, and a download in flight must survive the user
    // flicking over to another tab and back.
    val context = LocalContext.current
    val updater = remember(context) { UpdateController(context.applicationContext) }
    DisposableEffect(updater) { onDispose { updater.dispose() } }

    // Single funnel: apply the write, then re-read so the preview never shows a
    // value the repository did not accept (clamping happens on read).
    fun update(block: (SettingsRepository) -> Unit) {
        block(repository)
        settings = repository.read()
    }

    val gated = settings.enabled
    val tab = SettingsTab.entries[tabIndex]

    // A tab is a different page's worth of rows; keeping the offset would drop
    // the reader into the middle of the next one.
    LaunchedEffect(tabIndex) { listState.scrollToItem(0) }

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
            state = listState,
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                start = 12.dp,
                top = padding.calculateTopPadding() + 12.dp,
                end = 12.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { PreviewCard(settings) }

            item {
                // The strip sits below the preview, so the glyph stays in
                // view whichever tab is open: a setting and its effect can
                // always be compared without scrolling back to the top.
                val labels = SettingsTab.entries.map { stringResource(it.labelRes) }
                TabRow(
                    tabs = labels,
                    selectedTabIndex = tabIndex,
                    onTabSelected = { index -> tabIndex = index },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            when (tab) {
                SettingsTab.General -> generalTab(settings, gated) { block -> update(block) }
                SettingsTab.Geometry -> geometryTab(settings, gated) { block -> update(block) }
                SettingsTab.Colors -> colorsTab(settings, gated, { block -> update(block) }) { role ->
                    editing = role
                }

                SettingsTab.About -> aboutTab(settings, service, updater) { block -> update(block) }
            }
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
 * General: the master and appearance switches, then the advanced rows.
 * Advanced has no tab of its own, so it folds in here.
 */
private fun LazyListScope.generalTab(
    settings: TrioSettings,
    gated: Boolean,
    update: ((SettingsRepository) -> Unit) -> Unit,
) {
    item { SectionTitle(stringResource(R.string.group_appearance)) }
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
            SwitchPreference(
                checked = settings.swapWifiValue,
                onCheckedChange = { value -> update { it.setSwapWifiValue(value) } },
                title = stringResource(R.string.swap_wifi_value_title),
                summary = stringResource(R.string.swap_wifi_value_summary),
                insideMargin = SettingsItemMargin,
                // Moving the arcs is pointless if either side of the swap is
                // switched off, so the row needs both of them on.
                enabled = gated && settings.showWifi && settings.showValue,
            )
        }
    }
}

/** Geometry: the ring, arc, size, weight and track sliders. */
private fun LazyListScope.geometryTab(
    settings: TrioSettings,
    gated: Boolean,
    update: ((SettingsRepository) -> Unit) -> Unit,
) {
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
}

/**
 * Colors: the role-colour switch, the low-battery threshold and the three
 * per-role colour rows. The threshold stays here rather than under Geometry
 * because it only ever chooses between two colours.
 */
private fun LazyListScope.colorsTab(
    settings: TrioSettings,
    gated: Boolean,
    update: ((SettingsRepository) -> Unit) -> Unit,
    onEdit: (RoleColor) -> Unit,
) {
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
                    onClick = { onEdit(role) },
                )
            }
            ArrowPreference(
                title = stringResource(R.string.color_reset),
                summary = stringResource(R.string.color_reset_summary),
                insideMargin = SettingsItemMargin,
                onClick = { update { it.resetRoleColors() } },
            )
        }
    }
}

/** About: activation state, the counters, module info, updates and the log switch. */
private fun LazyListScope.aboutTab(
    settings: TrioSettings,
    service: XposedService?,
    updater: UpdateController,
    update: ((SettingsRepository) -> Unit) -> Unit,
) {
    item { ActivatedRow(settings = settings, service = service) }
    item { InfoCard(service = service) }
    item { UpdateCard(updater) }
    item {
        Card {
            SwitchPreference(
                checked = settings.debugLog,
                onCheckedChange = { value -> update { it.setDebugLog(value) } },
                title = stringResource(R.string.debug_title),
                summary = stringResource(R.string.debug_summary),
                insideMargin = SettingsItemMargin,
            )
        }
    }
}

/**
 * The in-app updater.
 *
 * <p>The card is one heading, one status sentence describing the current state,
 * and one action button whose label is the verb that state calls for — check,
 * download, install or retry. A single button in a fixed place reads better than
 * a menu whose contents depend on a state the user cannot see.
 *
 * <p>The state lives in [updater], which the screen remembers, so scrolling this
 * card out of view and back does not restart a download in flight.
 */
@Composable
private fun UpdateCard(updater: UpdateController) {
    val state = updater.state

    val status = when (state) {
        is UpdateState.Idle -> stringResource(R.string.update_summary_idle)
        is UpdateState.Checking -> stringResource(R.string.update_checking)
        is UpdateState.UpToDate -> stringResource(R.string.update_uptodate, state.current)
        is UpdateState.NoRelease -> stringResource(R.string.update_no_release)
        is UpdateState.Available -> stringResource(R.string.update_available, state.info.version)
        is UpdateState.Downloading -> stringResource(
            R.string.update_downloading,
            (state.progress * 100f).toInt(),
        )

        is UpdateState.Ready -> stringResource(R.string.update_ready_summary)
        // The raw reason is diagnostic text — an HTTP code, a socket error — and
        // would lose its meaning if it were run through a string resource.
        is UpdateState.Failed -> listOfNotNull(
            stringResource(R.string.update_failed),
            state.detail?.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = stringResource(R.string.update_title),
                style = MiuixTheme.textStyles.headline1,
            )
            Text(
                text = status,
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 6.dp),
            )

            // Only a pending install needs the extra sentence: it is the one state
            // where the user has to go and do something outside this card.
            if (state is UpdateState.Ready) {
                Text(
                    text = stringResource(R.string.update_ready),
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            // What the install would cost: the version being replaced and the
            // download size, so the choice can be made before tapping.
            if (state is UpdateState.Available) {
                Text(
                    text = stringResource(
                        R.string.update_available_summary,
                        state.current,
                        formatSize(state.info.apkSize),
                    ),
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            // The release notes are the only thing the user can weigh before
            // committing to an install, so a short excerpt earns its place.
            if (state is UpdateState.Available && state.info.notes.isNotEmpty()) {
                Text(
                    text = state.info.notes.lines().firstOrNull { it.isNotBlank() }.orEmpty(),
                    fontSize = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            if (state is UpdateState.Downloading) {
                LinearProgressIndicator(
                    // Null means the server never declared a length: the bar then
                    // animates instead of claiming a progress it does not have.
                    progress = state.progress.takeIf { it > 0f },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (state) {
                    is UpdateState.Available -> {
                        PrimaryAction(stringResource(R.string.update_download)) {
                            updater.download(state.info)
                        }
                        SecondaryAction(stringResource(R.string.update_release_page)) {
                            updater.openReleasePage(state.info.pageUrl)
                        }
                    }

                    is UpdateState.Ready -> PrimaryAction(stringResource(R.string.update_install)) {
                        // A refusal means the "install unknown apps" grant is
                        // missing, and only the system settings page can grant it.
                        if (!updater.install(state.file)) updater.openInstallPermissionScreen()
                    }

                    is UpdateState.Failed -> {
                        PrimaryAction(stringResource(R.string.update_retry)) { updater.check() }
                        SecondaryAction(stringResource(R.string.update_release_page)) {
                            updater.openReleasePage(RELEASES_PAGE)
                        }
                    }

                    // Idle, Checking, UpToDate and NoRelease all offer the same
                    // thing: another look at the release feed.
                    else -> PrimaryAction(
                        label = stringResource(R.string.update_check),
                        enabled = state !is UpdateState.Checking,
                        onClick = { updater.check() },
                    )
                }
            }
        }
    }
}

@Composable
private fun PrimaryAction(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColorsPrimary(),
    ) {
        Text(text = label, style = MiuixTheme.textStyles.button)
    }
}

/** A quieter action, sized to sit beside a filled [Button]. */
@Composable
private fun SecondaryAction(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            color = MiuixTheme.colorScheme.surfaceContainerHigh,
            contentColor = MiuixTheme.colorScheme.onSurfaceContainerHigh,
        ),
    ) {
        Text(text = label, style = MiuixTheme.textStyles.button)
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
    val dark = isDarkTheme()

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
 * One label/value pair of the about card: a heavy dark label over a lighter grey
 * value, stacked so long values (the framework string, the scope list) stay free
 * to wrap without a second column squeezing them.
 */
@Composable
private fun InfoRow(label: String, value: String, bottomPadding: Dp = 24.dp) {
    Text(
        text = label,
        style = MiuixTheme.textStyles.headline1,
        fontWeight = FontWeight.Medium,
        color = MiuixTheme.colorScheme.onSurface,
    )
    Text(
        text = value,
        style = MiuixTheme.textStyles.body2,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(top = 2.dp, bottom = bottomPadding),
    )
}

/**
 * The top block of the about page: a tall "activated" card on the left and two
 * short statistic cards stacked on the right.
 *
 * <p>The size is driven by aspectRatio(1f) rather than a fixed height, matching
 * the reference: the side length follows the available width on any device, so
 * the block keeps its proportions instead of looking squat on a tablet.
 */
@Composable
private fun ActivatedRow(settings: TrioSettings, service: XposedService?) {
    val context = LocalContext.current
    val module = remember(context) { readModuleInfo(context) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActivatedCard(
            active = service != null,
            version = module.moduleVersion,
            modifier = Modifier.weight(1f).aspectRatio(1f),
        )
        Column(
            modifier = Modifier.weight(1f).aspectRatio(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatCard(
                label = stringResource(R.string.about_scope_count),
                value = scopeCount(service).toString(),
                modifier = Modifier.weight(1f),
            )
            StatCard(
                label = stringResource(R.string.about_enabled_count),
                value = enabledCount(settings).toString(),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * The green activation card: state as a heavy title, module version underneath,
 * and the ring-and-check badge bleeding off the bottom-right corner.
 */
@Composable
private fun ActivatedCard(active: Boolean, version: String, modifier: Modifier = Modifier) {
    val dark = isDarkTheme()
    val content = if (dark) MiuixTheme.colorScheme.onSurface else ActivatedContent

    Card(
        modifier = modifier.fillMaxHeight(),
        colors = CardDefaults.defaultColors(
            color = if (dark) ActivatedContainerDark else ActivatedContainer,
            contentColor = content,
        ),
    ) {
        // clipToBounds rather than clip(RoundedCornerShape): the card has already
        // drawn its squircle outline, and clipping to a plain rounded rectangle
        // here would square off the corners the user is looking at.
        Box(modifier = Modifier.fillMaxSize().clipToBounds()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
            ) {
                Text(
                    text = stringResource(
                        if (active) R.string.about_activated else R.string.about_inactive,
                    ),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (active) {
                        stringResource(R.string.about_module_version, version)
                    } else {
                        stringResource(R.string.about_inactive_hint)
                    },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = content,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            // The badge is not aligned to the corner: it is nudged 27x31 further
            // out so a different slice of the ring survives the clip. The vector
            // is the reference's own asset rather than a redrawing of it — the
            // ring-to-viewport ratio is what the eye compares, and it is easy to
            // get wrong by hand.
            Box(
                modifier = Modifier.fillMaxSize().offset(27.dp, 31.dp),
                contentAlignment = Alignment.BottomEnd,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_check_circle_outline),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(if (dark) ActivatedBadgeDark else ActivatedBadge),
                    modifier = Modifier.size(110.dp),
                )
            }
        }
    }
}

/** One of the two short right-hand cards: a quiet label over a large figure. */
@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxHeight()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(14.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Text(
                text = value,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * The reference's four identity rows first, then the two the reference has no
 * equivalent for — the scope list and the Android level are what a user actually
 * needs to report a bug, so they stay on rather than being dropped for symmetry.
 */
@Composable
private fun InfoCard(service: XposedService?) {
    val context = LocalContext.current

    // Every framework getter is a binder call into the daemon: bind them to the
    // service identity so they run when it changes rather than on every
    // recomposition.
    val unknown = stringResource(R.string.info_unknown)
    val module = remember(context) { readModuleInfo(context) }
    val framework = remember(service, unknown) { readFrameworkInfo(service, unknown) }
    val scope = remember(service, unknown) { readScope(service, unknown) }

    // fillMaxWidth is required on both the card and its content column: Miuix's Card
    // wraps to its content, and this card's text is narrower than the sibling cards,
    // so without it the About card renders visibly inset on the right.
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            InfoRow(stringResource(R.string.info_system_version), module.systemVersion)
            InfoRow(stringResource(R.string.info_module_version), module.moduleVersion)
            InfoRow(stringResource(R.string.info_framework), framework)
            InfoRow(stringResource(R.string.info_device_model), module.deviceModel)
            InfoRow(stringResource(R.string.info_scope), scope)
            InfoRow(
                label = stringResource(R.string.info_android_version),
                value = module.androidVersion,
                bottomPadding = 12.dp,
            )

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

/** Palette for the activation card, sampled off the reference screenshot. */
private val ActivatedContainer = Color(0xFFE4F9E5)
private val ActivatedContent = Color(0xFF101010)
private val ActivatedBadge = Color(0xFF7FD78A)

/** Dark-mode counterparts; the reference only shows the light theme. */
private val ActivatedContainerDark = Color(0xFF1D2A1F)
private val ActivatedBadgeDark = Color(0xFF6FC77C)

/**
 * True when the app is rendering its dark palette, following the system setting
 * too. Miuix exposes the mode rather than a resolved boolean, so the system case
 * has to be resolved here.
 */
@Composable
private fun isDarkTheme(): Boolean = MiuixTheme.colorSchemeMode == ColorSchemeMode.Dark ||
    (MiuixTheme.colorSchemeMode == ColorSchemeMode.System && isSystemInDarkTheme())

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
        deviceModel = readDeviceName(context),
    )
}

/**
 * The name the user sees in Settings, not the one printed on the box.
 *
 * <p>[Build.MODEL] is the SKU (`23127PN0CC`); the marketing name (`Xiaomi 14`)
 * only exists as [Settings.Global.DEVICE_NAME], and `Build.MARKET_NAME` is not
 * present in this SDK. A device that has never had the setting written falls
 * back to the model rather than showing an empty row.
 */
private fun readDeviceName(context: Context): String {
    val name = runCatching {
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
    }.getOrNull()
    return name?.takeIf { it.isNotBlank() } ?: Build.MODEL.orEmpty()
}

/**
 * How many packages the framework will load this module into; the reference page
 * shows an equivalent count, and it is the fastest way to spot a mis-scoped
 * install.
 */
private fun scopeCount(service: XposedService?): Int {
    if (service == null) return 0
    return runCatching { service.scope.size }.getOrDefault(0)
}

/** How many of the appearance switches are on, as a single at-a-glance figure. */
private fun enabledCount(settings: TrioSettings): Int = listOf(
    settings.showWifi,
    settings.showMobile,
    settings.showValue,
    settings.showBolt,
    settings.showMobileType,
).count { it }

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
        "$name $version ($code) API ${service.apiVersion}"
    }.getOrDefault(unknown)
}

private fun readScope(service: XposedService?, unknown: String): String {
    if (service == null) return unknown
    val packages = runCatching { service.scope }.getOrNull()
    if (packages.isNullOrEmpty()) return unknown
    return packages.joinToString("\n")
}
