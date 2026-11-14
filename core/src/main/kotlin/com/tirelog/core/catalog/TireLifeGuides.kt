package com.tirelog.core.catalog

import com.tirelog.core.model.TireSet

/** 국내 공식 안내를 확인해 담은 오프라인 기준표. 보증거리와 UTQG는 수명값으로 변환하지 않는다. */
data class TireLifeGuide(
    val id: String,
    val label: String,
    val recommendedKm: Int?,
    val recommendedMonths: Int?,
    val sourceUrl: String?,
    val explanation: String,
    val distanceBasis: String? = null,
    val periodBasis: String? = null,
    val sourceTitle: String? = null,
) {
    val defaultKm: Int get() = recommendedKm ?: TireSet.DEFAULT_INSPECTION_KM
    val defaultMonths: Int get() = recommendedMonths ?: TireSet.DEFAULT_INSPECTION_MONTHS
}

object TireLifeGuides {
    const val VERIFIED_ON = "2026-11-07"
    const val DEFAULT_ID = "app"
    val entries = listOf(
        TireLifeGuide(DEFAULT_ID, "기타·모름", null, null, null,
            "확인된 제조사·제품 기준이 없습니다. 앱의 점검 기본값을 쓰거나 직접 입력하세요."),
        TireLifeGuide("nexen", "넥센 · 일반", 40_000, 48,
            "https://www.nexentire.com/kr/faq.php",
            "국내 FAQ의 일반 교체 안내 4~5만km·4~5년에서 이른 시점을 점검 기본값으로 사용합니다.",
            "넥센의 일반 안내 4~5만km 중 이른 값인 4만km를 앱의 알림 시점으로 정했습니다.",
            "넥센의 일반 안내 4~5년 중 이른 값인 4년을 앱의 알림 시점으로 정했습니다.", "넥센 국내 FAQ"),
        TireLifeGuide("nexen_ev", "넥센 · 전기차", 30_000, 48,
            "https://www.nexentire.com/kr/faq.php",
            "국내 FAQ의 전기차 평균 교체 거리 3~4만km와 일반 사용 기간 4~5년에서 이른 시점을 사용합니다. 차종 무게로 계산한 값은 아닙니다.",
            "넥센의 전기차 일반 안내 3~4만km 중 이른 값인 3만km를 앱의 알림 시점으로 정했습니다.",
            "넥센의 일반 사용 기간 안내 4~5년 중 이른 값인 4년을 사용합니다. 전기차 전용 기간은 아닙니다.", "넥센 국내 FAQ"),
        TireLifeGuide("michelin", "미쉐린", null, 60,
            "https://www.michelin.co.kr/auto/advice/change-tyres/how-long-do-tyres-last",
            "국내 안내의 사용 5년 후 전문가 점검 기준입니다. 공통 교체 거리 기준이 없어 거리는 앱 기본값을 사용합니다.",
            periodBasis = "미쉐린은 사용 5년이 지나면 매년 전문가 점검을 안내합니다. 앱은 첫 5년 시점에 알립니다.",
            sourceTitle = "미쉐린 코리아 수명 안내"),
        TireLifeGuide("hankook", "한국타이어", null, null,
            "https://www.hankooktire.com/kr/ko/help-support/care-guide/replacement.html",
            "국내 안내는 마모·손상 상태로 교체를 판단합니다. 공통 거리·기간 수치가 없어 앱 기본값을 사용합니다.",
            sourceTitle = "한국타이어 교체 안내"),
    )

    fun find(id: String): TireLifeGuide = entries.firstOrNull { it.id == id } ?: entries.first()

    fun sourceLabel(value: Int, recommended: Int?, fallback: Int): String = when {
        recommended != null && value == recommended -> "제조사 일반 안내"
        recommended == null && value == fallback -> "앱 기본값"
        else -> "사용자 설정"
    }

    fun basis(guide: TireLifeGuide, value: Int, distance: Boolean): String {
        val recommended = if (distance) guide.recommendedKm else guide.recommendedMonths
        val fallback = if (distance) TireSet.DEFAULT_INSPECTION_KM else TireSet.DEFAULT_INSPECTION_MONTHS
        return when (sourceLabel(value, recommended, fallback)) {
            "제조사 일반 안내" -> (if (distance) guide.distanceBasis else guide.periodBasis).orEmpty()
            "앱 기본값" -> "확인된 제품별 ${if (distance) "거리" else "기간"} 기준이 없어 앱이 제안한 알림값입니다. 제조사 권장 수명이 아닙니다."
            else -> "직접 정한 알림값입니다. 제조사 관리 안내나 정비소에서 받은 권고에 맞춰 조정하세요."
        }
    }

    fun periodLabel(months: Int): String = when {
        months < 12 -> "${months}개월"
        months % 12 == 0 -> "${months}개월 (${months / 12}년)"
        else -> "${months}개월 (${months / 12}년 ${months % 12}개월)"
    }
}
