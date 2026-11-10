package com.github.shoccho.batteryindicatorsma566b.overlay

import android.app.PendingIntent
import android.content.Intent
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.github.shoccho.batteryindicatorsma566b.core.SettingsRepository
import com.github.shoccho.batteryindicatorsma566b.ui.MainActivity

/** Quick Settings tile: one tap turns the pill on or off. */
class PillTileService : TileService() {

    override fun onStartListening() {
        refresh()
    }

    override fun onClick() {
        val repo = SettingsRepository.get(this)
        val turnOn = !repo.current.enabled
        if (turnOn && !Settings.canDrawOverlays(this)) {
            openApp()
            return
        }
        repo.update { it.copy(enabled = turnOn) }
        if (turnOn) {
            if (!OverlayService.start(this)) openApp()
        } else {
            OverlayService.stop(this)
        }
        refresh()
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val on = SettingsRepository.get(this).current.enabled
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = if (on) "On" else "Off"
        tile.updateTile()
    }
}
