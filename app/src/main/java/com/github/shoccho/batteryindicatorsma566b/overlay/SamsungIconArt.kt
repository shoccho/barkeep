package com.github.shoccho.batteryindicatorsma566b.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.util.Log
import com.github.shoccho.batteryindicatorsma566b.core.IconArt

/**
 * Loads Samsung's own status icons from the phone's installed SystemUI (and NFC app) at runtime,
 * so the "Own icons" row looks exactly like One UI. Nothing is copied into this app; on a phone
 * without these resources every lookup returns null and the built-in glyphs are used instead.
 */
class SamsungIconArt(context: Context) : IconArt {

    private val pm = context.packageManager
    private val packages = mapOf(
        "" to SYSUI,
        "nfc" to NFC_APP,
    )
    private val resources = HashMap<String, Resources?>()
    private val cache = HashMap<String, Drawable?>()

    val available: Boolean get() = res(SYSUI) != null

    @SuppressLint("DiscouragedApi") // Looking up another package's resources by name is the point.
    override fun drawable(name: String): Drawable? = cache.getOrPut(name) {
        val (prefix, entry) = if (':' in name) name.substringBefore(':') to name.substringAfter(':') else "" to name
        val pkg = packages[prefix] ?: return@getOrPut null
        val r = res(pkg) ?: return@getOrPut null
        try {
            val id = r.getIdentifier(entry, "drawable", pkg)
            if (id == 0) null else r.getDrawable(id, null)?.mutate()
        } catch (e: Exception) {
            Log.d(TAG, "No art for $name: ${e.message}")
            null
        }
    }

    private fun res(pkg: String): Resources? = resources.getOrPut(pkg) {
        try {
            pm.getResourcesForApplication(pkg)
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val TAG = "SamsungIconArt"
        const val SYSUI = "com.android.systemui"
        const val NFC_APP = "com.samsung.android.nfc"
    }
}
