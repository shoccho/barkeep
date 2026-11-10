package com.github.shoccho.batteryindicatorsma566b.core

/**
 * Works out the colour Samsung is tinting its status-bar icons with, from the pixels of a
 * region that contains an icon or the clock on top of some background.
 *
 * The background is the majority (median); the icon is whichever extreme sits furthest from it.
 * Returns an opaque colour, or null when there's no clear icon in the region.
 */
object TintSampler {
    fun pick(pixels: IntArray): Int? {
        if (pixels.size < 16) return null
        val sorted = pixels.sortedBy { PillColors.luminance(it) }
        val lo = sorted[(sorted.size * 0.01f).toInt()]
        val hi = sorted[(sorted.size * 0.99f).toInt().coerceAtMost(sorted.size - 1)]
        val median = PillColors.luminance(sorted[sorted.size / 2])
        val dark = PillColors.luminance(lo)
        val light = PillColors.luminance(hi)
        if (light - dark < 0.25f) return null
        val icon = if (light - median > median - dark) hi else lo
        return icon or (0xFF shl 24)
    }
}
