package com.github.shoccho.batteryindicatorsma566b.core

/** Status-bar icons the app can draw itself in "Own icons" placement. */
enum class StatusIcon(val label: String, val defaultOn: Boolean) {
    ALARM("Alarm", false),
    BLUETOOTH("Bluetooth", false),
    NFC("NFC", false),
    VPN("VPN", true),
    HOTSPOT("Hotspot", true),
    DATA_SAVER("Data saver", true),
    DND("Do not disturb", true),
    RINGER("Sound (mute / vibrate)", true),
    AIRPLANE("Airplane mode", true),
    WIFI("Wi-Fi", true),
    MOBILE("Mobile signal", true);

    companion object {
        /** Left-to-right default order, Samsung-like; the battery always sits to the right of all of them. */
        val DEFAULT_ORDER: List<StatusIcon> = entries.toList()

        /** Keeps a stored order valid: drops unknown names, appends icons added in newer versions. */
        fun normalise(order: List<StatusIcon>): List<StatusIcon> = order.distinct() + entries.filter { it !in order }

        fun parseOrder(raw: String?): List<StatusIcon> =
            normalise(raw.orEmpty().split(',').mapNotNull { n -> entries.firstOrNull { it.name == n } })
    }
}

enum class RingerState { NORMAL, VIBRATE, SILENT }

data class WifiState(
    val connected: Boolean = false,
    /** 0..4 */
    val level: Int = 0,
    /** Wi-Fi generation shown as a small digit (5, 6, 7), or null. */
    val generation: Int? = null,
    /** On a 6 GHz channel (Wi-Fi 6E). */
    val sixGhz: Boolean = false,
    val validated: Boolean = true,
)

data class MobileState(
    /** 0..4 */
    val level: Int = 0,
    val inService: Boolean = true,
    /** "5G", "LTE", "4G+", "3G"… shown only while mobile carries the internet. */
    val typeLabel: String? = null,
    /**
     * 5G is on offer but the phone is on LTE (5G NSA). One UI draws this as an outlined "5G" box
     * and a filled box once the phone is really on 5G. Android doesn't tell apps whether an NSA
     * link is carrying data on 5G, so every NSA state counts as "available".
     */
    val fiveGAvailableOnly: Boolean = false,
    val roaming: Boolean = false,
)

/** Everything the "Own icons" row needs, in one snapshot. */
data class StatusState(
    val ringer: RingerState = RingerState.NORMAL,
    val dnd: Boolean = false,
    val airplane: Boolean = false,
    val wifi: WifiState = WifiState(),
    /** One entry per active SIM. */
    val mobile: List<MobileState> = listOf(MobileState()),
    /** Which network carries the internet right now. */
    val mobileIsDefault: Boolean = false,
    val activityIn: Boolean = false,
    val activityOut: Boolean = false,
    val bluetoothOn: Boolean = false,
    val bluetoothConnected: Boolean = false,
    val nfc: Boolean = false,
    val alarm: Boolean = false,
    val hotspot: Boolean = false,
    val vpn: Boolean = false,
    val dataSaver: Boolean = false,
) {
    /** Whether [icon] has anything to show right now. */
    fun shows(icon: StatusIcon): Boolean = when (icon) {
        StatusIcon.ALARM -> alarm
        StatusIcon.BLUETOOTH -> bluetoothOn
        StatusIcon.NFC -> nfc
        StatusIcon.VPN -> vpn
        StatusIcon.HOTSPOT -> hotspot
        StatusIcon.DATA_SAVER -> dataSaver
        StatusIcon.DND -> dnd
        StatusIcon.RINGER -> ringer != RingerState.NORMAL
        StatusIcon.AIRPLANE -> airplane
        StatusIcon.WIFI -> wifi.connected
        StatusIcon.MOBILE -> !airplane && mobile.isNotEmpty()
    }

    companion object {
        /** Sample used by the in-app preview. */
        val SAMPLE = StatusState(
            ringer = RingerState.SILENT,
            wifi = WifiState(connected = true, level = 4, generation = 6),
            mobile = listOf(MobileState(level = 2)),
            activityIn = true,
        )
    }
}

/**
 * SystemUI slot names hidden in "Own icons" placement (the app redraws what it can detect).
 * Unknown names are harmless — SystemUI just ignores them.
 */
val OWN_ICONS_SLOTS: List<String> = listOf(
    "volume", "mute", "zen", "wifi", "mobile", "stacked_mobile", "ims_volte", "call_strength",
    "airplane", "bluetooth", "bluetooth_connected", "nfc", "alarm_clock", "hotspot", "bt_tethering",
    "vpn", "data_saver", "location", "screen_record", "managed_profile", "satellite",
    "connected_display", "sensors_off", "camera", "microphone", "cast", "ethernet",
)
