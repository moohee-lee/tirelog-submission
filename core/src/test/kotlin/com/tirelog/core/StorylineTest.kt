package com.tirelog.core

import com.tirelog.core.alert.AlertAction
import com.tirelog.core.demo.DemoStoryline
import com.tirelog.core.engine.CheckInRequest
import com.tirelog.core.engine.InMemoryTireLogStore
import com.tirelog.core.engine.NewSetInput
import com.tirelog.core.engine.TireLogEngine
import com.tirelog.core.engine.WheelState
import com.tirelog.core.engine.LiveReadings
import com.tirelog.core.model.AlertType
import com.tirelog.core.model.RecommendedPressure
import com.tirelog.core.model.RecommendedSource
import com.tirelog.core.model.RotationPattern
import com.tirelog.core.model.TireSetType
import com.tirelog.core.model.WheelPosition
import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 유저 시나리오 5장(시연 스토리라인)을 엔진에 그대로 재생한다. */
class StorylineTest {

    private val zone = DemoStoryline.ZONE
    private fun at(date: LocalDate, hour: Int, minute: Int = 0) =
        date.atTime(LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `추운 아침부터 세트 교체까지 시나리오대로 알린다`() = runTest {
        val engine = TireLogEngine(InMemoryTireLogStore(), zone)
        val base = DemoStoryline.baseline
        engine.completeOnboarding(
            TireLogEngine.OnboardingInput(
                recommended = RecommendedPressure(250.0, 250.0, RecommendedSource.LABEL_CONFIRMED),
                setType = TireSetType.SUMMER,
                setSize = DemoStoryline.TIRE_SIZE,
                setDot = DemoStoryline.SUMMER_DOT,
            ),
            now = at(base.date, 7),
            odoKm = DemoStoryline.INSTALL_ODO_KM,
        )
        engine.recordColdReading(base.kpa, base.tempC, base.departOdoKm, at(base.date, 7, 52))
        val (d1, d2, d3, d4, d5) = DemoStoryline.days
        val d6 = DemoStoryline.days[5]

        // 1일차: 기온 영향 + 보충 필요를 한 팝업으로
        engine.recordColdReading(d1.kpa, d1.tempC, d1.departOdoKm + 0.9, at(d1.date, 7, 52))
        val day1 = engine.nextPopup(at(d1.date, 8, 30), null)
        assertNotNull(day1)
        assertEquals(AlertType.REFILL_NEEDED, day1.type)
        assertEquals("기온이 내려가 압력이 낮아진 것으로 보입니다. 권장값보다 낮아 보충이 필요합니다.", day1.message)
        engine.markShown(day1.key, at(d1.date, 8, 30))
        assertNull(engine.nextPopup(at(d1.date, 18), null), "같은 알림은 하루 한 번만")
        val dash1 = engine.dashboard(at(d1.date, 8, 31))
        assertEquals("31psi · 권장 36 · 5psi 부족 · 오늘 07:52 냉간", dash1.wheels.first().line)
        assertTrue(dash1.wheels.all { it.state == WheelState.REFILL })
        assertEquals("오늘 판단 · 기온 영향으로 보입니다(기준 20℃ → −10℃)", dash1.causeText)

        // 2일차: 보충 감지 → 운전자 확인 → 새 기준
        engine.recordColdReading(d2.kpa, d2.tempC, d2.departOdoKm + 0.9, at(d2.date, 7, 52))
        assertEquals("공기를 보충하셨나요?", engine.dashboard(at(d2.date, 8, 30)).refillQuestion?.text)
        assertNull(engine.nextPopup(at(d2.date, 8, 30), null))
        engine.answerRefillQuestion(refilled = true, now = at(d2.date, 8, 31))
        assertNull(engine.dashboard(at(d2.date, 8, 32)).refillQuestion)

        // 3일차: 누설 추적은 조용히. 여름 세트로 영하 아침이 사흘 이어져 시즌 재알림(★)만 뜬다
        engine.recordColdReading(d3.kpa, d3.tempC, d3.departOdoKm + 0.9, at(d3.date, 7, 52))
        val day3 = engine.nextPopup(at(d3.date, 8, 30), null)
        assertNotNull(day3)
        assertEquals(AlertType.SEASON, day3.type)
        engine.markShown(day3.key, at(d3.date, 8, 30))
        engine.respond(day3, AlertAction.CONFIRM, at(d3.date, 8, 30), null)

        // 4일차: 아무것도 띄우지 않는다
        engine.recordColdReading(d4.kpa, d4.tempC, d4.departOdoKm + 0.9, at(d4.date, 7, 52))
        assertNull(engine.nextPopup(at(d4.date, 8, 30), null))

        // 5일차: 앞오른쪽 누설 의심 + 길안내
        engine.recordColdReading(d5.kpa, d5.tempC, d5.departOdoKm + 0.9, at(d5.date, 7, 52))
        val day5 = engine.nextPopup(at(d5.date, 8, 30), null)
        assertNotNull(day5)
        assertEquals(AlertType.LEAK, day5.type)
        assertEquals("앞오른쪽이 다른 바퀴보다 빨리 빠집니다. 점검을 권합니다.", day5.message)
        assertEquals("보충 뒤 3일 −12kPa(약 1.7psi) · 다른 바퀴 평균 −1kPa", day5.detail)
        assertTrue(AlertAction.NAVIGATE in day5.actions)
        assertEquals("앞오른쪽이 다른 바퀴보다 빨리 빠집니다.", day5.speech)
        engine.markShown(day5.key, at(d5.date, 8, 30))
        val card = engine.mechanicCard(at(d5.date, 18), DemoStoryline.SHOP_ODO_KM)
        assertEquals("누설 의심", card.rows.first { it.position == WheelPosition.FRONT_RIGHT }.flag)

        // 5일차 저녁 정비소: 수리 + 위치교환 → 기준 리셋, 물리 타이어 기준 이력
        engine.checkIn(
            CheckInRequest(repair = setOf(WheelPosition.FRONT_RIGHT), rotation = RotationPattern.FRONT_REAR),
            now = at(d5.date, 18, 30),
            odoKm = DemoStoryline.SHOP_ODO_KM,
        )
        val tires = engine.mountedTires()
        assertEquals("B", tires[WheelPosition.REAR_RIGHT]?.label)
        assertTrue(engine.activeAlerts(at(d5.date, 18, 31), DemoStoryline.SHOP_ODO_KM).none { it.type == AlertType.LEAK })
        assertTrue(engine.dashboard(at(d5.date, 18, 31), LiveReadings(odoKm = DemoStoryline.SHOP_ODO_KM)).pending.any { it.text == "너트 점검 · 50km 뒤 안내" })

        // 6일차: 새 기준 측정 → 50km를 넘긴 뒤 첫 정차에 너트 점검
        engine.recordColdReading(d6.kpa, d6.tempC, d6.departOdoKm + 0.9, at(d6.date, 7, 52))
        assertNull(engine.nextPopup(at(d6.date, 8, 30), 23_530.0))
        val nut = engine.nextPopup(at(d6.date, 18, 40), 23_555.0)
        assertNotNull(nut)
        assertEquals(AlertType.NUT_CHECK, nut.type)
        engine.respond(nut, AlertAction.NUT_DONE, at(d6.date, 18, 41), 23_555.0)
        assertTrue(engine.activeAlerts(at(d6.date, 18, 42), 23_555.0).none { it.type == AlertType.NUT_CHECK })

        // 다음 주: 겨울 세트 등록·교체 → 세트별 주행거리가 따로 쌓인다
        val week = d6.date.plusDays(2)
        val winterId = engine.addSet(
            NewSetInput(TireSetType.WINTER, DemoStoryline.TIRE_SIZE, DemoStoryline.WINTER_DOT),
            at(week, 10),
        )
        engine.checkIn(CheckInRequest(changeToSetId = winterId), at(week, 10, 30), DemoStoryline.SET_CHANGE_ODO_KM)
        val sets = engine.setsOverview(at(week.plusDays(7), 9), DemoStoryline.SET_CHANGE_ODO_KM + 280)
        val summer = sets.rows.first { it.set.type == TireSetType.SUMMER }
        val winter = sets.rows.first { it.set.type == TireSetType.WINTER }
        assertEquals(3_700, summer.km)
        assertEquals("보관", summer.status)
        assertEquals(280, winter.km)
        assertEquals("장착 중", winter.status)
        assertTrue(winter.olderThanSixYears)
        val age = engine.activeAlerts(at(week, 11), DemoStoryline.SET_CHANGE_ODO_KM).firstOrNull { it.type == AlertType.AGE }
        assertEquals("윈터 세트가 제조 6년을 넘었습니다. 타이어 점검을 권합니다.", age?.message)
        assertTrue(
            engine.activeAlerts(at(week, 11), DemoStoryline.SET_CHANGE_ODO_KM + 60).any { it.type == AlertType.NUT_CHECK },
            "세트 교체 뒤에도 50km 너트 점검",
        )
    }

    @Test
    fun `공기압 미지원 차량은 공기압 알림 없이 세트와 위치교환만 쓴다`() = runTest {
        val engine = TireLogEngine(InMemoryTireLogStore(), zone)
        val day = LocalDate.of(2026, 6, 1)
        engine.completeOnboarding(
            TireLogEngine.OnboardingInput(
                recommended = null,
                setType = TireSetType.SUMMER,
                setSize = null,
                setDot = null,
                kmSinceLastRotation = 6_800.0,
                pressureSupported = false,
            ),
            now = at(day, 9),
            odoKm = 50_000.0,
        )
        val dash = engine.dashboard(at(day, 9, 1), LiveReadings(odoKm = 50_000.0))
        assertTrue(dash.pending.none { it.text.contains("측정") || it.text.contains("권장값") })
        assertEquals("위치교환까지 3,200km · 썸머 세트 0km", dash.headline)
        val due = engine.activeAlerts(at(day.plusDays(30), 9), 53_300.0)
        assertTrue(due.single().type == AlertType.ROTATION)
    }
}
