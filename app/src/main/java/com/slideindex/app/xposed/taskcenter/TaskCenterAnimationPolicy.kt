package com.slideindex.app.xposed.taskcenter

import kotlin.math.roundToLong

/** 动画时长策略：speedIndex 越大越慢（与原 FanFreeform 一致）。 */
object TaskCenterAnimationPolicy {
    private val MULTIPLIERS = floatArrayOf(0.68f, 0.84f, 1f, 1.25f, 1.55f)

    fun durationMs(baseDurationMs: Long, speedIndex: Int): Long {
        val clamped = speedIndex.coerceIn(0, MULTIPLIERS.lastIndex)
        return maxOf(1L, (baseDurationMs * MULTIPLIERS[clamped]).roundToLong())
    }
}
