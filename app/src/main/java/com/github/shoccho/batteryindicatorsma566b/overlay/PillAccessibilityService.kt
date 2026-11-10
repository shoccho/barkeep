package com.github.shoccho.batteryindicatorsma566b.overlay

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.github.shoccho.batteryindicatorsma566b.core.BatterySnapshot
import com.github.shoccho.batteryindicatorsma566b.core.Box
import com.github.shoccho.batteryindicatorsma566b.core.ColorMode
import com.github.shoccho.batteryindicatorsma566b.core.OverlayGeometry
import com.github.shoccho.batteryindicatorsma566b.core.PillSettings
import com.github.shoccho.batteryindicatorsma566b.core.PillStyle
import com.github.shoccho.batteryindicatorsma566b.core.SettingsRepository
import com.github.shoccho.batteryindicatorsma566b.core.SystemBatteryIcon
import com.github.shoccho.batteryindicatorsma566b.core.TintSampler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Optional helper with two jobs, neither of which a normal app can do:
 *
 * 1. **Samsung's spot** — find Samsung's battery icon in SystemUI's view tree and paint a solid
 *    pill exactly on top of it (accessibility overlays sit above the status bar). Samsung's icon
 *    keeps its rightmost slot, so the network and Wi-Fi icons never shift or overlap.
 * 2. **Match icons** — read the colour SystemUI is tinting its icons with (from a screenshot of
 *    the clock) so the pill turns dark/light exactly when Samsung's icons do.
 *
 * It only reacts to SystemUI and window changes, throttled, and never touches app content.
 */
class PillAccessibilityService : AccessibilityService() {

    private lateinit var repo: SettingsRepository
    private lateinit var wm: WindowManager
    private lateinit var power: PowerManager
    private val handler = Handler(Looper.getMainLooper())
    private var scope: CoroutineScope? = null

    private class Cover(val view: PillView, val params: WindowManager.LayoutParams)

