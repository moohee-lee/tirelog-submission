package com.tirelog.app.vehicle

/** 지원 여부 확인 API 한 번의 결과. */
enum class CheckOutcome { YES, NO, PERMISSION_DENIED, DISCONNECTED, FAILED }

/** 확인 API 결과와, 값을 직접 읽어 본 결과. */
data class Probe(val check: CheckOutcome, val readable: Boolean)

/**
 * "차가 미지원"과 "확인하지 못함"을 가른다.
 * Vehicle SDK 예외 메시지에는 ErrorCode 이름이 들어 있다(예: ERROR_NOT_SUPPORTED_PROPERTY).
 */
object CapabilityResolver {
    /** 실제 휠과 압력 지원 영역을 모두 제공하면 교집합만 조회한다. 실패를 빈 목록으로 취급하지 않는다. */
    fun pressurePositions(wheels: Set<com.tirelog.core.model.WheelPosition>?, pressure: Set<com.tirelog.core.model.WheelPosition>?) = when {
        wheels != null && pressure != null -> wheels intersect pressure
        else -> pressure ?: wheels
    }

    /** null은 시간 초과다. SDK는 서비스에 붙기 전 요청을 쌓아 두므로 연결이 늦으면 응답이 없다. */
    fun outcomeOf(result: Boolean?): CheckOutcome = when (result) {
        true -> CheckOutcome.YES
        false -> CheckOutcome.NO
        null -> CheckOutcome.FAILED
    }

    fun classify(error: Throwable): CheckOutcome {
        val message = error.message.orEmpty()
        return when {
            "ERROR_NOT_SUPPORTED" in message -> CheckOutcome.NO
            error is SecurityException || "ERROR_SECURITY_PERMISSION" in message -> CheckOutcome.PERMISSION_DENIED
            "ERROR_SERVICE_CONNECTION_FAILURE" in message -> CheckOutcome.DISCONNECTED
            else -> CheckOutcome.FAILED
        }
    }

    /** 확인 API가 아니라고 해도 값을 직접 읽을 수 있으면 지원으로 본다. */
    fun support(probe: Probe): Support = when {
        probe.check == CheckOutcome.YES || probe.readable -> Support.SUPPORTED
        probe.check == CheckOutcome.NO -> Support.UNSUPPORTED
        else -> Support.UNKNOWN
    }

    fun resolve(pressure: Probe, odometer: Probe): VehicleCapabilities {
        val outcomes = setOf(pressure.check, odometer.check)
        return VehicleCapabilities(
            pressure = support(pressure),
            odometer = support(odometer),
            cause = when {
                CheckOutcome.PERMISSION_DENIED in outcomes -> PendingCause.PERMISSION
                CheckOutcome.DISCONNECTED in outcomes -> PendingCause.NO_SERVICE
                else -> PendingCause.NOT_YET
            },
        )
    }
}
