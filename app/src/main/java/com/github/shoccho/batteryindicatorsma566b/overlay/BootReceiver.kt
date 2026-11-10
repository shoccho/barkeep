package com.github.shoccho.batteryindicatorsma566b.overlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.github.shoccho.batteryindicatorsma566b.core.SettingsRepository
import com.github.shoccho.batteryindicatorsma566b.core.SystemBatteryIcon

/** Brings the pill back after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val repo = SettingsRepository.get(context)
        val s = repo.current
        val shouldRun = s.enabled && Settings.canDrawOverlays(context) &&
            (s.startOnBoot || action == Intent.ACTION_MY_PACKAGE_REPLACED)

        if (shouldRun) {
            OverlayService.start(context)
        } else {
            // Pill isn't coming back: put Samsung's battery icon back the way the user had it.
            SystemBatteryIcon.sync(context, repo, want = null)
        }
    }
}
