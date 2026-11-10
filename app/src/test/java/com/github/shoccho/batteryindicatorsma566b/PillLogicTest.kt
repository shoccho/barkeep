package com.github.shoccho.batteryindicatorsma566b

import com.github.shoccho.batteryindicatorsma566b.core.Anchor
import com.github.shoccho.batteryindicatorsma566b.core.BatterySnapshot
import com.github.shoccho.batteryindicatorsma566b.core.Box
import com.github.shoccho.batteryindicatorsma566b.core.ColorMode
import com.github.shoccho.batteryindicatorsma566b.core.OverlayGeometry
import com.github.shoccho.batteryindicatorsma566b.core.PillColors
import com.github.shoccho.batteryindicatorsma566b.core.PillSettings
import com.github.shoccho.batteryindicatorsma566b.core.PillStyle
import com.github.shoccho.batteryindicatorsma566b.core.WindowSpec
import org.junit.Assert.assertEquals
import org.junit.Test

class PillLogicTest {
    // Galaxy A56: 1080 px wide, density 3.0, camera hole (506,24)-(574,92), status bar 101 px.
    private val hole = Box(506, 24, 574, 92)
    private val density = 3f

    @Test fun percentMatchesSystemUiFloor() {
        assertEquals(76, BatterySnapshot.percentOf(76, 100))
        assertEquals(49, BatterySnapshot.percentOf(99, 200))
        assertEquals(0, BatterySnapshot.percentOf(-1, 100))
        assertEquals(100, BatterySnapshot.percentOf(120, 100))
    }

    @Test fun stateColoursPriority() {
        val s = PillSettings(lowThreshold = 15)
        val base = PillColors.WHITE
        assertEquals(PillColors.CHARGING, PillColors.fill(s, base, BatterySnapshot(10, plugged = true, powerSave = true)))
        assertEquals(PillColors.SAVER, PillColors.fill(s, base, BatterySnapshot(10, plugged = false, powerSave = true)))
        assertEquals(PillColors.LOW, PillColors.fill(s, base, BatterySnapshot(15, plugged = false)))
        assertEquals(base, PillColors.fill(s, base, BatterySnapshot(16, plugged = false)))
        assertEquals(base, PillColors.fill(s.copy(stateColors = false), base, BatterySnapshot(3, plugged = true)))
    }

    @Test fun autoColourFollowsTheme() {
        assertEquals(PillColors.WHITE, PillColors.base(ColorMode.AUTO, 0, night = true))
        assertEquals(PillColors.BLACK, PillColors.base(ColorMode.AUTO, 0, night = false))
    }

    @Test fun withAlphaScalesExistingAlpha() {
        assertEquals(0x80FFFFFF.toInt(), PillColors.withAlpha(PillColors.WHITE, 128 / 255f))
        assertEquals(0x00123456, PillColors.withAlpha(0xFF123456.toInt(), 0f))
    }

    @Test fun cameraRingIsCentredOnTheHole() {
        val s = PillSettings(style = PillStyle.CAMERA_RING, ringGapDp = 1.5f, ringThicknessDp = 2.5f)
        val spec = OverlayGeometry.spec(s, density, hole)
        // 68 px hole + 2 * (5 px gap + 8 px stroke) (rounded per side)
        assertEquals(68 + 2 * (5 + 8), spec.width)
        assertEquals(spec.width, spec.height)
        assertEquals(540, spec.x + spec.width / 2)
        assertEquals(58, spec.y + spec.height / 2)
        assertEquals(Anchor.LEFT, spec.anchor)
    }

    @Test fun edgeLineIsFullWidth() {
        val spec = OverlayGeometry.spec(PillSettings(style = PillStyle.EDGE_LINE, lineThicknessDp = 3f), density, hole)
        assertEquals(WindowSpec.MATCH_PARENT, spec.width)
        assertEquals(9, spec.height)
    }

    @Test fun besideCameraClearsTheHole() {
        val s = OverlayGeometry.besideCamera(PillSettings(), density, 1080, hole, 101)
        assertEquals(Anchor.CENTER, s.anchor)
        val pillLeftPx = 540 + s.offsetXDp * density - s.widthDp * density / 2
        assert(pillLeftPx > hole.right) { "pill starts at $pillLeftPx, hole ends at ${hole.right}" }
        val pillCentreY = (s.topDp + s.heightDp / 2) * density
        assertEquals(hole.centerY.toFloat(), pillCentreY, 2f)
    }
}
