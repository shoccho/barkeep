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
import com.github.shoccho.batteryindicatorsma566b.core.Placement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
    /** This instance is connected and hasn't been torn down. */
    private var live = false
    private var lastShotAt = 0L
    private var shotPending = false
    private val rect = Rect()
    private var lastStatusBarBattery: Box? = null
    private var lastLogged: List<Box>? = null
    private lateinit var keyguard: KeyguardManager

    // "Own icons" row
    private var rowView: StatusRowView? = null
    private var rowParams: WindowManager.LayoutParams? = null
    private var monitor: StatusMonitor? = null
    private var monitorJob: Job? = null
    private var rowCenterY: Int? = null

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
                    handler.removeCallbacks(pollRunnable)
                    scheduleScan(0)
                }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    screenOn = true
                    scheduleScan(0)
                    tintSoon()
                    startTintPoll()
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
                    tintSoon()
                    startTintPoll()
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
        live = true
        current = this
        _connected.value = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                scheduleScan(60)
                tintSoon()
            }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                scheduleScan(40)
                tintSoon()
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (event.packageName == SYSUI) scheduleScan(60)
                // An app changing what's behind the status bar (launcher → app drawer, a dimmed
                // backdrop, a scrolled page) flips Samsung's icons without any window change.
                else tintSoon(now = false)
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> tintSoon(now = false)
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

    private fun wantCover(s: PillSettings) = s.enabled && s.placement == Placement.SAMSUNG_SPOT && screenOn

    private fun scan() {
        val s = repo.current
        if (s.enabled && s.placement == Placement.OWN_ICONS) {
            startMonitor()
            if (screenOn) syncRow(s) else removeRow()
        } else {
            removeRow()
            stopMonitor()
        }
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
        rowView?.bind(s, battery, isNight(), _tint.value)
    }

    // ---- Own icons ----------------------------------------------------------------------------

    private fun startMonitor() {
        if (monitor != null) return
        val m = StatusMonitor(this).also { it.start() }
        monitor = m
        monitorJob = scope?.launch {
            m.state.collect { st ->
                rowView?.let { v ->
                    v.status = st
                    layoutRow(repo.current, v)
                }
            }
        }
    }

    private fun stopMonitor() {
        monitorJob?.cancel()
        monitorJob = null
        monitor?.stop()
        monitor = null
    }

    private fun syncRow(s: PillSettings) {
        val v = rowView ?: addRow() ?: return
        v.bind(s, battery, isNight(), _tint.value)
        monitor?.let { v.status = it.state.value }
        layoutRow(s, v)
    }

    private fun addRow(): StatusRowView? {
        val view = StatusRowView(this)
        val params = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            title = "BatteryPillRow"
            gravity = Gravity.TOP or Gravity.RIGHT
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
            windowAnimations = 0
            alpha = 0f
        }
        return try {
            wm.addView(view, params)
            rowView = view
            rowParams = params
            view
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't add row", e)
            null
        }
    }

    private fun removeRow() {
        rowView?.let { runCatching { wm.removeViewImmediate(it) } }
        rowView = null
        rowParams = null
        monitor?.setPolling(false)
    }

    /** Right-anchored, vertically centred on the status bar clock; hidden in full screen / open shade. */
    private fun layoutRow(s: PillSettings, v: StatusRowView) {
        val p = rowParams ?: return
        val locked = keyguard.isKeyguardLocked
        val visible = (statusBarVisible || locked) && !(!locked && shadeExpanded())
        val w = v.preferredWidth()
        val h = v.preferredHeight()
        val cy = rowCenter()
        val x = (s.rowEndMarginDp * resources.displayMetrics.density).toInt()
        val alpha = if (visible) s.opacity / 100f else 0f
        val changed = p.width != w || p.height != h || p.x != x || p.y != cy - h / 2 || p.alpha != alpha
        p.width = w
        p.height = h
        p.x = x
        p.y = cy - h / 2
        p.alpha = alpha
        v.setActive(visible && screenOn)
        monitor?.setPolling(visible && screenOn && s.rowActivityArrows)
        if (changed) runCatching { wm.updateViewLayout(v, p) }
    }

    /** Status bar icon centre line: the clock's centre when visible, else the camera hole's. */
    private fun rowCenter(): Int {
        findNodes(CLOCK_ID).firstOrNull { it.top < statusBarHeightPx() }?.let { rowCenterY = it.centerY }
        return rowCenterY ?: run {
            val cut = wm.currentWindowMetrics.windowInsets.displayCutout?.boundingRects?.firstOrNull { !it.isEmpty }
            cut?.centerY() ?: (statusBarHeightPx() / 2)
        }
    }

    /** A full-width, tall SystemUI window means the notification shade / quick panel is open. */
    private fun shadeExpanded(): Boolean {
        val sb = statusBarHeightPx()
        val screenW = wm.currentWindowMetrics.bounds.width()
        return try {
            windows.any { w ->
                if (w.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) return@any false
                w.getBoundsInScreen(rect)
                val pkg = w.root?.packageName
                pkg == SYSUI && rect.height() > sb * 3 && rect.width() >= screenW * 0.9f
            }
        } catch (e: Exception) {
            false
        }
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

    // A "burst" samples as fast as Android allows (~3/s) until things stop changing, so our icons
    // follow SystemUI's colour fade step by step instead of waiting for it to finish.
    private var burstStart = 0L
    private var burstUntil = 0L
    private var burstFromContent = false
    private var burstSawChange = false
    /** Content-only bursts that found nothing back off, so a busy app doesn't keep us shooting. */
    private var contentCooldownUntil = 0L
    private var contentCooldownMs = CONTENT_COOLDOWN_MIN_MS

    private val burstRunnable = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            if (now >= burstUntil) {
                if (burstFromContent && !burstSawChange) {
                    contentCooldownUntil = now + contentCooldownMs
                    contentCooldownMs = (contentCooldownMs * 2).coerceAtMost(CONTENT_COOLDOWN_MAX_MS)
                }
                burstUntil = 0L
                return
            }
            requestTint()
            handler.postDelayed(this, SHOT_INTERVAL_MS)
        }
    }

    /**
     * Something on screen changed. Window changes sample at once and keep sampling for a moment
     * after the last event; app content changes / scrolls (launcher → app drawer) do the same,
     * but a burst they start is capped, and backs off when it finds the colour unchanged.
     */
    private fun tintSoon(now: Boolean = true) {
        val t = SystemClock.uptimeMillis()
        if (now) {
            contentCooldownUntil = 0L
            contentCooldownMs = CONTENT_COOLDOWN_MIN_MS
        } else if (t < contentCooldownUntil) {
            return
        }
        val idle = burstUntil == 0L
        if (idle) {
            burstStart = t
            burstFromContent = !now
            burstSawChange = false
        } else if (now) {
            burstFromContent = false
        }
        val cap = burstStart + if (burstFromContent) CONTENT_BURST_MAX_MS else WINDOW_BURST_MAX_MS
        burstUntil = maxOf(burstUntil, minOf(t + BURST_TAIL_MS, cap))
        if (idle) {
            requestTint()
            handler.postDelayed(burstRunnable, SHOT_INTERVAL_MS)
        }
    }

    /** Safety net for changes no event reports (live wallpapers, video behind the bar). */
    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!screenOn || repo.current.colorMode != ColorMode.MATCH || !repo.current.enabled) return
            if (statusBarVisible) requestTint()
            handler.postDelayed(this, POLL_MS)
        }
    }

    private fun startTintPoll() {
        handler.removeCallbacks(pollRunnable)
        handler.postDelayed(pollRunnable, POLL_MS)
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
                    burstSawChange = true
                    Log.d(TAG, "status bar icon colour: #%08X".format(sampled))
                    _tint.value = sampled
                    rebind()
                }
            }

            override fun onFailure(errorCode: Int) {
                if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT && !shotPending) {
                    // Asked a hair too early for Android's limit; try again shortly.
                    shotPending = true
                    handler.postDelayed(tintRunnable, RETRY_MS)
                } else {
                    Log.d(TAG, "screenshot failed: $errorCode")
                }
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
        if (!live) return
        live = false
        // On a quick rebind the old instance can be destroyed after the new one connected; only
        // the current instance may report the service as gone.
        if (current === this) {
            current = null
            _connected.value = false
            _tint.value = null
        }
        scope?.cancel()
        scope = null
        handler.removeCallbacksAndMessages(null)
        burstUntil = 0L
        shotPending = false
        runCatching { unregisterReceiver(receiver) }
        clearCovers()
        removeRow()
        stopMonitor()
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
        /** Android allows an accessibility screenshot every 333 ms. */
        private const val SHOT_INTERVAL_MS = 340L
        private const val RETRY_MS = 60L
        /** Keep sampling this long after the last event (covers SystemUI's colour fade). */
        private const val BURST_TAIL_MS = 1000L
        private const val WINDOW_BURST_MAX_MS = 3000L
        private const val CONTENT_BURST_MAX_MS = 1600L
        private const val CONTENT_COOLDOWN_MIN_MS = 1500L
        private const val CONTENT_COOLDOWN_MAX_MS = 12_000L
        private const val POLL_MS = 4000L

        /** The connected instance, if any. Main thread only. */
        private var current: PillAccessibilityService? = null

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
