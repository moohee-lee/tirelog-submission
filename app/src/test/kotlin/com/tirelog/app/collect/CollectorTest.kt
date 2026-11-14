package com.tirelog.app.collect

import com.tirelog.app.vehicle.DemoVehicleGateway
import com.tirelog.app.vehicle.VehicleGateway
import com.tirelog.core.TimeSource
import com.tirelog.core.alert.ActiveAlert
import com.tirelog.core.drive.GearState
import com.tirelog.core.engine.InMemoryTireLogStore
import com.tirelog.core.engine.TireLogEngine
import com.tirelog.core.model.TireSetType
import com.tirelog.core.model.*
import com.tirelog.core.engine.CheckInRequest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

class CollectorTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun pressureListenerChangesWhileDrivingAreRecordedWithoutPopups() = runTest {
        val store = InMemoryTireLogStore()
        val engine = TireLogEngine(store, ZoneId.of("Asia/Seoul"))
        var clock = now
        engine.completeOnboarding(TireLogEngine.OnboardingInput(
            RecommendedPressure(250.0, 250.0, RecommendedSource.LABEL_CONFIRMED), TireSetType.SUMMER, null, null),
            now - 1_000, 20_000.0)
        val source = gateway().apply { update { it.copy(pressuresKpa = WheelPosition.entries.associateWith { 250.0 }) } }
        val presenter = Presenter()
        val collector = Collector(engine, source, object : TimeSource { override fun now() = clock }, backgroundScope, presenter, 90_000)
        collector.start()
        runCurrent()
        clock += 1_000
        source.update { it.copy(gear = GearState.DRIVE, restricted = true, pressuresAt = clock) }
        runCurrent()
        clock += 30_000
        source.update { it.copy(pressuresKpa = it.pressuresKpa + (WheelPosition.FRONT_RIGHT to 210.0), pressuresAt = clock) }
        runCurrent()
        assertEquals(EventType.RAPID_PRESSURE_DROP, store.events().single().type)
        assertEquals(2, store.measurementsSince(0).size)
        assertTrue(presenter.alerts.isEmpty())
        assertFalse(engine.measuredToday(clock))
        collector.stop()
    }
    private val now = 1_800_000_000_000L
    private val pressures = WheelPosition.entries.associateWith { 210.0 }

    private suspend fun setup(parked: Boolean = true): TireLogEngine {
        val engine = TireLogEngine(InMemoryTireLogStore(), ZoneId.of("Asia/Seoul"))
        engine.completeOnboarding(TireLogEngine.OnboardingInput(
            RecommendedPressure(250.0, 250.0, RecommendedSource.LABEL_CONFIRMED), TireSetType.SUMMER, null, null),
            now - TireLogEngine.DAY_MILLIS, 20_000.0)
        engine.saveDriveState(DriveState(if (parked) now - 4 * 60 * 60_000 else null,
            if (parked) 20_000.0 else null, 20_000.0))
        return engine
    }

    private fun gateway() = DemoVehicleGateway().apply {
        update { it.copy(pressuresKpa = pressures, pressuresAt = now, odoKm = 20_000.0, tempC = 10.0) }
    }

    private class Presenter : AlertPresenter {
        val alerts = mutableListOf<ActiveAlert>()
        override val hasActiveAlert get() = alerts.isNotEmpty()
        override fun present(alert: ActiveAlert) { alerts += alert }
        override fun dismiss() { alerts.clear() }
    }

    @Test fun openingAppAfterKnownLongParkingMeasuresAndAlertsWithoutADeparture() = runTest {
        val engine = setup()
        val presenter = Presenter()
        val collector = Collector(engine, gateway(), object : TimeSource { override fun now() = now }, backgroundScope, presenter, 1_000)
        collector.refreshOnOpen()
        assertTrue(engine.measuredToday(now))
        assertTrue(engine.latestReadings().all { it.cold })
        assertEquals(AlertType.REFILL_NEEDED, presenter.alerts.single().type)
        assertEquals(1, engine.dashboard(now).measuredDaysLast7)
    }

    @Test fun unknownParkingTimeStoresReferenceValuesAndDefersJudgment() = runTest {
        val engine = setup(parked = false)
        val presenter = Presenter()
        val collector = Collector(engine, gateway(), object : TimeSource { override fun now() = now }, backgroundScope, presenter, 1_000)
        collector.refreshOnOpen()
        assertFalse(engine.measuredToday(now))
        assertEquals(4, engine.latestReadings().size)
        assertTrue(engine.latestReadings().none { it.cold })
        assertTrue(presenter.alerts.isEmpty())
    }

    @Test fun departureDuringARefreshCannotProduceColdJudgmentOrPopup() = runTest {
        val engine = setup()
        val source = gateway()
        val changing = object : VehicleGateway by source {
            override suspend fun readPressuresKpa(): Map<WheelPosition, Double> {
                source.update { it.copy(gear = GearState.DRIVE, restricted = true) }
                return pressures
            }
        }
        val presenter = Presenter()
        val collector = Collector(engine, changing, object : TimeSource { override fun now() = now }, backgroundScope, presenter, 1_000)
        collector.refreshOnOpen()
        assertFalse(engine.measuredToday(now))
        assertTrue(presenter.alerts.isEmpty())
    }

    @Test fun pressureUnsupportedVehicleStillUpdatesMileageAndShowsNutCheck() = runTest {
        val engine = setup()
        engine.checkIn(CheckInRequest(rotation = RotationPattern.FRONT_REAR), now - 1_000, 20_000.0)
        engine.updateSettings { it.copy(pressureSupported = false) }
        val source = gateway().apply { update { it.copy(pressuresKpa = emptyMap(), odoKm = 20_060.0) } }
        val presenter = Presenter()
        val collector = Collector(engine, source, object : TimeSource { override fun now() = now }, backgroundScope, presenter, 1_000)
        collector.refreshOnOpen()
        assertEquals(60, engine.setsOverview(now, null).rows.single().km)
        assertEquals(AlertType.NUT_CHECK, presenter.alerts.single().type)
        assertTrue(engine.latestReadings().isEmpty())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun openingBeforeCollectorIsReadyIsQueuedUntilStartupCompletes() = runTest {
        val engine = setup()
        val collector = Collector(engine, gateway(), object : TimeSource { override fun now() = now }, backgroundScope, Presenter(), 1_000)
        collector.onAppOpened()
        collector.start()
        runCurrent()
        assertTrue(engine.measuredToday(now))
        collector.stop()
    }

    @Test
    fun alertsRequireParkAndCannotReplaceAnOpenPopup() = runTest {
        val engine = TireLogEngine(InMemoryTireLogStore(), ZoneId.of("Asia/Seoul"))
        engine.completeOnboarding(
            TireLogEngine.OnboardingInput(
                null, TireSetType.SUMMER, null, null, kmSinceLastRotation = 11_000.0,
            ),
            0, 20_000.0,
        )
        val gateway = DemoVehicleGateway().apply { update { it.copy(odoKm = 20_000.0) } }
        val shown = mutableListOf<ActiveAlert>()
        val presenter = object : AlertPresenter {
            override val hasActiveAlert: Boolean get() = shown.isNotEmpty()
            override fun present(alert: ActiveAlert) { shown += alert }
            override fun dismiss() { shown.clear() }
        }
        val collector = Collector(engine, gateway, object : TimeSource {
            override fun now() = 0L
        }, backgroundScope, presenter, 1_000)

        listOf(GearState.UNKNOWN, GearState.DRIVE).forEach { gear ->
            gateway.update { it.copy(gear = gear, restricted = false) }
            collector.presentNext()
        }
        gateway.update { it.copy(gear = GearState.PARK, ignitionOn = false) }
        collector.presentNext()
        assertEquals(0, shown.size)
        gateway.update { it.copy(ignitionOn = true, restricted = true) }
        collector.presentNext()
        assertEquals(0, shown.size)
        gateway.update { it.copy(restricted = false) }
        collector.presentNext()
        collector.presentNext()
        assertEquals(1, shown.size)
    }
}
