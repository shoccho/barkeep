package com.github.shoccho.batteryindicatorsma566b.core

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.max
import kotlin.math.min

/**
 * Draws every indicator style onto a plain [Canvas]. Shared by the overlay window and the in-app
 * previews so what you tune is exactly what you get.
 *
 * @param density pixels per dp of the surface being drawn on.
 */
class PillRenderer(private val density: Float) {

    /** Per-frame inputs. [level] is 0..100 and may be fractional while animating. */
    data class Frame(
        val level: Float,
        val base: Int,
        val fill: Int,
        val plugged: Boolean,
        /** Charging shimmer position 0..1, or negative when no shimmer is showing. */
        val sweep: Float = -1f,
        /** Whole-indicator alpha (critical-battery pulse). */
        val alpha: Float = 1f,
        /**
         * Paint every pixel solid (no transparency, no cut-outs) — used when sitting on top of
         * Samsung's battery so its number can never show through. Pulse then fades colours instead.
         */
        val opaque: Boolean = false,
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        strokeJoin = Paint.Join.ROUND
    }
    private val layerPaint = Paint()
    private val rect = RectF()
    private val inner = RectF()
    private val clip = Path()
    private val bolt = Path()

    fun draw(canvas: Canvas, w: Float, h: Float, s: PillSettings, f: Frame) {
        if (w <= 0f || h <= 0f) return
        // Own layer so CLEAR (bolt cut-outs) only punches through the indicator, never the surface below.
        if (f.opaque) {
            drawOpaqueCapsule(canvas, w, h, s, f)
            return
        }
        layerPaint.alpha = (f.alpha.coerceIn(0f, 1f) * 255).toInt()
        val layer = canvas.saveLayer(0f, 0f, w, h, layerPaint)
        when (s.style) {
            PillStyle.CAPSULE -> drawCapsule(canvas, w, h, s, f)
            PillStyle.CLASSIC -> drawClassic(canvas, w, h, s, f)
            PillStyle.DOT -> drawRing(canvas, w, h, s, f, thickness = min(w, h) * 0.17f, bolt = true)
            PillStyle.CAMERA_RING -> drawRing(
                canvas, w, h, s, f, thickness = s.ringThicknessDp * density, bolt = false,
            )
            PillStyle.EDGE_LINE -> drawLine(canvas, w, h, s, f)
        }
        canvas.restoreToCount(layer)
    }

    // ---- Capsule: solid One UI-style pill; level = opaque part, bolt is a cut-out ------------

    private fun drawCapsule(c: Canvas, w: Float, h: Float, s: PillSettings, f: Frame) {
        val rim = rimWidth(s)
        rect.set(rim, rim, w - rim, h - rim)
        val r = rect.height() / 2f
        if (rim > 0f) strokeRim(c, rect, r, rim, f.base)

        paint.style = Paint.Style.FILL
        paint.color = PillColors.withAlpha(f.base, TRACK_ALPHA)
        c.drawRoundRect(rect, r, r, paint)

        clip.reset()
        clip.addRoundRect(rect, r, r, Path.Direction.CW)
        c.save()
        c.clipPath(clip)
        val right = rect.left + rect.width() * f.level / 100f
        paint.color = f.fill
        c.drawRect(rect.left, rect.top, right, rect.bottom, paint)
        drawSweep(c, rect.left, rect.top, right, rect.bottom, f)
        c.restore()

        if (f.plugged && s.showBolt) {
            buildBolt(rect.centerX(), rect.centerY(), rect.height() * 0.78f)
            clearPaint.style = Paint.Style.FILL
            c.drawPath(bolt, clearPaint)
        }
    }

