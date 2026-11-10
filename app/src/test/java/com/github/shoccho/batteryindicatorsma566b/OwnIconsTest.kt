package com.github.shoccho.batteryindicatorsma566b

import com.github.shoccho.batteryindicatorsma566b.core.IconBlacklist
import com.github.shoccho.batteryindicatorsma566b.core.OWN_ICONS_SLOTS
import com.github.shoccho.batteryindicatorsma566b.core.RingerState
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
}
