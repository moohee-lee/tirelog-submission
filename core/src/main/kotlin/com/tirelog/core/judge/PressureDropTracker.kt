package com.tirelog.core.judge

import com.tirelog.core.model.WheelPosition
import java.util.ArrayDeque

data class PressureSample(val atMillis: Long, val kpa: Double)
data class PressureDrop(val tireId: Long, val position: WheelPosition, val before: PressureSample, val after: PressureSample)

/** 주행 중 변화의 기록 기준이다. 운행 가능 여부를 판단하거나 앱 경고를 만드는 데 쓰지 않는다. */
class PressureDropTracker {
    private data class History(val position: WheelPosition, val samples: ArrayDeque<PressureSample>, var lastRecordedAt: Long? = null)
    private val histories = mutableMapOf<Long, History>()

    fun clear() = histories.clear()

    fun observe(tireId: Long, position: WheelPosition, kpa: Double, atMillis: Long, now: Long): PressureDrop? {
        if (!kpa.isFinite() || kpa < 0 || now - atMillis !in 0..WINDOW_MILLIS) return null
        val history = histories[tireId]?.takeIf { it.position == position }
            ?: History(position, ArrayDeque()).also { histories[tireId] = it }
        if (history.samples.lastOrNull()?.atMillis?.let { atMillis <= it } == true) return null
        while (history.samples.firstOrNull()?.atMillis?.let { atMillis - it > WINDOW_MILLIS } == true) history.samples.removeFirst()
        val before = history.samples.maxByOrNull { it.kpa }
        val after = PressureSample(atMillis, kpa)
        history.samples.addLast(after)
        while (history.samples.size > 256) history.samples.removeFirst()
        if (history.lastRecordedAt?.let { atMillis - it < WINDOW_MILLIS } == true) return null
        if (before == null || before.kpa - kpa < MIN_DROP_KPA || before.kpa - kpa < before.kpa * MIN_DROP_RATIO) return null
        history.lastRecordedAt = atMillis
        history.samples.clear()
        history.samples.addLast(after)
        return PressureDrop(tireId, position, before, after)
    }

    companion object {
        const val WINDOW_MILLIS = 60_000L
        const val MIN_DROP_KPA = 20.0
        const val MIN_DROP_RATIO = 0.10
    }
}
