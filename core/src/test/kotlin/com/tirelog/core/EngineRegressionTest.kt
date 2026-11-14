package com.tirelog.core

import com.tirelog.core.engine.CheckInRequest
import com.tirelog.core.engine.InMemoryTireLogStore
import com.tirelog.core.engine.NewSetInput
import com.tirelog.core.engine.TireLogEngine
import com.tirelog.core.model.DriveState
import com.tirelog.core.model.AlertType
import com.tirelog.core.model.RecommendedPressure
import com.tirelog.core.model.RecommendedSource
import com.tirelog.core.model.RotationPattern
import com.tirelog.core.model.TireSetType
import com.tirelog.core.model.WheelPosition
import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class EngineRegressionTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val now = LocalDate.of(2026, 10, 29).atStartOfDay(zone).toInstant().toEpochMilli()
    private val input = TireLogEngine.OnboardingInput(
        recommended = RecommendedPressure(250.0, 250.0, RecommendedSource.LABEL_CONFIRMED),
        setType = TireSetType.SUMMER,
        setSize = null,
        setDot = null,
    )

    @Test
    fun `ODO 미수신으로 초기 설정해도 차량 전체 거리가 세트에 쌓이지 않는다`() = runTest {
        val engine = TireLogEngine(InMemoryTireLogStore(), zone)
        engine.completeOnboarding(input, now, null)
        assertEquals(0, engine.setsOverview(now, 80_000.0).rows.single().km)
        engine.saveDriveState(DriveState(lastKnownOdoKm = 80_000.0))
        assertEquals(120, engine.setsOverview(now, 80_120.0).rows.single().km)
        engine.recordHotReading(emptyMap(), null, 80_200.0, now + 1)
        assertEquals(200, engine.setsOverview(now + 1, null).rows.single().km)
    }

    @Test
    fun `초기 설정 중복 저장은 장착 세트를 늘리지 않는다`() = runTest {
        val engine = TireLogEngine(InMemoryTireLogStore(), zone)
        engine.completeOnboarding(input, now, 10_000.0)
        assertFailsWith<IllegalArgumentException> { engine.completeOnboarding(input, now, 10_000.0) }
        assertEquals(1, engine.sets().size)
    }

    @Test
    fun `잘못된 권장값과 세트 DOT를 저장하지 않는다`() = runTest {
        val engine = TireLogEngine(InMemoryTireLogStore(), zone)
        engine.completeOnboarding(input, now, 10_000.0)
        assertFailsWith<IllegalArgumentException> {
            engine.updateSettings { it.copy(recommended = RecommendedPressure(-1.0, 250.0, RecommendedSource.USER_INPUT)) }
        }
        assertFailsWith<IllegalArgumentException> {
            engine.addSet(NewSetInput(TireSetType.WINTER, null, "5423"), now)
        }
        assertEquals(input.recommended, engine.settings().recommended)
        assertEquals(1, engine.sets().size)
    }

    @Test
    fun `새 타이어 작업과 같은 기록으로 50km 뒤 너트 점검을 없앨 수 없다`() = runTest {
        val engine = TireLogEngine(InMemoryTireLogStore(), zone)
        engine.completeOnboarding(input, now, 10_000.0)
        assertFailsWith<IllegalArgumentException> {
            engine.checkIn(CheckInRequest(rotation = RotationPattern.FRONT_REAR, nutCheck = true), now, 10_000.0)
        }
        assertEquals(emptyList(), engine.setsOverview(now, null).history)
    }

    @Test
    fun `세트를 바꾸면 이전 세트의 보충 확인 질문을 지운다`() = runTest {
        val engine = TireLogEngine(InMemoryTireLogStore(), zone)
        engine.completeOnboarding(input, now, 10_000.0)
        engine.recordColdReading(WheelPosition.entries.associateWith { 220.0 }, 20.0, 10_000.0, now)
        val nextDay = now + TireLogEngine.DAY_MILLIS
        engine.recordColdReading(WheelPosition.entries.associateWith { 250.0 }, 20.0, 10_020.0, nextDay)
        assertEquals(4, engine.settings().refillQuestion?.tireIds?.size)
        val winter = engine.addSet(NewSetInput(TireSetType.WINTER, null, null), nextDay)
        engine.checkIn(CheckInRequest(changeToSetId = winter), nextDay + 1, 10_020.0)
        assertNull(engine.settings().refillQuestion)
    }

    @Test
    fun `일부 바퀴만 보충하면 나머지 확인 질문은 보존한다`() = runTest {
        val engine = TireLogEngine(InMemoryTireLogStore(), zone)
        engine.completeOnboarding(input, now, 10_000.0)
        engine.recordColdReading(WheelPosition.entries.associateWith { 220.0 }, 20.0, 10_000.0, now)
        val nextDay = now + TireLogEngine.DAY_MILLIS
        engine.recordColdReading(WheelPosition.entries.associateWith { 250.0 }, 20.0, 10_020.0, nextDay)
        engine.checkIn(CheckInRequest(refill = setOf(WheelPosition.FRONT_LEFT)), nextDay + 1, 10_020.0)
        assertEquals(3, engine.settings().refillQuestion?.tireIds?.size)
    }

    @Test
    fun `권장값을 고치면 같은 날의 보충 필요 안내에도 반영한다`() = runTest {
        val engine = TireLogEngine(InMemoryTireLogStore(), zone)
        engine.completeOnboarding(input, now, 10_000.0)
        engine.recordColdReading(WheelPosition.entries.associateWith { 240.0 }, 20.0, 10_000.0, now)
        assertTrue(engine.activeAlerts(now, null).none { it.type == AlertType.REFILL_NEEDED })
        engine.updateSettings { it.copy(recommended = RecommendedPressure(280.0, 280.0, RecommendedSource.LABEL_CONFIRMED)) }
        assertTrue(engine.activeAlerts(now, null).any { it.type == AlertType.REFILL_NEEDED })
        engine.updateSettings { it.copy(recommended = input.recommended) }
        assertTrue(engine.activeAlerts(now, null).none { it.type == AlertType.REFILL_NEEDED })
    }

    @Test
    fun `세트별 권장값을 수정하고 저장하면 차량 공통값보다 우선한다`() = runTest {
        val engine = TireLogEngine(InMemoryTireLogStore(), zone)
        engine.completeOnboarding(input.copy(recommended = null), now, 10_000.0)
        val set = engine.mountedSet()!!
        engine.updateRegistration(set.id, NewSetInput(set.type, null, null,
            recommendedFrontKpa = 250.0, recommendedRearKpa = 260.0), now)
        engine.recordColdReading(WheelPosition.entries.associateWith { 230.0 }, 20.0, 10_000.0, now)
        val snapshot = engine.dashboard(now)
        assertFalse(snapshot.needsRecommended)
        assertTrue(snapshot.alerts.any { it.type == AlertType.REFILL_NEEDED && it.positions.all { p -> !p.isFront } })
        assertEquals("타이어별 사용자 설정", snapshot.recommendedSourceLabel)
        engine.updateSettings { it.copy(recommended = input.recommended) }
        assertEquals(260.0, engine.mountedSet()!!.recommendedRearKpa)
        engine.updateRegistration(set.id, NewSetInput(set.type, null, null), now)
        assertNull(engine.mountedSet()!!.recommendedRearKpa)
        assertTrue(engine.dashboard(now).alerts.none { it.type == AlertType.REFILL_NEEDED })
    }

    @Test
    fun `공기압 미지원 전환 후에는 기존 수치와 보충 질문을 노출하지 않는다`() = runTest {
        val engine = TireLogEngine(InMemoryTireLogStore(), zone)
        engine.completeOnboarding(input, now, 10_000.0)
        engine.recordColdReading(WheelPosition.entries.associateWith { 210.0 }, 20.0, 10_000.0, now)
        val next = now + TireLogEngine.DAY_MILLIS
        engine.recordColdReading(WheelPosition.entries.associateWith { 250.0 }, 20.0, 10_020.0, next)
        engine.updateSettings { it.copy(pressureSupported = false) }
        val snapshot = engine.dashboard(next)
        assertTrue(snapshot.wheels.all { it.psi == null && it.displayPsi == null && it.recommendedPsi == null })
        assertNull(snapshot.refillQuestion)
        assertFalse(snapshot.needsRecommended)
        assertEquals(20, engine.setsOverview(next, 10_020.0).rows.single().km)
    }

    @Test
    fun `보충과 재점검이 동시에 필요하면 팝업과 메인 모두 보충을 먼저 안내한다`() = runTest {
        val engine = TireLogEngine(InMemoryTireLogStore(), zone)
        engine.completeOnboarding(input, now, 10_000.0)
        engine.checkIn(CheckInRequest(refill = WheelPosition.entries.toSet()), now, 10_000.0)
        engine.recordColdReading(WheelPosition.entries.associateWith { 250.0 }, 20.0, 10_000.0, now + 1)
        for (day in 1..3) engine.recordColdReading(WheelPosition.entries.associateWith { 240.0 - day * 10 },
            20.0, 10_000.0 + day * 20, now + day * TireLogEngine.DAY_MILLIS)
        val last = now + 3 * TireLogEngine.DAY_MILLIS
        val snapshot = engine.dashboard(last)
        assertTrue(snapshot.alerts.any { it.type == AlertType.RECHECK })
        assertEquals(AlertType.REFILL_NEEDED, engine.nextPopup(last, null)?.type)
        assertEquals("공기압 부족 · 정차 후 안내", snapshot.headline)
        assertTrue(snapshot.wheels.all { it.state == com.tirelog.core.engine.WheelState.REFILL })
    }
}
