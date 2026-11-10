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
        "adb shell pm grant com.github.shoccho.batteryindicatorsma566b android.permission.WRITE_SECURE_SETTINGS"

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    /** null when the setting can't be read. */
    fun isHidden(context: Context): Boolean? = try {
        IconBlacklist.contains(Settings.Secure.getString(context.contentResolver, KEY))
    } catch (e: Exception) {
        Log.w(TAG, "Can't read $KEY", e)
        null
    }

    /** Returns true when the icon ends up in the requested state. */
    fun setHidden(context: Context, hidden: Boolean): Boolean {
        if (!hasPermission(context)) return false
        return try {
            val cr = context.contentResolver
            val current = Settings.Secure.getString(cr, KEY)
            val next = if (hidden) IconBlacklist.add(current) else IconBlacklist.remove(current)
            if (next == current.orEmpty()) true else Settings.Secure.putString(cr, KEY, next)
        } catch (e: Exception) {
            Log.w(TAG, "Can't write $KEY", e)
            false
        }
    }

    /**
     * "Own icons": hide Samsung's other right-side icons ([hide] = true), or un-hide exactly the
     * ones this app hid earlier (false). Slots you had hidden yourself are never touched.
     */
    fun syncSlots(context: Context, repo: SettingsRepository, hide: Boolean, slots: List<String> = OWN_ICONS_SLOTS) {
        if (!hasPermission(context)) return
        try {
            val cr = context.contentResolver
            val current = Settings.Secure.getString(cr, KEY)
            if (hide) {
                val (next, added) = IconBlacklist.addAll(current, slots)
                if (added.isNotEmpty() && Settings.Secure.putString(cr, KEY, next)) {
                    repo.weHidSlots = repo.weHidSlots + added
                }
            } else {
                val ours = repo.weHidSlots
                if (ours.isNotEmpty() && Settings.Secure.putString(cr, KEY, IconBlacklist.removeAll(current, ours))) {
                    repo.weHidSlots = emptySet()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Can't sync slots", e)
        }
    }

    /** Brings the icon to the wanted state (see [IconOwnership.decide]) and records ownership. */
    fun sync(context: Context, repo: SettingsRepository, want: Boolean?) {
        if (!hasPermission(context)) return
        val step = repo.iconOwnership.decide(want, isHidden(context))
        val ok = step.setHidden?.let { setHidden(context, it) } ?: true
        if (ok && step.next != repo.iconOwnership) repo.iconOwnership = step.next
    }
}
