package com.tirelog.core.season

import com.tirelog.core.TimeLabels
import com.tirelog.core.model.TireSetType
import java.time.LocalDate

object SeasonCalendar {
    const val THRESHOLD_C = 7.0

    /** 평년(비윤년) 기준 월 중앙 일자. */
    private val midMonthDay = intArrayOf(15, 46, 74, 105, 135, 166, 196, 227, 258, 288, 319, 349)

    private fun points(region: Region): List<Pair<Double, Double>> {
        val means = region.monthlyMeanC
        val pts = mutableListOf(-16.0 to means[11])
        midMonthDay.forEachIndexed { i, d -> pts += d.toDouble() to means[i] }
        pts += (365.0 + 15.0) to means[0]
        return pts
    }

    /** 가을에 일평균기온이 7℃ 아래로 내려가는 날(1월 1일 = 1, 해를 넘기면 365보다 크다). */
    fun autumnCrossDay(region: Region): Int? = points(region).zipWithNext().firstNotNullOfOrNull { (a, b) ->
        val (d1, t1) = a
        val (d2, t2) = b
        if (d1 >= 196 && t1 >= THRESHOLD_C && t2 < THRESHOLD_C) {
            Math.round(d1 + (t1 - THRESHOLD_C) / (t1 - t2) * (d2 - d1)).toInt()
        } else {
            null
        }
    }

    /** 봄에 일평균기온이 7℃ 위로 올라가는 날. */
    fun springCrossDay(region: Region): Int? = points(region).zipWithNext().firstNotNullOfOrNull { (a, b) ->
        val (d1, t1) = a
        val (d2, t2) = b
        if (d2 <= 196 && t1 < THRESHOLD_C && t2 >= THRESHOLD_C) {
            Math.round(d1 + (THRESHOLD_C - t1) / (t2 - t1) * (d2 - d1)).toInt()
        } else {
            null
        }
    }

    fun dateOf(year: Int, dayOfYear: Int): LocalDate = LocalDate.of(year, 1, 1).plusDays(dayOfYear - 1L)
}

data class SeasonNotice(
    val key: String,
    val targetType: TireSetType,
    val message: String,
)

/**
 * 시즌 교체 알림(MVP 추가): 평년 기온이 7℃를 지나기 30일 전에 알리고,
 * 아침 기온이 7℃ 아래(봄에는 위)로 사흘 이어지면 다시 알린다. 위치 정보는 쓰지 않는다.
 */
object SeasonAdvisor {
    const val PREVIEW_DAYS = 30L
    const val CONFIRM_MORNINGS = 3

    /** @param mornings 날짜별 아침(그날 첫 냉간 측정) 외기온도. */
    fun evaluate(
        today: LocalDate,
        region: Region?,
        mountedType: TireSetType?,
        mornings: List<Pair<LocalDate, Double>>,
    ): SeasonNotice? = when (mountedType) {
        TireSetType.SUMMER -> winterNotice(today, region, mornings)
        TireSetType.WINTER -> summerNotice(today, region, mornings)
        else -> null
    }

    private fun winterNotice(today: LocalDate, region: Region?, mornings: List<Pair<LocalDate, Double>>): SeasonNotice? {
        val month = today.monthValue
        val seasonYear = if (month <= 6) today.year - 1 else today.year
        val cross = region?.let(SeasonCalendar::autumnCrossDay)?.let { SeasonCalendar.dateOf(seasonYear, it) }
        val inColdSeason = if (cross != null) {
            !today.isBefore(cross.minusDays(PREVIEW_DAYS)) && (month >= 9 || month <= 2)
        } else {
            month >= 10 || month <= 2
        }
        val mornings3 = lastMornings(today, mornings)
        if (inColdSeason && mornings3.size == CONFIRM_MORNINGS && mornings3.all { it < SeasonCalendar.THRESHOLD_C }) {
            return SeasonNotice(
                key = "SEASON:WINTER:$seasonYear:COLD",
                targetType = TireSetType.WINTER,
                message = "아침 기온이 7℃ 아래로 사흘 이어졌습니다. 겨울 세트 교체를 권합니다.",
            )
        }
        if (cross != null && !today.isBefore(cross.minusDays(PREVIEW_DAYS)) && today.isBefore(cross)) {
            return SeasonNotice("SEASON:WINTER:$seasonYear:PREVIEW", TireSetType.WINTER,
                "평년 기준 ${TimeLabels.monthDay(cross)}쯤 기온이 7℃ 아래로 내려갑니다. 겨울 세트 교체를 예약하세요.")
        }
        return null
    }

    private fun summerNotice(today: LocalDate, region: Region?, mornings: List<Pair<LocalDate, Double>>): SeasonNotice? {
        val month = today.monthValue
        if (month > 6) return null
        val year = today.year
        val cross = region?.let(SeasonCalendar::springCrossDay)?.let { SeasonCalendar.dateOf(year, it) }
        val inWarmingSeason = if (cross != null) !today.isBefore(cross.minusDays(PREVIEW_DAYS)) else month >= 3
        val mornings3 = lastMornings(today, mornings)
        if (inWarmingSeason && mornings3.size == CONFIRM_MORNINGS && mornings3.all { it >= SeasonCalendar.THRESHOLD_C }) {
            return SeasonNotice(
                key = "SEASON:SUMMER:$year:WARM",
                targetType = TireSetType.SUMMER,
                message = "아침 기온이 7℃ 위로 사흘 이어졌습니다. 여름 세트 교체를 권합니다.",
            )
        }
        if (cross != null && !today.isBefore(cross.minusDays(PREVIEW_DAYS)) && today.isBefore(cross)) {
            return SeasonNotice("SEASON:SUMMER:$year:PREVIEW", TireSetType.SUMMER,
                "평년 기준 ${TimeLabels.monthDay(cross)}쯤 기온이 7℃ 위로 올라갑니다. 여름 세트 교체를 예약하세요.")
        }
        return null
    }

    /** 최근 5일 안의 아침 기온 중 마지막 3개(날짜 오름차순). */
    private fun lastMornings(today: LocalDate, mornings: List<Pair<LocalDate, Double>>): List<Double> =
        mornings
            .filter { (d, _) -> !d.isAfter(today) && !d.isBefore(today.minusDays(4)) }
            .distinctBy { it.first }
            .sortedBy { it.first }
            .takeLast(CONFIRM_MORNINGS)
            .takeIf { list -> list.zipWithNext().all { (a, b) -> a.first.plusDays(1) == b.first } }.orEmpty()
            .map { it.second }
}
