package com.github.shoccho.batteryindicatorsma566b

import com.github.shoccho.batteryindicatorsma566b.core.IconBlacklist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IconBlacklistTest {
    // Real value read from the Galaxy A56 (QuickStar writes duplicates; we must keep them).
    private val phone = "rotate,headset,rotate,headset,battery_icon,left_clock_position,slimindicator_show_date,fuseboxon"
    private val phoneWithoutBattery = "rotate,headset,rotate,headset,left_clock_position,slimindicator_show_date,fuseboxon"

    @Test fun detectsBatterySlot() {
        assertTrue(IconBlacklist.contains(phone))
        assertFalse(IconBlacklist.contains(phoneWithoutBattery))
        assertFalse(IconBlacklist.contains(null))
        assertFalse(IconBlacklist.contains("battery"))
    }

    @Test fun removeKeepsEverythingElseInOrder() {
        assertEquals(phoneWithoutBattery, IconBlacklist.remove(phone))
    }

    @Test fun addAppendsOnceAndIsIdempotent() {
        val added = IconBlacklist.add(phoneWithoutBattery)
        assertEquals("$phoneWithoutBattery,battery_icon", added)
        assertEquals(added, IconBlacklist.add(added))
    }

    @Test fun handlesEmptyAndNull() {
        assertEquals("battery_icon", IconBlacklist.add(null))
        assertEquals("battery_icon", IconBlacklist.add(""))
        assertEquals("", IconBlacklist.remove("battery_icon"))
        assertEquals("", IconBlacklist.remove(null))
    }

    @Test fun roundTripRestoresOriginal() {
        assertEquals(phoneWithoutBattery, IconBlacklist.remove(IconBlacklist.add(phoneWithoutBattery)))
    }
}
