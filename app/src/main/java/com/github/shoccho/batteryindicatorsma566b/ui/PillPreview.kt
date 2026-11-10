package com.github.shoccho.batteryindicatorsma566b.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.github.shoccho.batteryindicatorsma566b.core.BatterySnapshot
import com.github.shoccho.batteryindicatorsma566b.core.PillColors
import com.github.shoccho.batteryindicatorsma566b.core.PillRenderer
import com.github.shoccho.batteryindicatorsma566b.core.PillSettings
import com.github.shoccho.batteryindicatorsma566b.core.PillStyle

/**
 * Draws the indicator with the very same [PillRenderer] as the overlay, magnified by [scale],
 * with the same animations. Camera-ring previews get a fake punch hole in the middle.
 */
@Composable
fun PillPreview(
    settings: PillSettings,
    battery: BatterySnapshot,
    night: Boolean,
    scale: Float,
    modifier: Modifier = Modifier,
    holeDp: Float = 22.7f,
    lineLengthDp: Float = 90f,
    animate: Boolean = true,
    matchedTint: Int? = null,
    /** Preview the solid "Samsung's spot" capsule. */
    opaque: Boolean = false,
) {
    val density = LocalDensity.current.density
    val renderer = remember(density) { PillRenderer(density) }

    val (wDp, hDp) = when (settings.style) {
        PillStyle.CAPSULE, PillStyle.CLASSIC -> settings.widthDp to settings.heightDp
        PillStyle.DOT -> settings.heightDp to settings.heightDp
        PillStyle.CAMERA_RING -> {
            val d = holeDp + 2f * (settings.ringGapDp + settings.ringThicknessDp)
            d to d
        }
        PillStyle.EDGE_LINE -> lineLengthDp to settings.lineThicknessDp
    }

    val level by animateFloatAsState(battery.percent.toFloat(), tween(700), label = "level")
    val transition = rememberInfiniteTransition(label = "pill")
    val phase by transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(3600, easing = LinearEasing)), label = "sweep",
    )
    val pulsePhase by transition.animateFloat(
        1f, 0.25f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse",
    )
    val sweeping = animate && settings.chargingAnimation && battery.isCharging
    val sweep = if (sweeping && phase < 0.42f) FastOutSlowInEasing.transform(phase / 0.42f) else -1f
    val critical = animate && settings.criticalPulse && !battery.plugged &&
        battery.percent <= PillSettings.CRITICAL_PERCENT
    val base = PillColors.base(settings.colorMode, settings.customColor, night, matchedTint)

    Canvas(
        modifier
            .size((wDp * scale).dp, (hDp * scale).dp)
            .alpha(settings.opacity / 100f),
    ) {
        if (settings.style == PillStyle.CAMERA_RING) {
            drawCircle(Color(0xFF050505), radius = holeDp * density * scale / 2f)
            drawCircle(Color(0xFF1C2233), radius = holeDp * density * scale / 5f)
        }
        drawIntoCanvas { c ->
            val nc = c.nativeCanvas
            nc.save()
            nc.scale(scale, scale)
            renderer.draw(
                nc, wDp * density, hDp * density, settings,
                PillRenderer.Frame(
                    level = level,
                    base = base,
                    fill = PillColors.fill(settings, base, battery),
                    plugged = battery.plugged,
                    sweep = sweep,
                    alpha = if (critical) pulsePhase else 1f,
                    opaque = opaque,
                ),
            )
            nc.restore()
        }
    }
}
