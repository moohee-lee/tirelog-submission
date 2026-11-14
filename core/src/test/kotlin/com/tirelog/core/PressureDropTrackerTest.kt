package com.tirelog.core

import com.tirelog.core.judge.PressureDropTracker
import com.tirelog.core.model.WheelPosition
import kotlin.test.*

class PressureDropTrackerTest {
    private val p = WheelPosition.FRONT_LEFT
    @Test fun `60초 이내 20kPa 이상과 10퍼센트 이상 떨어진 경우만 기록한다`() {
        val t = PressureDropTracker()
        assertNull(t.observe(1, p, 250.0, 0, 0))
        assertNull(t.observe(1, p, 231.0, 10_000, 10_000))
        assertNull(t.observe(1, p, 226.0, 20_000, 20_000))
        assertNotNull(t.observe(1, p, 225.0, 30_000, 30_000))
        assertNull(t.observe(1, p, 190.0, 40_000, 40_000), "동일 구간 중복 기록 방지")
    }
    @Test fun `오래된 측정과 시간 역전 및 위치 변경을 급감으로 오인하지 않는다`() {
        val t = PressureDropTracker()
        t.observe(1, p, 250.0, 0, 0)
        assertNull(t.observe(1, p, 200.0, 61_000, 61_000))
        assertNull(t.observe(1, p, 100.0, 60_000, 61_000))
        assertNull(t.observe(1, WheelPosition.REAR_LEFT, 100.0, 62_000, 62_000))
        assertNull(t.observe(2, p, 100.0, 63_000, 63_000))
        t.clear()
        assertNull(t.observe(1, p, 0.0, 64_000, 64_000))
    }
}