    /** Solid capsule: filled part in the fill colour, the rest a solid tone between base and its opposite. */
    private fun drawOpaqueCapsule(c: Canvas, w: Float, h: Float, s: PillSettings, f: Frame) {
        rect.set(0f, 0f, w, h)
        val r = h / 2f
        val track = PillColors.mix(f.base, PillColors.contrastOf(f.base), OPAQUE_TRACK_MIX)
        val fill = PillColors.mix(f.fill, track, 1f - f.alpha.coerceIn(0f, 1f))

        paint.style = Paint.Style.FILL
        paint.color = track
        c.drawRoundRect(rect, r, r, paint)

        clip.reset()
        clip.addRoundRect(rect, r, r, Path.Direction.CW)
        c.save()
        c.clipPath(clip)
        val right = w * f.level / 100f
        paint.color = fill
        c.drawRect(0f, 0f, right, h, paint)
        drawSweep(c, 0f, 0f, right, h, f)
        c.restore()

        if (f.plugged && s.showBolt) {
            buildBolt(w / 2f, h / 2f, h * 0.7f)
            paint.color = PillColors.contrastOf(fill)
            c.drawPath(bolt, paint)
        }
    }

    // ---- Classic: outline + tip + inset fill ---------------------------------------------------

    private fun drawClassic(c: Canvas, w: Float, h: Float, s: PillSettings, f: Frame) {
        val rim = rimWidth(s)
        val stroke = max(density, h * 0.09f)
        val tipW = max(density * 1.5f, w * 0.075f)
        val gap = stroke * 0.6f
        rect.set(
            rim + stroke / 2f, rim + stroke / 2f,
            w - rim - tipW - gap - stroke / 2f, h - rim - stroke / 2f,
        )
        val r = rect.height() * 0.32f
        if (rim > 0f) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = stroke + rim * 2f
            paint.color = PillColors.withAlpha(PillColors.contrastOf(f.base), RIM_ALPHA)
            c.drawRoundRect(rect, r, r, paint)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        paint.color = PillColors.withAlpha(f.base, OUTLINE_ALPHA)
        c.drawRoundRect(rect, r, r, paint)

        paint.style = Paint.Style.FILL
        val tipH = h * 0.36f
        inner.set(w - rim - tipW, (h - tipH) / 2f, w - rim, (h + tipH) / 2f)
        c.drawRoundRect(inner, tipW * 0.6f, tipW * 0.6f, paint)

        inner.set(rect)
        val pad = stroke / 2f + stroke * 0.8f
        inner.inset(pad, pad)
        val ir = inner.height() * 0.25f
        clip.reset()
        clip.addRoundRect(inner, ir, ir, Path.Direction.CW)
        c.save()
        c.clipPath(clip)
        val right = inner.left + inner.width() * f.level / 100f
        paint.color = f.fill
        c.drawRect(inner.left, inner.top, right, inner.bottom, paint)
        drawSweep(c, inner.left, inner.top, right, inner.bottom, f)
        c.restore()

        if (f.plugged && s.showBolt) {
            buildBolt(rect.centerX(), rect.centerY(), h * 0.86f)
            clearPaint.style = Paint.Style.FILL_AND_STROKE
            clearPaint.strokeWidth = stroke * 1.3f
            c.drawPath(bolt, clearPaint)
            paint.style = Paint.Style.FILL
            paint.color = f.base
            c.drawPath(bolt, paint)
        }
    }

    // ---- Dot / camera ring: circular gauge ---------------------------------------------------

    private fun drawRing(
        c: Canvas, w: Float, h: Float, s: PillSettings, f: Frame, thickness: Float, bolt: Boolean,
    ) {
        val rim = rimWidth(s)
        val d = min(w, h)
        val radius = d / 2f - thickness / 2f - rim
        if (radius <= 0f) return
        val cx = w / 2f
        val cy = h / 2f
        rect.set(cx - radius, cy - radius, cx + radius, cy + radius)

        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        if (rim > 0f) {
            paint.strokeWidth = thickness + rim * 2f
            paint.color = PillColors.withAlpha(PillColors.contrastOf(f.base), RIM_ALPHA)
            c.drawOval(rect, paint)
        }
        paint.strokeWidth = thickness
        paint.color = PillColors.withAlpha(f.base, TRACK_ALPHA)
        c.drawOval(rect, paint)

        val sweepAngle = 360f * f.level / 100f
        paint.color = f.fill
        if (sweepAngle >= 359.9f) c.drawOval(rect, paint) else c.drawArc(rect, -90f, sweepAngle, false, paint)

        if (f.sweep >= 0f && sweepAngle > 1f) {
            // A bright comet travelling along the filled arc.
            val head = sweepAngle * f.sweep
            val len = min(40f, sweepAngle)
            paint.color = highlightFor(f.fill)
            c.drawArc(rect, -90f + max(0f, head - len), min(len, head).coerceAtLeast(0.1f), false, paint)
        }
        paint.strokeCap = Paint.Cap.BUTT

        if (bolt && f.plugged && s.showBolt) {
            buildBolt(cx, cy, (radius - thickness / 2f) * 1.5f)
            paint.style = Paint.Style.FILL
            paint.color = f.fill
            c.drawPath(this.bolt, paint)
        }
    }

