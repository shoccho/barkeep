package com.github.shoccho.batteryindicatorsma566b.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

/** Pure edits to SystemUI's comma-separated `icon_blacklist` value. Order and unrelated entries are kept. */
object IconBlacklist {
    const val BATTERY_SLOT = "battery_icon"

    private fun entries(value: String?): List<String> =
        value.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }

    fun contains(value: String?, slot: String = BATTERY_SLOT): Boolean = slot in entries(value)

    fun add(value: String?, slot: String = BATTERY_SLOT): String =
        if (contains(value, slot)) value.orEmpty() else (entries(value) + slot).joinToString(",")

    fun remove(value: String?, slot: String = BATTERY_SLOT): String =
        entries(value).filter { it != slot }.joinToString(",")

    /** Adds every slot not already present; also returns which ones were actually added. */
    fun addAll(value: String?, slots: Collection<String>): Pair<String, Set<String>> {
        val current = entries(value)
        val added = slots.filter { it !in current }.distinct()
        return (current + added).joinToString(",") to added.toSet()
    }

    /** Removes exactly [slots] (every occurrence), keeping everything else in order. */
    fun removeAll(value: String?, slots: Collection<String>): String =
        entries(value).filter { it !in slots }.joinToString(",")

    /**
     * Written just before the slots "Own icons" hides. SystemUI ignores names it doesn't know, so
     * it's harmless — and it makes the list say which slots this app hid, so they still come back
     * after a reinstall, a data wipe or a different build of the app lost its own record.
     */
    const val OWN_ICONS_MARKER = "barkeep_own_icons"

    /** Builds before the marker wrote a bare run; this many of our slots in a row, in our order, is ours. */
    private const val LEGACY_RUN = 10

    /**
     * Slots in [value] that this app hid for "Own icons", read from the list itself: every one of
     * [slots] after [OWN_ICONS_MARKER] (anything the user hid earlier sits before it), plus any
     * marker-less run of at least [LEGACY_RUN] of them in [slots] order left by older builds.
     */
    fun ownIconsBlock(value: String?, slots: List<String>): Set<String> {
        val e = entries(value)
        val marker = e.indexOf(OWN_ICONS_MARKER)
        val afterMarker = if (marker < 0) emptyList() else e.drop(marker + 1).filter { it in slots }

        val runs = mutableListOf<List<String>>()
        var run = mutableListOf<String>()
        var last = -1
        for (x in e) {
            val i = slots.indexOf(x)
            if (i < 0 || i <= last) {
                runs += run
                run = mutableListOf()
                last = -1
            }
            if (i >= 0) {
                run += x
                last = i
            }
        }
        runs += run
        return (afterMarker + runs.filter { it.size >= LEGACY_RUN }.flatten()).toSet()
    }
}

/**
 * One pass over `icon_blacklist`: Samsung's battery icon and the "Own icons" slots, planned together
 * from a single read so the list is written at most once.
 */
data class IconPlan(val value: String, val ownership: IconOwnership, val weHidSlots: Set<String>) {
    companion object {
        /**
         * @param wantBatteryHidden see [IconOwnership.decide].
         * @param ownIcons true = hide [slots] (only those not already hidden count as ours);
         *                 false = un-hide every slot this app hid, whether it's in [weHidSlots]
         *                 or only recognisable from the list ([IconBlacklist.ownIconsBlock]).
         */
        fun of(
            current: String?,
            ownership: IconOwnership,
            wantBatteryHidden: Boolean?,
            ownIcons: Boolean,
            weHidSlots: Set<String>,
            slots: List<String> = OWN_ICONS_SLOTS,
        ): IconPlan {
            val battery = ownership.decide(wantBatteryHidden, IconBlacklist.contains(current))
            val value = when (battery.setHidden) {
                true -> IconBlacklist.add(current)
                false -> IconBlacklist.remove(current)
                null -> current.orEmpty()
            }
            val ours = weHidSlots + IconBlacklist.ownIconsBlock(value, slots)
            return if (ownIcons) {
                val (marked, _) = IconBlacklist.addAll(value, listOf(IconBlacklist.OWN_ICONS_MARKER))
                val (next, added) = IconBlacklist.addAll(marked, slots)
                IconPlan(next, battery.next, ours + added)
            } else {
                IconPlan(IconBlacklist.removeAll(value, ours + IconBlacklist.OWN_ICONS_MARKER), battery.next, emptySet())
            }
        }
    }
}

/**
 * Remembers which change *this app* made to Samsung's battery icon, so "restore" puts back
 * exactly what was there before — including a hide you set yourself in QuickStar.
 */
data class IconOwnership(val weHid: Boolean = false, val weShowed: Boolean = false) {

    /** What to do: [setHidden] is null when the icon should be left alone. */
    data class Step(val setHidden: Boolean?, val next: IconOwnership)

    /**
     * @param want true = must be hidden (free placement + "hide Samsung's battery"),
     *             false = must be visible (pill sits on top of it), null = put things back.
     * @param hiddenNow current state, or null if it can't be read.
     */
    fun decide(want: Boolean?, hiddenNow: Boolean?): Step {
        if (hiddenNow == null) return Step(null, this)
        return when (want) {
            true -> when {
                hiddenNow -> Step(null, this)
                weShowed -> Step(true, copy(weShowed = false))     // undo our own "show"
                else -> Step(true, copy(weHid = true))
            }
            false -> when {
                !hiddenNow -> Step(null, this)
                weHid -> Step(false, copy(weHid = false))          // undo our own "hide"
                else -> Step(false, copy(weShowed = true))
            }
            null -> when {
                weHid && hiddenNow -> Step(false, IconOwnership())
                weShowed && !hiddenNow -> Step(true, IconOwnership())
                else -> Step(null, IconOwnership())
            }
        }
    }
}

/**
 * Shows/hides Samsung's own battery icon through the `icon_blacklist` secure setting — the same
 * switch Good Lock QuickStar flips. Needs WRITE_SECURE_SETTINGS, which can only be granted once
 * over adb (see [ADB_GRANT_COMMAND]). Without it every call is a harmless no-op.
 */
object SystemBatteryIcon {
    private const val TAG = "SystemBatteryIcon"
    private const val KEY = "icon_blacklist"

    const val ADB_GRANT_COMMAND =
        "adb shell pm grant com.github.shoccho.barkeep android.permission.WRITE_SECURE_SETTINGS"

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Brings Samsung's battery icon ([IconOwnership.decide]) and the "Own icons" slots ([IconPlan])
     * to the wanted state in one read and at most one write, then records what this app now owns.
     */
    fun sync(context: Context, repo: SettingsRepository, wantBatteryHidden: Boolean?, ownIcons: Boolean) {
        if (!hasPermission(context)) return
        try {
            val cr = context.contentResolver
            val current = Settings.Secure.getString(cr, KEY)
            val plan = IconPlan.of(current, repo.iconOwnership, wantBatteryHidden, ownIcons, repo.weHidSlots)
            if (plan.value != current.orEmpty() && !Settings.Secure.putString(cr, KEY, plan.value)) return
            if (plan.ownership != repo.iconOwnership) repo.iconOwnership = plan.ownership
            if (plan.weHidSlots != repo.weHidSlots) repo.weHidSlots = plan.weHidSlots
        } catch (e: Exception) {
            Log.w(TAG, "Can't sync $KEY", e)
        }
    }
}
