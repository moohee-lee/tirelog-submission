package com.tirelog.core

import com.tirelog.core.alert.AlertAction
import com.tirelog.core.engine.*
import com.tirelog.core.judge.PressureDrop
import com.tirelog.core.judge.PressureSample
import com.tirelog.core.maintenance.Mileage
import com.tirelog.core.model.*
import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.*

class CompletionRegressionTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val now = LocalDate.of(2026, 11, 1).atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
    private val day = TireLogEngine.DAY_MILLIS
    private val all = WheelPosition.entries
    private suspend fun setup(store: InMemoryTireLogStore = InMemoryTireLogStore(), initialOdo: Double? = 10_000.0): TireLogEngine {
        val engine = TireLogEngine(store, zone)
        engine.completeOnboarding(TireLogEngine.OnboardingInput(
            RecommendedPressure(250.0, 250.0, RecommendedSource.LABEL_CONFIRMED), TireSetType.SUMMER,
            "235/55R19", null, regionCode = "SEOUL"), now - 10 * day, initialOdo)
        return engine
    }

    @Test fun `ODO 없는 세트 교체는 이전 값을 재사용하지 않고 뒤늦은 수신에도 공백을 보존한다`() = runTest {
        val store = InMemoryTireLogStore()
        val e = setup(store)
        val old = e.mountedSet()!!.id
        val next = e.addSet(NewSetInput(TireSetType.WINTER, null, null), now)
        e.checkIn(CheckInRequest(changeToSetId = next), now, null)
        assertNull(store.mounts().first { it.setId == old }.unmountOdoKm)
        assertNull(store.mounts().first { it.setId == next }.mountOdoKm)
        assertNull(store.events().last().odoKm)
        e.saveDriveState(DriveState(lastKnownOdoKm = 12_000.0), now + day)
        val rows = e.setsOverview(now + 2 * day, 12_100.0).rows
        assertTrue(rows.all { !it.distanceComplete && it.tireLife.all { life -> life.recordedKm == null } })
        assertTrue(e.activeAlerts(now + 2 * day, 12_100.0).none { it.type == AlertType.NUT_CHECK || it.type == AlertType.ROTATION })
    }

    @Test fun `부분 교체 때 거리 누락은 교체된 타이어에만 영향을 준다`() = runTest {
        val e = setup()
        e.replaceTires(setOf(WheelPosition.FRONT_LEFT), NewSetInput(TireSetType.SUMMER, "245/45R19", null, newUntilMounted = true), null, false, now, null)
        val row = e.setsOverview(now + day, 10_200.0).rows.single()
        assertNull(row.tireLife.first { it.tire.position == WheelPosition.FRONT_LEFT }.recordedKm)
        assertTrue(row.tireLife.filter { it.tire.position != WheelPosition.FRONT_LEFT }.all { it.recordedKm == 200.0 })
        assertNull(row.previousTireLife.single().recordedKm)
    }

    @Test fun `ODO 역전 구간은 값이 회복되어도 거리 판단에서 제외한다`() = runTest {
        val e = setup()
        e.recordHotReading(emptyMap(), null, 9_500.0, now)
        e.recordHotReading(emptyMap(), null, 11_000.0, now + day)
        assertTrue(e.setsOverview(now + day, 11_000.0).rows.single().tireLife.all { it.recordedKm == null })
        e.checkIn(CheckInRequest(rotation = RotationPattern.FRONT_REAR), now + day, 11_000.0)
        assertEquals(9_500, e.dashboard(now + 2 * day, LiveReadings(odoKm = 11_500.0)).rotationRemainingKm)
    }

    @Test fun `가장 최근 너트 작업 거리를 모르면 이전 작업 거리로 알리지 않는다`() {
        val events = listOf(
            TireEvent(type = EventType.ROTATION, atMillis = 100, odoKm = 10_000.0, setId = 1),
            TireEvent(type = EventType.SET_CHANGE, atMillis = 200, odoKm = null, setId = 2))
        assertNull(Mileage.nutStatus(events, 12_000.0))
    }

    @Test fun `여러 차례 ODO가 역전되어도 이미 탈거한 타이어의 첫 누락 구간이 남는다`() = runTest {
        val e = setup()
        e.recordHotReading(emptyMap(), null, 9_500.0, now)
        e.replaceTires(setOf(WheelPosition.FRONT_LEFT), NewSetInput(TireSetType.SUMMER, null, null, newUntilMounted = true), null, false, now + day, 9_800.0)
        e.recordHotReading(emptyMap(), null, 9_000.0, now + 2 * day)
        e.recordHotReading(emptyMap(), null, 12_000.0, now + 3 * day)
        val row = e.setsOverview(now + 3 * day, 12_000.0).rows.single()
        assertNull(row.previousTireLife.single().recordedKm)
        assertNull(row.tireLife.first { it.tire.position == WheelPosition.FRONT_LEFT }.recordedKm)
    }

    @Test fun `한 짝의 규격 수정과 부분 교체 규격은 공통 정보에서 독립한다`() = runTest {
        val e = setup()
        val left = e.mountedTires().getValue(WheelPosition.FRONT_LEFT)
        e.updateTire(left.id, NewSetInput(TireSetType.SUMMER, "245/45R19", null), now)
        assertEquals("245/45R19", e.tires().first { it.id == left.id }.profile!!.size)
        assertEquals("235/55R19", e.mountedSet()!!.size)
        e.replaceTires(setOf(WheelPosition.FRONT_RIGHT), NewSetInput(TireSetType.SUMMER, "255/45R20", null, newUntilMounted = true), null, false, now + 1, 10_100.0)
        assertEquals("255/45R20", e.mountedTires().getValue(WheelPosition.FRONT_RIGHT).profile!!.size)
        assertEquals("235/55R19", e.tires().first { !it.active }.profile!!.size)
    }

    @Test fun `평년 예고 확인 후 연속 저온이면 같은 예고 기간에도 다시 알린다`() = runTest {
        val e = setup()
        val preview = e.activeAlerts(now - 3 * day, 10_000.0).first { it.type == AlertType.SEASON }
        assertTrue(preview.key.endsWith(":PREVIEW"))
        e.respond(preview, AlertAction.CONFIRM, now - 3 * day, 10_000.0)
        for (i in 2 downTo 0) e.recordColdReading(all.associateWith { 250.0 }, 4.0, 10_000.0, now - i * day)
        val cold = e.activeAlerts(now, 10_000.0).single { it.type == AlertType.SEASON }
        assertTrue(cold.key.endsWith(":COLD"))
        e.respond(cold, AlertAction.CONFIRM, now, 10_000.0)
        assertTrue(e.activeAlerts(now + 5 * day, 10_000.0).none { it.type == AlertType.SEASON })
    }

    @Test fun `급격한 저하는 물리 타이어 이력만 저장하고 냉간 판단과 알림을 바꾸지 않는다`() = runTest {
        val store = InMemoryTireLogStore()
        val e = setup(store)
        e.updateSettings { it.copy(regionCode = null) }
        e.recordColdReading(all.associateWith { 250.0 }, 20.0, 10_000.0, now)
        val baselines = store.baselines()
        val tire = e.mountedTires().getValue(WheelPosition.FRONT_RIGHT)
        e.recordPressureDrop(PressureDrop(tire.id, tire.position, PressureSample(now + 1_000, 250.0), PressureSample(now + 31_000, 210.0)), 20.0, 10_001.0)
        assertEquals(EventType.RAPID_PRESSURE_DROP, store.events().single().type)
        assertEquals(listOf(tire.id), store.events().single().tireIds)
        assertEquals(2, store.measurementsSince(now).count { !it.cold })
        assertEquals(baselines, store.baselines())
        assertEquals(250.0, store.latestDayResult()!!.tires.first { it.tireId == tire.id }.kpa)
        assertNull(e.nextPopup(now + 32_000, 10_001.0))
    }

    @Test fun `바퀴 미지원 표시와 저압 참고값은 권장값 판단과 분리한다`() = runTest {
        val e = setup()
        e.updateSettings { it.copy(pressurePositions = setOf(WheelPosition.FRONT_LEFT), regionCode = null) }
        e.recordColdReading(mapOf(WheelPosition.FRONT_LEFT to 230.0), 20.0, 10_000.0, now)
        val snapshot = e.dashboard(now, LiveReadings(odoKm = 10_000.0,
            lowPressureKpa = mapOf(WheelPosition.FRONT_LEFT to 240.0)))
        assertTrue(snapshot.wheels.filter { it.position != WheelPosition.FRONT_LEFT }.all { !it.supported && it.displayPsi == null })
        assertEquals(240.0, snapshot.wheels.first().lowPressureKpa)
        assertFalse(snapshot.alerts.any { it.type == AlertType.REFILL_NEEDED })
    }
}
