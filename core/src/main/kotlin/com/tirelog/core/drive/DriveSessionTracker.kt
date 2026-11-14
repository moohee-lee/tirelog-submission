package com.tirelog.core.drive

import com.tirelog.core.model.DriveState
import kotlin.math.abs

enum class GearState { PARK, DRIVE, UNKNOWN }

data class VehicleSignals(
    val gear: GearState,
    /** 주행 규제(CarUxRestrictions) 중이면 true. */
    val restricted: Boolean,
    val odoKm: Double?,
    val ignitionOn: Boolean?,
)

/**
 * 기어·시동·주행 규제·ODO로 정차와 출발을 가른다.
 * 주차 시간은 "마지막 정차 이후 ODO가 그대로인지"로 확인해, 앱이 꺼져 있던 동안 움직였으면 모른다고 본다.
 */
class DriveSessionTracker(initial: DriveState) {

    enum class Phase { UNKNOWN, PARKED, DRIVING }

    data class Departure(val atMillis: Long, val odoKm: Double?, val parkedMinutes: Long?)

    sealed interface Effect {
        data class SaveState(val state: DriveState) : Effect
        data class Departed(val departure: Departure) : Effect
        data object Parked : Effect

        /** P단이고 조작 제한이 풀린 순간. 대기 알림을 하나 띄울 때다. */
        data object ReadyForAlerts : Effect
    }

    var state: DriveState = initial
        private set
    var phase: Phase = Phase.UNKNOWN
        private set
    var departure: Departure? = null
        private set
    private var alertsSignaled = false

    fun update(now: Long, signals: VehicleSignals): List<Effect> {
        val effects = mutableListOf<Effect>()
        val parkedNow = when {
            signals.ignitionOn == false -> true
            signals.gear == GearState.PARK -> true
            signals.gear == GearState.DRIVE -> false
            else -> !signals.restricted
        }

        if (parkedNow && phase != Phase.PARKED) {
            // 앱이 막 켜진 경우: ODO가 같거나 아직 모르면 이전 정차 기록을 이어 쓴다.
            // 그사이 움직였는지는 출발 때 ODO를 비교해 가린다.
            val keepPrevious = phase == Phase.UNKNOWN &&
                state.lastStopAtMillis != null &&
                (signals.odoKm == null || sameSpot(state.lastStopOdoKm, signals.odoKm))
            if (!keepPrevious) {
                state = state.copy(
                    lastStopAtMillis = now,
                    lastStopOdoKm = signals.odoKm ?: state.lastStopOdoKm,
                    lastKnownOdoKm = signals.odoKm ?: state.lastKnownOdoKm,
                )
                effects += Effect.SaveState(state)
            }
            phase = Phase.PARKED
            departure = null
            alertsSignaled = false
            effects += Effect.Parked
        } else if (!parkedNow && phase != Phase.DRIVING) {
            val fromParked = phase == Phase.PARKED
            val d = Departure(
                atMillis = now,
                odoKm = if (fromParked) signals.odoKm else null,
                parkedMinutes = if (fromParked) parkedMinutes(now, signals.odoKm) else null,
            )
            departure = d
            phase = Phase.DRIVING
            alertsSignaled = false
            if (signals.odoKm != null) {
                state = state.copy(lastKnownOdoKm = signals.odoKm)
                effects += Effect.SaveState(state)
            }
            effects += Effect.Departed(d)
        }

        if (phase == Phase.PARKED && signals.gear == GearState.PARK && signals.odoKm != null &&
            state.lastStopOdoKm == null
        ) {
            state = state.copy(lastStopAtMillis = now, lastStopOdoKm = signals.odoKm, lastKnownOdoKm = signals.odoKm)
            effects += Effect.SaveState(state)
        }

        if (phase == Phase.PARKED && signals.gear == GearState.PARK &&
            !signals.restricted && signals.ignitionOn != false && !alertsSignaled
        ) {
            alertsSignaled = true
            effects += Effect.ReadyForAlerts
        }
        if (signals.ignitionOn == false) alertsSignaled = false
        // 주행 중에도 ODO 변화(역전 포함)를 저장소에 전달해 다음 정차까지 공백을 놓치지 않는다.
        if (signals.odoKm != null && signals.odoKm != state.lastKnownOdoKm) {
            state = state.copy(lastKnownOdoKm = signals.odoKm)
            effects += Effect.SaveState(state)
        }
        return effects
    }

    private fun sameSpot(a: Double?, b: Double?): Boolean = a != null && b != null && abs(a - b) <= SAME_SPOT_KM

    private fun parkedMinutes(now: Long, odoKm: Double?): Long? {
        val at = state.lastStopAtMillis ?: return null
        if (!sameSpot(state.lastStopOdoKm, odoKm)) return null
        return (now - at) / 60_000
    }

    companion object {
        const val SAME_SPOT_KM = 0.3
    }
}
