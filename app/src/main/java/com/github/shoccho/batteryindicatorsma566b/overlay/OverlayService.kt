package com.github.shoccho.batteryindicatorsma566b.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import com.github.shoccho.batteryindicatorsma566b.R
import com.github.shoccho.batteryindicatorsma566b.core.Anchor
import com.github.shoccho.batteryindicatorsma566b.core.BatterySnapshot
import com.github.shoccho.batteryindicatorsma566b.core.Box
import com.github.shoccho.batteryindicatorsma566b.core.OverlayGeometry
import com.github.shoccho.batteryindicatorsma566b.core.PillSettings
import com.github.shoccho.batteryindicatorsma566b.core.Placement
import com.github.shoccho.batteryindicatorsma566b.core.SettingsRepository
import com.github.shoccho.batteryindicatorsma566b.core.SystemBatteryIcon
import com.github.shoccho.batteryindicatorsma566b.core.WindowSpec
import com.github.shoccho.batteryindicatorsma566b.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * Keeps the indicator on screen: a foreground service owning one tiny, untouchable
 * TYPE_APPLICATION_OVERLAY window that sits under the (transparent) status bar.
 *
 * Touch safety: the window is FLAG_NOT_TOUCHABLE. While the status bar is showing, the status bar
 * window above us takes every touch in that strip anyway. When an app goes fullscreen we either
 * hide (window alpha 0) or cap the window alpha at 0.8 — Android only lets touches pass through
 * another app's overlay at or below that opacity, so games and video controls never get blocked.
 */
class OverlayService : Service() {

    private lateinit var repo: SettingsRepository
    private lateinit var wm: WindowManager
    private lateinit var power: PowerManager

    private var view: PillView? = null
    private var params: WindowManager.LayoutParams? = null
    private var scope: CoroutineScope? = null
    private var receiverRegistered = false

