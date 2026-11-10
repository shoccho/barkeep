package com.github.shoccho.batteryindicatorsma566b.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.shoccho.batteryindicatorsma566b.core.Anchor
import com.github.shoccho.batteryindicatorsma566b.core.BatterySnapshot
import com.github.shoccho.batteryindicatorsma566b.core.ColorMode
import com.github.shoccho.batteryindicatorsma566b.core.OverlayGeometry
import com.github.shoccho.batteryindicatorsma566b.core.PillColors
import com.github.shoccho.batteryindicatorsma566b.core.PillSettings
import com.github.shoccho.batteryindicatorsma566b.core.PillStyle
import com.github.shoccho.batteryindicatorsma566b.core.Placement
import com.github.shoccho.batteryindicatorsma566b.core.SettingsRepository
import com.github.shoccho.batteryindicatorsma566b.core.SystemBatteryIcon
import com.github.shoccho.batteryindicatorsma566b.overlay.OverlayService
import com.github.shoccho.batteryindicatorsma566b.overlay.PillAccessibilityService
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class Backdrop(val label: String) { LIGHT("Light"), DARK("Dark"), PHOTO("Photo") }
private enum class Simulate(val label: String) { LIVE("Live"), CHARGING("Charging"), LOW("Low"), SAVER("Saver") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val repo = remember { SettingsRepository.get(context) }
    val settings by repo.state.collectAsStateWithLifecycle()
    val running by OverlayService.running.collectAsStateWithLifecycle()
    val a11yConnected by PillAccessibilityService.connected.collectAsStateWithLifecycle()
    val tint by PillAccessibilityService.tint.collectAsStateWithLifecycle()
    val liveBattery = rememberBattery()
    var permissions by remember { mutableStateOf(Permissions.read(context)) }
    var screen by remember { mutableStateOf(ScreenInfo.read(context)) }
    var wantEnable by rememberSaveable { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        permissions = Permissions.read(context)
        screen = ScreenInfo.read(context)
        // Came back from the "Appear on top" screen after asking to turn on.
        if (wantEnable && permissions.overlay) {
            wantEnable = false
            repo.update { it.copy(enabled = true) }
        }
        if (repo.current.enabled && permissions.overlay && !OverlayService.running.value) {
            OverlayService.start(context)
        }
        onPauseOrDispose { }
    }

