package com.github.shoccho.batteryindicatorsma566b.core

/** How the indicator is drawn. */
enum class PillStyle(val label: String, val blurb: String) {
    CAPSULE("Capsule", "One UI-style solid pill"),
    CLASSIC("Classic", "Outlined battery with a tip"),
    DOT("Dot", "Tiny circular gauge"),
    CAMERA_RING("Camera ring", "Wraps the punch-hole camera"),
    EDGE_LINE("Edge line", "Thin bar along the top edge"),
}

/** Where the pill lives. */
enum class Placement(val label: String) {
    /** Anywhere you like (app overlay). */
    FREE("Free"),
    /** Exactly over Samsung's battery, hiding its number (accessibility overlay). */
    SAMSUNG_SPOT("Samsung's spot"),
    /** Hide Samsung's right-side icons and draw our own row, pill rightmost (accessibility overlay). */
    OWN_ICONS("Own icons"),
}

/** Which side of the screen the pill is measured from. */
enum class Anchor(val label: String) { LEFT("Left"), CENTER("Center"), RIGHT("Right") }

enum class ColorMode(val label: String) {
    AUTO("Follow theme"),
    /** Same colour as Samsung's status bar icons right now (read via the accessibility service). */
    MATCH("Match icons"),
    WHITE("White"),
    BLACK("Black"),
    CUSTOM("Custom"),
}

/**
 * Everything the user can tune. Sizes are in dp so the pill looks the same if display
 * size (density) changes. Defaults put a stock-sized Classic battery just right of the camera hole on
 * the Galaxy A56: Samsung's own battery spot is taken by the signal icons once its battery
 * icon is hidden, while the strip beside the camera is always empty.
 */
data class PillSettings(
    val enabled: Boolean = false,
    val style: PillStyle = PillStyle.CLASSIC,

    /** See [Placement]. SAMSUNG_SPOT and OWN_ICONS need the accessibility service. */
    val placement: Placement = Placement.FREE,
    val coverExtraWidthDp: Float = 1f,
    val coverExtraHeightDp: Float = 1f,

    // Own icons row
    val iconOrder: List<StatusIcon> = StatusIcon.DEFAULT_ORDER,
    val iconsOn: Set<StatusIcon> = StatusIcon.entries.filter { it.defaultOn }.toSet(),
    val rowIconSizeDp: Float = 13f,
    val rowGapDp: Float = 2.5f,
    val rowEndMarginDp: Float = 15f,
    val rowWifiGeneration: Boolean = true,
    val rowActivityArrows: Boolean = true,
    val rowNetworkType: Boolean = true,
    /** Use the phone's own Samsung status icons in the row (falls back to drawn glyphs). */
    val rowSamsungArt: Boolean = true,

    // Pill geometry (Capsule / Classic / Dot)
    val widthDp: Float = 26f,
    val heightDp: Float = 12f,
    val anchor: Anchor = Anchor.CENTER,
    /** Distance from the anchored edge; for CENTER it is a signed offset from the middle. */
    val offsetXDp: Float = 34f,
    val topDp: Float = 13f,

    // Edge line
    val lineThicknessDp: Float = 3f,
    val lineInsetDp: Float = 0f,
    val lineTopDp: Float = 0f,
    val lineGrowFrom: Anchor = Anchor.LEFT,

    // Camera ring
    val ringThicknessDp: Float = 2.5f,
    val ringGapDp: Float = 1.5f,

    // Colour
    val colorMode: ColorMode = ColorMode.AUTO,
    val customColor: Int = 0xFF4FC3F7.toInt(),
    val stateColors: Boolean = true,
    val lowThreshold: Int = 15,
    val opacity: Int = 100,
    val contrastRim: Boolean = false,

    // Behaviour
    val showBolt: Boolean = true,
    val chargingAnimation: Boolean = true,
    val criticalPulse: Boolean = true,
    val hideInFullscreen: Boolean = true,
    val hideInLandscape: Boolean = false,
    val startOnBoot: Boolean = true,
    val hideSystemIcon: Boolean = false,
) {
    val coverStock: Boolean get() = placement == Placement.SAMSUNG_SPOT

    companion object {
        val WIDTH_RANGE = 10f..60f
        val HEIGHT_RANGE = 5f..30f
        val OFFSET_X_RANGE = -180f..180f
        val OFFSET_X_EDGE_RANGE = 0f..180f
        val TOP_RANGE = 0f..60f
        val LINE_THICKNESS_RANGE = 1f..10f
        val LINE_INSET_RANGE = 0f..120f
        val LINE_TOP_RANGE = 0f..60f
        val RING_THICKNESS_RANGE = 1f..6f
        val RING_GAP_RANGE = -3f..8f
        val COVER_EXTRA_RANGE = -3f..6f
        val ROW_ICON_RANGE = 8f..20f
        val ROW_GAP_RANGE = 0f..8f
        val ROW_END_RANGE = 0f..60f
        val LOW_RANGE = 5..50
        val OPACITY_RANGE = 20..100

        /** At or below this the pill pulses (when not charging). */
        const val CRITICAL_PERCENT = 5

        /** Galaxy A56 stock battery icon, measured from a screenshot. */
        const val STOCK_WIDTH_DP = 26f
        const val STOCK_HEIGHT_DP = 12f
    }
}
