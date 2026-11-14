package com.tirelog.core

import com.tirelog.core.judge.AssessmentEngine
import com.tirelog.core.judge.ColdRule
import com.tirelog.core.judge.TemperatureModel
import com.tirelog.core.judge.TireReading
import com.tirelog.core.model.Baseline
import com.tirelog.core.model.BaselineOrigin
import com.tirelog.core.model.Cause
import com.tirelog.core.model.TrendState
import com.tirelog.core.model.WheelPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AssessmentEngineTest {

    private val positions = WheelPosition.entries

    private fun readings(
        kpa: List<Double>,
        baselineKpa: List<Double?>,
        baselineTemp: Double?,
        baselineDay: Long,
        recommended: Double? = 250.0,
        origin: BaselineOrigin = BaselineOrigin.FIRST,
        previous: List<TrendState?> = listOf(null, null, null, null),
    ) = positions.mapIndexed { i, p ->
        TireReading(
            tireId = i + 1L,
            position = p,
            recommendedKpa = recommended,
            coldKpa = kpa[i],
            baseline = baselineKpa[i]?.let { Baseline(i + 1L, it, baselineTemp, baselineDay, origin) },
            previous = previous[i],
        )
    }

    @Test
    fun `기획서 예시 - 20도 250kPa는 영하 10도에 약 214kPa`() {
        val expected = TemperatureModel.expectedKpa(250.0, 20.0, -10.0)
        assertEquals(214.0, expected, 0.5)
    }

    @Test
    fun `냉간 측정은 3시간 이상 주차 후 1_6km 이내만 인정한다`() {
        assertTrue(ColdRule.isCold(180, 0.9))
        assertFalse(ColdRule.isCold(179, 0.9))
        assertFalse(ColdRule.isCold(600, 1.7))
        assertFalse(ColdRule.isCold(null, 0.5))
        assertFalse(ColdRule.isCold(600, null))
    }

    @Test
    fun `추위로 네 바퀴가 함께 낮아지면 기온 영향과 보충 필요를 함께 낸다`() {
        val a = AssessmentEngine.assess(
            epochDay = 100, tempC = -10.0,
            readings = readings(listOf(214.0, 214.0, 215.0, 214.0), List(4) { 250.0 }, 20.0, 44),
        )
        assertEquals(Cause.TEMPERATURE, a.cause)
        assertTrue(a.tires.all { it.needsRefill })
        assertTrue(a.tires.none { it.leakCondition || it.refillSuspected })
    }

    @Test
    fun `권장값보다 10퍼센트 미만으로 낮으면 보충 필요가 아니다`() {
        val a = AssessmentEngine.assess(100, 20.0, readings(List(4) { 226.0 }, List(4) { null }, null, 0))
        assertTrue(a.tires.none { it.needsRefill })
        val b = AssessmentEngine.assess(100, 20.0, readings(List(4) { 225.0 }, List(4) { null }, null, 0))
        assertTrue(b.tires.all { it.needsRefill })
    }

    @Test
    fun `권장값이 없으면 보충 필요를 판단하지 않는다`() {
        val a = AssessmentEngine.assess(100, 20.0, readings(List(4) { 150.0 }, List(4) { null }, null, 0, recommended = null))
        assertTrue(a.tires.none { it.needsRefill })
        assertEquals(Cause.NONE, a.cause)
    }

    @Test
    fun `기준이 없으면 원인을 보류하고 오늘 값을 새 기준으로 삼는다`() {
        val a = AssessmentEngine.assess(100, 5.0, readings(List(4) { 200.0 }, List(4) { null }, null, 0))
        assertEquals(Cause.UNDETERMINED, a.cause)
        a.tires.forEach {
            assertEquals(200.0, it.baselineAfter.kpa)
            assertEquals(100L, it.baselineAfter.epochDay)
            assertEquals(5.0, it.baselineAfter.tempC)
        }
    }

    @Test
    fun `기온 예상보다 8kPa 이상 높으면 보충 추정이다`() {
        val a = AssessmentEngine.assess(
            101, -8.0, readings(listOf(249.0, 249.0, 250.0, 249.0), List(4) { 250.0 }, 20.0, 44),
        )
        assertTrue(a.tires.all { it.refillSuspected })
        assertTrue(a.tires.none { it.needsRefill })
    }

    @Test
    fun `한 바퀴만 빨리 빠지면 3회 연속일 때만 누설 의심이다`() {
        var previous: List<TrendState?> = List(4) { null }
        val days = listOf(
            -5.0 to listOf(253.0, 249.0, 254.0, 253.0),
            -2.0 to listOf(256.0, 249.0, 257.0, 256.0),
            -6.0 to listOf(251.0, 240.0, 252.0, 251.0),
        )
        val streaks = days.mapIndexed { i, (temp, kpa) ->
            val a = AssessmentEngine.assess(
                epochDay = 102L + i, tempC = temp,
                readings = readings(kpa, listOf(249.0, 249.0, 250.0, 249.0), -8.0, 101, origin = BaselineOrigin.REFILL, previous = previous),
            )
            previous = a.tires.map { it.trendAfter }
            a
        }
        assertEquals(listOf(1, 2, 3), streaks.map { it.tires[1].trendAfter.leakStreak })
        assertFalse(streaks[1].tires[1].leakSuspected)
        assertTrue(streaks[2].tires[1].leakSuspected)
        assertEquals(Cause.LEAK, streaks[2].cause)
        assertTrue(streaks[2].tires.filter { it.position != WheelPosition.FRONT_RIGHT }.none { it.leakCondition })
        assertFalse(streaks[2].tires[1].recheckNeeded, "누설 의심이 뜨면 재점검은 따로 띄우지 않는다")
    }

    @Test
    fun `조건이 하루라도 깨지면 연속 횟수를 다시 센다`() {
        val previous = listOf(null, TrendState(2, leakStreak = 2, declineStreak = 2, lastEvalDay = 103), null, null)
        val a = AssessmentEngine.assess(
            104, -5.0,
            readings(listOf(253.0, 253.0, 254.0, 253.0), listOf(249.0, 249.0, 250.0, 249.0), -8.0, 101, previous = previous),
        )
        assertEquals(0, a.tires[1].trendAfter.leakStreak)
    }

    @Test
    fun `하루 1kPa 센서 오차는 누설로 세지 않는다`() {
        val a = AssessmentEngine.assess(
            102, 20.0, readings(listOf(250.0, 249.0, 250.0, 250.0), List(4) { 250.0 }, 20.0, 101),
        )
        assertFalse(a.tires[1].leakCondition)
    }

    @Test
    fun `보충 뒤 같은 타이어만 꾸준히 빠지는데 상대 조건이 안 되면 재점검이다`() {
        var previous: List<TrendState?> = List(4) { null }
        lateinit var last: com.tirelog.core.judge.Assessment
        // 네 바퀴가 함께 빠져 상대 비교로는 누설이 아니지만, 보충한 타이어가 계속 빠진다
        for (i in 1..3) {
            last = AssessmentEngine.assess(
                10L + i, 10.0,
                readings(List(4) { 250.0 - 3.0 * i }, List(4) { 250.0 }, 10.0, 10, origin = BaselineOrigin.REFILL, previous = previous),
            )
            previous = last.tires.map { it.trendAfter }
        }
        assertTrue(last.tires.none { it.leakSuspected })
        assertTrue(last.tires.all { it.recheckNeeded })
    }

    @Test
    fun `보정 후에도 네 바퀴가 함께 줄면 기온 영향이 아니라 자연 감소다`() {
        val a = AssessmentEngine.assess(
            130, 20.0, readings(List(4) { 240.0 }, List(4) { 250.0 }, 20.0, 100),
        )
        assertEquals(Cause.NATURAL, a.cause)
    }

    @Test
    fun `외기온도가 없으면 기온 영향이라고 하지 않는다`() {
        val a = AssessmentEngine.assess(
            130, null, readings(List(4) { 214.0 }, List(4) { 250.0 }, null, 100),
        )
        assertEquals(Cause.UNDETERMINED, a.cause)
    }

    @Test
    fun `현재 또는 기준 기온이 없으면 누설과 재점검을 확정하지 않고 부족 안내는 유지한다`() {
        for ((baseTemp, temp) in listOf(20.0 to null, null to 20.0)) {
            val prior = positions.mapIndexed { i, _ -> TrendState(i + 1L, 2, 2, 129) }
            val a = AssessmentEngine.assess(130, temp,
                readings(listOf(248.0, 210.0, 248.0, 248.0), List(4) { 250.0 }, baseTemp, 100,
                    origin = BaselineOrigin.REFILL, previous = prior))
            assertEquals(Cause.UNDETERMINED, a.cause)
            assertTrue(a.tires[1].needsRefill)
            assertTrue(a.tires.none { it.leakSuspected || it.recheckNeeded || it.temperatureCorrected })
        }
    }

    @Test
    fun `기온을 모르는 압력 상승으로 보충 여부를 묻지 않는다`() {
        val a = AssessmentEngine.assess(130, null, readings(List(4) { 265.0 }, List(4) { 250.0 }, 20.0, 100))
        assertTrue(a.tires.none { it.refillSuspected })
        assertTrue(a.tires.all { it.baselineAfter.epochDay == 100L && it.baselineAfter.tempC == 20.0 })
    }

    @Test
    fun `비교할 바퀴 하나가 누락되면 누설을 확정하지 않는다`() {
        val prior = positions.mapIndexed { i, _ -> TrendState(i + 1L, 2, 2, 129) }
        val a = AssessmentEngine.assess(130, 20.0,
            readings(listOf(248.0, 210.0, 248.0, 248.0), List(4) { 250.0 }, 20.0, 100, previous = prior).take(3))
        assertTrue(a.tires[1].needsRefill)
        assertTrue(a.tires.none { it.leakSuspected })
    }

    @Test
    fun `기준 기온을 몰랐다면 기온이 있는 첫 냉간 측정을 새 기준으로 삼는다`() {
        val a = AssessmentEngine.assess(130, 5.0, readings(List(4) { 230.0 }, List(4) { 250.0 }, null, 100,
            origin = BaselineOrigin.REPAIR))
        assertTrue(a.tires.all { it.baselineAfter.epochDay == 130L && it.baselineAfter.tempC == 5.0 })
        assertTrue(a.tires.all { it.baselineAfter.origin == BaselineOrigin.REPAIR })
    }
}
