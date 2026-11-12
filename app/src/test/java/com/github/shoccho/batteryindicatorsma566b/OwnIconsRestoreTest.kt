package com.github.shoccho.batteryindicatorsma566b

import com.github.shoccho.batteryindicatorsma566b.core.IconBlacklist
import com.github.shoccho.batteryindicatorsma566b.core.IconOwnership
import com.github.shoccho.batteryindicatorsma566b.core.IconPlan
import com.github.shoccho.batteryindicatorsma566b.core.OWN_ICONS_SLOTS
import com.github.shoccho.batteryindicatorsma566b.core.PillSettings
import com.github.shoccho.batteryindicatorsma566b.core.Placement
import com.github.shoccho.batteryindicatorsma566b.core.effectivePlacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Leaving "Own icons" must always bring Samsung's icons back — even when the app's own record is gone. */
class OwnIconsRestoreTest {
    // The Galaxy A56's own entries (QuickStar duplicates included); they must survive every round trip.
    private val phone = "rotate,headset,fuseboxon,rotate,headset,left_clock_position,slimindicator_show_date"

    // Exactly what the phone held when the bug was reported: an older build's slot run (no marker),
    // Samsung's battery hidden for free placement, and an empty record of what the app hid.
    private val stuck = "$phone,volume,mute,zen,wifi,mobile,stacked_mobile,ims_volte,call_strength,airplane," +
        "bluetooth,bluetooth_connected,nfc,alarm_clock,hotspot,bt_tethering,vpn,data_saver,location," +
        "screen_record,managed_profile,satellite,connected_display,sensors_off,camera,microphone,cast," +
        "ethernet,battery_icon"

    private fun enter(current: String?, record: Set<String> = emptySet(), own: IconOwnership = IconOwnership()) =
        IconPlan.of(current, own, wantBatteryHidden = true, ownIcons = true, weHidSlots = record)

    private fun leave(current: String?, record: Set<String>, own: IconOwnership, wantBattery: Boolean? = null) =
        IconPlan.of(current, own, wantBatteryHidden = wantBattery, ownIcons = false, weHidSlots = record)

    @Test fun roundTripRestoresTheListExactly() {
        val on = enter(phone)
        assertTrue(IconBlacklist.contains(on.value))
        assertTrue(on.value.contains(IconBlacklist.OWN_ICONS_MARKER))
        assertEquals(OWN_ICONS_SLOTS.toSet(), on.weHidSlots)

        val off = leave(on.value, on.weHidSlots, on.ownership)
        assertEquals(phone, off.value)
        assertTrue(off.weHidSlots.isEmpty())
    }

    @Test fun lostRecordStillRestoresThroughTheMarker() {
        val on = enter(phone)
        // Reinstall / data wipe / another build: the record is gone, only the phone's list remains.
        val off = leave(on.value, record = emptySet(), own = IconOwnership(weHid = true))
        assertEquals(phone, off.value)
    }

    @Test fun slotsTheUserHadHiddenStayHiddenEvenWithoutARecord() {
        val mine = "$phone,wifi,nfc"
        val on = enter(mine)
        assertFalse("wifi" in on.weHidSlots)
        val off = leave(on.value, record = emptySet(), own = on.ownership)
        assertEquals(mine, off.value)
    }

    @Test fun theReportedStuckListIsRepairedWhenLeaving() {
        // Free placement with "Hide Samsung's battery" on: battery stays hidden, everything else returns.
        val fixed = leave(stuck, record = emptySet(), own = IconOwnership(weHid = true), wantBattery = true)
        assertEquals("$phone,battery_icon", fixed.value)
    }

    @Test fun enteringOverALeftoverRunAdoptsItSoLeavingRestoresIt() {
        // The original bug: the slots were already there, so nothing was recorded and nothing came back.
        val on = enter(stuck, own = IconOwnership(weHid = true))
        assertEquals(OWN_ICONS_SLOTS.toSet(), on.weHidSlots)
        val off = leave(on.value, on.weHidSlots, on.ownership)
        assertEquals(phone, off.value)
    }

    @Test fun shortRunsOfOurSlotNamesAreNotMistakenForOurs() {
        assertTrue(IconBlacklist.ownIconsBlock("$phone,wifi,mobile,nfc", OWN_ICONS_SLOTS).isEmpty())
        // Long enough but out of our order: also not ours.
        assertTrue(IconBlacklist.ownIconsBlock("$phone," + OWN_ICONS_SLOTS.reversed().joinToString(","), OWN_ICONS_SLOTS).isEmpty())
    }

    @Test fun batteryAndSlotsChangeInOneWrite() {
        // From free placement with Samsung's battery visible: one value hides both.
        val on = enter(phone)
        assertTrue(IconBlacklist.contains(on.value))
        assertTrue(OWN_ICONS_SLOTS.all { IconBlacklist.contains(on.value, it) })
        assertEquals(IconOwnership(weHid = true), on.ownership)
    }

    @Test fun nothingToDoLeavesTheValueUntouched() {
        val plan = leave(phone, record = emptySet(), own = IconOwnership())
        assertEquals(phone, plan.value)
        val on = enter(phone)
        assertEquals(on.value, enter(on.value, on.weHidSlots, on.ownership).value)
    }
}

class OwnIconsNeedsGrantTest {
    @Test fun ownIconsRunsAsFreeUntilTheAdbGrantArrives() {
        val own = PillSettings(placement = Placement.OWN_ICONS)
        assertEquals(Placement.FREE, own.effectivePlacement(canHideIcons = false))
        assertEquals(Placement.OWN_ICONS, own.effectivePlacement(canHideIcons = true))
        // The saved choice isn't touched, and the other placements don't depend on the grant.
        assertEquals(Placement.OWN_ICONS, own.placement)
        assertEquals(Placement.SAMSUNG_SPOT, PillSettings(placement = Placement.SAMSUNG_SPOT).effectivePlacement(false))
    }
}
