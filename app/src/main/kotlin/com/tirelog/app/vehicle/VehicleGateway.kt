package com.tirelog.app.vehicle

import com.tirelog.core.drive.GearState
import com.tirelog.core.model.WheelPosition
import kotlinx.coroutines.flow.StateFlow

/** 차가 그 값을 주는지. UNKNOWN은 미지원이 아니라 아직 확인하지 못한 상태다. */
enum class Support { SUPPORTED, UNSUPPORTED, UNKNOWN }

/** 확인하지 못한 까닭. */
enum class PendingCause { NOT_YET, NO_SERVICE, PERMISSION }

/** 미지원은 차가 그렇다고 답했을 때만 쓴다. 서비스 연결 지연·시간 초과·권한 오류는 UNKNOWN으로 두고 다시 확인한다. */
data class VehicleCapabilities(
    val pressure: Support,
    val odometer: Support,
    val cause: PendingCause = PendingCause.NOT_YET,
    /** null은 아직 확인하지 못함. 빈 집합은 지원 바퀴가 없다고 확인됨. */
    val pressurePositions: Set<WheelPosition>? = null,
) {
    /** 공기압이나 주행거리 중 하나라도 받을 수 있다. */
    val connected: Boolean get() = pressure == Support.SUPPORTED || odometer == Support.SUPPORTED

    /** 아직 확인하지 못한 항목이 있다. */
    val pending: Boolean get() = pressure == Support.UNKNOWN || odometer == Support.UNKNOWN

    /** 차가 공기압·주행거리를 모두 제공하지 않는다고 답했다. */
    val unsupported: Boolean get() = pressure == Support.UNSUPPORTED && odometer == Support.UNSUPPORTED

    val message: String?
        get() {
            if (unsupported) return "이 차는 공기압과 주행거리를 제공하지 않습니다"
            val target = when {
                pressure == Support.UNKNOWN && odometer == Support.UNKNOWN -> "차량 데이터를"
                pressure == Support.UNKNOWN -> "공기압 값을"
                odometer == Support.UNKNOWN -> "주행거리 값을"
                else -> return null
            }
            return when (cause) {
                PendingCause.NOT_YET -> "$target 아직 받지 못했습니다"
                PendingCause.NO_SERVICE -> "차량 서비스에 연결하지 못해 $target 받지 못했습니다"
                PendingCause.PERMISSION -> "권한 오류로 $target 받지 못했습니다"
            }
        }

    /** 새 결과로 갱신한다. 이미 확인된 항목은 새 결과가 "확인 못 함"이어도 그대로 둔다. */
    fun updatedBy(newer: VehicleCapabilities) = newer.copy(
        pressure = newer.pressure.takeUnless { it == Support.UNKNOWN } ?: pressure,
        odometer = newer.odometer.takeUnless { it == Support.UNKNOWN } ?: odometer,
        pressurePositions = if (newer.pressure == Support.UNSUPPORTED) emptySet() else newer.pressurePositions ?: pressurePositions,
    )

    companion object {
        fun unknown(cause: PendingCause) = VehicleCapabilities(Support.UNKNOWN, Support.UNKNOWN, cause)
    }
}

/** 확인된 항목만 true/false. 확인하지 못했으면 null. */
val Support.known: Boolean?
    get() = when (this) {
        Support.SUPPORTED -> true
        Support.UNSUPPORTED -> false
        Support.UNKNOWN -> null
    }

/** 차량이 지금 주는 값. 판단은 수집기가 냉간 여부를 확인한 뒤에만 한다. */
data class LiveVehicle(
    val pressuresKpa: Map<WheelPosition, Double> = emptyMap(),
    val pressuresAt: Long? = null,
    val odoKm: Double? = null,
    val gear: GearState = GearState.UNKNOWN,
    val ignitionOn: Boolean? = null,
    val restricted: Boolean = false,
    val tempC: Double? = null,
    val pressurePositions: Set<WheelPosition>? = null,
    val pressureTimes: Map<WheelPosition, Long> = emptyMap(),
    val lowPressureKpa: Map<WheelPosition, Double> = emptyMap(),
)

interface VehicleGateway {
    val isDemo: Boolean
    val live: StateFlow<LiveVehicle>

    /** 차량 신호를 받기 시작하고 지원 여부를 처음 확인한다. */
    suspend fun start(): VehicleCapabilities

    /** 지원 여부를 다시 확인한다. 새로 확인된 항목은 리스너를 등록한다. */
    suspend fun checkCapabilities(): VehicleCapabilities
    fun stop()

    /** 네 바퀴 공기압을 지금 한 번 읽는다(kPa). */
    suspend fun readPressuresKpa(): Map<WheelPosition, Double>
    suspend fun readOdometerKm(): Double?
    suspend fun readOutsideTempC(): Double?
    suspend fun readVin(): String?
    suspend fun readIdentity(): com.tirelog.core.catalog.VehicleIdentity? = null
}