    private val covers = mutableListOf<Cover>()
    private var battery: BatterySnapshot? = null
    private var screenOn = true
    private var statusBarVisible = true
    private var scanPending = false
    private var lastShotAt = 0L
    private var shotPending = false
    private val rect = Rect()
    private var lastStatusBarBattery: Box? = null
    private var lastLogged: List<Box>? = null
    private lateinit var keyguard: KeyguardManager

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    battery = BatterySnapshot.from(intent, power.isPowerSaveMode)
                    rebind()
                }
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                    battery = battery?.copy(powerSave = power.isPowerSaveMode)
                    rebind()
                }
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    scheduleScan(0)
                }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    screenOn = true
                    scheduleScan(0)
                    requestTint()
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        repo = SettingsRepository.get(this)
        wm = getSystemService(WindowManager::class.java)
        power = getSystemService(PowerManager::class.java)
        keyguard = getSystemService(KeyguardManager::class.java)
        screenOn = power.isInteractive
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)?.let {
            battery = BatterySnapshot.from(it, power.isPowerSaveMode)
        }
        scope = MainScope().also { s ->
            s.launch {
                repo.state.collect {
                    scheduleScan(0)
                    requestTint()
                }
            }
            s.launch {
                // Full-screen apps hide the status bar; the cover vanishes with it.
                OverlayService.statusBarShown.collect { shown ->
                    statusBarVisible = shown
                    scheduleScan(0)
                }
            }
        }
        _connected.value = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                scheduleScan(60)
                requestTint()
            }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                scheduleScan(40)
                requestTint()
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ->
                if (event.packageName == SYSUI) scheduleScan(60)
        }
    }

    override fun onInterrupt() = Unit

    // ---- Samsung's spot -----------------------------------------------------------------------

    private val scanRunnable = Runnable {
        scanPending = false
        scan()
    }

    private fun scheduleScan(delayMs: Long) {
        if (delayMs == 0L) {
            handler.removeCallbacks(scanRunnable)
            scanPending = false
            scan()
            return
        }
        if (scanPending) return
        scanPending = true
        handler.postDelayed(scanRunnable, delayMs)
    }

    private fun wantCover(s: PillSettings) = s.enabled && s.coverStock && screenOn

    private fun scan() {
        val s = repo.current
        if (!wantCover(s)) {
            clearCovers()
            return
        }
        val density = resources.displayMetrics.density
        val locked = keyguard.isKeyguardLocked
        var nodes = findNodes(BATTERY_ID)
        if (nodes.isNotEmpty() && !locked) {
            lastStatusBarBattery = nodes.first()
        } else if (nodes.isEmpty() && locked) {
            // The lock screen's status bar is drawn by Samsung's AOD service and its battery isn't
            // exposed to accessibility, but it sits exactly where the normal one does.
            nodes = listOfNotNull(lastStatusBarBattery)
        }
        if (nodes != lastLogged) {
            Log.d(TAG, "battery nodes=$nodes locked=$locked")
            lastLogged = nodes
        }
        syncCovers(nodes.map { OverlayGeometry.cover(it, s, density) }, s, forceVisible = locked)
    }

    /**
     * Visible SystemUI nodes with [viewId], in screen pixels, top-most first.
     *
     * One UI 9 draws the status bar battery with Jetpack Compose, whose nodes are virtual and
     * invisible to findAccessibilityNodeInfosByViewId — so walk each SystemUI window's tree.
     */
    private fun findNodes(viewId: String): List<Box> {
        val out = mutableListOf<Box>()
        val all = try {
            windows
        } catch (e: Exception) {
            Log.w(TAG, "windows unavailable", e)
            return out
        }
        for (w in all) {
            if (w.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
            val root: AccessibilityNodeInfo = w.root ?: continue
            if (root.packageName != SYSUI) continue
            walk(root) { node ->
                if (node.viewIdResourceName == viewId && node.isVisibleToUser) {
                    node.getBoundsInScreen(rect)
                    if (!rect.isEmpty && rect.width() <= MAX_ICON_PX && rect.height() <= MAX_ICON_PX) {
                        out += Box(rect.left, rect.top, rect.right, rect.bottom)
                    }
                }
            }
        }
        return out.distinct().sortedBy { it.top }
    }

    /** Depth-first walk, capped so a huge window (e.g. the expanded shade) can't stall us. */
    private inline fun walk(root: AccessibilityNodeInfo, visit: (AccessibilityNodeInfo) -> Unit) {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var seen = 0
        while (stack.isNotEmpty() && seen < MAX_NODES) {
            val node = stack.removeLast()
            seen++
            visit(node)
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let(stack::addLast)
        }
    }

    private fun syncCovers(boxes: List<Box>, s: PillSettings, forceVisible: Boolean) {
        while (covers.size > boxes.size) removeCover(covers.removeAt(covers.lastIndex))
        boxes.forEachIndexed { i, box ->
            val cover = covers.getOrNull(i) ?: addCover() ?: return
            val p = cover.params
            val changed = p.x != box.left || p.y != box.top || p.width != box.width || p.height != box.height
            p.x = box.left
            p.y = box.top
            p.width = box.width
            p.height = box.height
            val alpha = if (statusBarVisible || forceVisible) 1f else 0f
            val alphaChanged = p.alpha != alpha
            p.alpha = alpha
            bindCover(cover, s)
            if (changed || alphaChanged) runCatching { wm.updateViewLayout(cover.view, p) }
        }
    }

    private fun addCover(): Cover? {
        val view = PillView(this).apply { opaque = true }
        val params = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            title = "BatteryPillCover"
            gravity = Gravity.TOP or Gravity.LEFT
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
            windowAnimations = 0
            alpha = if (statusBarVisible) 1f else 0f
        }
        return try {
            wm.addView(view, params)
            Cover(view, params).also { covers += it }
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't add cover", e)
            null
        }
    }

    private fun bindCover(cover: Cover, s: PillSettings) {
        // Always the solid capsule, full opacity: anything else would let Samsung's number show.
        val look = s.copy(style = PillStyle.CAPSULE, contrastRim = false, opacity = 100)
        cover.view.bind(look, battery, isNight(), _tint.value)
        cover.view.setActive(screenOn && statusBarVisible)
    }

    private fun rebind() {
        val s = repo.current
        covers.forEach { bindCover(it, s) }
    }

    private fun removeCover(cover: Cover) {
        runCatching { wm.removeViewImmediate(cover.view) }
    }

    private fun clearCovers() {
        covers.forEach(::removeCover)
        covers.clear()
    }

    // ---- Match icons ------------------------------------------------------------------------

    private val tintRunnable = Runnable {
        shotPending = false
        requestTint()
    }

    /** Screenshots are rate-limited by Android (~1/s), so coalesce requests. */
    private fun requestTint() {
        val s = repo.current
        if (s.colorMode != ColorMode.MATCH || !s.enabled || !screenOn) return
        val wait = lastShotAt + SHOT_INTERVAL_MS - SystemClock.uptimeMillis()
        if (wait > 0) {
            if (!shotPending) {
                shotPending = true
                handler.postDelayed(tintRunnable, wait)
            }
            return
        }
        val probe = (findNodes(CLOCK_ID) + findNodes(WIFI_ID) + findNodes(SIGNAL_ID))
            .firstOrNull { it.top < statusBarHeightPx() } ?: return
        lastShotAt = SystemClock.uptimeMillis()
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val sampled = try {
                    sample(result, probe)
                } finally {
                    result.hardwareBuffer.close()
                }
                if (sampled != null && sampled != _tint.value) {
                    Log.d(TAG, "status bar icon colour: #%08X".format(sampled))
                    _tint.value = sampled
                    rebind()
                }
            }

            override fun onFailure(errorCode: Int) {
                Log.d(TAG, "screenshot failed: $errorCode")
            }
        })
    }

    private fun sample(result: ScreenshotResult, probe: Box): Int? {
        val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace) ?: return null
        val sw = hw.copy(Bitmap.Config.ARGB_8888, false)
        hw.recycle()
        return try {
            val l = probe.left.coerceIn(0, sw.width - 1)
            val t = probe.top.coerceIn(0, sw.height - 1)
            val w = probe.width.coerceAtMost(sw.width - l)
            val h = probe.height.coerceAtMost(sw.height - t)
            if (w <= 0 || h <= 0) return null
            val px = IntArray(w * h)
            sw.getPixels(px, 0, w, l, t, w, h)
            TintSampler.pick(px)
        } finally {
            sw.recycle()
        }
    }

    // ---- misc --------------------------------------------------------------------------------

    private fun statusBarHeightPx(): Int =
        wm.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top
            .coerceAtLeast(1)

    private fun isNight() =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        scheduleScan(0)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        if (!_connected.value) return
        _connected.value = false
        _tint.value = null
        scope?.cancel()
        scope = null
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(receiver) }
        clearCovers()
    }

    companion object {
        private const val TAG = "PillA11y"
        private const val SYSUI = "com.android.systemui"
        private const val BATTERY_ID = "$SYSUI:id/battery"
        private const val CLOCK_ID = "$SYSUI:id/clock"
        private const val WIFI_ID = "$SYSUI:id/wifi_signal"
        private const val SIGNAL_ID = "$SYSUI:id/mobile_signal"
        private const val MAX_ICON_PX = 400
        private const val MAX_NODES = 1500
        private const val SHOT_INTERVAL_MS = 1100L

        private val _connected = MutableStateFlow(false)
        /** True while the user has this service turned on and it's running. */
        val connected: StateFlow<Boolean> = _connected.asStateFlow()

        private val _tint = MutableStateFlow<Int?>(null)
        /** Latest colour of Samsung's status-bar icons, when known. */
        val tint: StateFlow<Int?> = _tint.asStateFlow()

        fun component(context: Context) = ComponentName(context, PillAccessibilityService::class.java)

        fun isEnabledInSettings(context: Context): Boolean {
            val list = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            val me = component(context)
            return list.orEmpty().split(':').any { ComponentName.unflattenFromString(it) == me }
        }

        /**
         * Turns the service on without a trip through Settings — possible only because the app
         * holds WRITE_SECURE_SETTINGS (the one-time adb grant). Other enabled services are kept.
         */
        fun enableWithSecureSettings(context: Context): Boolean {
            if (!SystemBatteryIcon.hasPermission(context)) return false
            return try {
                val cr = context.contentResolver
                val current = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                val me = component(context).flattenToString()
                val entries = current.orEmpty().split(':').filter { it.isNotBlank() }
                val next = if (entries.any { ComponentName.unflattenFromString(it) == component(context) }) {
                    current.orEmpty()
                } else {
                    (entries + me).joinToString(":")
                }
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next)
                Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                true
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't enable via secure settings", e)
                false
            }
        }
    }
}
