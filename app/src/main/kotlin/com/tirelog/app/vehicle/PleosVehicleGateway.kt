package com.tirelog.app.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.WheelArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.constant.area.WheelValue
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.TirePressureListener
import android.content.Context
import android.util.Log
import com.tirelog.core.PressureUnit
import com.tirelog.core.TimeSource
import com.tirelog.core.model.WheelPosition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * Pleos Vehicle SDK로 바퀴별 공기압·누적 주행거리·VIN을 읽는다. 기어·시동·외기온도·주행 규제는 AAOS Car API(CarSignals)로 읽는다.
 * 공기압 API·리스너는 공모전에서 제공되는 것으로 전제한다(권한은 매니페스트에 선언).
 */
class PleosVehicleGateway(
    context: Context,
    private val time: TimeSource,
) : VehicleGateway {

    private val appContext = context.applicationContext
    override val isDemo = false
    private val _live = MutableStateFlow(LiveVehicle())
    override val live: StateFlow<LiveVehicle> = _live.asStateFlow()

    @Volatile private var vehicle: Vehicle? = null
    @Volatile private var stopped = false
    private val checkMutex = Mutex()
    private val rawPressures = ConcurrentHashMap<WheelPosition, Double>()
    private val pressureTimes = ConcurrentHashMap<WheelPosition, Long>()
    @Volatile private var supportedPressurePositions: Set<WheelPosition>? = null
    @Volatile private var checkedPressurePositions: Set<WheelPosition>? = null
    @Volatile private var unit = PressureUnit.KPA
    @Volatile private var unitResolved = false
    private val tireListeners = ConcurrentHashMap<WheelPosition, TirePressureListener>()
    @Volatile private var odometerRegistered = false

    private val carSignals = CarSignals.createOrNull(
        appContext,
        onGear = { gear -> _live.update { it.copy(gear = gear) } },
        onIgnition = { on -> _live.update { it.copy(ignitionOn = on) } },
        onRestricted = { restricted -> _live.update { it.copy(restricted = restricted) } },
    )

    private val odometerListener = object : DistanceDrivenListener {
        override fun onDistanceDrivenUpdated(value: Float?) {
            val km = value?.toDouble()?.takeIf { it.isFinite() && it >= 0 }
            _live.update { it.copy(odoKm = km) }
        }

        override fun onFailed(e: Exception) { _live.update { it.copy(odoKm = null) } }
    }

    override suspend fun start(): VehicleCapabilities {
        carSignals?.start()
        return checkCapabilities()
    }

    /**
     * 공기압·주행거리 지원 여부를 확인한다. 확인 API가 실패하면 값을 직접 읽어 본다.
     * 시간 초과·연결 실패·권한 오류는 미지원이 아니라 UNKNOWN으로 돌려주고, 수집기가 다시 확인한다.
     */
    override suspend fun checkCapabilities(): VehicleCapabilities = checkMutex.withLock {
        if (stopped) return@withLock VehicleCapabilities.unknown(PendingCause.NO_SERVICE)
        val v = vehicle ?: connect() ?: return@withLock VehicleCapabilities.unknown(PendingCause.NO_SERVICE)
        val wheels = orNull {
            awaitCallback<List<WheelArea>> { ok, fail -> v.getTire().checkWheelAreaCapabilities({ ok(it) }, { fail(it) }) }
        }?.toPositions()
        checkedPressurePositions = null
        val (pressure, odometer) = coroutineScope {
            val p = async { probe({ checkPressure(v) }, { readsPressure(v, wheels) }) }
            val o = async { probe({ checkOdometer(v) }, { readOdometerRaw(v) != null }) }
            p.await() to o.await()
        }
        val positions = CapabilityResolver.pressurePositions(wheels, checkedPressurePositions)
        val resolved = CapabilityResolver.resolve(pressure, odometer)
        val caps = resolved.copy(
            pressure = if (positions?.isEmpty() == true) Support.UNSUPPORTED else resolved.pressure,
            pressurePositions = if (resolved.pressure == Support.UNSUPPORTED) emptySet() else positions,
        )
        if (caps.pressurePositions != null) supportedPressurePositions = caps.pressurePositions
        _live.update { it.copy(pressurePositions = supportedPressurePositions) }
        Log.i(TAG, "지원 확인: 공기압 $pressure, 주행거리 $odometer → $caps")
        if (caps.pressure != Support.UNKNOWN) registerTires(v, if (caps.pressure == Support.SUPPORTED) queryPositions() else emptySet())
        if (caps.pressure == Support.SUPPORTED) readLowPressure(v)
        if (caps.odometer == Support.SUPPORTED && !odometerRegistered) registerOdometer(v)
        val disconnected = CheckOutcome.DISCONNECTED in setOf(pressure.check, odometer.check)
        // SDK는 서비스 연결 재시도를 약 15초 뒤 멈춘다. 받은 값이 없으면 다음 확인 때 처음부터 다시 연결한다.
        if (stopped || (disconnected && !caps.connected)) release(v)
        caps
    }

    private fun connect(): Vehicle? = try {
        Vehicle(appContext).also {
            it.initialize()
            vehicle = it
        }
    } catch (t: Throwable) {
        Log.w(TAG, "Vehicle SDK 초기화 실패", t)
        null
    }

    private suspend fun probe(check: suspend () -> CheckOutcome, read: suspend () -> Boolean): Probe {
        val outcome = check()
        val readable = outcome != CheckOutcome.YES && orNull { read() } == true
        return Probe(outcome, readable)
    }

    private suspend fun checkPressure(v: Vehicle): CheckOutcome = outcome {
        awaitCallback(CHECK_TIMEOUT_MILLIS) { ok, fail ->
            v.getTire().checkTirePressureCapabilities(
                { config, areas ->
                    checkedPressurePositions = if (config.accessType.readable()) areas.toPositions() else emptySet()
                    ok(checkedPressurePositions!!.isNotEmpty())
                },
                { fail(it) },
            )
        }
    }

    private suspend fun checkOdometer(v: Vehicle): CheckOutcome = outcome {
        awaitCallback(CHECK_TIMEOUT_MILLIS) { ok, fail ->
            v.getOdometer().checkCurrentDistanceDrivenCapabilities({ config -> ok(config.accessType.readable()) }, { fail(it) })
        }
    }

    private suspend fun outcome(check: suspend () -> Boolean?): CheckOutcome = try {
        CapabilityResolver.outcomeOf(check())
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Log.w(TAG, "지원 여부 확인 실패", t)
        CapabilityResolver.classify(t)
    }

    /** 확인 API 실패 시 실제 휠 영역에서 읽힌 바퀴만 지원으로 기록한다. */
    private suspend fun readsPressure(v: Vehicle, wheels: Set<WheelPosition>?): Boolean {
        val readable = coroutineScope {
            (wheels ?: WheelPosition.entries.toSet()).map { position -> async {
                val raw = orNull { awaitCallback<Float?> { ok, fail ->
                    v.getTire().getTirePressure(position.toArea(), { ok(it) }, { fail(it) }) } }
                position.takeIf { raw != null && raw.isFinite() && raw >= 0 }
            } }.awaitAll().filterNotNull().toSet()
        }
        // 조회 성공은 지원의 근거지만 다른 바퀴의 시간 초과를 미지원으로 확정할 수는 없다.
        if (readable.isNotEmpty()) checkedPressurePositions = null
        return readable.isNotEmpty()
    }

    private suspend fun readOdometerRaw(v: Vehicle): Double? =
        awaitCallback<Float?> { ok, fail -> v.getOdometer().getCurrentDistanceDriven({ ok(it) }, { fail(it) }) }
            ?.toDouble()?.takeIf { it.isFinite() && it >= 0 }

    private suspend fun registerOdometer(v: Vehicle) {
        try {
            v.getOdometer().registerDistanceDriven(odometerListener)
            odometerRegistered = true
        } catch (t: Throwable) {
            Log.w(TAG, "주행거리 리스너 등록 실패", t)
        }
        readOdometerKm()?.let { km -> _live.update { it.copy(odoKm = km) } }
    }

    private fun registerTires(v: Vehicle, positions: Set<WheelPosition>) {
        tireListeners.keys.filterNot { it in positions }.forEach { position ->
            tireListeners.remove(position)?.let { listener -> runCatching { v.getTire().unregisterTirePressure(position.toArea(), listener) } }
            rawPressures.remove(position)
            pressureTimes.remove(position)
        }
        publishPressures()
        positions.filterNot { tireListeners.containsKey(it) }.forEach { position ->
            val listener = object : TirePressureListener {
                override fun onTirePressureUpdated(pressure: Float?) {
                    if (stopped || position !in queryPositions()) return
                    val raw = pressure?.toDouble()?.takeIf { it.isFinite() && it >= 0 } ?: return
                    rawPressures[position] = raw
                    pressureTimes[position] = time.now()
                    publishPressures()
                }

                override fun onFailed(e: Exception) = Unit
            }
            runCatching {
                v.getTire().registerTirePressure(position.toArea(), listener)
                tireListeners[position] = listener
            }.onFailure { Log.w(TAG, "공기압 리스너 등록 실패: $position", it) }
        }
    }

    private fun publishPressures() {
        if (!unitResolved && rawPressures.isNotEmpty() && rawPressures.keys.containsAll(queryPositions())) {
            unit = PressureUnit.detect(rawPressures.values, unit)
            unitResolved = true
        }
        val kpa = rawPressures.mapValues { (_, raw) -> unit.toKpa(raw) }
        _live.update { it.copy(pressuresKpa = kpa, pressuresAt = pressureTimes.values.maxOrNull(), pressureTimes = pressureTimes.toMap()) }
    }

    override suspend fun readPressuresKpa(): Map<WheelPosition, Double> {
        val v = vehicle ?: return emptyMap()
        val fresh = coroutineScope {
            queryPositions().map { position ->
                async {
                    val raw = orNull {
                        awaitCallback<Float?> { ok, fail ->
                            v.getTire().getTirePressure(position.toArea(), { ok(it) }, { fail(it) })
                        }
                    }?.toDouble()?.takeIf { it.isFinite() && it >= 0 }
                    raw?.let { position to it }
                }
            }.awaitAll().filterNotNull().toMap()
        }
        if (fresh.isEmpty()) return emptyMap()
        if (!unitResolved && fresh.keys.containsAll(queryPositions())) {
            unit = PressureUnit.detect(fresh.values, unit)
            unitResolved = true
        }
        val kpa = fresh.mapValues { (_, raw) -> unit.toKpa(raw) }
        rawPressures.clear()
        rawPressures.putAll(fresh)
        val at = time.now()
        pressureTimes.clear()
        fresh.keys.forEach { pressureTimes[it] = at }
        _live.update { it.copy(pressuresKpa = kpa, pressuresAt = at, pressureTimes = pressureTimes.toMap()) }
        return kpa
    }

    override suspend fun readOdometerKm(): Double? {
        val value = vehicle?.let { orNull { readOdometerRaw(it) } }
        _live.update { it.copy(odoKm = value) }
        return value
    }

    override suspend fun readOutsideTempC(): Double? = carSignals?.readOutsideTempC()

    override suspend fun readIdentity() = carSignals?.readIdentity()

    private fun queryPositions(): Set<WheelPosition> = supportedPressurePositions ?: WheelPosition.entries.toSet()

    private fun List<WheelArea>.toPositions(): Set<WheelPosition> = flatMap { it.values.toList() }.mapNotNull {
        when (it) {
            WheelValue.LEFT_FRONT -> WheelPosition.FRONT_LEFT
            WheelValue.RIGHT_FRONT -> WheelPosition.FRONT_RIGHT
            WheelValue.LEFT_REAR -> WheelPosition.REAR_LEFT
            WheelValue.RIGHT_REAR -> WheelPosition.REAR_RIGHT
            else -> null
        }
    }.toSet()

    private suspend fun readLowPressure(v: Vehicle) {
        val areas = orNull {
            awaitCallback<Set<WheelPosition>> { ok, fail -> v.getTire().checkLowTirePressureCapabilities(
                { config, list -> ok(if (config.accessType.readable()) list.toPositions() else emptySet()) }, { fail(it) }) }
        } ?: return
        val values = coroutineScope {
            (areas intersect queryPositions()).map { position -> async {
                orNull { awaitCallback<Float?> { ok, fail -> v.getTire().getLowTirePressure(position.toArea(), { ok(it) }, { fail(it) }) } }
                    ?.toDouble()?.takeIf { it.isFinite() && it > 0 }?.let { position to it }
            } }.awaitAll().filterNotNull().toMap()
        }
        _live.update { it.copy(lowPressureKpa = values) }
    }

    override suspend fun readVin(): String? {
        val v = vehicle ?: return null
        return orNull {
            awaitCallback<String?> { ok, fail -> v.getCarInfo().getVINInfo({ ok(it) }, { fail(it) }) }
        }
    }

    override fun stop() {
        stopped = true
        vehicle?.let(::release)
        carSignals?.stop()
    }

    private fun release(v: Vehicle) {
        tireListeners.forEach { (position, listener) ->
            runCatching { v.getTire().unregisterTirePressure(position.toArea(), listener) }
        }
        tireListeners.clear()
        rawPressures.clear()
        pressureTimes.clear()
        unitResolved = false
        unit = PressureUnit.KPA
        _live.update { it.copy(pressuresKpa = emptyMap(), pressuresAt = null, pressureTimes = emptyMap(), lowPressureKpa = emptyMap(), odoKm = null) }
        if (odometerRegistered) runCatching { v.getOdometer().unregisterDistanceDriven(odometerListener) }
        odometerRegistered = false
        runCatching { v.release() }
        if (vehicle === v) vehicle = null
    }

    private suspend fun <T> orNull(block: suspend () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (t: Exception) {
        Log.w(TAG, "차량 데이터 조회 실패", t)
        null
    }

    private suspend fun <T> awaitCallback(
        timeoutMillis: Long = 3_000,
        call: (ok: (T) -> Unit, fail: (Exception) -> Unit) -> Unit,
    ): T? = withTimeoutOrNull(timeoutMillis) {
        suspendCancellableCoroutine<Result<T>> { cont ->
            try {
                call({ if (cont.isActive) cont.resume(Result.success(it)) }, { if (cont.isActive) cont.resume(Result.failure(it)) })
            } catch (t: Throwable) {
                if (cont.isActive) cont.resume(Result.failure(t))
            }
        }
    }?.getOrThrow()

    private fun AccessType.readable() = this == AccessType.READ || this == AccessType.READ_WRITE

    private fun WheelPosition.toArea(): WheelArea = WheelArea(
        when (this) {
            WheelPosition.FRONT_LEFT -> WheelValue.LEFT_FRONT
            WheelPosition.FRONT_RIGHT -> WheelValue.RIGHT_FRONT
            WheelPosition.REAR_LEFT -> WheelValue.LEFT_REAR
            WheelPosition.REAR_RIGHT -> WheelValue.RIGHT_REAR
        },
    )

    companion object {
        private const val TAG = "PleosVehicleGateway"

        /** 첫 확인은 SDK가 차량 서비스에 붙을 때까지 기다린다. */
        private const val CHECK_TIMEOUT_MILLIS = 6_000L
    }
}
