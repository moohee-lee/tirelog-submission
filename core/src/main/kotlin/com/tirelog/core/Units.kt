package com.tirelog.core

import kotlin.math.roundToInt

object Units {
    const val KPA_PER_PSI = 6.894757
    const val ATMOSPHERE_KPA = 101.3

    fun kpaToPsi(kpa: Double): Double = kpa / KPA_PER_PSI

    fun psiToKpa(psi: Double): Double = psi * KPA_PER_PSI

    fun psiRounded(kpa: Double): Int = kpaToPsi(kpa).roundToInt()

    fun psiOneDecimal(kpa: Double): String = String.format(java.util.Locale.ROOT, "%.1f", kpaToPsi(kpa))
}

/** SDK 공기압 값의 단위. AAOS 표준(TIRE_PRESSURE)은 kPa다. */
enum class PressureUnit {
    KPA, PSI, BAR;

    fun toKpa(value: Double): Double = when (this) {
        KPA -> value
        PSI -> Units.psiToKpa(value)
        BAR -> value * 100.0
    }

    companion object {
        /**
         * Pleos SDK의 공기압 단위가 공개되지 않아 네 바퀴 값 중 최댓값으로 판별한다.
         * 승용차 범위에서 bar(1~5)·psi(15~70)·kPa(100~500)는 겹치지 않고,
         * 최댓값을 쓰므로 바퀴 하나가 거의 빠져도 단위가 바뀌지 않는다.
         */
        fun detect(rawValues: Collection<Double>, fallback: PressureUnit = KPA): PressureUnit {
            val max = rawValues.filter { it.isFinite() && it > 0.0 }.maxOrNull() ?: return fallback
            return when {
                max < 10.0 -> BAR
                max < 100.0 -> PSI
                else -> KPA
            }
        }
    }
}
