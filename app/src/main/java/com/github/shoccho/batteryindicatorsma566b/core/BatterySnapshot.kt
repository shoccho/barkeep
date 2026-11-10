package com.github.shoccho.batteryindicatorsma566b.core

import android.content.Intent
import android.os.BatteryManager

/** One reading of the battery, taken from the sticky ACTION_BATTERY_CHANGED broadcast. */
data class BatterySnapshot(
    val percent: Int,
    val plugged: Boolean,
    val status: Int = BatteryManager.BATTERY_STATUS_UNKNOWN,
    val powerSave: Boolean = false,
    val temperatureC: Float = Float.NaN,
    val voltageMv: Int = 0,
    val health: Int = BatteryManager.BATTERY_HEALTH_UNKNOWN,
    val plugType: Int = 0,
    val cycleCount: Int = -1,
) {
    val isFull: Boolean get() = status == BatteryManager.BATTERY_STATUS_FULL || (plugged && percent >= 100)
    val isCharging: Boolean get() = status == BatteryManager.BATTERY_STATUS_CHARGING

    companion object {
        /** Same rounding SystemUI uses (floor), so the pill and Samsung agree on the level. */
        fun percentOf(level: Int, scale: Int): Int =
            if (level < 0 || scale <= 0) 0 else (level * 100 / scale).coerceIn(0, 100)

        fun from(intent: Intent, powerSave: Boolean): BatterySnapshot {
            val plugType = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
            val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            return BatterySnapshot(
                percent = percentOf(
                    intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
                    intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100),
                ),
                plugged = plugType != 0,
                status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN),
                powerSave = powerSave,
                temperatureC = if (temp == Int.MIN_VALUE) Float.NaN else temp / 10f,
                voltageMv = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0),
                health = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN),
                plugType = plugType,
                cycleCount = intent.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1),
            )
        }
    }
}
