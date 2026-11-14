package com.tirelog.core.judge

import com.tirelog.core.judge.JudgmentRules.DAYS_PER_MONTH
import com.tirelog.core.judge.JudgmentRules.DECLINE_KPA_PER_MONTH
import com.tirelog.core.judge.JudgmentRules.DECLINE_MIN_LOSS_KPA
import com.tirelog.core.judge.JudgmentRules.LEAK_EXCESS_KPA_PER_MONTH
import com.tirelog.core.judge.JudgmentRules.LEAK_RATIO
import com.tirelog.core.judge.JudgmentRules.MIN_RATE_WINDOW_DAYS
import com.tirelog.core.judge.JudgmentRules.REFILL_DETECT_KPA
import com.tirelog.core.judge.JudgmentRules.REFILL_NEEDED_RATIO
import com.tirelog.core.judge.JudgmentRules.SIMILAR_SPREAD_KPA
import com.tirelog.core.judge.JudgmentRules.STREAK_REQUIRED
import com.tirelog.core.judge.JudgmentRules.TEMPERATURE_EFFECT_MIN_KPA
import com.tirelog.core.model.Baseline
import com.tirelog.core.model.BaselineOrigin
import com.tirelog.core.model.Cause
import com.tirelog.core.model.TrendState
import com.tirelog.core.model.WheelPosition
import kotlin.math.abs
import kotlin.math.max

data class TireReading(
    val tireId: Long,
    val position: WheelPosition,
    /** null이면 권장값 미확정이라 보충 필요를 판단하지 않는다. */
    val recommendedKpa: Double?,
    val coldKpa: Double,
    val baseline: Baseline?,
    val previous: TrendState?,
)

data class TireAssessment(
    val tireId: Long,
    val position: WheelPosition,
    val coldKpa: Double,
    val recommendedKpa: Double?,
    val needsRefill: Boolean,
    val shortfallKpa: Double?,
    val expectedKpa: Double?,
    val correctedLossKpa: Double?,
    val lossRatePerMonth: Double?,
    val temperatureCorrected: Boolean,
    val refillSuspected: Boolean,
    val leakCondition: Boolean,
    val declineCondition: Boolean,
    val leakSuspected: Boolean,
    val recheckNeeded: Boolean,
    val baselineUsed: Baseline?,
    val baselineAfter: Baseline,
    val trendAfter: TrendState,
)

data class Assessment(
    val epochDay: Long,
    val tempC: Double?,
    val baselineTempC: Double?,
    val cause: Cause,
    val tires: List<TireAssessment>,
)

/**
 * 하루 첫 유효 냉간 측정으로 "지금 부족한가"(보충 필요)와 "왜 빠졌나"(원인)를 따로 판단한다.
 */
object AssessmentEngine {

    private class Work(
        val reading: TireReading,
        val trendCapable: Boolean,
        val expected: Double?,
        val corrected: Double?,
        val rate: Double?,
        val temperatureCorrected: Boolean,
        val refillSuspected: Boolean,
    )

    fun assess(epochDay: Long, tempC: Double?, readings: List<TireReading>): Assessment {
        val works = readings.map { work(epochDay, tempC, it) }

        val tires = works.map { w ->
            val r = w.reading
            val recommended = r.recommendedKpa
            val needsRefill = recommended != null && r.coldKpa <= recommended * REFILL_NEEDED_RATIO + 1e-9
            val comparable = w.trendCapable && !w.refillSuspected
            val others = works.filter { it !== w && it.trendCapable && !it.refillSuspected }.mapNotNull { it.rate }
            val leakCondition = comparable && others.size == WheelPosition.entries.size - 1 && run {
                val othersMean = max(others.average(), 0.0)
                w.rate!! > LEAK_RATIO * othersMean && (w.rate - othersMean) >= LEAK_EXCESS_KPA_PER_MONTH
            }
            val declineCondition = comparable &&
                w.rate!! >= DECLINE_KPA_PER_MONTH && w.corrected!! >= DECLINE_MIN_LOSS_KPA

            val previous = r.previous ?: TrendState(r.tireId)
            val sameDay = previous.lastEvalDay == epochDay
            val leakStreak = when {
                !comparable -> 0
                sameDay -> previous.leakStreak
                leakCondition -> previous.leakStreak + 1
                else -> 0
            }
            val declineStreak = when {
                !comparable -> 0
                sameDay -> previous.declineStreak
                declineCondition -> previous.declineStreak + 1
                else -> 0
            }
            val leakSuspected = leakStreak >= STREAK_REQUIRED
            val origin = r.baseline?.origin
            val recheckNeeded = !leakSuspected && declineStreak >= STREAK_REQUIRED &&
                (origin == BaselineOrigin.REFILL || origin == BaselineOrigin.REPAIR)

            TireAssessment(
                tireId = r.tireId,
                position = r.position,
                coldKpa = r.coldKpa,
                recommendedKpa = r.recommendedKpa,
                needsRefill = needsRefill,
                shortfallKpa = recommended?.let { it - r.coldKpa },
                expectedKpa = w.expected,
                correctedLossKpa = w.corrected,
                lossRatePerMonth = w.rate,
                temperatureCorrected = w.temperatureCorrected,
                refillSuspected = w.refillSuspected,
                leakCondition = leakCondition,
                declineCondition = declineCondition,
                leakSuspected = leakSuspected,
                recheckNeeded = recheckNeeded,
                baselineUsed = if (w.trendCapable) r.baseline else null,
                baselineAfter = baselineAfter(epochDay, tempC, w),
                trendAfter = TrendState(r.tireId, leakStreak, declineStreak, epochDay),
            )
        }

        val cause = cause(works, tires)
        val baselineTemp = works.firstOrNull { it.trendCapable }?.reading?.baseline?.tempC
        return Assessment(epochDay, tempC, baselineTemp, cause, tires)
    }

