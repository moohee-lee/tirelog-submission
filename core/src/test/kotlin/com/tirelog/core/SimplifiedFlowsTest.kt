package com.tirelog.core

import com.tirelog.core.engine.*
import com.tirelog.core.model.*
import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.*

class SimplifiedFlowsTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val now = LocalDate.of(2026, 10, 1).atStartOfDay(zone).toInstant().toEpochMilli()
    private val day = TireLogEngine.DAY_MILLIS
    private val all = WheelPosition.entries.toSet()
    private val input = TireLogEngine.OnboardingInput(
        RecommendedPressure(250.0, 250.0, RecommendedSource.LABEL_CONFIRMED), TireSetType.ALL_SEASON, null, "0125",
        initialKm = 5_000.0, manufacturer = "기존 제조사", tireModel = "기존 모델",
    )

    @Test fun `교체 등록과 장착과 폐기를 함께 저장하고 과거 측정은 보존한다`() = runTest {
        val engine = TireLogEngine(InMemoryTireLogStore(), zone)
        engine.completeOnboarding(input, now, 10_000.0)
        val previous = engine.mountedSet()!!
        engine.recordColdReading(all.associateWith { 250.0 }, 15.0, 10_000.0, now)
        engine.replaceTires(all, NewSetInput(TireSetType.WINTER, null, "0126", newUntilMounted = true,
            manufacturer = "새 제조사", tireModel = "윈터 모델"), null, true, now + day, 10_100.0)
        assertTrue(engine.sets().first { it.id == previous.id }.retired)
        assertEquals("윈터 모델", engine.mountedSet()!!.tireModel)
        assertEquals(4, engine.mountedTires().size)
        assertEquals(0, engine.setsOverview(now + day, 10_100.0).rows.single { it.mounted }.km)
        assertEquals(4, engine.trend(now + day, 7, previous.id).series.sumOf { it.points.size })
        assertFailsWith<IllegalArgumentException> { engine.deleteSet(previous.id) }
    }

    @Test fun `보관 타이어는 장착한 구간의 거리만 이어서 센다`() = runTest {
        val engine = TireLogEngine(InMemoryTireLogStore(), zone)
        engine.completeOnboarding(input, now, 10_000.0)
        val summer = engine.mountedSet()!!.id
        val winter = engine.addSet(NewSetInput(TireSetType.WINTER, null, null, initialKm = 2_000.0), now)
        engine.replaceTires(all, null, winter, false, now + day, 10_100.0)
        engine.replaceTires(all, null, summer, false, now + 2 * day, 10_600.0)
        val rows = engine.setsOverview(now + 3 * day, 10_650.0).rows
        assertEquals(5_150, rows.single { it.set.id == summer }.km)
        assertEquals(2_500, rows.single { it.set.id == winter }.km)
        assertFalse(rows.any { it.set.retired })
    }

    @Test fun `한 짝 교체는 개별 제품과 거리를 저장하고 분리된 타이어 거리는 멈춘다`() = runTest {
        val engine = TireLogEngine(InMemoryTireLogStore(), zone)
        engine.completeOnboarding(input, now, 10_000.0)
        val original = engine.mountedTires().getValue(WheelPosition.FRONT_LEFT)
        engine.replaceTires(setOf(WheelPosition.FRONT_LEFT), NewSetInput(TireSetType.ALL_SEASON, null, null,
            initialKm = 1_000.0, manufacturer = "다른 제조사", tireModel = "다른 모델", inspectionKm = 30_000),
            null, false, now + day, 10_100.0)
        val replacement = engine.mountedTires().getValue(WheelPosition.FRONT_LEFT)
        assertNotEquals(original.id, replacement.id)
        assertEquals("다른 모델", replacement.profile!!.tireModel)
        val row = engine.setsOverview(now + 2 * day, 10_200.0).rows.single()
        assertEquals(1_100.0, row.tireLife.single { it.tire.id == replacement.id }.recordedKm)
        assertEquals(5_200.0, row.tireLife.first { it.tire.id != replacement.id }.recordedKm)
        assertEquals(5_100.0, row.previousTireLife.single().recordedKm)
        assertFalse(row.previousTireLife.single().tire.retired)
    }

    @Test fun `기존 거리 모름은 총사용 거리나 남은 거리로 가장하지 않는다`() = runTest {
        val engine = TireLogEngine(InMemoryTireLogStore(), zone)
        engine.completeOnboarding(input.copy(initialKm = 0.0, initialKmKnown = false), now, 10_000.0)
        val life = engine.setsOverview(now + day, 10_100.0).rows.single().tireLife.first()
        assertEquals(100.0, life.recordedKm)
        assertFalse(life.totalKmKnown)
        assertNull(life.remainingKm)
    }

    @Test fun `장착 중 삭제를 막고 잘못 등록한 미사용 정보만 지운다`() = runTest {
        val engine = TireLogEngine(InMemoryTireLogStore(), zone)
        engine.completeOnboarding(input, now, 10_000.0)
        val current = engine.mountedSet()!!.id
        assertFailsWith<IllegalArgumentException> { engine.deleteSet(current) }
        assertFailsWith<IllegalArgumentException> { engine.retireSet(current) }
        val unused = engine.addSet(NewSetInput(TireSetType.WINTER, null, null), now)
        engine.deleteSet(unused)
        assertEquals(listOf(current), engine.sets().map { it.id })
        assertTrue(engine.tires().all { it.setId == current })
    }

    @Test fun `잘못된 교체 입력은 새 등록을 남기지 않는다`() = runTest {
        val engine = TireLogEngine(InMemoryTireLogStore(), zone)
        engine.completeOnboarding(input, now, 10_000.0)
        assertFailsWith<IllegalArgumentException> {
            engine.replaceTires(all, NewSetInput(TireSetType.WINTER, null, "9999"), null, true, now, 10_000.0)
        }
        assertFailsWith<IllegalArgumentException> {
            engine.replaceTires(all, NewSetInput(TireSetType.WINTER, null, null), null, true, now, -1.0)
        }
        assertEquals(1, engine.sets().size)
        assertFalse(engine.mountedSet()!!.retired)
    }

    @Test fun `선택 기간의 양끝을 포함하고 보관 전후의 선을 나눈다`() = runTest {
        val engine = TireLogEngine(InMemoryTireLogStore(), zone)
        engine.completeOnboarding(input, now, 10_000.0)
        val firstSet = engine.mountedSet()!!.id
        val firstTire = engine.mountedTires().getValue(WheelPosition.FRONT_LEFT).id
        for (i in 0..1) engine.recordColdReading(all.associateWith { 250.0 }, 15.0, 10_000.0 + i, now + i * day)
        val winter = engine.addSet(NewSetInput(TireSetType.WINTER, null, null), now + day)
        engine.replaceTires(all, null, winter, false, now + day + 1, 10_001.0)
        engine.replaceTires(all, null, firstSet, false, now + 2 * day - 1, 10_050.0)
        engine.recordColdReading(all.associateWith { 249.0 }, 14.0, 10_050.0, now + 2 * day)
        val before = engine.trendRange(now + 3 * day, now + day, now + 2 * day, firstSet).series.single { it.tireId == firstTire }
        assertEquals(listOf(now + day, now + 2 * day), before.points.map { it.atMillis })
        assertNotEquals(before.points[0].segment, before.points[1].segment)
        engine.checkIn(CheckInRequest(rotation = RotationPattern.SIDE_TO_SIDE), now + 2 * day + 1, 10_050.0)
        val after = engine.trendRange(now + 3 * day, now + day, now + 2 * day, firstSet).series.single { it.tireId == firstTire }
        assertEquals(before.colorIndex, after.colorIndex)
        assertTrue(after.label.startsWith("앞오른쪽"))
        assertEquals(WheelPosition.FRONT_LEFT, after.points.first().position)
    }

    @Test fun `권장값이 없거나 오래된 값은 정상으로 표시하지 않는다`() = runTest {
        val engine = TireLogEngine(InMemoryTireLogStore(), zone)
        engine.completeOnboarding(input.copy(recommended = null), now, 10_000.0)
        engine.recordColdReading(all.associateWith { 250.0 }, 15.0, 10_000.0, now)
        assertTrue(engine.dashboard(now).wheels.all { it.state == WheelState.WAITING })
        val stale = engine.dashboard(now + 3 * day, LiveReadings(all.associateWith { 280.0 }, now, true))
        assertTrue(stale.wheels.all { it.measuredLabel != "지금" && it.displayPsi == Units.psiRounded(250.0) })
    }

    @Test fun `좌우 교환은 같은 축에서 이동하고 모든 방식이 네 위치를 중복 없이 채운다`() {
        assertEquals(WheelPosition.FRONT_RIGHT, RotationPattern.SIDE_TO_SIDE.destinationOf(WheelPosition.FRONT_LEFT))
        assertEquals(WheelPosition.REAR_LEFT, RotationPattern.SIDE_TO_SIDE.destinationOf(WheelPosition.REAR_RIGHT))
        RotationPattern.entries.forEach { assertEquals(all, all.map(it::destinationOf).toSet()) }
    }

    @Test fun `부분 교체 뒤에도 네 타이어의 색이 구분되고 개별 점검 기준을 따른다`() = runTest {
        val engine = TireLogEngine(InMemoryTireLogStore(), zone)
        engine.completeOnboarding(input, now, 10_000.0)
        engine.replaceTires(setOf(WheelPosition.FRONT_RIGHT), NewSetInput(TireSetType.ALL_SEASON, null, null,
            initialKm = 1500.0, inspectionKm = 1000, tireModel = "개별 타이어"), null, true, now + day, 10_100.0)
        assertEquals(4, engine.mountedTires().values.map { it.colorIndex }.toSet().size)
        val due = engine.activeAlerts(now + day, 10_100.0).single { it.type == AlertType.LIFE_DUE }
        assertTrue(due.detail!!.contains("1,000km"))
        assertFalse(due.detail!!.contains("40,000km"))
        val tire = engine.mountedTires().getValue(WheelPosition.FRONT_RIGHT)
        engine.updateRegistration(tire.setId, NewSetInput(TireSetType.SUMMER, null, "0125", initialKm = 6000.0,
            tireModel = "공통 이름"), now + day)
        assertEquals("개별 타이어", engine.mountedTires().getValue(WheelPosition.FRONT_RIGHT).profile!!.tireModel)
    }

    @Test fun `보관 중인 새 타이어를 수정해도 첫 장착일 자동 기록을 유지한다`() = runTest {
        val engine = TireLogEngine(InMemoryTireLogStore(), zone)
        engine.completeOnboarding(input, now, 10_000.0)
        val fresh = NewSetInput(TireSetType.WINTER, null, null, newUntilMounted = true)
        val id = engine.addSet(fresh, now)
        engine.updateRegistration(id, fresh.copy(tireModel = "수정한 모델"), now)
        engine.replaceTires(all, null, id, false, now + day, 10_100.0)
        assertEquals(engine.epochDay(now + day), engine.mountedSet()!!.firstUsedEpochDay)
    }
}
