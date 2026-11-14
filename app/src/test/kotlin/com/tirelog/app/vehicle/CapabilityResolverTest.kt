package com.tirelog.app.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityResolverTest {
    @Test fun `휠 영역과 압력 제공 영역의 교집합만 사용하며 조회 실패와 미지원을 구분한다`() {
        val front = setOf(com.tirelog.core.model.WheelPosition.FRONT_LEFT, com.tirelog.core.model.WheelPosition.FRONT_RIGHT)
        val left = setOf(com.tirelog.core.model.WheelPosition.FRONT_LEFT, com.tirelog.core.model.WheelPosition.REAR_LEFT)
        assertEquals(setOf(com.tirelog.core.model.WheelPosition.FRONT_LEFT), CapabilityResolver.pressurePositions(front, left))
        assertEquals(front, CapabilityResolver.pressurePositions(front, null))
        assertNull(CapabilityResolver.pressurePositions(null, null))
        assertEquals(emptySet<com.tirelog.core.model.WheelPosition>(), CapabilityResolver.pressurePositions(front, emptySet()))
        val known = VehicleCapabilities(Support.SUPPORTED, Support.SUPPORTED, pressurePositions = front)
        assertEquals(front, known.updatedBy(VehicleCapabilities(Support.UNKNOWN, Support.UNKNOWN)).pressurePositions)
        assertEquals(emptySet<com.tirelog.core.model.WheelPosition>(), known.updatedBy(VehicleCapabilities(Support.UNSUPPORTED, Support.SUPPORTED)).pressurePositions)
    }

    private fun probe(check: CheckOutcome, readable: Boolean = false) = Probe(check, readable)

    @Test
    fun `차가 미지원 코드를 주면 미지원이다`() {
        assertEquals(CheckOutcome.NO, CapabilityResolver.classify(Exception("ERROR_NOT_SUPPORTED_PROPERTY")))
        assertEquals(CheckOutcome.NO, CapabilityResolver.classify(Exception("Error code:ERROR_NOT_SUPPORTED_AREA")))
        val caps = CapabilityResolver.resolve(probe(CheckOutcome.NO), probe(CheckOutcome.NO))
        assertTrue(caps.unsupported)
        assertEquals("이 차는 공기압과 주행거리를 제공하지 않습니다", caps.message)
    }

    @Test
    fun `시간 초과·연결 실패·권한 오류는 미지원이 아니라 확인 안 됨이다`() {
        assertEquals(CheckOutcome.FAILED, CapabilityResolver.outcomeOf(null))
        assertEquals(CheckOutcome.DISCONNECTED, CapabilityResolver.classify(Exception("ERROR_SERVICE_CONNECTION_FAILURE")))
        assertEquals(CheckOutcome.PERMISSION_DENIED, CapabilityResolver.classify(Exception("ERROR_SECURITY_PERMISSION")))
        assertEquals(CheckOutcome.PERMISSION_DENIED, CapabilityResolver.classify(SecurityException("PRIVATE_CAR_SAFETY required")))
        assertEquals(CheckOutcome.FAILED, CapabilityResolver.classify(Exception("ERROR_INTERNAL_SERVICE")))

        val timedOut = CapabilityResolver.resolve(probe(CheckOutcome.FAILED), probe(CheckOutcome.FAILED))
        assertEquals(Support.UNKNOWN, timedOut.pressure)
        assertFalse(timedOut.unsupported)
        assertTrue(timedOut.pending)
        assertEquals("차량 데이터를 아직 받지 못했습니다", timedOut.message)

        val denied = CapabilityResolver.resolve(probe(CheckOutcome.PERMISSION_DENIED), probe(CheckOutcome.YES))
        assertEquals("권한 오류로 공기압 값을 받지 못했습니다", denied.message)
        assertEquals(
            "차량 서비스에 연결하지 못해 차량 데이터를 받지 못했습니다",
            CapabilityResolver.resolve(probe(CheckOutcome.DISCONNECTED), probe(CheckOutcome.DISCONNECTED)).message,
        )
    }

    @Test
    fun `확인 API가 실패해도 값을 읽을 수 있으면 지원이다`() {
        val caps = CapabilityResolver.resolve(probe(CheckOutcome.FAILED, readable = true), probe(CheckOutcome.NO, readable = true))
        assertEquals(Support.SUPPORTED, caps.pressure)
        assertEquals(Support.SUPPORTED, caps.odometer)
        assertNull(caps.message)
    }

    @Test
    fun `확인된 항목은 다음 확인이 실패해도 유지하고, 확인되면 바꾼다`() {
        val known = VehicleCapabilities(Support.SUPPORTED, Support.UNKNOWN)
        val merged = known.updatedBy(VehicleCapabilities(Support.UNKNOWN, Support.UNSUPPORTED))
        assertEquals(Support.SUPPORTED, merged.pressure)
        assertEquals(Support.UNSUPPORTED, merged.odometer)
        assertFalse(merged.pending)
    }
}
