package com.github.shoccho.batteryindicatorsma566b

import com.github.shoccho.batteryindicatorsma566b.core.Box
import com.github.shoccho.batteryindicatorsma566b.core.ColorMode
import com.github.shoccho.batteryindicatorsma566b.core.IconOwnership
import com.github.shoccho.batteryindicatorsma566b.core.OverlayGeometry
import com.github.shoccho.batteryindicatorsma566b.core.PillColors
import com.github.shoccho.batteryindicatorsma566b.core.PillSettings
import com.github.shoccho.batteryindicatorsma566b.core.TintSampler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverModeTest {

    // ---- who owns Samsung's icon visibility -------------------------------------------------

    @Test fun hideThenRestoreWhenWeHidIt() {
        val hide = IconOwnership().decide(want = true, hiddenNow = false)
        assertEquals(true, hide.setHidden)
        val restore = hide.next.decide(want = null, hiddenNow = true)
        assertEquals(false, restore.setHidden)
        assertEquals(IconOwnership(), restore.next)
    }

    @Test fun coverShowsAUserHiddenIconAndRestoresTheHide() {
        // User hid it in QuickStar; Samsung's-spot mode needs it visible; turning off hides it again.
        val show = IconOwnership().decide(want = false, hiddenNow = true)
        assertEquals(false, show.setHidden)
        assertEquals(IconOwnership(weShowed = true), show.next)
        val restore = show.next.decide(want = null, hiddenNow = false)
        assertEquals(true, restore.setHidden)
        assertEquals(IconOwnership(), restore.next)
    }

    @Test fun switchingFreeHideToCoverUndoesOurHide() {
        val hid = IconOwnership(weHid = true)
        val toCover = hid.decide(want = false, hiddenNow = true)
        assertEquals(false, toCover.setHidden)
        assertEquals(IconOwnership(), toCover.next)
        // ...and nothing left to restore afterwards.
        assertNull(toCover.next.decide(want = null, hiddenNow = false).setHidden)
    }

    @Test fun neverTouchesWhatItDidNotChange() {
        assertNull(IconOwnership().decide(want = null, hiddenNow = true).setHidden)
        assertNull(IconOwnership().decide(want = null, hiddenNow = false).setHidden)
        assertNull(IconOwnership().decide(want = true, hiddenNow = true).setHidden)
        assertNull(IconOwnership(weHid = true).decide(want = true, hiddenNow = null).setHidden)
    }

    // ---- cover geometry (A56: battery node [955,24][1034,91], density 3) --------------------

    @Test fun coverCentresOnSamsungsCapsuleAndOverlapsIt() {
        val node = Box(955, 24, 1034, 91)
        val c = OverlayGeometry.cover(node, PillSettings(coverExtraWidthDp = 1f, coverExtraHeightDp = 1f), 3f)
        assertEquals(node.width + 3, c.width)
        assertEquals(39, c.height)                       // (12 + 1) dp
        assertTrue(c.left < node.left && c.right > node.right)
        assertEquals(node.centerY, c.top + c.height / 2)
    }

    // ---- colours ------------------------------------------------------------------------

    @Test fun matchFallsBackToThemeUntilKnown() {
        assertEquals(PillColors.BLACK, PillColors.base(ColorMode.MATCH, 0, night = false, matched = null))
        assertEquals(0xFF202020.toInt(), PillColors.base(ColorMode.MATCH, 0, night = true, matched = 0xFF202020.toInt()))
    }

    @Test fun mixIsOpaqueAndLinear() {
        assertEquals(0xFF808080.toInt(), PillColors.mix(PillColors.BLACK, PillColors.WHITE, 128 / 255f))
        assertEquals(PillColors.WHITE, PillColors.mix(PillColors.BLACK, PillColors.WHITE, 1f))
    }

    @Test fun samplerFindsDarkIconsOnLightBackground() {
        val px = IntArray(400) { if (it % 5 == 0) 0xFF111111.toInt() else 0xFFDDE6F0.toInt() }
        assertEquals(0xFF111111.toInt(), TintSampler.pick(px))
    }

    @Test fun samplerFindsWhiteIconsOnDarkPhoto() {
        val px = IntArray(400) { i ->
            when {
                i % 6 == 0 -> 0xFFFFFFFF.toInt()
                i % 2 == 0 -> 0xFF3A3530.toInt()
                else -> 0xFF4A443E.toInt()
            }
        }
        assertEquals(0xFFFFFFFF.toInt(), TintSampler.pick(px))
    }

    @Test fun samplerGivesUpOnFlatRegions() {
        assertNull(TintSampler.pick(IntArray(400) { 0xFF777777.toInt() }))
    }
}
