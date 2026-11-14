package com.tirelog.core

import com.tirelog.core.alert.AlertAction
import com.tirelog.core.alert.AlertPlanner
import com.tirelog.core.catalog.TireLifeGuides
import com.tirelog.core.engine.CheckInRequest
import com.tirelog.core.engine.InMemoryTireLogStore
import com.tirelog.core.engine.NewSetInput
import com.tirelog.core.engine.TireLogEngine
import com.tirelog.core.maintenance.TireLife
import com.tirelog.core.model.AlertType
import com.tirelog.core.model.Mount
import com.tirelog.core.model.RotationPattern
import com.tirelog.core.model.Tire
import com.tirelog.core.model.TireSet
import com.tirelog.core.model.TireSetType
import com.tirelog.core.model.WheelPosition
import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TireLifeTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val today = LocalDate.of(2026, 10, 31)
    private val now = at(today)
    private fun at(date: LocalDate) = date.atStartOfDay(zone).toInstant().toEpochMilli()
    private val input = TireLogEngine.OnboardingInput(null, TireSetType.SUMMER, null, null)
    private val set = TireSet(1, TireSetType.SUMMER, null, null, now)
    private val tire = Tire(2, 1, "A", WheelPosition.FRONT_LEFT)
    private val lifeTypes = setOf(AlertType.LIFE_SOON, AlertType.LIFE_DUE)

    @Test
    fun `최초 등록은 제조사 기준을 불러오고 사용자가 수정한 값은 보존한다`() = runTest {
        val engine = TireLogEngine(InMemoryTireLogStore(), zone)
        engine.completeOnboarding(TireLogEngine.OnboardingInput(null, TireSetType.SUMMER, null, null,
            lifeGuideId = "nexen_ev", tireModel = "N'FERA", inspectionKm = 32_000), now, 10_000.0)
        val first = engine.mountedSet()!!
        assertEquals("nexen_ev", first.lifeGuideId)
        assertEquals("N'FERA", first.tireModel)
        assertEquals(32_000, first.inspectionKm)
        assertEquals(48, first.inspectionMonths)
        val id = engine.addSet(NewSetInput(TireSetType.WINTER, null, null, lifeGuideId = "nexen"), now)
        assertEquals(40_000, engine.sets().single { it.id == id }.inspectionKm)
        assertEquals(48, engine.sets().single { it.id == id }.inspectionMonths)
    }

    @Test
    fun `제조사 정보가 없는 항목은 앱 기본값으로 채우고 출처를 구분한다`() {
        val unknown = TireLifeGuides.find("unregistered-brand")
        assertEquals("app", unknown.id)
        assertEquals(40_000, unknown.defaultKm)
        assertEquals(60, unknown.defaultMonths)
        val michelin = TireLifeGuides.find("michelin")
        assertNull(michelin.recommendedKm, "거리 미제공을 제조사 권장 4만km로 위장하지 않는다")
        assertEquals("앱 기본값", TireLifeGuides.sourceLabel(40_000, michelin.recommendedKm, 40_000))
        assertEquals("제조사 일반 안내", TireLifeGuides.sourceLabel(60, michelin.recommendedMonths, 60))
        assertEquals("사용자 설정", TireLifeGuides.sourceLabel(30_000, michelin.recommendedKm, 40_000))
        assertTrue(TireLifeGuides.basis(michelin, 40_000, distance = true).contains("앱이 제안"))
        assertTrue(TireLifeGuides.basis(michelin, 60, distance = false).contains("매년 전문가 점검"))
        assertTrue(TireLifeGuides.basis(michelin, 48, distance = false).contains("직접 정한"))
        val nexen = TireLifeGuides.find("nexen_ev")
        assertTrue(TireLifeGuides.basis(nexen, 30_000, distance = true).contains("3~4만km 중 이른 값"))
        assertTrue(TireLifeGuides.basis(nexen, 48, distance = false).contains("전기차 전용 기간은 아닙니다"))
    }

    @Test
    fun `기본 거리 90퍼센트부터 예고하고 기준 도달부터 점검을 알린다`() = runTest {
        val engine = TireLogEngine(InMemoryTireLogStore(), zone)
        engine.completeOnboarding(input.copy(initialKm = 35_999.0), now, 80_000.0)
        assertTrue(engine.activeAlerts(now, 80_000.0).none { it.type in lifeTypes })
        assertEquals(AlertType.LIFE_SOON, engine.activeAlerts(now, 80_001.0).single { it.type in lifeTypes }.type)
        assertEquals(AlertType.LIFE_DUE, engine.activeAlerts(now, 84_001.0).single { it.type in lifeTypes }.type)
        assertEquals(40_000.0, engine.setsOverview(now, 84_001.0).rows.single().tireLife.first().recordedKm)
    }

    @Test
    fun `시간 기준은 90일 전 경계와 윤년 달력 기념일을 사용한다`() {
        val used = LocalDate.of(2020, 2, 29)
        val due = LocalDate.of(2025, 2, 28)
        val s = set.copy(firstUsedEpochDay = used.toEpochDay())
        fun evaluate(date: LocalDate) = TireLife.evaluate(s, tire, emptyList(), emptyList(), null, at(date), zone)
        assertEquals(TireLife.Stage.TRACKING, evaluate(due.minusDays(91)).stage)
        assertEquals(TireLife.Stage.SOON, evaluate(due.minusDays(90)).stage)
        assertEquals(TireLife.Stage.SOON, evaluate(due.minusDays(1)).stage)
        assertEquals(TireLife.Stage.DUE, evaluate(due).stage)
        assertEquals(0, evaluate(due).remainingDays)
        assertEquals(60, evaluate(due).elapsedMonths)
    }

    @Test
    fun `보관 중 거리와 장착 일수는 멈추지만 첫 사용 후 기간은 계속 센다`() {
        val mounts = listOf(Mount(setId = 1, mountOdoKm = 10_000.0, mountAtMillis = at(today.minusDays(200)),
            unmountOdoKm = 12_000.0, unmountAtMillis = at(today.minusDays(100))))
        val result = TireLife.evaluate(set.copy(firstUsedEpochDay = today.minusYears(5).toEpochDay()), tire,
            mounts, emptyList(), 90_000.0, now, zone)
        assertEquals(2_000.0, result.recordedKm)
        assertEquals(100, result.recordedMountedDays)
        assertEquals(60, result.elapsedMonths)
        assertEquals(TireLife.Stage.DUE, result.stage)
    }

    @Test
    fun `ODO 누락과 역전은 거리 판단을 보류하고 기간 판단은 유지한다`() {
        for (odo in listOf(null, 9_999.0)) {
            val result = TireLife.evaluate(set.copy(firstUsedEpochDay = today.minusYears(5).toEpochDay()), tire,
                listOf(Mount(setId = 1, mountOdoKm = 10_000.0, mountAtMillis = now - 100)), emptyList(), odo, now, zone)
            assertNull(result.recordedKm)
            assertEquals(TireLife.Stage.DUE, result.timeStage)
        }
        val result = TireLife.evaluate(set, tire, listOf(Mount(setId = 1, mountOdoKm = null, mountAtMillis = now)), emptyList(), 80_000.0, now, zone)
        assertNull(result.recordedKm)
        assertNull(result.remainingDays)
        assertEquals(TireLife.Stage.TRACKING, result.stage)
    }

    @Test
    fun `한 짝만 교체하면 그 타이어만 거리와 기간을 새로 센다`() = runTest {
        val engine = TireLogEngine(InMemoryTireLogStore(), zone)
        engine.completeOnboarding(input.copy(initialKm = 40_000.0, firstUsedEpochDay = today.minusYears(5).toEpochDay()), now, 80_000.0)
        engine.checkIn(CheckInRequest(replacements = mapOf(WheelPosition.FRONT_LEFT to null)), now + 1, 80_000.0)
        val statuses = engine.setsOverview(now + 2, 80_010.0).rows.single().tireLife
        val replaced = statuses.single { it.tire.position == WheelPosition.FRONT_LEFT }
        assertEquals(10.0, replaced.recordedKm)
        assertEquals(today, replaced.firstUsed)
        assertEquals(TireLife.Stage.TRACKING, replaced.stage)
        assertTrue(statuses.filter { it != replaced }.all { it.recordedKm == 40_010.0 && it.stage == TireLife.Stage.DUE })
        engine.checkIn(CheckInRequest(rotation = RotationPattern.FRONT_REAR), now + 3, 80_010.0)
        val rotated = engine.setsOverview(now + 4, 80_020.0).rows.single().tireLife.single { it.tire.id == replaced.tire.id }
        assertEquals(20.0, rotated.recordedKm)
        assertNotEquals(replaced.tire.position, rotated.tire.position)
    }

    @Test
    fun `새 세트는 첫 장착일을 기록하고 재장착해도 최초 사용일을 보존한다`() = runTest {
        val engine = TireLogEngine(InMemoryTireLogStore(), zone)
        engine.completeOnboarding(input, now, 10_000.0)
        assertNull(engine.mountedSet()!!.firstUsedEpochDay, "0km로 등록한 기존 타이어를 새것으로 추정하지 않는다")
        val summerId = engine.mountedSet()!!.id
        val winterId = engine.addSet(NewSetInput(TireSetType.WINTER, null, null, newUntilMounted = true), now)
        assertNull(engine.sets().single { it.id == winterId }.firstUsedEpochDay)
        val mountedAt = at(today.plusDays(1))
        engine.checkIn(CheckInRequest(changeToSetId = winterId), mountedAt, 10_100.0)
        engine.checkIn(CheckInRequest(changeToSetId = summerId), at(today.plusDays(11)), 11_100.0)
        engine.checkIn(CheckInRequest(changeToSetId = winterId), at(today.plusDays(21)), 12_100.0)
        val winter = engine.setsOverview(at(today.plusDays(31)), 12_200.0).rows.single { it.set.id == winterId }
        assertEquals(today.plusDays(1).toEpochDay(), winter.set.firstUsedEpochDay)
        assertEquals(1_100.0, winter.tireLife.first().recordedKm)
        assertEquals(20, winter.tireLife.first().recordedMountedDays)
    }

    @Test
    fun `예고 확인 뒤 30일 보류해도 도달 알림은 바로 나오고 영구 해제되지 않는다`() = runTest {
        val store = InMemoryTireLogStore()
        val engine = TireLogEngine(store, zone)
        engine.completeOnboarding(input.copy(initialKm = 36_000.0), now, 80_000.0)
        val soon = engine.activeAlerts(now, 80_000.0).single { it.type == AlertType.LIFE_SOON }
        engine.respond(soon, AlertAction.CONFIRM, now, 80_000.0)
        val states = store.alertStates().associateBy { it.key }
        assertNull(AlertPlanner.pickPopup(listOf(soon), states, today.plusDays(29).toEpochDay(), false))
        val due = engine.activeAlerts(now, 84_000.0).single { it.type == AlertType.LIFE_DUE }
        assertNotEquals(soon.key, due.key)
        assertNotNull(AlertPlanner.pickPopup(listOf(due), states, today.toEpochDay(), false))
        engine.respond(due, AlertAction.CONFIRM, now, 84_000.0)
        assertFalse(store.alertStates().single { it.key == due.key }.acknowledged)
        val after = store.alertStates().associateBy { it.key }
        assertNull(AlertPlanner.pickPopup(listOf(due), after, today.plusDays(29).toEpochDay(), false))
        assertNotNull(AlertPlanner.pickPopup(listOf(due), after, today.plusDays(30).toEpochDay(), false))
    }

    @Test
    fun `설정 변경은 즉시 반영하고 잘못된 기준과 첫 사용일은 거절한다`() = runTest {
        val engine = TireLogEngine(InMemoryTireLogStore(), zone)
        engine.completeOnboarding(input.copy(initialKm = 30_000.0), now, 80_000.0)
        val setId = engine.mountedSet()!!.id
        engine.updateLifeSettings(setId, 31_000.0, null, 30_000, 48, now)
        assertEquals(AlertType.LIFE_DUE, engine.activeAlerts(now, 80_000.0).single { it.type in lifeTypes }.type)
        assertEquals("위치교환까지 10,000km", engine.setsOverview(now, 80_000.0).rows.single().rotationText)
        assertFailsWith<IllegalArgumentException> { engine.updateLifeSettings(setId, 0.0, null, 0, 60, now) }
        assertFailsWith<IllegalArgumentException> { engine.updateLifeSettings(setId, 0.0, today.plusDays(1).toEpochDay(), 40_000, 60, now) }
        assertFailsWith<IllegalArgumentException> { engine.addSet(NewSetInput(TireSetType.WINTER, null, "0126", firstUsedEpochDay = today.minusYears(1).toEpochDay()), now) }
        assertFailsWith<IllegalArgumentException> { engine.addSet(NewSetInput(TireSetType.WINTER, null, null, initialKm = 1.0, newUntilMounted = true), now) }
    }

    @Test
    fun `네 짝을 새 타이어로 바꾸면 기존 거리 기간 DOT 알림이 사라진다`() = runTest {
        val engine = TireLogEngine(InMemoryTireLogStore(), zone)
        engine.completeOnboarding(input.copy(setDot = "0120", initialKm = 40_000.0, firstUsedEpochDay = today.minusYears(5).toEpochDay()), now, 80_000.0)
        assertTrue(engine.activeAlerts(now, null).any { it.type == AlertType.AGE })
        engine.checkIn(CheckInRequest(replacements = WheelPosition.entries.associateWith { "0126" }), now + 1, 80_000.0)
        assertTrue(engine.activeAlerts(now + 2, 80_000.0).none { it.type in lifeTypes || it.type == AlertType.AGE })
    }

    @Test
    fun `폐기한 보관 세트에서는 교체 점검 알림을 종료한다`() = runTest {
        val engine = TireLogEngine(InMemoryTireLogStore(), zone)
        engine.completeOnboarding(input, now, 80_000.0)
        val unused = engine.addSet(NewSetInput(TireSetType.WINTER, null, null, initialKm = 40_000.0), now)
        assertTrue(engine.activeAlerts(now, null).any { it.type == AlertType.LIFE_DUE })
        engine.retireSet(unused)
        assertTrue(engine.activeAlerts(now, null).none { it.type in lifeTypes })
    }
}
