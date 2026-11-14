package com.tirelog.app.collect

import com.tirelog.app.vehicle.VehicleCapabilities
import com.tirelog.app.vehicle.VehicleGateway
import com.tirelog.app.vehicle.known
import com.tirelog.core.TimeSource
import com.tirelog.core.alert.ActiveAlert
import com.tirelog.core.drive.DriveSessionTracker
import com.tirelog.core.drive.DriveSessionTracker.Effect
import com.tirelog.core.drive.GearState
import com.tirelog.core.drive.VehicleSignals
import com.tirelog.core.engine.TireLogEngine
import com.tirelog.core.judge.ColdRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.roundToLong

interface AlertPresenter {
    val hasActiveAlert: Boolean
    fun present(alert: ActiveAlert)
    fun dismiss()
}

/**
 * 수집 → 판단 → 알림의 연결부.
 * - 출발 1~2분 뒤 공기압·외기온도·ODO를 기록한다. 3시간 이상 주차 후 1.6km 이내면 냉간 측정으로 판단한다.
 * - P단이고 조작 제한이 풀리면 미뤄 둔 알림을 하나만 띄운다.
 */
class Collector(
    private val engine: TireLogEngine,
    private val gateway: VehicleGateway,
    private val time: TimeSource,
    private val scope: CoroutineScope,
    private val presenter: AlertPresenter,
    private val measureDelayMillis: Long,
) {
    private val _capabilities = MutableStateFlow<VehicleCapabilities?>(null)
    val capabilities: StateFlow<VehicleCapabilities?> = _capabilities.asStateFlow()

    private val _checking = MutableStateFlow(false)

    /** 지원 여부를 확인하는 중. */
    val checking: StateFlow<Boolean> = _checking.asStateFlow()
    private val recheckRequests = Channel<Unit>(Channel.CONFLATED)
    private val openRequests = Channel<Unit>(Channel.CONFLATED)
    private val measurementMutex = Mutex()

    private val _afterDriving = MutableStateFlow(false)

    /** 이번 시동에서 이미 달렸으면 true. 지금 값을 "주행 직후 값"으로 표시한다. */
    val afterDriving: StateFlow<Boolean> = _afterDriving.asStateFlow()

    private var job: Job? = null
    private var measureJob: Job? = null
    private val popupMutex = Mutex()

    fun start() {
        if (job != null) return
        job = scope.launch {
            val tracker = DriveSessionTracker(engine.driveState())
            // 차량 연결 확인(수 초)을 기다리지 않고 바로 주행 상태를 따라간다.
            launch {
                gateway.live
                    // 차량 신호가 하나도 오기 전의 기본값을 정차로 오해하지 않는다(주행 중 팝업 방지).
                    .dropWhile { it.gear == GearState.UNKNOWN && it.odoKm == null && it.ignitionOn == null && !it.restricted }
                    .map { VehicleSignals(it.gear, it.restricted, it.odoKm?.let { km -> (km * 10).roundToLong() / 10.0 }, it.ignitionOn) }
                    .distinctUntilChanged()
                    .collect { signals ->
                        if (signals.ignitionOn == false) _afterDriving.value = false
                        if (signals.restricted || signals.gear != GearState.PARK || signals.ignitionOn == false) presenter.dismiss()
                        handle(tracker.update(time.now(), signals))
                    }
            }
            check { gateway.start() }
            launch {
                val drops = com.tirelog.core.judge.PressureDropTracker()
                gateway.live.collect { live ->
                    val settings = engine.settings()
                    if (!settings.onboardingDone || !settings.pressureSupported || live.gear != GearState.DRIVE || live.ignitionOn == false) {
                        drops.clear()
                        return@collect
                    }
                    val tires = engine.mountedTires()
                    live.pressuresKpa.forEach { (position, kpa) ->
                        val tire = tires[position] ?: return@forEach
                        val at = if (live.pressureTimes.isEmpty()) live.pressuresAt else live.pressureTimes[position]
                        if (at != null) drops.observe(tire.id, position, kpa, at, time.now())?.let { drop ->
                            engine.recordPressureDrop(drop, live.tempC, live.odoKm)
                        }
                    }
                }
            }
            launch {
                for (request in openRequests) refreshOnOpen()
            }
            // 확인하지 못한 항목이 있으면 간격을 늘려 가며 다시 확인한다. 차량 서비스가 늦게 뜨는 경우(시동 직후)에 대비한다.
            var autoChecks = 0
            while (true) {
                val wait = if (_capabilities.value?.pending == true) RECHECK_DELAYS_MILLIS.getOrNull(autoChecks++) else null
                if (wait == null) recheckRequests.receive() else withTimeoutOrNull(wait) { recheckRequests.receive() }
                check { gateway.checkCapabilities() }
            }
        }
    }

    /** [다시 확인]. */
    fun recheck() {
        if (!_checking.value) recheckRequests.trySend(Unit)
    }

    /** 연결 준비가 끝난 뒤 최근 값을 받아 판단한다. 시동 시 자동 실행이 안 된 차량의 대체 경로다. */
    fun onAppOpened() {
        openRequests.trySend(Unit)
    }

    internal suspend fun refreshOnOpen() {
        measurementMutex.withLock {
            val settings = engine.settings()
            if (!settings.onboardingDone) return@withLock
            val before = gateway.live.value
            val parked = engine.driveState()
            val odo = gateway.readOdometerKm()
            val kpa = if (settings.pressureSupported) gateway.readPressuresKpa() else emptyMap()
            val temp = if (kpa.isNotEmpty()) gateway.readOutsideTempC() else null
            val now = time.now()
            val after = gateway.live.value
            val stop = parked.lastStopAtMillis
            val distance = if (odo != null && parked.lastStopOdoKm != null) odo - parked.lastStopOdoKm!! else null
            val sameStop = engine.driveState().lastStopAtMillis == stop
            val stationary = before.gear == GearState.PARK && after.gear == GearState.PARK &&
                !before.restricted && !after.restricted && before.ignitionOn != false && after.ignitionOn != false
            val cold = stationary && sameStop && distance != null && abs(distance) <= DriveSessionTracker.SAME_SPOT_KM &&
                ColdRule.isCold(stop?.let { (now - it) / 60_000 }, distance)
            if (kpa.isNotEmpty() && cold && !engine.measuredToday(now)) {
                engine.recordColdReading(kpa, temp, odo, now)
            } else {
                // 주차 시간을 모르면 참고값만 저장한다. ODO만 있는 차량도 관리 거리를 갱신한다.
                engine.recordHotReading(kpa, temp, odo, now)
            }
        }
        presentNext()
    }

    private suspend fun check(block: suspend () -> VehicleCapabilities) {
        _checking.value = true
        try {
            val result = block()
            val caps = _capabilities.value?.updatedBy(result) ?: result
            _capabilities.value = caps
            syncSettings(caps)
        } finally {
            _checking.value = false
        }
    }

    /** 확인된 항목만 설정에 반영한다. 확인하지 못했다고 미지원으로 바꾸지 않는다. */
    private suspend fun syncSettings(caps: VehicleCapabilities) {
        val settings = engine.settings()
        if (!settings.onboardingDone) return
        val pressure = caps.pressure.known ?: settings.pressureSupported
        val odometer = caps.odometer.known ?: settings.odometerSupported
        val positions = caps.pressurePositions ?: settings.pressurePositions
        if (pressure != settings.pressureSupported || odometer != settings.odometerSupported || positions != settings.pressurePositions) {
            engine.updateSettings { it.copy(pressureSupported = pressure, odometerSupported = odometer, pressurePositions = positions) }
        }
    }

    fun stop() {
        job?.cancel()
        measureJob?.cancel()
        job = null
        gateway.stop()
    }

    private suspend fun handle(effects: List<Effect>) {
        for (effect in effects) {
            when (effect) {
                is Effect.SaveState -> engine.saveDriveState(effect.state, time.now())
                is Effect.Departed -> {
                    _afterDriving.value = true
                    presenter.dismiss()
                    measureJob?.cancel()
                    measureJob = scope.launch {
                        delay(measureDelayMillis)
                        measure(effect.departure)
                    }
                }
                Effect.Parked -> measureJob?.takeIf { it.isActive }?.cancel()
                Effect.ReadyForAlerts -> presentNext()
            }
        }
    }

    private suspend fun measure(departure: DriveSessionTracker.Departure) = measurementMutex.withLock {
        val settings = engine.settings()
        if (!settings.onboardingDone) return@withLock
        val kpa = if (settings.pressureSupported) gateway.readPressuresKpa() else emptyMap()
        val now = time.now()
        val temp = gateway.readOutsideTempC()
        val odo = gateway.readOdometerKm()
        val distance = if (odo != null && departure.odoKm != null) odo - departure.odoKm!! else null
        if (kpa.isNotEmpty() && ColdRule.isCold(departure.parkedMinutes, distance) && !engine.measuredToday(now)) {
            engine.recordColdReading(kpa, temp, odo, now)
        } else {
            engine.recordHotReading(kpa, temp, odo, now)
        }
    }

    /** 정차 때(또는 앱을 연 직후 정차 중이면) 대기 알림 하나를 띄운다. */
    suspend fun presentNext() = popupMutex.withLock {
        if (presenter.hasActiveAlert) return
        if (!engine.settings().onboardingDone) return
        val live = gateway.live.value
        if (live.restricted || live.gear != GearState.PARK || live.ignitionOn == false) return
        val now = time.now()
        val alert = engine.nextPopup(now, live.odoKm) ?: return
        val latest = gateway.live.value
        if (latest.restricted || latest.gear != GearState.PARK || latest.ignitionOn == false) return
        engine.markShown(alert.key, now)
        val afterSave = gateway.live.value
        if (afterSave.restricted || afterSave.gear != GearState.PARK || afterSave.ignitionOn == false) return
        presenter.present(alert)
    }

    private companion object {
        /** 자동 재확인 간격. 합쳐 약 17분 뒤에는 [다시 확인]이나 다음 실행 때 확인한다. */
        val RECHECK_DELAYS_MILLIS = listOf(5, 10, 20, 30, 60, 60, 120, 120, 300, 300).map { it * 1_000L }
    }
}
