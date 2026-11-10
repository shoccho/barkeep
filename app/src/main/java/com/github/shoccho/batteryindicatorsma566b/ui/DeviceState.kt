package com.github.shoccho.batteryindicatorsma566b.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.github.shoccho.batteryindicatorsma566b.core.BatterySnapshot
import com.github.shoccho.batteryindicatorsma566b.core.Box
import com.github.shoccho.batteryindicatorsma566b.core.SystemBatteryIcon
import com.github.shoccho.batteryindicatorsma566b.overlay.PillAccessibilityService
import kotlinx.coroutines.delay
import kotlin.math.abs

/** Which of the app's permissions are in place. */
data class Permissions(
    val overlay: Boolean,
    val unrestricted: Boolean,
    val secureSettings: Boolean,
    val accessibility: Boolean,
    /** Phone: both SIMs and the 5G/LTE label in "Own icons". */
    val phone: Boolean,
    /** Nearby devices: Bluetooth "connected" state in "Own icons". */
    val nearby: Boolean,
) {
    companion object {
        fun read(context: Context) = Permissions(
            overlay = Settings.canDrawOverlays(context),
            unrestricted = context.getSystemService(PowerManager::class.java)
                .isIgnoringBatteryOptimizations(context.packageName),
            secureSettings = SystemBatteryIcon.hasPermission(context),
            accessibility = PillAccessibilityService.isEnabledInSettings(context),
            phone = context.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED,
            nearby = context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED,
        )
    }
}

/** Screen facts the placement presets need. */
data class ScreenInfo(val widthPx: Int, val statusBarPx: Int, val cutout: Box?, val density: Float) {
    val holeDp: Float get() = cutout?.let { maxOf(it.width, it.height) / density } ?: 22.7f

    companion object {
        fun read(context: Context): ScreenInfo {
            val metrics = context.getSystemService(WindowManager::class.java).currentWindowMetrics
            val insets = metrics.windowInsets
            val rect = insets.displayCutout?.boundingRects?.firstOrNull { !it.isEmpty }
            return ScreenInfo(
                widthPx = metrics.bounds.width(),
                statusBarPx = insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top,
                cutout = rect?.let { Box(it.left, it.top, it.right, it.bottom) },
                density = context.resources.displayMetrics.density,
            )
        }
    }
}

/** Live battery snapshot, updated from the sticky broadcast for as long as the caller is composed. */
@Composable
fun rememberBattery(): BatterySnapshot? {
    val context = LocalContext.current
    var snapshot by remember { mutableStateOf<BatterySnapshot?>(null) }
    DisposableEffect(context) {
        val power = context.getSystemService(PowerManager::class.java)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
                    snapshot = BatterySnapshot.from(intent, power.isPowerSaveMode)
                } else {
                    snapshot = snapshot?.copy(powerSave = power.isPowerSaveMode)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)?.let {
            snapshot = BatterySnapshot.from(it, power.isPowerSaveMode)
        }
        onDispose { context.unregisterReceiver(receiver) }
    }
    return snapshot
}

/** Electrical readings that aren't in the broadcast; polled once a second. */
data class PowerStats(val currentMa: Int?, val timeToFullMs: Long?)

@Composable
fun rememberPowerStats(): PowerStats {
    val context = LocalContext.current
    var stats by remember { mutableStateOf(PowerStats(null, null)) }
    LaunchedEffect(context) {
        val bm = context.getSystemService(BatteryManager::class.java)
        while (true) {
            val raw = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            // Spec says microamps, but some Samsung builds report milliamps.
            val ma = when {
                raw == Int.MIN_VALUE || raw == 0 -> null
                abs(raw) > 20_000 -> raw / 1000
                else -> raw
            }
            val ttf = bm.computeChargeTimeRemaining().takeIf { it > 0 }
            stats = PowerStats(ma, ttf)
            delay(1000)
        }
    }
    return stats
}
