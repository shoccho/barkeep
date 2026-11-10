package com.github.shoccho.batteryindicatorsma566b.core

import kotlin.math.max
import kotlin.math.roundToInt

/** A screen-space rectangle in pixels (kept android-free for unit tests). */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2
}

/**
 * Where the overlay window goes. [width] == [MATCH_PARENT] means full width.
 * [anchor] says which edge [x] is measured from; for CENTER, [x] is a signed offset from the middle.
 */
data class WindowSpec(val width: Int, val height: Int, val anchor: Anchor, val x: Int, val y: Int) {
    companion object {
        const val MATCH_PARENT = -1
    }
}

object OverlayGeometry {

    fun spec(s: PillSettings, density: Float, cutout: Box?): WindowSpec {
        fun px(dp: Float) = (dp * density).roundToInt()
        return when (s.style) {
            PillStyle.EDGE_LINE -> WindowSpec(
                WindowSpec.MATCH_PARENT, max(1, px(s.lineThicknessDp)), Anchor.LEFT, 0, px(s.lineTopDp),
            )

            PillStyle.CAMERA_RING -> if (cutout != null && cutout.width > 0 && cutout.height > 0) {
                val d = max(4, max(cutout.width, cutout.height) + 2 * (px(s.ringGapDp) + px(s.ringThicknessDp)))
                WindowSpec(d, d, Anchor.LEFT, cutout.centerX - d / 2, cutout.centerY - d / 2)
            } else {
                // No punch hole (e.g. landscape on some devices): fall back to a dot at the pill spot.
                val h = max(1, px(s.heightDp))
                WindowSpec(h, h, s.anchor, px(s.offsetXDp), px(s.topDp))
            }

            PillStyle.DOT -> {
                val h = max(1, px(s.heightDp))
                WindowSpec(h, h, s.anchor, px(s.offsetXDp), px(s.topDp))
            }

            PillStyle.CAPSULE, PillStyle.CLASSIC -> WindowSpec(
                max(1, px(s.widthDp)), max(1, px(s.heightDp)), s.anchor, px(s.offsetXDp), px(s.topDp),
            )
        }
    }

    /** Settings that put the pill just right of the camera hole, vertically centred on it. */
    fun besideCamera(s: PillSettings, density: Float, screenWidthPx: Int, cutout: Box?, statusBarPx: Int): PillSettings {
        val gapDp = 6f
        val h = if (s.style == PillStyle.DOT) s.heightDp else s.widthDp
        val holeRightDp = cutout?.let { (it.right - screenWidthPx / 2f) / density } ?: 12f
        val centreYDp = (cutout?.centerY ?: (statusBarPx / 2)) / density
        return s.copy(
            anchor = Anchor.CENTER,
            offsetXDp = round1(holeRightDp + gapDp + h / 2f),
            topDp = round1(centreYDp - s.heightDp / 2f).coerceAtLeast(0f),
        )
    }

    /** Settings that put the pill at an edge of the status bar, vertically centred in it. */
    fun atEdge(s: PillSettings, density: Float, statusBarPx: Int, anchor: Anchor): PillSettings = s.copy(
        anchor = anchor,
        offsetXDp = 15f,
        topDp = round1((statusBarPx / density - s.heightDp) / 2f).coerceAtLeast(0f),
    )

    /**
     * The rectangle that hides Samsung's battery capsule. [node] is the battery view's bounds as
     * reported by accessibility (it includes vertical padding): keep its width, centre a
     * stock-height capsule in it, and grow both a little so anti-aliased edges never peek out.
     */
    fun cover(node: Box, s: PillSettings, density: Float): Box {
        val h = ((PillSettings.STOCK_HEIGHT_DP + s.coverExtraHeightDp) * density).roundToInt().coerceAtLeast(2)
        val extraW = (s.coverExtraWidthDp * density).roundToInt()
        val w = (node.width + extraW).coerceAtLeast(2)
        val left = node.centerX - w / 2
        val top = node.centerY - h / 2
        return Box(left, top, left + w, top + h)
    }

    private fun round1(v: Float) = (v * 2f).roundToInt() / 2f
}
