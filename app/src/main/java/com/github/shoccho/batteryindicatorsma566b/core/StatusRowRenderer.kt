package com.github.shoccho.batteryindicatorsma566b.core

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import kotlin.math.max

/**
 * Draws the "Own icons" row: the enabled status icons in the user's order, then the battery pill
 * at the far right. Glyphs are drawn from simple geometry so they scale cleanly at any size and
 * take the same colour as the pill. Shared by the overlay and the in-app preview.
 */
class StatusRowRenderer(private val density: Float) {

    /** Samsung's own icon drawables, used instead of the drawn glyphs when available. */
    var art: IconArt? = null

    /** One drawable in an icon; an [overlay] sits on top of the previous part (activity arrows). */
    private class Part(val drawable: Drawable, val overlay: Boolean = false, val gapBefore: Float = 0f)

    private val pill = PillRenderer(density)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        strokeCap = Paint.Cap.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }
    private val path = Path()
    private val oval = RectF()

    /** Pill geometry inside the row, in px. */
    fun pillSize(s: PillSettings): Pair<Float, Float> {
        val h = s.heightDp * density
        return when (rowStyle(s)) {
            PillStyle.DOT -> h to h
            else -> s.widthDp * density to h
        }
    }

    fun height(s: PillSettings): Float = max(s.rowIconSizeDp * density, pillSize(s).second) + 2f * density

    /** Total row width in px for the current state. */
    fun width(s: PillSettings, st: StatusState): Float {
        val size = s.rowIconSizeDp * density
        val gap = s.rowGapDp * density
        val icons = visibleIcons(s, st)
        var w = pillSize(s).first
        for (icon in icons) w += gap * 2f + iconWidth(icon, s, st, size)
        return w + 2f * density
    }

    fun visibleIcons(s: PillSettings, st: StatusState): List<StatusIcon> =
        s.iconOrder.filter { it in s.iconsOn && st.shows(it) }

    fun draw(
        c: Canvas, w: Float, h: Float,
        s: PillSettings, st: StatusState,
        base: Int, pillFrame: PillRenderer.Frame,
    ) {
        val size = s.rowIconSizeDp * density
        val gap = s.rowGapDp * density
        val dim = PillColors.withAlpha(base, 0.32f)
        val layer = c.saveLayer(0f, 0f, w, h, null)

        // Battery pill, flush right.
        val (pw, ph) = pillSize(s)
        var right = w - density
        c.save()
        c.translate(right - pw, (h - ph) / 2f)
        pill.draw(c, pw, ph, s.copy(style = rowStyle(s)), pillFrame)
        c.restore()
        right -= pw

        // Icons right-to-left, so the first in the user's order ends up leftmost.
        val top = (h - size) / 2f
        for (icon in visibleIcons(s, st).asReversed()) {
            right -= gap * 2f
            val iw = iconWidth(icon, s, st, size)
            drawIcon(c, icon, right - iw, top, iw, size, s, st, base, dim)
            right -= iw
        }
        c.restoreToCount(layer)
    }

    private fun rowStyle(s: PillSettings) = when (s.style) {
        PillStyle.CAPSULE, PillStyle.CLASSIC, PillStyle.DOT -> s.style
        else -> PillStyle.CAPSULE
    }

    // ---- per-icon width ------------------------------------------------------------------------

    private fun iconWidth(icon: StatusIcon, s: PillSettings, st: StatusState, z: Float): Float {
        artParts(icon, s, st)?.let { parts ->
            return parts.sumOf { p -> if (p.overlay) 0.0 else ((p.gapBefore + aspect(p.drawable)) * z).toDouble() }.toFloat()
        }
        return glyphWidth(icon, s, st, z)
    }

    private fun glyphWidth(icon: StatusIcon, s: PillSettings, st: StatusState, z: Float): Float = when (icon) {
        StatusIcon.RINGER -> 0.85f * z
        StatusIcon.WIFI -> 1.34f * z + if (wifiColumn(s, st)) 0.42f * z else 0f
        StatusIcon.MOBILE -> st.mobile.sumOf { mobileWidth(it, s, st, z).toDouble() }.toFloat() +
            (st.mobile.size - 1).coerceAtLeast(0) * 0.25f * z
        StatusIcon.AIRPLANE -> 0.95f * z
        StatusIcon.BLUETOOTH -> if (st.bluetoothConnected) 0.95f * z else 0.6f * z
        StatusIcon.NFC -> 0.9f * z
        StatusIcon.ALARM -> 0.95f * z
        StatusIcon.HOTSPOT -> 1.0f * z
        StatusIcon.VPN -> 1.15f * z
        StatusIcon.DATA_SAVER -> 0.95f * z
        StatusIcon.DND -> 0.9f * z
    }

    private fun wifiColumn(s: PillSettings, st: StatusState) =
        (s.rowWifiGeneration && st.wifi.generation != null) ||
            (s.rowActivityArrows && !st.mobileIsDefault) || !st.wifi.validated

    private fun mobileLabel(m: MobileState, s: PillSettings, st: StatusState): String? =
        m.typeLabel?.takeIf { s.rowNetworkType && st.mobileIsDefault }

    private fun mobileWidth(m: MobileState, s: PillSettings, st: StatusState, z: Float): Float {
        var w = 0.85f * z
        val label = mobileLabel(m, s, st)
        if (label != null) {
            text.textSize = 0.62f * z
            w += text.measureText(label) + 0.08f * z
            if (s.rowActivityArrows) w += 0.36f * z
        }
        return w
    }

    // ---- drawing ------------------------------------------------------------------------------

    private fun drawIcon(
        c: Canvas, icon: StatusIcon, x: Float, y: Float, w: Float, z: Float,
        s: PillSettings, st: StatusState, fg: Int, dim: Int,
    ) {
        artParts(icon, s, st)?.let { parts ->
            var cx = x
            var lastLeft = x
            for (p in parts) {
                cx += p.gapBefore * z
                val pw = aspect(p.drawable) * z
                if (p.overlay) {
                    drawArt(c, p.drawable, lastLeft, y, pw, z, fg)
                } else {
                    drawArt(c, p.drawable, cx, y, pw, z, fg)
                    lastLeft = cx
                    cx += pw
                }
            }
            return
        }
        paint.color = fg
        when (icon) {
            StatusIcon.RINGER -> if (st.ringer == RingerState.VIBRATE) vibrate(c, x, y, z) else silent(c, x, y, z)
            StatusIcon.WIFI -> wifi(c, x, y, z, s, st, fg, dim)
            StatusIcon.MOBILE -> {
                var cx = x
                st.mobile.forEach { m ->
                    mobile(c, cx, y, z, m, s, st, fg, dim)
                    cx += mobileWidth(m, s, st, z) + 0.25f * z
                }
            }
            StatusIcon.AIRPLANE -> airplane(c, x, y, z)
            StatusIcon.BLUETOOTH -> bluetooth(c, x, y, z, st.bluetoothConnected)
            StatusIcon.NFC -> nfc(c, x, y, z)
            StatusIcon.ALARM -> alarm(c, x, y, z)
            StatusIcon.HOTSPOT -> hotspot(c, x, y, z)
            StatusIcon.VPN -> vpn(c, x, y, z)
            StatusIcon.DATA_SAVER -> dataSaver(c, x, y, z)
            StatusIcon.DND -> dnd(c, x, y, z)
        }
    }

    // ---- Samsung art ----------------------------------------------------------------------------

    private fun aspect(d: Drawable): Float {
        val iw = d.intrinsicWidth
        val ih = d.intrinsicHeight
        return if (iw > 0 && ih > 0) iw.toFloat() / ih else 1f
    }

    private fun drawArt(c: Canvas, d: Drawable, x: Float, y: Float, w: Float, h: Float, tint: Int) {
        d.setBounds(x.toInt(), y.toInt(), (x + w).toInt(), (y + h).toInt())
        d.setTint(tint)
        d.draw(c)
    }

    /** The Samsung drawables that make up [icon] right now, or null to use the drawn glyph. */
    private fun artParts(icon: StatusIcon, s: PillSettings, st: StatusState): List<Part>? {
        val a = art?.takeIf { s.rowSamsungArt } ?: return null
        fun one(name: String) = a.drawable(name)?.let { listOf(Part(it)) }
        return when (icon) {
            StatusIcon.RINGER -> one(if (st.ringer == RingerState.VIBRATE) SamsungArtNames.VIBRATE else SamsungArtNames.SILENT)
            StatusIcon.BLUETOOTH -> one(if (st.bluetoothConnected) SamsungArtNames.BLUETOOTH_CONNECTED else SamsungArtNames.BLUETOOTH)
            StatusIcon.NFC -> one(SamsungArtNames.NFC)
            StatusIcon.ALARM -> one(SamsungArtNames.ALARM)
            StatusIcon.HOTSPOT -> one(SamsungArtNames.HOTSPOT)
            StatusIcon.VPN -> one(SamsungArtNames.VPN)
            StatusIcon.DATA_SAVER -> one(SamsungArtNames.DATA_SAVER)
            StatusIcon.DND -> one(SamsungArtNames.DND)
            StatusIcon.AIRPLANE -> one(SamsungArtNames.AIRPLANE)
            StatusIcon.WIFI -> {
                val w = st.wifi
                val gen = w.generation.takeIf { s.rowWifiGeneration }
                val arrows = s.rowActivityArrows && !st.mobileIsDefault
                val signal = a.drawable(SamsungArtNames.wifi(w.level, gen, w.sixGhz, w.validated, arrows)) ?: return null
                buildList {
                    add(Part(signal))
                    if (s.rowActivityArrows && w.validated && !st.mobileIsDefault) {
                        a.drawable(SamsungArtNames.wifiActivity(st.activityIn, st.activityOut))?.let { add(Part(it, overlay = true)) }
                    }
                }
            }
            StatusIcon.MOBILE -> buildList {
                st.mobile.forEachIndexed { i, m ->
                    val gap = if (i > 0) 0.25f else 0f
                    var first = true
                    fun gapOnce() = if (first) gap.also { first = false } else 0.05f
                    mobileLabel(m, s, st)?.let(SamsungArtNames::networkType)?.let(a::drawable)?.let { type ->
                        add(Part(type, gapBefore = gapOnce()))
                        if (s.rowActivityArrows) {
                            a.drawable(SamsungArtNames.dataActivity(st.activityIn, st.activityOut))
                                ?.let { add(Part(it, overlay = true)) }
                        }
                    }
                    if (m.roaming) a.drawable(SamsungArtNames.ROAMING)?.let { add(Part(it, gapBefore = gapOnce())) }
                    val bars = a.drawable(SamsungArtNames.mobileBars(m.level, m.inService, i, st.mobile.size)) ?: return null
                    add(Part(bars, gapBefore = gapOnce()))
                }
            }
        }
    }

    private fun fill() {
        paint.style = Paint.Style.FILL
    }

    private fun stroke(width: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = width
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
    }

    /** Polygon/polyline from normalised points inside a [w]×[z] box at ([x],[y]). */
    private fun poly(x: Float, y: Float, w: Float, z: Float, close: Boolean, vararg p: Float): Path {
        path.reset()
        path.moveTo(x + p[0] * w, y + p[1] * z)
        var i = 2
        while (i < p.size) {
            path.lineTo(x + p[i] * w, y + p[i + 1] * z)
            i += 2
        }
        if (close) path.close()
        return path
    }

    private fun speaker(c: Canvas, x: Float, y: Float, z: Float) {
        fill()
        c.drawPath(poly(x, y, z, z, true, 0.06f, 0.36f, 0.27f, 0.36f, 0.55f, 0.1f, 0.55f, 0.9f, 0.27f, 0.64f, 0.06f, 0.64f), paint)
    }

    private fun silent(c: Canvas, x: Float, y: Float, z: Float) {
        speaker(c, x, y, z)
        clear.style = Paint.Style.STROKE
        clear.strokeWidth = 0.24f * z
        c.drawLine(x + 0.06f * z, y + 0.1f * z, x + 0.8f * z, y + 0.92f * z, clear)
        stroke(0.1f * z)
        c.drawLine(x + 0.06f * z, y + 0.1f * z, x + 0.8f * z, y + 0.92f * z, paint)
    }

    private fun vibrate(c: Canvas, x: Float, y: Float, z: Float) {
        stroke(0.09f * z)
        oval.set(x + 0.27f * z, y + 0.1f * z, x + 0.58f * z, y + 0.9f * z)
        c.drawRoundRect(oval, 0.08f * z, 0.08f * z, paint)
        stroke(0.08f * z)
        c.drawPath(poly(x, y, z, z, false, 0.14f, 0.25f, 0.06f, 0.38f, 0.14f, 0.5f, 0.06f, 0.62f, 0.14f, 0.75f), paint)
        c.drawPath(poly(x, y, z, z, false, 0.71f, 0.25f, 0.79f, 0.38f, 0.71f, 0.5f, 0.79f, 0.62f, 0.71f, 0.75f), paint)
    }

    private fun wedge(cx: Float, by: Float, r: Float) {
        path.reset()
        path.moveTo(cx, by)
        oval.set(cx - r, by - r, cx + r, by + r)
        path.arcTo(oval, 225f, 90f)
        path.close()
    }

    private fun wifi(c: Canvas, x: Float, y: Float, z: Float, s: PillSettings, st: StatusState, fg: Int, dim: Int) {
        val r = 0.95f * z
        val cx = x + 0.67f * z
        val by = y + 0.98f * z
        fill()
        paint.color = dim
        wedge(cx, by, r)
        c.drawPath(path, paint)
        paint.color = fg
        val level = st.wifi.level.coerceIn(0, 4)
        wedge(cx, by, r * max(0.28f, level / 4f))
        c.drawPath(path, paint)

        if (!wifiColumn(s, st)) return
        val col = x + 1.36f * z
        if (!st.wifi.validated) {
            text.textSize = 0.6f * z
            text.color = fg
            c.drawText("!", col + 0.05f * z, y + 0.95f * z, text)
            return
        }
        st.wifi.generation?.takeIf { s.rowWifiGeneration }?.let { gen ->
            text.textSize = 0.45f * z
            text.color = fg
            c.drawText(gen.toString(), col, y + 0.4f * z, text)
        }
        if (s.rowActivityArrows && !st.mobileIsDefault) arrows(c, col, y + 0.52f * z, z, st, fg, dim)
    }

    /** Tiny ↓↑ pair; lit when data flows that way. */
    private fun arrows(c: Canvas, x: Float, y: Float, z: Float, st: StatusState, fg: Int, dim: Int) {
        fill()
        val a = 0.17f * z
        val h = 0.36f * z
        paint.color = if (st.activityIn) fg else dim
        c.drawRect(x + a * 0.6f, y, x + a * 1.0f, y + h * 0.6f, paint)
        c.drawPath(poly(x, y, 1f, 1f, true, 0f, h * 0.55f, a * 1.6f, h * 0.55f, a * 0.8f, h), paint)
        paint.color = if (st.activityOut) fg else dim
        val x2 = x + a * 1.9f
        c.drawRect(x2 + a * 0.6f, y + h * 0.4f, x2 + a * 1.0f, y + h, paint)
        c.drawPath(poly(x2, y, 1f, 1f, true, 0f, h * 0.45f, a * 1.6f, h * 0.45f, a * 0.8f, 0f), paint)
    }

    private fun mobile(c: Canvas, x0: Float, y: Float, z: Float, m: MobileState, s: PillSettings, st: StatusState, fg: Int, dim: Int) {
        var x = x0
        mobileLabel(m, s, st)?.let { label ->
            text.textSize = 0.62f * z
            text.color = fg
            c.drawText(label, x, y + 0.72f * z, text)
            x += text.measureText(label) + 0.08f * z
            if (s.rowActivityArrows) {
                arrows(c, x - 0.04f * z, y + 0.15f * z, z, st, fg, dim)
                x += 0.36f * z
            }
        }
        fill()
        val bw = 0.17f * z
        val gap = 0.06f * z
        val bottom = y + 0.95f * z
        for (i in 0 until 4) {
            val bh = z * (0.35f + 0.2f * i)
            paint.color = if (m.inService && i < m.level) fg else dim
            oval.set(x + i * (bw + gap), bottom - bh, x + i * (bw + gap) + bw, bottom)
            c.drawRoundRect(oval, bw * 0.3f, bw * 0.3f, paint)
        }
        if (m.roaming) {
            text.textSize = 0.4f * z
            text.color = fg
            c.drawText("R", x, y + 0.38f * z, text)
        }
        if (!m.inService) {
            paint.color = fg
            stroke(0.08f * z)
            c.drawLine(x, y + 0.08f * z, x + 0.3f * z, y + 0.38f * z, paint)
            c.drawLine(x + 0.3f * z, y + 0.08f * z, x, y + 0.38f * z, paint)
        }
    }

    private fun airplane(c: Canvas, x: Float, y: Float, z: Float) {
        fill()
        oval.set(x + 0.43f * z, y + 0.04f * z, x + 0.57f * z, y + 0.88f * z)
        c.drawRoundRect(oval, 0.07f * z, 0.07f * z, paint)
        c.drawPath(poly(x, y, z, z, true, 0.45f, 0.36f, 0.02f, 0.6f, 0.02f, 0.7f, 0.45f, 0.57f, 0.55f, 0.57f, 0.98f, 0.7f, 0.98f, 0.6f, 0.55f, 0.36f), paint)
        c.drawPath(poly(x, y, z, z, true, 0.45f, 0.76f, 0.3f, 0.88f, 0.3f, 0.95f, 0.5f, 0.9f, 0.7f, 0.95f, 0.7f, 0.88f, 0.55f, 0.76f), paint)
    }

    private fun bluetooth(c: Canvas, x: Float, y: Float, z: Float, connected: Boolean) {
        val ox = if (connected) x + 0.18f * z else x
        stroke(0.09f * z)
        c.drawPath(poly(ox, y, 0.6f * z, z, false, 0.1f, 0.3f, 0.85f, 0.72f, 0.45f, 0.95f, 0.45f, 0.05f, 0.85f, 0.28f, 0.1f, 0.7f), paint)
        if (connected) {
            fill()
            c.drawCircle(x + 0.07f * z, y + 0.5f * z, 0.07f * z, paint)
            c.drawCircle(x + 0.88f * z, y + 0.5f * z, 0.07f * z, paint)
        }
    }

    private fun nfc(c: Canvas, x: Float, y: Float, z: Float) {
        stroke(0.08f * z)
        oval.set(x + 0.06f * z, y + 0.08f * z, x + 0.84f * z, y + 0.92f * z)
        c.drawRoundRect(oval, 0.18f * z, 0.18f * z, paint)
        c.drawPath(poly(x, y, z, z, false, 0.3f, 0.7f, 0.3f, 0.3f, 0.6f, 0.7f, 0.6f, 0.3f), paint)
    }

    private fun alarm(c: Canvas, x: Float, y: Float, z: Float) {
        stroke(0.09f * z)
        c.drawCircle(x + 0.48f * z, y + 0.56f * z, 0.34f * z, paint)
        c.drawLine(x + 0.48f * z, y + 0.56f * z, x + 0.48f * z, y + 0.38f * z, paint)
        c.drawLine(x + 0.48f * z, y + 0.56f * z, x + 0.61f * z, y + 0.64f * z, paint)
        c.drawLine(x + 0.08f * z, y + 0.2f * z, x + 0.24f * z, y + 0.07f * z, paint)
        c.drawLine(x + 0.88f * z, y + 0.2f * z, x + 0.72f * z, y + 0.07f * z, paint)
    }

    private fun hotspot(c: Canvas, x: Float, y: Float, z: Float) {
        val cx = x + 0.5f * z
        val cy = y + 0.55f * z
        fill()
        c.drawCircle(cx, cy, 0.1f * z, paint)
        stroke(0.08f * z)
        for (r in floatArrayOf(0.25f, 0.42f)) {
            oval.set(cx - r * z, cy - r * z, cx + r * z, cy + r * z)
            c.drawArc(oval, 135f, 270f, false, paint)
        }
    }

    private fun vpn(c: Canvas, x: Float, y: Float, z: Float) {
        stroke(0.1f * z)
        c.drawCircle(x + 0.27f * z, y + 0.5f * z, 0.19f * z, paint)
        c.drawLine(x + 0.46f * z, y + 0.5f * z, x + 1.05f * z, y + 0.5f * z, paint)
        c.drawLine(x + 0.86f * z, y + 0.5f * z, x + 0.86f * z, y + 0.7f * z, paint)
        c.drawLine(x + 1.05f * z, y + 0.5f * z, x + 1.05f * z, y + 0.7f * z, paint)
    }

    private fun dataSaver(c: Canvas, x: Float, y: Float, z: Float) {
        val cx = x + 0.48f * z
        val cy = y + 0.5f * z
        stroke(0.1f * z)
        oval.set(cx - 0.37f * z, cy - 0.37f * z, cx + 0.37f * z, cy + 0.37f * z)
        c.drawArc(oval, -50f, 300f, false, paint)
        stroke(0.09f * z)
        c.drawLine(cx - 0.15f * z, cy, cx + 0.15f * z, cy, paint)
        c.drawLine(cx, cy - 0.15f * z, cx, cy + 0.15f * z, paint)
    }

    private fun dnd(c: Canvas, x: Float, y: Float, z: Float) {
        fill()
        c.drawCircle(x + 0.45f * z, y + 0.5f * z, 0.4f * z, paint)
        clear.style = Paint.Style.FILL
        c.drawRect(x + 0.24f * z, y + 0.44f * z, x + 0.66f * z, y + 0.56f * z, clear)
    }
}
