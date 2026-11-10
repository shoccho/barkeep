package com.github.shoccho.batteryindicatorsma566b.overlay

import android.content.Context
import android.graphics.Canvas
import com.github.shoccho.batteryindicatorsma566b.core.PillRenderer
import com.github.shoccho.batteryindicatorsma566b.core.StatusRowRenderer
import com.github.shoccho.batteryindicatorsma566b.core.StatusState

/** "Own icons" row: the user's status icons followed by the pill, with the pill's animations. */
class StatusRowView(context: Context) : PillView(context) {

    private val row = StatusRowRenderer(resources.displayMetrics.density).apply {
        art = SamsungIconArt(context)
    }

    var status = StatusState()
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    fun preferredWidth(): Int = kotlin.math.ceil(row.width(settings, status)).toInt()
    fun preferredHeight(): Int = kotlin.math.ceil(row.height(settings)).toInt()

    override fun drawContent(canvas: Canvas, base: Int, frame: PillRenderer.Frame) {
        row.draw(canvas, width.toFloat(), height.toFloat(), settings, status, base, frame)
    }
}