    private fun work(epochDay: Long, tempC: Double?, r: TireReading): Work {
        val b = r.baseline
        val baseKpa = b?.kpa
        val baseDay = b?.epochDay
        if (b == null || baseKpa == null || baseDay == null || baseDay >= epochDay) {
            return Work(r, false, null, null, null, false, false)
        }
        val baseTemp = b.tempC
        // 원인·보충 추정은 기온 보정이 가능한 측정에서만 한다. 부족 여부는 위에서 별도로 판단한다.
        if (tempC == null || baseTemp == null) return Work(r, false, null, null, null, false, false)
        val expected = TemperatureModel.expectedKpa(baseKpa, baseTemp, tempC)
        val corrected = expected - r.coldKpa
        val window = max((epochDay - baseDay).toDouble(), MIN_RATE_WINDOW_DAYS)
        val rate = corrected / window * DAYS_PER_MONTH
        val refillSuspected = r.coldKpa - expected >= REFILL_DETECT_KPA
        return Work(r, true, expected, corrected, rate, true, refillSuspected)
    }

    private fun baselineAfter(epochDay: Long, tempC: Double?, w: Work): Baseline {
        val r = w.reading
        val current = r.baseline
        if (w.trendCapable && current != null) return current
        // 현재 기온 누락으로 이미 유효한 기준을 잃지 않는다. 기준 기온이 없었다면 다음 측정으로 보완한다.
        if (tempC == null && current != null && !current.isPending) return current
        if (current != null && !current.isPending && current.epochDay == epochDay) return current
        return Baseline(
            tireId = r.tireId,
            kpa = r.coldKpa,
            tempC = tempC,
            epochDay = epochDay,
            origin = current?.origin ?: BaselineOrigin.FIRST,
        )
    }

    private fun cause(works: List<Work>, tires: List<TireAssessment>): Cause {
        if (tires.any { it.leakSuspected }) return Cause.LEAK
        val capable = works.filter { it.trendCapable }
        val base = if (works.size >= 4 && capable.size == works.size && capable.none { it.refillSuspected }) {
            val losses = capable.map { it.corrected!! }
            val spread = losses.max() - losses.min()
            val meanLoss = losses.average()
            val rawDrop = capable.map { it.reading.baseline!!.kpa!! - it.reading.coldKpa }.average()
            val allTemperatureCorrected = capable.all { it.temperatureCorrected }
            when {
                spread > SIMILAR_SPREAD_KPA -> Cause.NONE
                allTemperatureCorrected && abs(meanLoss) <= SIMILAR_SPREAD_KPA &&
                    rawDrop >= TEMPERATURE_EFFECT_MIN_KPA -> Cause.TEMPERATURE
                meanLoss > SIMILAR_SPREAD_KPA -> if (allTemperatureCorrected) Cause.NATURAL else Cause.UNDETERMINED
                else -> Cause.NONE
            }
        } else {
            Cause.NONE
        }
        return if (base == Cause.NONE && tires.any { it.needsRefill }) Cause.UNDETERMINED else base
    }
}
