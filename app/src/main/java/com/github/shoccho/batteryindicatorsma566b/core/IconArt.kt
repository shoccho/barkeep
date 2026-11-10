package com.github.shoccho.batteryindicatorsma566b.core

import android.graphics.drawable.Drawable

/**
 * Source of ready-made status icon drawables, looked up by name. When a lookup returns null the
 * row falls back to its own drawn glyph, so the row works on any phone.
 */
interface IconArt {
    fun drawable(name: String): Drawable?
}

/**
 * Which of Samsung's SystemUI drawables (by resource name) stand for each icon state — the same
 * ones One UI 9's own `SamsungWifiIcons` / `SamsungMobileIcons` mappers pick. Only the names live
 * in this app; the drawables are loaded from the phone at runtime.
 */
object SamsungArtNames {
    const val SILENT = "sec_stat_sys_ringer_silent"
    const val VIBRATE = "sec_stat_sys_ringer_vibrate"
    const val BLUETOOTH = "sec_stat_sys_data_bluetooth"
    const val BLUETOOTH_CONNECTED = "sec_stat_sys_data_bluetooth_connected"
    const val ALARM = "stat_sys_alarm"
    const val DND = "stat_sys_do_not_disturb_mode"
    const val VPN = "stat_sys_vpn_ic"
    const val HOTSPOT = "sec_stat_sys_hotspot"
    const val DATA_SAVER = "sec_stat_sys_data_saver"
    const val AIRPLANE = "samsung_stat_sys_airplane_mode"
    const val ROAMING = "stat_sys_signal_roaming"
    /** Lives in Samsung's NFC app, not SystemUI. */
    const val NFC = "nfc:stat_sys_nfc_on"

    fun wifi(level: Int, generation: Int?, sixGhz: Boolean, validated: Boolean, arrows: Boolean): String {
        val l = level.coerceIn(0, 4)
        return when {
            !validated -> "stat_sys_wifi_captive_$l"
            generation == 6 && sixGhz -> "stat_sys_6ewifi_signal_$l"
            generation in 5..7 -> "stat_sys_wifi${generation}_signal_$l"
            !arrows -> "stat_sys_wifi_signal_pure_$l"
            else -> "stat_sys_wifi_signal_$l"
        }
    }

    fun wifiActivity(inActive: Boolean, outActive: Boolean) = when {
        inActive && outActive -> "stat_sys_wifi_signal_inout"
        inActive -> "stat_sys_wifi_signal_in"
        outActive -> "stat_sys_wifi_signal_out"
        else -> "stat_sys_wifi_signal_no_inout"
    }

    /** [sim] is 0-based; dual-SIM phones get Samsung's numbered bars. */
    fun mobileBars(level: Int, inService: Boolean, sim: Int, simCount: Int): String {
        val l = level.coerceIn(0, 4)
        return when {
            !inService -> "stat_sys_signal_null_4"
            simCount > 1 -> "stat_sys_signal_dual${sim + 1}_$l"
            else -> "stat_sys_signal_$l"
        }
    }

    fun networkType(label: String): String? = when (label) {
        "5G" -> "datatype_5g"
        "4G+" -> "datatype_4gplus"
        "4G" -> "datatype_4g"
        "3G" -> "datatype_3g"
        "H" -> "datatype_h"
        "H+" -> "datatype_hplus"
        "E" -> "datatype_e"
        "G" -> "datatype_g"
        else -> null
    }

    fun dataActivity(inActive: Boolean, outActive: Boolean) = when {
        inActive && outActive -> "stat_sys_data_inout"
        inActive -> "stat_sys_data_in"
        outActive -> "stat_sys_data_out"
        else -> "stat_sys_data_no_inout"
    }
}
