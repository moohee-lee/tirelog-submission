package com.tirelog.core

import com.tirelog.core.engine.CheckInRequest
import com.tirelog.core.engine.InMemoryTireLogStore
import com.tirelog.core.engine.NewSetInput
import com.tirelog.core.engine.TireLogEngine
import com.tirelog.core.model.EventType
import com.tirelog.core.model.RotationPattern
import com.tirelog.core.model.TireEvent
import com.tirelog.core.model.TireSetType
import com.tirelog.core.model.WheelPosition
import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RotationHistoryTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val now = LocalDate.of(2026, 11, 6).atStartOfDay(zone).toInstant().toEpochMilli()
    private val input = TireLogEngine.OnboardingInput(null, TireSetType.SUMMER, null, null)

    @Test fun `모든 교환 방식은 물리 타이어와 측정 이력을 유지하고 당시 이동을 저장한다`() = runTest {
        RotationPattern.entries.forEach { pattern ->
            val store = InMemoryTireLogStore()
            val engine = TireLogEngine(store, zone)
            engine.completeOnboarding(input, now, 10_000.0)
            val before = engine.mountedTires()
            engine.recordColdReading(WheelPosition.entries.associateWith { 250.0 }, 10.0, 10_000.0, now + 1)
            engine.checkIn(CheckInRequest(rotation = pattern), now + 2, 10_500.0)
            val event = store.events().single()
            val moves = event.rotation!!.moves
            assertEquals(pattern, event.rotation.pattern)
            assertEquals(4, moves.size)
            val after = engine.mountedTires()
            before.forEach { (position, tire) ->
                val moved = after.getValue(pattern.destinationOf(position))
                assertEquals(tire.id, moved.id)
                assertEquals(tire.colorIndex, moved.colorIndex)
                assertEquals(position, moves.single { it.tireId == tire.id }.from)
                assertEquals(pattern.destinationOf(position), moves.single { it.tireId == tire.id }.to)
                assertEquals(tire.label, moves.single { it.tireId == tire.id }.tireLabel)
                assertEquals(position, store.measurementsSince(now).single { it.tireId == tire.id }.position)
            }
        }
    }

    @Test fun `다시 교환하고 한 짝을 교체해도 과거 이동 경로와 이전 타이어를 보존한다`() = runTest {
        val store = InMemoryTireLogStore()
        val engine = TireLogEngine(store, zone)
        engine.completeOnboarding(input, now, 10_000.0)
        engine.recordColdReading(WheelPosition.entries.associateWith { 250.0 }, 10.0, 10_000.0, now + 1)
        engine.checkIn(CheckInRequest(rotation = RotationPattern.FORWARD_CROSS), now + 2, 11_000.0)
        val first = engine.rotationHistory().entries.single()
        engine.checkIn(CheckInRequest(rotation = RotationPattern.SIDE_TO_SIDE), now + 3, 12_000.0)
        val old = engine.mountedTires().getValue(WheelPosition.FRONT_LEFT)
        engine.checkIn(CheckInRequest(replacements = mapOf(WheelPosition.FRONT_LEFT to null)), now + 4, 12_100.0)
        val replacement = store.events().last()
        val new = engine.mountedTires().getValue(WheelPosition.FRONT_LEFT)
        assertNotEquals(old.id, new.id)
        assertEquals(listOf(old.id), replacement.previousTireIds)
        assertEquals(listOf(new.id), replacement.tireIds)
        assertFalse(store.tires().single { it.id == old.id }.active)
        assertTrue(store.measurementsSince(now).any { it.tireId == old.id })
        val reopened = TireLogEngine(store, zone).rotationHistory()
        assertEquals(2, reopened.entries.size)
        assertEquals(first.moves, reopened.entries.last().moves)
        assertEquals(first.pattern, reopened.entries.last().pattern)
        assertEquals(false, reopened.entries.last().sameTires)
    }

    @Test fun `세트를 바꿔도 현재 세트의 최근 교환과 다른 세트 기록을 구분한다`() = runTest {
        val store = InMemoryTireLogStore()
        val engine = TireLogEngine(store, zone)
        engine.completeOnboarding(input, now, 10_000.0)
        val summer = engine.mountedSet()!!
        val summerTires = engine.mountedTires().values.map { it.id }.toSet()
        engine.checkIn(CheckInRequest(rotation = RotationPattern.FRONT_REAR), now + 1, 11_000.0)
        val winter = engine.addSet(NewSetInput(TireSetType.WINTER, null, null), now + 2)
        engine.checkIn(CheckInRequest(changeToSetId = winter), now + 3, 12_000.0)
        assertEquals(summerTires, store.events().last().previousTireIds.toSet())
        assertNull(engine.rotationHistory().latestForMountedSet)
        engine.checkIn(CheckInRequest(rotation = RotationPattern.X_CROSS), now + 4, 13_000.0)
        assertEquals(RotationPattern.X_CROSS, engine.rotationHistory().latestForMountedSet!!.pattern)
        engine.checkIn(CheckInRequest(changeToSetId = summer.id), now + 5, 14_000.0)
        val history = engine.rotationHistory()
        assertEquals(2, history.entries.size)
        assertEquals(RotationPattern.FRONT_REAR, history.latestForMountedSet!!.pattern)
        assertEquals(true, history.latestForMountedSet!!.sameTires)
        assertNull(history.entries.first().sameTires)
    }

    @Test fun `정비 요약 30건 밖의 교환과 같은 시각의 교환도 최신순으로 조회한다`() = runTest {
        val store = InMemoryTireLogStore()
        val engine = TireLogEngine(store, zone)
        engine.completeOnboarding(input, now, 10_000.0)
        engine.checkIn(CheckInRequest(rotation = RotationPattern.FRONT_REAR), now + 1, 11_000.0)
        engine.checkIn(CheckInRequest(rotation = RotationPattern.X_CROSS), now + 1, null)
        repeat(35) { engine.checkIn(CheckInRequest(refill = setOf(WheelPosition.FRONT_LEFT)), now + 2 + it, null) }
        val beforeVersion = engine.version.value
        val history = engine.rotationHistory()
        assertEquals(2, history.entries.size)
        assertEquals(RotationPattern.X_CROSS, history.entries.first().pattern)
        assertNull(history.entries.first().odoKm)
        assertTrue(history.entries.first().dateLabel.startsWith("2026-"))
        assertEquals(beforeVersion, engine.version.value, "이력 조회는 기록을 변경하지 않는다")
    }

    @Test fun `과거 설명의 알려진 방식만 읽고 없는 이동 정보는 추측하지 않는다`() = runTest {
        val store = InMemoryTireLogStore()
        val engine = TireLogEngine(store, zone)
        engine.completeOnboarding(input, now, 10_000.0)
        val set = engine.mountedSet()!!
        store.addEvent(TireEvent(type = EventType.ROTATION, atMillis = now + 1, odoKm = null,
            setId = set.id, detail = "X자 교환 · A 앞왼쪽→뒤오른쪽"))
        store.addEvent(TireEvent(type = EventType.ROTATION, atMillis = now + 1, odoKm = null,
            setId = set.id, tireIds = listOf(engine.mountedTires().values.first().id), detail = "앞뒤 교환"))
        store.addEvent(TireEvent(type = EventType.ROTATION, atMillis = now + 2, odoKm = null,
            setId = set.id, detail = "정비소에서 교환"))
        val history = engine.rotationHistory()
        assertNull(history.entries.first().pattern)
        assertEquals("정비소에서 교환", history.entries.first().detail)
        assertEquals(RotationPattern.X_CROSS, history.entries.last().pattern)
        assertTrue(history.entries.all { it.moves.isEmpty() && it.sameTires == null })
    }

    @Test fun `교환 기록이 없으면 빈 이력이며 초기 등록을 교환으로 만들지 않는다`() = runTest {
        val engine = TireLogEngine(InMemoryTireLogStore(), zone)
        assertNull(engine.rotationHistory().mountedSetId)
        engine.completeOnboarding(input, now, 10_000.0)
        assertTrue(engine.rotationHistory().entries.isEmpty())
        assertNull(engine.rotationHistory().latestForMountedSet)
    }
}