    // ---- Edge line: Powerline-style bar ------------------------------------------------------

    private fun drawLine(c: Canvas, w: Float, h: Float, s: PillSettings, f: Frame) {
        val rim = rimWidth(s)
        val inset = s.lineInsetDp * density
        val avail = (w - inset * 2f).coerceAtLeast(0f)
        val len = avail * f.level / 100f
        val left = when (s.lineGrowFrom) {
            Anchor.LEFT -> inset
            Anchor.RIGHT -> w - inset - len
            Anchor.CENTER -> (w - len) / 2f
        }
        rect.set(left, rim, left + len, h - rim)
        val r = rect.height() / 2f
        if (rim > 0f) strokeRim(c, rect, r, rim, f.base)
        paint.style = Paint.Style.FILL
        paint.color = f.fill
        c.drawRoundRect(rect, r, r, paint)
        c.save()
        c.clipRect(rect)
        drawSweep(c, rect.left, rect.top, rect.right, rect.bottom, f)
        c.restore()
    }

    // ---- helpers -----------------------------------------------------------------------------

    private fun rimWidth(s: PillSettings) = if (s.contrastRim) max(1f, density * 0.75f) else 0f

    private fun strokeRim(c: Canvas, r: RectF, radius: Float, rim: Float, base: Int) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = rim * 2f
        paint.color = PillColors.withAlpha(PillColors.contrastOf(base), RIM_ALPHA)
        c.drawRoundRect(r, radius, radius, paint)
    }

    private fun highlightFor(fill: Int): Int =
        if (PillColors.luminance(fill) > 0.6f) PillColors.withAlpha(PillColors.BLACK, 0.28f)
        else PillColors.withAlpha(PillColors.WHITE, 0.6f)

    /** A soft band of light gliding left-to-right across the filled part while charging. */
    private fun drawSweep(c: Canvas, l: Float, t: Float, r: Float, b: Float, f: Frame) {
        if (f.sweep < 0f || r - l < 1f) return
        val band = max((r - l) * 0.5f, (b - t) * 2.5f)
        val x = l - band + (r - l + band) * f.sweep
        val hi = highlightFor(f.fill)
        sweepPaint.shader = LinearGradient(
            x, 0f, x + band, 0f,
            intArrayOf(hi and 0x00FFFFFF, hi, hi and 0x00FFFFFF), floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
        c.drawRect(l, t, r, b, sweepPaint)
    }

    private fun buildBolt(cx: Float, cy: Float, height: Float) {
        val bw = height * 0.62f
        val x0 = cx - bw / 2f
        val y0 = cy - height / 2f
        bolt.reset()
        fun p(x: Float, y: Float) = Pair(x0 + bw * x, y0 + height * y)
        val pts = listOf(
            p(0.62f, 0f), p(0.06f, 0.57f), p(0.45f, 0.57f),
            p(0.36f, 1f), p(0.94f, 0.40f), p(0.55f, 0.40f),
        )
        bolt.moveTo(pts[0].first, pts[0].second)
        for (i in 1 until pts.size) bolt.lineTo(pts[i].first, pts[i].second)
        bolt.close()
    }

    private companion object {
        const val TRACK_ALPHA = 0.32f
        const val OUTLINE_ALPHA = 0.5f
        const val RIM_ALPHA = 0.45f
        const val OPAQUE_TRACK_MIX = 0.62f
    }
}
