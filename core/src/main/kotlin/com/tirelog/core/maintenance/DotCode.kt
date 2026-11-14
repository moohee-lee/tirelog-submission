package com.tirelog.core.maintenance

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters

/** 타이어 옆면 DOT의 제조 주·연도 4자리(예: 2423 = 2023년 24주). */
data class DotCode(val week: Int, val year: Int) {
    val manufactureDate: LocalDate
        get() = LocalDate.of(year, 1, 4)
            .with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, week.toLong())
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    val text: String get() = "%02d%02d".format(week, year % 100)

    val display: String get() = "${year}년 ${week}주"

    fun ageYears(today: LocalDate): Int {
        var years = today.year - manufactureDate.year
        if (manufactureDate.plusYears(years.toLong()).isAfter(today)) years -= 1
        return years.coerceAtLeast(0)
    }

    fun isOlderThan(years: Long, today: LocalDate): Boolean = !manufactureDate.plusYears(years).isAfter(today)

    companion object {
        const val AGE_ALERT_YEARS = 6L

        sealed interface Result {
            data class Valid(val code: DotCode) : Result
            data class Invalid(val reason: String) : Result
        }

        fun parse(input: String, today: LocalDate): Result {
            val digits = input.trim()
            if (!Regex("\\d{4}").matches(digits)) return Result.Invalid("숫자 4자리로 입력해 주세요")
            val week = digits.substring(0, 2).toInt()
            val year = 2000 + digits.substring(2, 4).toInt()
            if (week !in 1..53) return Result.Invalid("주는 01~53이어야 합니다")
            val weeksInYear = LocalDate.of(year, 12, 28).get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
            if (week > weeksInYear) return Result.Invalid("${year}년에는 ${week}주가 없습니다")
            val code = DotCode(week, year)
            if (code.manufactureDate.isAfter(today)) return Result.Invalid("미래 날짜입니다")
            return Result.Valid(code)
        }

        fun parseOrNull(input: String?, today: LocalDate): DotCode? =
            input?.let { (parse(it, today) as? Result.Valid)?.code }
    }
}
