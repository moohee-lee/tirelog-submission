package com.tirelog.core

import com.tirelog.core.drive.DriveSessionTracker
import com.tirelog.core.drive.DriveSessionTracker.Effect
import com.tirelog.core.drive.GearState
import com.tirelog.core.drive.VehicleSignals
import com.tirelog.core.model.DriveState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DriveSessionTrackerTest {
    @Test fun `주행 중 역전된 ODO도 정차 전에 저장 효과로 전달한다`() {
        val tracker = DriveSessionTracker(DriveState())
        tracker.update(0, signals(GearState.DRIVE, 10_000.0))
        val reversed = tracker.update(1_000, signals(GearState.DRIVE, 9_500.0))
        assertEquals(9_500.0, reversed.filterIsInstance<Effect.SaveState>().single().state.lastKnownOdoKm)
        assertTrue(reversed.none { it is Effect.Parked || it is Effect.ReadyForAlerts })
    }

    private val hour = 3_600_000L

    private fun signals(gear: GearState, odo: Double?, restricted: Boolean = gear == GearState.DRIVE, ignition: Boolean? = true) =
        VehicleSignals(gear, restricted, odo, ignition)

    @Test
    fun `밤새 세워 둔 차는 앱이 새로 켜져도 주차 시간을 이어 센다`() {
        val tracker = DriveSessionTracker(DriveState(lastStopAtMillis = 0, lastStopOdoKm = 23_300.0))
        tracker.update(13 * hour, signals(GearState.PARK, 23_300.05))
        val effects = tracker.update(13 * hour + 60_000, signals(GearState.DRIVE, 23_300.1))
        val departed = effects.filterIsInstance<Effect.Departed>().single()
        assertEquals(13 * 60L + 1, departed.departure.parkedMinutes)
    }

    @Test
    fun `앱이 켜질 때 ODO를 아직 모르면 이전 정차 기록을 지우지 않는다`() {
        val tracker = DriveSessionTracker(DriveState(lastStopAtMillis = 0, lastStopOdoKm = 23_300.0))
        tracker.update(13 * hour, signals(GearState.UNKNOWN, null))
        assertEquals(0L, tracker.state.lastStopAtMillis)
        val departed = tracker.update(13 * hour + 60_000, signals(GearState.DRIVE, 23_300.0))
            .filterIsInstance<Effect.Departed>().single()
        assertEquals(13 * 60L + 1, departed.departure.parkedMinutes)
    }

    @Test
    fun `앱이 꺼진 동안 차가 움직였으면 주차 시간을 모른다`() {
        val tracker = DriveSessionTracker(DriveState(lastStopAtMillis = 0, lastStopOdoKm = 23_300.0))
        tracker.update(13 * hour, signals(GearState.PARK, 23_340.0))
        val departed = tracker.update(13 * hour + 1, signals(GearState.DRIVE, 23_340.0))
            .filterIsInstance<Effect.Departed>().single()
        assertTrue(departed.departure.parkedMinutes!! < 1)
    }

    @Test
    fun `달리는 중에 앱이 켜지면 출발 지점을 몰라 냉간 판정을 하지 않는다`() {
        val tracker = DriveSessionTracker(DriveState())
        val departed = tracker.update(0, signals(GearState.DRIVE, 100.0)).filterIsInstance<Effect.Departed>().single()
        assertNull(departed.departure.parkedMinutes)
        assertNull(departed.departure.odoKm)
    }

    @Test
    fun `P단이고 조작 제한이 풀린 순간에 한 번만 알림 시점을 낸다`() {
        val tracker = DriveSessionTracker(DriveState())
        tracker.update(0, signals(GearState.DRIVE, 100.0))
        val parked = tracker.update(1_000, signals(GearState.PARK, 120.0, restricted = true))
        assertTrue(parked.contains(Effect.Parked))
        assertTrue(Effect.ReadyForAlerts !in parked, "조작 제한이 풀리기 전에는 띄우지 않는다")
        val ready = tracker.update(2_000, signals(GearState.PARK, 120.0, restricted = false))
        assertTrue(Effect.ReadyForAlerts in ready)
        assertTrue(Effect.ReadyForAlerts !in tracker.update(3_000, signals(GearState.PARK, 120.0, restricted = false)))
        assertEquals(1_000L, tracker.state.lastStopAtMillis)
    }

    @Test
    fun `시동을 껐다 켜면 알림 시점을 다시 낸다`() {
        val tracker = DriveSessionTracker(DriveState())
        tracker.update(0, signals(GearState.PARK, 100.0, restricted = false))
        tracker.update(1_000, signals(GearState.PARK, 100.0, restricted = false, ignition = false))
        val effects = tracker.update(2_000, signals(GearState.PARK, 100.0, restricted = false, ignition = true))
        assertTrue(Effect.ReadyForAlerts in effects)
    }

    @Test
    fun `주행 규제가 없어도 기어를 모르면 알림을 띄우지 않는다`() {
        val tracker = DriveSessionTracker(DriveState())
        val unknown = tracker.update(0, signals(GearState.UNKNOWN, 100.0, restricted = false))
        assertTrue(Effect.ReadyForAlerts !in unknown)
        val parked = tracker.update(1_000, signals(GearState.PARK, 100.0, restricted = false))
        assertTrue(Effect.ReadyForAlerts in parked)
    }

    @Test
    fun `최초 주차 뒤 늦게 도착한 ODO부터 냉간 대기 시간을 센다`() {
        val tracker = DriveSessionTracker(DriveState())
        tracker.update(0, signals(GearState.PARK, null))
        tracker.update(hour, signals(GearState.PARK, 50_000.0))
        val departed = tracker.update(4 * hour, signals(GearState.DRIVE, 50_000.1))
            .filterIsInstance<Effect.Departed>().single()
        assertEquals(180L, departed.departure.parkedMinutes)
    }
}
