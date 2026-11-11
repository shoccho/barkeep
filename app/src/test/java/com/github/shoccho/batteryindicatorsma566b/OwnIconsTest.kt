package com.github.shoccho.batteryindicatorsma566b

import com.github.shoccho.batteryindicatorsma566b.core.IconBlacklist
import com.github.shoccho.batteryindicatorsma566b.core.OWN_ICONS_SLOTS
import com.github.shoccho.batteryindicatorsma566b.core.RingerState
import com.github.shoccho.batteryindicatorsma566b.core.SamsungArtNames
import com.github.shoccho.batteryindicatorsma566b.core.StatusIcon
import com.github.shoccho.batteryindicatorsma566b.core.StatusState
import com.github.shoccho.batteryindicatorsma566b.core.WifiState
import com.github.shoccho.batteryindicatorsma566b.overlay.StatusMonitor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnIconsTest {
    // The phone's real list (user's own QuickStar entries) — these must survive any round trip.
    private val phone = "rotate,headset,fuseboxon,rotate,headset,left_clock_position,slimindicator_show_date"

    @Test fun hidingSlotsRecordsOnlyWhatWasAdded() {
        val (next, added) = IconBlacklist.addAll(phone, OWN_ICONS_SLOTS)
        assertTrue(next.startsWith(phone))
        assertEquals(OWN_ICONS_SLOTS.toSet(), added)
        // User's pre-existing "rotate"/"headset" are not claimed by us.
        val (_, addedAgain) = IconBlacklist.addAll("$phone,wifi", OWN_ICONS_SLOTS)
        assertFalse("wifi" in addedAgain)
    }

    @Test fun unhidingRestoresTheOriginalExactly() {
        val (hidden, added) = IconBlacklist.addAll(phone, OWN_ICONS_SLOTS)
        assertEquals(phone, IconBlacklist.removeAll(hidden, added))
    }

    @Test fun unhidingLeavesSlotsTheUserHadHidden() {
        val mine = "$phone,wifi"
        val (hidden, added) = IconBlacklist.addAll(mine, OWN_ICONS_SLOTS)
        assertEquals(mine, IconBlacklist.removeAll(hidden, added))
    }

    @Test fun orderParsingIsForgiving() {
        assertEquals(StatusIcon.DEFAULT_ORDER, StatusIcon.parseOrder(null))
        val custom = StatusIcon.parseOrder("MOBILE,WIFI,BOGUS,MOBILE")
        assertEquals(listOf(StatusIcon.MOBILE, StatusIcon.WIFI), custom.take(2))
        assertEquals(StatusIcon.entries.toSet(), custom.toSet())
        assertEquals(StatusIcon.entries.size, custom.size)
    }

    @Test fun onlyActiveIconsShow() {
        val st = StatusState(ringer = RingerState.NORMAL, wifi = WifiState(connected = false), airplane = true)
        assertFalse(st.shows(StatusIcon.RINGER))
        assertFalse(st.shows(StatusIcon.WIFI))
        assertFalse(st.shows(StatusIcon.MOBILE))          // airplane hides signal
        assertTrue(st.shows(StatusIcon.AIRPLANE))
        assertTrue(StatusState(ringer = RingerState.VIBRATE).shows(StatusIcon.RINGER))
    }

    @Test fun wifiGenerationDigits() {
        assertEquals(6, StatusMonitor.wifiGeneration(6))  // 802.11ax
        assertEquals(7, StatusMonitor.wifiGeneration(8))  // 802.11be
        assertNull(StatusMonitor.wifiGeneration(1))       // legacy
    }

    @Test fun networkTypeUsesOneUisFullSizeLabelsFirst() {
        // One UI 9 draws "5G" as an outlined box while 5G is only available (NSA)…
        assertEquals("stat_sys_data_connected_5g_available", SamsungArtNames.networkType("5G", fiveGAvailableOnly = true).first())
        // …and as a filled box once connected; the small corner art is only a fallback.
        assertEquals(listOf("stat_sys_data_connected_5g", "datatype_5g"), SamsungArtNames.networkType("5G"))
        assertEquals("stat_sys_data_connected_4g_plus", SamsungArtNames.networkType("4G+").first())
        assertEquals("stat_sys_data_connected_h_plus", SamsungArtNames.networkType("H+").first())
        // Only 5G has an "available" variant.
        assertEquals("stat_sys_data_connected_4g", SamsungArtNames.networkType("4G", fiveGAvailableOnly = true).first())
        assertTrue(SamsungArtNames.networkType("?").isEmpty())
    }
}