    private var battery: BatterySnapshot? = null
    private var statusBarVisible = true
    private var screenOn = true

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> onBattery(intent)
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED ->
                    registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let(::onBattery)
                Intent.ACTION_SCREEN_ON -> { screenOn = true; refreshWindow() }
                Intent.ACTION_SCREEN_OFF -> { screenOn = false; refreshWindow() }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        repo = SettingsRepository.get(this)
        wm = getSystemService(WindowManager::class.java)
        power = getSystemService(PowerManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always promote first: a service started with startForegroundService() that stops before
        // calling startForeground() crashes the app.
        goForeground()
        if (!repo.current.enabled || !Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (view == null) attach()
        return START_STICKY
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Battery pill", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Keeps the battery pill on screen. Safe to turn off."
                setShowBadge(false)
            },
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_pill)
            .setContentTitle("Battery pill is on")
            .setContentText("Tap to customise")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        try {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } catch (e: Exception) {
            // e.g. a sticky restart from the background. The overlay window itself still keeps the
            // process important, so carry on rather than crash.
            Log.w(TAG, "startForeground refused", e)
        }
    }

    private fun attach() {
        val v = PillView(this)
        v.setOnApplyWindowInsetsListener { _, insets ->
            val visible = insets.isVisible(WindowInsets.Type.statusBars())
            Log.d(TAG, "insets: statusBars visible=$visible top=${insets.getInsets(WindowInsets.Type.statusBars()).top}")
            _statusBarShown.value = visible
            if (visible != statusBarVisible) {
                statusBarVisible = visible
                refreshWindow()
            }
            insets
        }
        val p = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            title = "BatteryPill"
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
            windowAnimations = 0
        }
        view = v
        params = p
        screenOn = power.isInteractive
        applyLayout(p, repo.current)
        try {
            wm.addView(v, p)
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't add overlay", e)
            view = null
            params = null
            stopSelf()
            return
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)?.let(::onBattery)
        receiverRegistered = true

        scope = MainScope().also { s ->
            s.launch {
                // Re-evaluate when settings change, when the accessibility helper comes/goes,
                // and when it reports a new status-bar icon colour.
                combine(repo.state, PillAccessibilityService.connected, PillAccessibilityService.tint) { st, _, _ -> st }
                    .collect(::onSettings)
            }
        }
        _running.value = true
    }

    private fun onSettings(s: PillSettings) {
        if (!s.enabled) {
            stopSelf()
            return
        }
        SystemBatteryIcon.sync(this, repo, wantSystemIconHidden(s))
        SystemBatteryIcon.syncSlots(this, repo, hide = ownIconsActive(s))
        refreshWindow()
    }

    private fun onBattery(intent: Intent) {
        battery = BatterySnapshot.from(intent, power.isPowerSaveMode)
        view?.bind(repo.current, battery, isNight(), PillAccessibilityService.tint.value)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Rotation moves the camera cut-out; theme changes flip AUTO colours.
        refreshWindow()
    }

    private fun refreshWindow() {
        val v = view ?: return
        val p = params ?: return
        val s = repo.current
        applyLayout(p, s)
        v.bind(s, battery, isNight(), PillAccessibilityService.tint.value)
        v.setActive(screenOn && p.alpha > 0f)
        try {
            wm.updateViewLayout(v, p)
        } catch (e: Exception) {
            Log.w(TAG, "updateViewLayout failed", e)
        }
    }

    private fun applyLayout(p: WindowManager.LayoutParams, s: PillSettings) {
        val spec = OverlayGeometry.spec(s, resources.displayMetrics.density, currentCutout())
        p.width = if (spec.width == WindowSpec.MATCH_PARENT) WindowManager.LayoutParams.MATCH_PARENT else spec.width
        p.height = spec.height
        p.gravity = Gravity.TOP or when (spec.anchor) {
            Anchor.LEFT -> Gravity.LEFT
            Anchor.CENTER -> Gravity.CENTER_HORIZONTAL
            Anchor.RIGHT -> Gravity.RIGHT
        }
        p.x = spec.x
        p.y = spec.y

        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val hidden = coverActive(s) ||
            (s.hideInFullscreen && !statusBarVisible) || (s.hideInLandscape && landscape)
        // Under a visible status bar we can be "touchable": the status bar window above us takes
        // every touch in that strip, and Android only caps opacity of *untouchable* overlays at 0.8.
        // Anywhere apps can be touched (status bar hidden), go untouchable and stay within the cap.
        val underStatusBar = statusBarVisible && !hidden && spec.y + spec.height <= statusBarHeightPx()
        p.flags = if (underStatusBar) p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        else p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        var alpha = s.opacity / 100f
        if (!underStatusBar) alpha = min(alpha, MAX_PASS_THROUGH_ALPHA)
        p.alpha = if (hidden) 0f else alpha
    }

    private fun statusBarHeightPx(): Int =
        wm.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top

    private fun currentCutout(): Box? {
        val cutout = wm.currentWindowMetrics.windowInsets.displayCutout ?: return null
        val r = cutout.boundingRects.firstOrNull { !it.isEmpty } ?: return null
        return Box(r.left, r.top, r.right, r.bottom)
    }

    private fun isNight() =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** The accessibility helper draws ("Samsung's spot" / "Own icons"), so this window steps aside. */
    private fun coverActive(s: PillSettings) =
        s.placement != Placement.FREE && PillAccessibilityService.connected.value

    /**
     * Samsung's battery icon: must stay visible under the cover (it holds the rightmost slot);
     * hidden for "Own icons" (we draw the whole row); in free placement hidden only if asked;
     * otherwise left as the user had it.
     */
    private fun wantSystemIconHidden(s: PillSettings): Boolean? = when {
        coverActive(s) && s.placement == Placement.SAMSUNG_SPOT -> false
        coverActive(s) && s.placement == Placement.OWN_ICONS -> true
        s.hideSystemIcon -> true
        else -> null
    }

    private fun ownIconsActive(s: PillSettings) = coverActive(s) && s.placement == Placement.OWN_ICONS

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        if (receiverRegistered) {
            unregisterReceiver(receiver)
            receiverRegistered = false
        }
        view?.let { runCatching { wm.removeViewImmediate(it) } }
        view = null
        params = null
        SystemBatteryIcon.sync(this, repo, want = null)
        SystemBatteryIcon.syncSlots(this, repo, hide = false)
        _running.value = false
        super.onDestroy()
    }

    companion object {
        private const val TAG = "OverlayService"
        private const val CHANNEL_ID = "pill"
        private const val NOTIFICATION_ID = 1

        /** Highest overlay opacity Android still lets touches pass through (untrusted-touch rule). */
        private const val MAX_PASS_THROUGH_ALPHA = 0.8f

        private val _statusBarShown = MutableStateFlow(true)
        /**
         * Whether the status bar is on screen (false in full-screen apps). Only windows *below* the
         * status bar learn this, so the accessibility cover borrows it from our app overlay.
         */
        val statusBarShown: StateFlow<Boolean> = _statusBarShown.asStateFlow()

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

        /** Returns false when Android refuses to start us right now (e.g. from the background). */
        fun start(context: Context): Boolean = try {
            context.startForegroundService(Intent(context, OverlayService::class.java))
            true
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't start overlay service", e)
            false
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }
}
