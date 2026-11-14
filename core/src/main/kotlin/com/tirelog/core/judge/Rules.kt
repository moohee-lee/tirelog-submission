package com.tirelog.core.judge

import com.tirelog.core.Units

/**
 * 기획서 4장 판단 규칙의 수치. 모두 초기값이며 실차 데이터로 조정한다.
 */
object JudgmentRules {
    /** 냉간 측정: 3시간 이상 주차 후 출발 1.6km 이내. */
    const val COLD_MIN_PARKED_MINUTES = 180L
    const val COLD_MAX_DISTANCE_KM = 1.6

    /** 보충 필요: 냉간 값이 권장값보다 10% 이상 낮음. */
    const val REFILL_NEEDED_RATIO = 0.90

    /** 보충 추정: 냉간 값이 기온 예상보다 8kPa 이상 높음. */
    const val REFILL_DETECT_KPA = 8.0

    /** 누설 의심: 한 바퀴가 나머지 평균의 2배 넘게, 한 달 5kPa 이상 더 빠짐, 3회 연속. */
    const val LEAK_RATIO = 2.0
    const val LEAK_EXCESS_KPA_PER_MONTH = 5.0
    const val STREAK_REQUIRED = 3

    /** 재점검: 보충·수리 뒤 같은 타이어가 한 달 5kPa 이상 속도로 계속 빠짐(3회 연속). */
    const val DECLINE_KPA_PER_MONTH = 5.0
    const val DECLINE_MIN_LOSS_KPA = 3.0

    /** 하루 1kPa 센서 오차가 한 달 30kPa로 부풀지 않도록 속도 계산 기간을 최소 7일로 둔다. */
    const val MIN_RATE_WINDOW_DAYS = 7.0
    const val DAYS_PER_MONTH = 30.0

    /** 기온 영향·자연 감소 판정: 네 바퀴 보정 후 변화의 폭과 크기. */
    const val SIMILAR_SPREAD_KPA = 3.0
    const val TEMPERATURE_EFFECT_MIN_KPA = 5.0
}

object ColdRule {
    fun isCold(parkedMinutes: Long?, distanceSinceDepartureKm: Double?): Boolean {
        if (parkedMinutes == null || distanceSinceDepartureKm == null) return false
        return parkedMinutes >= JudgmentRules.COLD_MIN_PARKED_MINUTES &&
            distanceSinceDepartureKm >= -0.05 &&
            distanceSinceDepartureKm <= JudgmentRules.COLD_MAX_DISTANCE_KM
    }
}

object TemperatureModel {
    /** 예상값 = (기준값 + 101.3) × (지금 기온 + 273.15) ÷ (기준 기온 + 273.15) − 101.3 (kPa, ℃) */
    fun expectedKpa(baseKpa: Double, baseTempC: Double, nowTempC: Double): Double =
        (baseKpa + Units.ATMOSPHERE_KPA) * (nowTempC + 273.15) / (baseTempC + 273.15) - Units.ATMOSPHERE_KPA
}
