package com.tirelog.core.demo

import com.tirelog.core.model.WheelPosition
import com.tirelog.core.model.WheelPosition.FRONT_LEFT
import com.tirelog.core.model.WheelPosition.FRONT_RIGHT
import com.tirelog.core.model.WheelPosition.REAR_LEFT
import com.tirelog.core.model.WheelPosition.REAR_RIGHT
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * 시연 시나리오(기획서 10장, 유저 시나리오 5장)의 테스트 데이터.
 * 바퀴별 공기압·외기온도를 VHAL로 넣지 못할 때 이 값을 재생하고, 시연에서 테스트 데이터 구간임을 밝힌다.
 */
object DemoStoryline {
    val ZONE: ZoneId = ZoneId.of("Asia/Seoul")
    const val RECOMMENDED_KPA = 250.0
    const val INSTALL_ODO_KM = 20_000.0
    const val TIRE_SIZE = "235/55R19"
    const val SUMMER_DOT = "1224"
    const val WINTER_DOT = "4220"
    const val SHOP_ODO_KM = 23_500.0
    const val NUT_DUE_ODO_KM = 23_550.0
    const val SET_CHANGE_ODO_KM = 23_700.0
    val DEPART_TIME: LocalTime = LocalTime.of(7, 50)

    data class ColdDay(
        val title: String,
        val date: LocalDate,
        val tempC: Double,
        val departOdoKm: Double,
        val kpa: Map<WheelPosition, Double>,
        val expectation: String,
    )

    private fun four(fl: Double, fr: Double, rl: Double, rr: Double) =
        mapOf(FRONT_LEFT to fl, FRONT_RIGHT to fr, REAR_LEFT to rl, REAR_RIGHT to rr)

    /** 10월 20℃에서 36psi(250kPa)로 맞춘 기준 측정. */
    val baseline = ColdDay(
        "기준 · 10월", LocalDate.of(2026, 10, 12), 20.0, 20_100.0,
        four(250.0, 250.0, 250.0, 250.0), "기준 설정",
    )

    val days = listOf(
        ColdDay(
            "1일차 · 추운 아침", LocalDate.of(2026, 12, 7), -10.0, 23_300.0,
            four(214.0, 214.0, 215.0, 214.0), "보충 필요 + 기온 영향",
        ),
        ColdDay(
            "2일차 · 보충 확인", LocalDate.of(2026, 12, 8), -8.0, 23_340.0,
            four(249.0, 249.0, 250.0, 249.0), "공기를 보충하셨나요?",
        ),
        ColdDay(
            "3일차 · 추적", LocalDate.of(2026, 12, 9), -5.0, 23_380.0,
            four(253.0, 249.0, 254.0, 253.0), "앞오른쪽만 보정 후 감소(1회)",
        ),
        ColdDay(
            "4일차 · 추적", LocalDate.of(2026, 12, 10), -2.0, 23_420.0,
            four(256.0, 249.0, 257.0, 256.0), "앞오른쪽만 보정 후 감소(2회)",
        ),
        ColdDay(
            "5일차 · 누설 의심", LocalDate.of(2026, 12, 11), -6.0, 23_460.0,
            four(251.0, 240.0, 252.0, 251.0), "누설 의심 + 타이어점 길안내",
        ),
        ColdDay(
            "6일차 · 너트 점검", LocalDate.of(2026, 12, 12), -4.0, 23_510.0,
            four(248.0, 248.0, 248.0, 248.0), "새 기준 측정 → 50km 뒤 너트 점검",
        ),
    )
}
