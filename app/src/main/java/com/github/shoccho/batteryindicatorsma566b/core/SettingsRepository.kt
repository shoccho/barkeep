package com.github.shoccho.batteryindicatorsma566b.core

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single source of truth for [PillSettings], shared by the settings screen, the overlay service,
 * the Quick Settings tile and the boot receiver (all in one process). Writes go straight to the
 * flow, so the overlay follows slider drags frame by frame.
 */
class SettingsRepository private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<PillSettings> = _state.asStateFlow()
    val current: PillSettings get() = _state.value

    // Held in a field: SharedPreferences keeps listeners weakly.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key?.startsWith("internal.") != true) _state.value = read()
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun update(transform: (PillSettings) -> PillSettings) {
        val next = transform(current)
        if (next == current) return
        _state.value = next
        write(next)
    }

    /** Extra SystemUI slots this app hid for "Own icons", so exactly those get un-hidden later. */
    var weHidSlots: Set<String>
        get() = prefs.getStringSet(K_WE_HID_SLOTS, emptySet()).orEmpty().toSet()
        set(value) = prefs.edit { putStringSet(K_WE_HID_SLOTS, value) }

    /** Who changed Samsung's battery icon visibility, so it can be put back exactly. */
    var iconOwnership: IconOwnership
        get() = IconOwnership(
            weHid = prefs.getBoolean(K_WE_HID_ICON, false),
            weShowed = prefs.getBoolean(K_WE_SHOWED_ICON, false),
        )
        set(value) = prefs.edit {
            putBoolean(K_WE_HID_ICON, value.weHid)
            putBoolean(K_WE_SHOWED_ICON, value.weShowed)
        }

    private fun read(): PillSettings {
        val d = PillSettings()
        return PillSettings(
            enabled = prefs.getBoolean("enabled", d.enabled),
            style = enumOr(prefs.getString("style", null), d.style),
            placement = enumOr(
                prefs.getString("placement", null),
                // Migrate the earlier on/off "coverStock" switch.
                if (prefs.getBoolean("coverStock", false)) Placement.SAMSUNG_SPOT else d.placement,
            ),
            iconOrder = StatusIcon.parseOrder(prefs.getString("iconOrder", null)),
            iconsOn = prefs.getString("iconsOn", null)?.let { raw ->
                raw.split(',').mapNotNull { n -> StatusIcon.entries.firstOrNull { it.name == n } }.toSet()
            } ?: d.iconsOn,
            rowIconSizeDp = prefs.getFloat("rowIconSizeDp", d.rowIconSizeDp),
            rowGapDp = prefs.getFloat("rowGapDp", d.rowGapDp),
            rowEndMarginDp = prefs.getFloat("rowEndMarginDp", d.rowEndMarginDp),
            rowWifiGeneration = prefs.getBoolean("rowWifiGeneration", d.rowWifiGeneration),
            rowActivityArrows = prefs.getBoolean("rowActivityArrows", d.rowActivityArrows),
            rowNetworkType = prefs.getBoolean("rowNetworkType", d.rowNetworkType),
            rowSamsungArt = prefs.getBoolean("rowSamsungArt", d.rowSamsungArt),
            coverExtraWidthDp = prefs.getFloat("coverExtraWidthDp", d.coverExtraWidthDp),
            coverExtraHeightDp = prefs.getFloat("coverExtraHeightDp", d.coverExtraHeightDp),
            widthDp = prefs.getFloat("widthDp", d.widthDp),
            heightDp = prefs.getFloat("heightDp", d.heightDp),
            anchor = enumOr(prefs.getString("anchor", null), d.anchor),
            offsetXDp = prefs.getFloat("offsetXDp", d.offsetXDp),
            topDp = prefs.getFloat("topDp", d.topDp),
            lineThicknessDp = prefs.getFloat("lineThicknessDp", d.lineThicknessDp),
            lineInsetDp = prefs.getFloat("lineInsetDp", d.lineInsetDp),
            lineTopDp = prefs.getFloat("lineTopDp", d.lineTopDp),
            lineGrowFrom = enumOr(prefs.getString("lineGrowFrom", null), d.lineGrowFrom),
            ringThicknessDp = prefs.getFloat("ringThicknessDp", d.ringThicknessDp),
            ringGapDp = prefs.getFloat("ringGapDp", d.ringGapDp),
            colorMode = enumOr(prefs.getString("colorMode", null), d.colorMode),
            customColor = prefs.getInt("customColor", d.customColor),
            stateColors = prefs.getBoolean("stateColors", d.stateColors),
            lowThreshold = prefs.getInt("lowThreshold", d.lowThreshold),
            opacity = prefs.getInt("opacity", d.opacity),
            contrastRim = prefs.getBoolean("contrastRim", d.contrastRim),
            showBolt = prefs.getBoolean("showBolt", d.showBolt),
            chargingAnimation = prefs.getBoolean("chargingAnimation", d.chargingAnimation),
            criticalPulse = prefs.getBoolean("criticalPulse", d.criticalPulse),
            hideInFullscreen = prefs.getBoolean("hideInFullscreen", d.hideInFullscreen),
            hideInLandscape = prefs.getBoolean("hideInLandscape", d.hideInLandscape),
            startOnBoot = prefs.getBoolean("startOnBoot", d.startOnBoot),
            hideSystemIcon = prefs.getBoolean("hideSystemIcon", d.hideSystemIcon),
        )
    }

    private fun write(s: PillSettings) = prefs.edit {
        putBoolean("enabled", s.enabled)
        putString("style", s.style.name)
        putString("placement", s.placement.name)
        remove("coverStock")
        putString("iconOrder", s.iconOrder.joinToString(",") { it.name })
        putString("iconsOn", s.iconsOn.joinToString(",") { it.name })
        putFloat("rowIconSizeDp", s.rowIconSizeDp)
        putFloat("rowGapDp", s.rowGapDp)
        putFloat("rowEndMarginDp", s.rowEndMarginDp)
        putBoolean("rowWifiGeneration", s.rowWifiGeneration)
        putBoolean("rowActivityArrows", s.rowActivityArrows)
        putBoolean("rowNetworkType", s.rowNetworkType)
        putBoolean("rowSamsungArt", s.rowSamsungArt)
        putFloat("coverExtraWidthDp", s.coverExtraWidthDp)
        putFloat("coverExtraHeightDp", s.coverExtraHeightDp)
        putFloat("widthDp", s.widthDp)
        putFloat("heightDp", s.heightDp)
        putString("anchor", s.anchor.name)
        putFloat("offsetXDp", s.offsetXDp)
        putFloat("topDp", s.topDp)
        putFloat("lineThicknessDp", s.lineThicknessDp)
        putFloat("lineInsetDp", s.lineInsetDp)
        putFloat("lineTopDp", s.lineTopDp)
        putString("lineGrowFrom", s.lineGrowFrom.name)
        putFloat("ringThicknessDp", s.ringThicknessDp)
        putFloat("ringGapDp", s.ringGapDp)
        putString("colorMode", s.colorMode.name)
        putInt("customColor", s.customColor)
        putBoolean("stateColors", s.stateColors)
        putInt("lowThreshold", s.lowThreshold)
        putInt("opacity", s.opacity)
        putBoolean("contrastRim", s.contrastRim)
        putBoolean("showBolt", s.showBolt)
        putBoolean("chargingAnimation", s.chargingAnimation)
        putBoolean("criticalPulse", s.criticalPulse)
        putBoolean("hideInFullscreen", s.hideInFullscreen)
        putBoolean("hideInLandscape", s.hideInLandscape)
        putBoolean("startOnBoot", s.startOnBoot)
        putBoolean("hideSystemIcon", s.hideSystemIcon)
    }

    companion object {
        private const val PREFS = "pill_settings"
        private const val K_WE_HID_ICON = "internal.weHidSystemIcon"
        private const val K_WE_SHOWED_ICON = "internal.weShowedSystemIcon"
        private const val K_WE_HID_SLOTS = "internal.weHidSlots"

        @Volatile
        private var instance: SettingsRepository? = null

        fun get(context: Context): SettingsRepository =
            instance ?: synchronized(this) {
                instance ?: SettingsRepository(context).also { instance = it }
            }

        private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
            enumValues<E>().firstOrNull { it.name == name } ?: default
    }
}