    val setEnabled: (Boolean) -> Unit = { on ->
        if (on && !permissions.overlay) {
            wantEnable = true
            openOverlaySettings(context)
        } else {
            repo.update { it.copy(enabled = on) }
            if (on) OverlayService.start(context) else OverlayService.stop(context)
        }
    }
    val update: ((PillSettings) -> PillSettings) -> Unit = { repo.update(it) }

    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            LargeTopAppBar(
                title = { Text("Battery Pill") },
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                HeroCard(
                    settings, liveBattery, screen, tint,
                    coverLive = settings.placement == Placement.SAMSUNG_SPOT && a11yConnected,
                    rowLive = settings.placement == Placement.OWN_ICONS && a11yConnected,
                )
            }
            item { MasterSwitch(settings.enabled, running, permissions.overlay, setEnabled) }
            if (!permissions.overlay || !permissions.unrestricted || !permissions.secureSettings) {
                item { SetupSection(permissions) }
            }
            item { StyleSection(settings, liveBattery, screen, tint, update) }
            item {
                PlacementSection(settings, screen, permissions, a11yConnected, update) {
                    permissions = Permissions.read(context)
                }
            }
            item { ColorSection(settings, a11yConnected, update) }
            item { BehaviourSection(settings, permissions, update) }
            item { BatterySection(liveBattery) }
            item {
                Text(
                    "Made for Galaxy A56 · One UI 9 · v1.0",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

// ---- Hero: magnified live preview -------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeroCard(
    settings: PillSettings,
    live: BatterySnapshot?,
    screen: ScreenInfo,
    tint: Int?,
    coverLive: Boolean,
    rowLive: Boolean,
) {
    var backdrop by rememberSaveable { mutableStateOf(Backdrop.PHOTO) }
    var simulate by rememberSaveable { mutableStateOf(Simulate.LIVE) }
    val night = androidx.compose.foundation.isSystemInDarkTheme()
    val base = live ?: BatterySnapshot(percent = 76, plugged = false)
    val shown = when (simulate) {
        Simulate.LIVE -> base
        Simulate.CHARGING -> base.copy(plugged = true, status = BatteryManager.BATTERY_STATUS_CHARGING, percent = base.percent.coerceAtMost(99))
        Simulate.LOW -> base.copy(plugged = false, powerSave = false, percent = 4)
        Simulate.SAVER -> base.copy(plugged = false, powerSave = true, percent = 42)
    }
    val brush = when (backdrop) {
        Backdrop.LIGHT -> Brush.verticalGradient(listOf(Color(0xFFF4F6FA), Color(0xFFDCE3EE)))
        Backdrop.DARK -> Brush.verticalGradient(listOf(Color(0xFF14171C), Color(0xFF262B33)))
        Backdrop.PHOTO -> Brush.linearGradient(
            listOf(Color(0xFFBFD6EE), Color(0xFF8FA3B8), Color(0xFF5B5550), Color(0xFF2E2A27)),
        )
    }
    Card(
        shape = RoundedCornerShape(30.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(170.dp)
                .padding(10.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(brush),
            contentAlignment = Alignment.Center,
        ) {
            val scale = when (settings.style) {
                PillStyle.EDGE_LINE -> 3f
                PillStyle.CAMERA_RING, PillStyle.DOT -> 3.5f
                else -> 4.5f
            }
            if (rowLive) {
                RowPreview(settings, shown, night, 2.6f, matchedTint = tint)
            } else if (coverLive) {
                PillPreview(
                    settings.copy(style = PillStyle.CAPSULE, contrastRim = false, opacity = 100,
                        widthDp = PillSettings.STOCK_WIDTH_DP, heightDp = PillSettings.STOCK_HEIGHT_DP),
                    shown, night, 4.5f, matchedTint = tint, opaque = true,
                )
            } else {
                PillPreview(settings, shown, night, scale, holeDp = screen.holeDp, lineLengthDp = 95f, matchedTint = tint)
            }
            Text(
                "${shown.percent}%" + when {
                    shown.isCharging -> " · charging"
                    shown.plugged -> " · plugged in, holding"
                    shown.powerSave -> " · power saving"
                    shown.percent <= settings.lowThreshold -> " · low"
                    else -> ""
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (backdrop == Backdrop.LIGHT) Color(0x99000000) else Color(0xCCFFFFFF),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
            )
        }
        FlowRow(
            Modifier.padding(horizontal = 14.dp).padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Backdrop.entries.forEach { b ->
                FilterChip(selected = backdrop == b, onClick = { backdrop = b }, label = { Text(b.label) })
            }
            Spacer(Modifier.width(6.dp))
            Simulate.entries.forEach { s ->
                FilterChip(selected = simulate == s, onClick = { simulate = s }, label = { Text(s.label) })
            }
        }
    }
}

@Composable
private fun MasterSwitch(enabled: Boolean, running: Boolean, overlayAllowed: Boolean, onChange: (Boolean) -> Unit) {
    val container by animateColorAsState(
        if (enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        label = "master",
    )
    Card(
        shape = RoundedCornerShape(30.dp),
        colors = CardDefaults.cardColors(containerColor = container),
        modifier = Modifier.clickable { onChange(!enabled) },
    ) {
        Row(Modifier.padding(horizontal = 24.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (enabled) "On" else "Off", style = MaterialTheme.typography.titleLarge)
                Text(
                    when {
                        !overlayAllowed -> "Needs permission to appear on top"
                        enabled && running -> "Pill is showing in your status bar"
                        enabled -> "Starting…"
                        else -> "Show a battery pill without the number"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = onChange)
        }
    }
}

// ---- Setup -------------------------------------------------------------------------------------

@Composable
private fun SetupSection(p: Permissions) {
    val context = LocalContext.current
    Section("Setup") {
        SetupRow(
            done = p.overlay,
            title = "Appear on top",
            body = "Required. Lets the pill draw over the status bar.",
            action = "Allow",
        ) { openOverlaySettings(context) }
        HorizontalDivider(Modifier.padding(horizontal = 20.dp))
        SetupRow(
            done = p.unrestricted,
            title = "Unrestricted battery use",
            body = "Recommended. Stops One UI from putting the pill to sleep.",
            action = "Allow",
        ) {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri()),
            )
        }
        HorizontalDivider(Modifier.padding(horizontal = 20.dp))
        SetupRow(
            done = p.secureSettings,
            title = "Hide Samsung's battery (optional)",
            body = "Grant once from a computer, then the app can hide the stock battery-with-number " +
                "while your pill is on and bring it back when it's off:",
            action = "Copy",
            extra = {
                Text(
                    SystemBatteryIcon.ADB_GRANT_COMMAND,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .padding(10.dp),
                )
            },
        ) {
            val cm = context.getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("adb", SystemBatteryIcon.ADB_GRANT_COMMAND))
            Toast.makeText(context, "Command copied", Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
private fun SetupRow(
    done: Boolean,
    title: String,
    body: String,
    action: String,
    extra: (@Composable () -> Unit)? = null,
    onAction: () -> Unit,
) {
    Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .padding(top = 2.dp)
                .size(22.dp)
                .clip(CircleShape)
                .background(if (done) PillColorsCompose.charging else MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (done) "✓" else "", color = Color.White, style = MaterialTheme.typography.labelMedium)
        }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!done) extra?.invoke()
        }
        if (!done) {
            FilledTonalButton(onClick = onAction, modifier = Modifier.padding(start = 8.dp)) { Text(action) }
        }
    }
}

// ---- Style -------------------------------------------------------------------------------------

@Composable
private fun StyleSection(
    settings: PillSettings,
    live: BatterySnapshot?,
    screen: ScreenInfo,
    tint: Int?,
    update: ((PillSettings) -> PillSettings) -> Unit,
) {
    val night = androidx.compose.foundation.isSystemInDarkTheme()
    val battery = live ?: BatterySnapshot(percent = 76, plugged = false)
    Section("Style") {
        if (settings.placement == Placement.OWN_ICONS) {
            Hint("In Own icons the row uses Capsule, Classic or Dot (others show as Capsule).")
        }
        if (settings.placement == Placement.SAMSUNG_SPOT) {
            Hint("In Samsung's spot the pill is always a solid Capsule, so the number underneath can't show through.")
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(PillStyle.entries) { style ->
                val selected = settings.style == style
                val tileSettings = settings.copy(style = style, opacity = 100)
                Column(
                    Modifier
                        .width(128.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(
                            if (selected) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerHigh,
                        )
                        .border(
                            BorderStroke(2.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent),
                            RoundedCornerShape(20.dp),
                        )
                        .clickable { update { it.copy(style = style) } }
                        .padding(12.dp),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (night) Color(0xFF101318) else Color(0xFFE6EBF2)),
                        contentAlignment = Alignment.Center,
                    ) {
                        PillPreview(
                            tileSettings, battery, night,
                            scale = if (style == PillStyle.CAMERA_RING) 1.4f else 2f,
                            holeDp = screen.holeDp, lineLengthDp = 48f, animate = selected, matchedTint = tint,
                        )
                    }
                    Text(style.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                    Text(
                        style.blurb,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        minLines = 2,
                    )
                }
            }
        }
    }
}

// ---- Placement ---------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlacementSection(
    s: PillSettings,
    screen: ScreenInfo,
    p: Permissions,
    a11yConnected: Boolean,
    update: ((PillSettings) -> PillSettings) -> Unit,
    refreshPermissions: () -> Unit,
) {
    Section("Position") {
        FlowRow(
            Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Placement.entries.forEach { pl ->
                FilterChip(
                    selected = s.placement == pl,
                    onClick = { update { it.copy(placement = pl) } },
                    label = { Text(pl.label) },
                )
            }
        }
        when (s.placement) {
            Placement.SAMSUNG_SPOT -> {
                CoverControls(s, p, a11yConnected, update, refreshPermissions)
                return@Section
            }
            Placement.OWN_ICONS -> {
                OwnIconsControls(s, p, a11yConnected, update, refreshPermissions)
                return@Section
            }
            Placement.FREE -> Unit
        }
        when (s.style) {
            PillStyle.CAMERA_RING -> {
                Text(
                    "Follows the punch-hole camera automatically, in any orientation.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                )
                SliderRow("Thickness", s.ringThicknessDp, PillSettings.RING_THICKNESS_RANGE, { v -> update { it.copy(ringThicknessDp = v) } }, step = 0.25f)
                SliderRow("Gap from camera", s.ringGapDp, PillSettings.RING_GAP_RANGE, { v -> update { it.copy(ringGapDp = v) } }, step = 0.25f)
            }

            PillStyle.EDGE_LINE -> {
                Segmented(Anchor.entries, s.lineGrowFrom, { "From ${it.label.lowercase()}" }, { a -> update { it.copy(lineGrowFrom = a) } })
                SliderRow("Thickness", s.lineThicknessDp, PillSettings.LINE_THICKNESS_RANGE, { v -> update { it.copy(lineThicknessDp = v) } })
                SliderRow("Side inset", s.lineInsetDp, PillSettings.LINE_INSET_RANGE, { v -> update { it.copy(lineInsetDp = v) } })
                SliderRow("From top", s.lineTopDp, PillSettings.LINE_TOP_RANGE, { v -> update { it.copy(lineTopDp = v) } })
            }

            PillStyle.CAPSULE, PillStyle.CLASSIC, PillStyle.DOT -> {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AssistChip(onClick = {
                        update { OverlayGeometry.besideCamera(it, screen.density, screen.widthPx, screen.cutout, screen.statusBarPx) }
                    }, label = { Text("Beside camera") })
                    AssistChip(onClick = {
                        update { OverlayGeometry.atEdge(it, screen.density, screen.statusBarPx, Anchor.RIGHT) }
                    }, label = { Text("Top right") })
                    AssistChip(onClick = {
                        update { OverlayGeometry.atEdge(it, screen.density, screen.statusBarPx, Anchor.LEFT) }
                    }, label = { Text("Top left") })
                    AssistChip(onClick = {
                        update { it.copy(widthDp = PillSettings.STOCK_WIDTH_DP, heightDp = PillSettings.STOCK_HEIGHT_DP) }
                    }, label = { Text("Samsung size") })
                }
                if (s.anchor == Anchor.RIGHT && s.hideSystemIcon) {
                    Hint("Heads-up: with Samsung's battery hidden, the Wi-Fi and signal icons slide into the top-right corner. \"Beside camera\" is always clear.")
                }
                Segmented(Anchor.entries, s.anchor, { it.label }, { a ->
                    update { it.copy(anchor = a, offsetXDp = if (a == Anchor.CENTER) it.offsetXDp else abs(it.offsetXDp)) }
                })
                if (s.style != PillStyle.DOT) {
                    SliderRow("Width", s.widthDp, PillSettings.WIDTH_RANGE, { v -> update { it.copy(widthDp = v) } })
                }
                SliderRow(
                    if (s.style == PillStyle.DOT) "Size" else "Height", s.heightDp, PillSettings.HEIGHT_RANGE,
                    { v -> update { it.copy(heightDp = v) } },
                )
                SliderRow(
                    if (s.anchor == Anchor.CENTER) "Offset from centre" else "Distance from edge",
                    s.offsetXDp,
                    if (s.anchor == Anchor.CENTER) PillSettings.OFFSET_X_RANGE else PillSettings.OFFSET_X_EDGE_RANGE,
                    { v -> update { it.copy(offsetXDp = v) } },
                )
                SliderRow("From top", s.topDp, PillSettings.TOP_RANGE, { v -> update { it.copy(topDp = v) } })
                NudgePad { dx, dy ->
                    update {
                        // "→" always moves right on screen; for a right-anchored pill that means a smaller edge distance.
                        val signedDx = if (it.anchor == Anchor.RIGHT) -dx else dx
                        val xRange = if (it.anchor == Anchor.CENTER) PillSettings.OFFSET_X_RANGE else PillSettings.OFFSET_X_EDGE_RANGE
                        it.copy(
                            offsetXDp = (it.offsetXDp + signedDx).coerceIn(xRange.start, xRange.endInclusive),
                            topDp = (it.topDp + dy).coerceIn(PillSettings.TOP_RANGE.start, PillSettings.TOP_RANGE.endInclusive),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CoverControls(
    s: PillSettings,
    p: Permissions,
    a11yConnected: Boolean,
    update: ((PillSettings) -> PillSettings) -> Unit,
    refreshPermissions: () -> Unit,
) {
    val context = LocalContext.current
    Hint(
        "The pill sits exactly on Samsung's battery, hiding its number. Samsung's icon keeps the " +
            "rightmost slot, so network and Wi-Fi never move or overlap. Follows it onto the lock screen too.",
    )
    AccessibilityRow(p, a11yConnected, refreshPermissions)
    if (!a11yConnected) {
        Hint("Until it's on, the pill stays in its free position.")
    }
    SliderRow("Cover width", s.coverExtraWidthDp, PillSettings.COVER_EXTRA_RANGE,
        { v -> update { it.copy(coverExtraWidthDp = v) } }, step = 0.25f, format = { "+" + formatDp(it) })
    SliderRow("Cover height", s.coverExtraHeightDp, PillSettings.COVER_EXTRA_RANGE,
        { v -> update { it.copy(coverExtraHeightDp = v) } }, step = 0.25f, format = { "+" + formatDp(it) })
    Hint("Grow these if a sliver of Samsung's icon peeks out around the edge.")
}

@Composable
private fun AccessibilityRow(p: Permissions, a11yConnected: Boolean, refreshPermissions: () -> Unit) {
    val context = LocalContext.current
    SetupRow(
        done = a11yConnected,
        title = if (p.accessibility && !a11yConnected) "Accessibility: starting…" else "Accessibility service",
        body = "Lets the pill draw above the status bar and see where Samsung's icons are. " +
            "It only reads the status bar — nothing from your apps.",
        action = if (p.secureSettings) "Turn on" else "Open",
    ) {
        if (p.secureSettings && PillAccessibilityService.enableWithSecureSettings(context)) {
            refreshPermissions()
        } else {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }
}

@Composable
private fun OwnIconsControls(
    s: PillSettings,
    p: Permissions,
    a11yConnected: Boolean,
    update: ((PillSettings) -> PillSettings) -> Unit,
    refreshPermissions: () -> Unit,
) {
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { refreshPermissions() }
    Hint(
        "Samsung's sound, Wi-Fi, signal and other right-side icons and its battery are hidden; " +
            "the app draws its own row in your order, pill rightmost. Leaving this mode brings " +
            "Samsung's icons back exactly as they were.",
    )
    AccessibilityRow(p, a11yConnected, refreshPermissions)
    if (!p.secureSettings) {
        Hint("Also needs the one-time adb grant from Setup, to hide Samsung's icons.")
    }
    SetupRow(
        done = p.phone && p.nearby,
        title = "Phone & Nearby devices (optional)",
        body = "Phone shows both SIMs and the 5G/4G label; Nearby devices shows when Bluetooth is connected.",
        action = "Allow",
    ) {
        permissionLauncher.launch(
            arrayOf(android.Manifest.permission.READ_PHONE_STATE, android.Manifest.permission.BLUETOOTH_CONNECT),
        )
    }
    Hint("Hidden for good in this mode (an app can't detect them): satellite, screen recording, location-in-use, VoLTE.")

    Text(
        "Icons, left to right",
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
    )
    s.iconOrder.forEachIndexed { index, icon ->
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Switch(
                checked = icon in s.iconsOn,
                onCheckedChange = { on ->
                    update { it.copy(iconsOn = if (on) it.iconsOn + icon else it.iconsOn - icon) }
                },
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            Text(icon.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            TextButton(enabled = index > 0, onClick = { update { it.copy(iconOrder = it.iconOrder.move(index, -1)) } }) {
                Text("↑", style = MaterialTheme.typography.titleLarge)
            }
            TextButton(
                enabled = index < s.iconOrder.lastIndex,
                onClick = { update { it.copy(iconOrder = it.iconOrder.move(index, 1)) } },
            ) { Text("↓", style = MaterialTheme.typography.titleLarge) }
        }
    }
    Hint("The battery pill always sits at the far right.")
    SliderRow("Icon size", s.rowIconSizeDp, PillSettings.ROW_ICON_RANGE, { v -> update { it.copy(rowIconSizeDp = v) } })
    SliderRow("Spacing", s.rowGapDp, PillSettings.ROW_GAP_RANGE, { v -> update { it.copy(rowGapDp = v) } }, step = 0.25f)
    SliderRow("Distance from right edge", s.rowEndMarginDp, PillSettings.ROW_END_RANGE, { v -> update { it.copy(rowEndMarginDp = v) } })
    SliderRow("Pill width", s.widthDp, PillSettings.WIDTH_RANGE, { v -> update { it.copy(widthDp = v) } })
    SliderRow("Pill height", s.heightDp, PillSettings.HEIGHT_RANGE, { v -> update { it.copy(heightDp = v) } })
    SwitchRow(
        "Samsung icon art", s.rowSamsungArt, { v -> update { it.copy(rowSamsungArt = v) } },
        subtitle = "Uses One UI's own icons from your phone; off = Battery Pill's drawn icons",
    )
    SwitchRow("Wi-Fi generation (5 / 6 / 7)", s.rowWifiGeneration, { v -> update { it.copy(rowWifiGeneration = v) } })
    SwitchRow("Data activity arrows", s.rowActivityArrows, { v -> update { it.copy(rowActivityArrows = v) } })
    SwitchRow("Network type (5G / 4G)", s.rowNetworkType, { v -> update { it.copy(rowNetworkType = v) } },
        subtitle = "Shown while mobile data carries the internet")
}

private fun <T> List<T>.move(index: Int, by: Int): List<T> {
    val target = (index + by).coerceIn(0, lastIndex)
    if (target == index) return this
    return toMutableList().apply { add(target, removeAt(index)) }
}

// ---- Colour ------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorSection(s: PillSettings, a11yConnected: Boolean, update: ((PillSettings) -> PillSettings) -> Unit) {
    Section("Colour") {
        FlowRow(
            Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ColorMode.entries.forEach { m ->
                FilterChip(
                    selected = s.colorMode == m,
                    onClick = { update { it.copy(colorMode = m) } },
                    label = { Text(if (m == ColorMode.AUTO) "Theme" else if (m == ColorMode.CUSTOM) "Pick" else m.label) },
                )
            }
        }
        when {
            s.colorMode == ColorMode.AUTO -> Hint("White in dark mode, black in light mode.")
            s.colorMode == ColorMode.MATCH && a11yConnected ->
                Hint("Turns dark or light together with Samsung's icons, in every app.")
            s.colorMode == ColorMode.MATCH ->
                Hint("Needs the Battery Pill accessibility service (Position → Samsung's spot shows how). Following the theme until then.")
        }
        AnimatedVisibility(s.colorMode == ColorMode.CUSTOM) {
            Column {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PillColors.SWATCHES.forEach { c ->
                        val selected = c == s.customColor
                        Box(
                            Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color(c))
                                .border(
                                    BorderStroke(if (selected) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface),
                                    CircleShape,
                                )
                                .clickable { update { it.copy(customColor = c) } },
                        )
                    }
                }
                val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(s.customColor, it) }
                SliderRow("Hue", hsv[0], 0f..360f, { h ->
                    update { it.copy(customColor = android.graphics.Color.HSVToColor(floatArrayOf(h, 0.7f, 1f))) }
                }, step = 1f, format = { "${it.roundToInt()}°" })
            }
        }
        SwitchRow(
            "Status colours", s.stateColors, { v -> update { it.copy(stateColors = v) } },
            subtitle = "Green charging · amber power saving · red low",
        )
        if (s.stateColors) {
            SliderRow("Low battery at", s.lowThreshold.toFloat(),
                PillSettings.LOW_RANGE.first.toFloat()..PillSettings.LOW_RANGE.last.toFloat(),
                { v -> update { it.copy(lowThreshold = v.roundToInt()) } }, step = 1f, format = { "${it.roundToInt()}%" })
        }
        SliderRow("Opacity", s.opacity.toFloat(),
            PillSettings.OPACITY_RANGE.first.toFloat()..PillSettings.OPACITY_RANGE.last.toFloat(),
            { v -> update { it.copy(opacity = v.roundToInt()) } }, step = 1f, format = { "${it.roundToInt()}%" })
        SwitchRow(
            "Contrast rim", s.contrastRim, { v -> update { it.copy(contrastRim = v) } },
            subtitle = "Thin opposite-colour edge so it stays readable on any wallpaper",
        )
    }
}

// ---- Behaviour ---------------------------------------------------------------------------------

@Composable
private fun BehaviourSection(s: PillSettings, p: Permissions, update: ((PillSettings) -> PillSettings) -> Unit) {
    Section("Behaviour") {
        SwitchRow("Charging bolt", s.showBolt, { v -> update { it.copy(showBolt = v) } })
        SwitchRow(
            "Charging shimmer", s.chargingAnimation, { v -> update { it.copy(chargingAnimation = v) } },
            subtitle = "A soft light glides across while charging. Paused when the screen is off.",
        )
        SwitchRow(
            "Pulse when nearly empty", s.criticalPulse, { v -> update { it.copy(criticalPulse = v) } },
            subtitle = "Breathes at ${PillSettings.CRITICAL_PERCENT}% and below",
        )
        SwitchRow(
            "Hide in full screen", s.hideInFullscreen, { v -> update { it.copy(hideInFullscreen = v) } },
            subtitle = "Videos and games get the whole screen",
        )
        SwitchRow("Hide in landscape", s.hideInLandscape, { v -> update { it.copy(hideInLandscape = v) } })
        SwitchRow("Start after restart", s.startOnBoot, { v -> update { it.copy(startOnBoot = v) } })
        SwitchRow(
            "Hide Samsung's battery", s.hideSystemIcon, { v -> update { it.copy(hideSystemIcon = v) } },
            subtitle = when {
                s.placement != Placement.FREE -> "Free position only — the other positions manage Samsung's icons themselves"
                p.secureSettings -> "Restored automatically when the pill turns off"
                else -> "Needs the one-time adb grant in Setup"
            },
            enabled = p.secureSettings && s.placement == Placement.FREE,
        )
    }
}

// ---- Battery stats -----------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BatterySection(b: BatterySnapshot?) {
    val power = rememberPowerStats()
    Section("Battery right now") {
        if (b == null) {
            Hint("Reading…")
            return@Section
        }
        val volts = b.voltageMv / 1000f
        val watts = power.currentMa?.let { abs(it) * volts / 1000f }
        val stats = buildList {
            add("Level" to "${b.percent}%")
            add("Status" to statusText(b))
            if (watts != null && b.plugged) add("Charging at" to String.format(Locale.US, "%.1f W", watts))
            if (watts != null && !b.plugged) add("Drawing" to String.format(Locale.US, "%.1f W", watts))
            power.currentMa?.let { add("Current" to "${abs(it)} mA") }
            if (b.voltageMv > 0) add("Voltage" to String.format(Locale.US, "%.2f V", volts))
            if (!b.temperatureC.isNaN()) add("Temperature" to String.format(Locale.US, "%.1f °C", b.temperatureC))
            add("Health" to healthText(b.health))
            if (b.cycleCount >= 0) add("Charge cycles" to "${b.cycleCount}")
            power.timeToFullMs?.takeIf { b.plugged }?.let { add("Full in" to durationText(it)) }
        }
        FlowRow(
            Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            maxItemsInEachRow = 3,
        ) {
            stats.forEach { (label, value) ->
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
    )
}

private object PillColorsCompose {
    val charging = Color(PillColors.CHARGING)
}

private fun statusText(b: BatterySnapshot) = when {
    b.isFull && b.plugged -> "Full"
    b.status == BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
    b.plugged -> "Plugged in · holding"
    b.powerSave -> "Power saving"
    else -> "On battery"
}

private fun healthText(h: Int) = when (h) {
    BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
    BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheating"
    BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
    BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
    BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over-voltage"
    else -> "Unknown"
}

private fun durationText(ms: Long): String {
    val minutes = (ms / 60_000).toInt()
    return if (minutes >= 60) "${minutes / 60} h ${minutes % 60} m" else "$minutes min"
}

private fun openOverlaySettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}".toUri()),
    )
}
