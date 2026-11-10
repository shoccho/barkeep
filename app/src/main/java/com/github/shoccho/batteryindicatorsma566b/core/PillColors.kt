package com.github.shoccho.batteryindicatorsma566b.core

import kotlin.math.roundToInt

/**
 * Colour rules, kept free of android.graphics so they can be unit-tested on the JVM.
 * Colours are packed ARGB ints.
 */
object PillColors {
    const val WHITE = 0xFFFFFFFF.toInt()
    const val BLACK = 0xFF000000.toInt()
    const val CHARGING = 0xFF34C759.toInt()
    const val SAVER = 0xFFFFB300.toInt()
    const val LOW = 0xFFFF3B30.toInt()

    /** Swatches offered for the custom colour. */
    val SWATCHES = listOf(
        0xFF4FC3F7.toInt(), 0xFF7C4DFF.toInt(), 0xFFFF4081.toInt(), 0xFFFFD54F.toInt(),
        0xFF69F0AE.toInt(), 0xFFFF8A65.toInt(), 0xFFB0BEC5.toInt(), 0xFFE040FB.toInt(),
    )

    /** [matched] is the live status-bar icon colour, or null when it isn't known (falls back to theme). */
    fun base(mode: ColorMode, custom: Int, night: Boolean, matched: Int? = null): Int = when (mode) {
        ColorMode.AUTO -> if (night) WHITE else BLACK
        ColorMode.MATCH -> matched ?: if (night) WHITE else BLACK
        ColorMode.WHITE -> WHITE
        ColorMode.BLACK -> BLACK
        ColorMode.CUSTOM -> custom
    }

    /** Colour of the filled part: state colours win over the base colour. */
    fun fill(settings: PillSettings, base: Int, battery: BatterySnapshot): Int = when {
        !settings.stateColors -> base
        battery.plugged -> CHARGING
        battery.powerSave -> SAVER
        battery.percent <= settings.lowThreshold -> LOW
        else -> base
    }

    /** Multiplies the colour's existing alpha by [factor]. */
    fun withAlpha(color: Int, factor: Float): Int {
        val a = ((color ushr 24) * factor.coerceIn(0f, 1f)).roundToInt()
        return (color and 0x00FFFFFF) or (a shl 24)
    }

    /** Relative luminance 0..1 (sRGB approximation is plenty for picking black vs white). */
    fun luminance(color: Int): Float {
        val r = (color shr 16 and 0xFF) / 255f
        val g = (color shr 8 and 0xFF) / 255f
        val b = (color and 0xFF) / 255f
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }

    /** Opaque blend from [a] (t = 0) to [b] (t = 1). */
    fun mix(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun ch(shift: Int) = (((a shr shift) and 0xFF) * (1 - k) + ((b shr shift) and 0xFF) * k).roundToInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    fun contrastOf(color: Int): Int = if (luminance(color) > 0.55f) BLACK else WHITE
}
