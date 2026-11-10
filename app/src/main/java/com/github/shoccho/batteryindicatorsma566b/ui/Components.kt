package com.github.shoccho.batteryindicatorsma566b.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Open/closed state of a foldable block. Kept in its own small prefs file (not with the pill's
 * settings), so the page opens the way you left it and "Reset to defaults" doesn't touch it.
 */
class Fold internal constructor(private val prefs: SharedPreferences, private val key: String, initial: Boolean) {
    var open by mutableStateOf(initial)
        private set

    fun toggle() {
        open = !open
        prefs.edit { putBoolean(key, open) }
    }
}

@Composable
fun rememberFold(key: String, openByDefault: Boolean = false): Fold {
    val context = LocalContext.current
    return remember(key) {
        val prefs = context.getSharedPreferences(FOLD_PREFS, Context.MODE_PRIVATE)
        Fold(prefs, key, prefs.getBoolean(key, openByDefault))
    }
}

private const val FOLD_PREFS = "ui_folds"

/**
 * One UI-style rounded section card with a small heading above it. With a [fold], the card
 * collapses to one tappable line showing [summary].
 */
@Composable
fun Section(
    title: String,
    modifier: Modifier = Modifier,
    fold: Fold? = null,
    summary: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 20.dp, bottom = 8.dp, top = 8.dp),
        )
        Card(
            shape = RoundedCornerShape(26.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (fold == null) {
                Column(Modifier.padding(vertical = 8.dp), content = content)
            } else {
                Column(Modifier.animateContentSize()) {
                    FoldHeader(
                        title = if (fold.open) "Hide" else (summary ?: "Show"),
                        open = fold.open,
                        onClick = fold::toggle,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                        quiet = fold.open,
                    )
                    if (fold.open) {
                        HorizontalDivider(Modifier.padding(horizontal = 20.dp))
                        Column(Modifier.padding(vertical = 8.dp), content = content)
                    }
                }
            }
        }
    }
}

/** A foldable group inside a section card: a titled row that opens to reveal [content]. */
@Composable
fun FoldGroup(
    title: String,
    fold: Fold,
    summary: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        HorizontalDivider(Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = fold::toggle)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (summary != null) {
                    Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Chevron(fold.open)
        }
        if (fold.open) Column(content = content)
    }
}

@Composable
private fun FoldHeader(title: String, open: Boolean, onClick: () -> Unit, modifier: Modifier, quiet: Boolean) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).then(modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (quiet) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Chevron(open)
    }
}

/** Down-pointing chevron that turns to point up when open. */
@Composable
private fun Chevron(open: Boolean) {
    val turn by animateFloatAsState(if (open) 180f else 0f, label = "chevron")
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(20.dp).rotate(turn)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.25f, h * 0.38f)
            lineTo(w * 0.5f, h * 0.63f)
            lineTo(w * 0.75f, h * 0.38f)
        }
        drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled, modifier = Modifier.padding(start = 12.dp))
    }
}

/** Slider with a value read-out and − / + buttons for precise 1-step nudges. */
@Composable
fun SliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    step: Float = 0.5f,
    format: (Float) -> String = { formatDp(it) },
) {
    val snap = { v: Float -> ((v / step).roundToInt() * step).coerceIn(range.start, range.endInclusive) }
    Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                format(value),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepButton("−") { onValueChange(snap(value - step)) }
            Slider(
                value = value.coerceIn(range.start, range.endInclusive),
                onValueChange = { onValueChange(snap(it)) },
                valueRange = range,
                modifier = Modifier.weight(1f),
            )
            StepButton("+") { onValueChange(snap(value + step)) }
        }
    }
}

@Composable
private fun StepButton(label: String, onStep: () -> Unit) {
    RepeatingButton(onStep = onStep, tonal = false, modifier = Modifier.width(44.dp)) {
        Text(label, style = MaterialTheme.typography.titleLarge)
    }
}

/** Fires once on tap, then keeps firing while held — for pixel-perfect nudging. */
@Composable
fun RepeatingButton(
    onStep: () -> Unit,
    modifier: Modifier = Modifier,
    tonal: Boolean = true,
    content: @Composable () -> Unit,
) {
    val latest by rememberUpdatedState(onStep)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    LaunchedEffect(pressed) {
        if (pressed) {
            delay(420)
            while (true) {
                latest()
                delay(55)
            }
        }
    }
    if (tonal) {
        FilledTonalButton(
            onClick = { latest() },
            interactionSource = interaction,
            modifier = modifier,
            contentPadding = PaddingValues(0.dp),
        ) { content() }
    } else {
        TextButton(
            onClick = { latest() },
            interactionSource = interaction,
            modifier = modifier,
            contentPadding = PaddingValues(0.dp),
        ) { content() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> Segmented(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        options.forEachIndexed { i, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                label = { Text(label(option), maxLines = 1) },
            )
        }
    }
}

/** Four-way arrow pad for moving the pill. */
@Composable
fun NudgePad(onMove: (dx: Float, dy: Float) -> Unit) {
    val step = 0.5f
    Column(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Arrow("↑") { onMove(0f, -step) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Arrow("←") { onMove(-step, 0f) }
            Text(
                "move\n½ dp",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(56.dp),
            )
            Arrow("→") { onMove(step, 0f) }
        }
        Arrow("↓") { onMove(0f, step) }
    }
}

@Composable
private fun Arrow(glyph: String, onStep: () -> Unit) {
    RepeatingButton(onStep = onStep, modifier = Modifier.size(52.dp)) {
        Text(glyph, style = MaterialTheme.typography.titleLarge)
    }
}

fun formatDp(v: Float): String =
    if (v % 1f == 0f) "${v.toInt()} dp" else String.format(Locale.US, "%.1f dp", v)
