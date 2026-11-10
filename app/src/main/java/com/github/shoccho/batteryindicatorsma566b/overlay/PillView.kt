package com.github.shoccho.batteryindicatorsma566b.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import com.github.shoccho.batteryindicatorsma566b.core.BatterySnapshot
import com.github.shoccho.batteryindicatorsma566b.core.PillColors
import com.github.shoccho.batteryindicatorsma566b.core.PillRenderer
import com.github.shoccho.batteryindicatorsma566b.core.PillSettings

/**
 * The view living in the overlay window. Owns the animations (level glide, charging shimmer,
 * critical pulse) and pauses all of them whenever it isn't [active] (screen off / hidden), so it
 * costs nothing while you aren't looking at it.
 */
class PillView(context: Context) : View(context) {

    private val renderer = PillRenderer(resources.displayMetrics.density)

    private var settings = PillSettings()
    private var battery: BatterySnapshot? = null
    private var night = false
    private var matchedTint: Int? = null
    private var active = false

    /** Draw solid (sitting on Samsung's battery). */
    var opaque = false
        set(value) {
            field = value
            invalidate()
        }

    private var shownLevel = -1f
    private var sweep = -1f
    private var pulse = 1f

    private var levelAnim: ValueAnimator? = null

    private val sweepEase = AccelerateDecelerateInterpolator()
    private val sweepAnim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = SWEEP_CYCLE_MS
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            val p = it.animatedValue as Float
            // Glide across during the first part of the cycle, then rest.
            val next = if (p < SWEEP_FRACTION) sweepEase.getInterpolation(p / SWEEP_FRACTION) else -1f
            if (next != sweep) {
                sweep = next
                invalidate()
            }
        }
    }

    private val pulseAnim = ValueAnimator.ofFloat(1f, 0.25f).apply {
        duration = 900
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        addUpdateListener {
            pulse = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun bind(settings: PillSettings, battery: BatterySnapshot?, night: Boolean, matchedTint: Int? = null) {
        this.settings = settings
        this.night = night
        this.matchedTint = matchedTint
        if (battery != null) setLevel(battery)
        updateAnimations()
        invalidate()
    }

    fun setActive(active: Boolean) {
        if (this.active == active) return
        this.active = active
        if (!active) levelAnim?.end()
        updateAnimations()
    }

    private fun setLevel(next: BatterySnapshot) {
        val target = next.percent.toFloat()
        val previous = battery
        battery = next
        if (shownLevel < 0f || !active || previous?.percent == next.percent) {
            if (levelAnim?.isRunning != true) shownLevel = target
            return
        }
        levelAnim?.cancel()
        levelAnim = ValueAnimator.ofFloat(shownLevel, target).apply {
            duration = 700
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                shownLevel = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun updateAnimations() {
        val b = battery
        // Only while current is actually flowing in — not when plugged in but held (Samsung
        // Battery protection stops at 80 %, status reports NOT_CHARGING) or full.
        val wantSweep = active && settings.chargingAnimation && b != null && b.isCharging
        if (wantSweep && !sweepAnim.isStarted) sweepAnim.start()
        if (!wantSweep && sweepAnim.isStarted) {
            sweepAnim.cancel()
            sweep = -1f
        }

        val wantPulse = active && settings.criticalPulse && b != null && !b.plugged &&
            b.percent <= PillSettings.CRITICAL_PERCENT
        if (wantPulse && !pulseAnim.isStarted) pulseAnim.start()
        if (!wantPulse && pulseAnim.isStarted) {
            pulseAnim.cancel()
            pulse = 1f
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        levelAnim?.cancel()
        sweepAnim.cancel()
        pulseAnim.cancel()
    }

    override fun onDraw(canvas: Canvas) {
        val b = battery ?: return
        val base = PillColors.base(settings.colorMode, settings.customColor, night, matchedTint)
        renderer.draw(
            canvas, width.toFloat(), height.toFloat(), settings,
            PillRenderer.Frame(
                level = shownLevel.coerceIn(0f, 100f),
                base = base,
                fill = PillColors.fill(settings, base, b),
                plugged = b.plugged,
                sweep = sweep,
                alpha = pulse,
                opaque = opaque,
            ),
        )
    }

    private companion object {
        const val SWEEP_CYCLE_MS = 3600L
        const val SWEEP_FRACTION = 0.42f
    }
}
