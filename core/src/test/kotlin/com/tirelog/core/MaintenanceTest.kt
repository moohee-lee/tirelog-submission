package com.tirelog.core

import com.tirelog.core.maintenance.DotCode
import com.tirelog.core.maintenance.Mileage
import com.tirelog.core.model.EventType
import com.tirelog.core.model.Mount
import com.tirelog.core.model.RotationPattern
import com.tirelog.core.model.TireEvent
import com.tirelog.core.model.TireSet
import com.tirelog.core.model.TireSetType
import com.tirelog.core.model.WheelPosition
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MaintenanceTest {

    private val today = LocalDate.of(2026, 12, 14)

    @Test
    fun `DOT 4자리는 제조 주와 연도다`() {
        val code = (DotCode.parse("2423", today) as DotCode.Companion.Result.Valid).code
        assertEquals(24, code.week)
        assertEquals(2023, code.year)
        assertEquals(LocalDate.of(2023, 6, 12), code.manufactureDate)
    }

    @Test
    fun `잘못된 DOT는 거부한다`() {
        assertIs<DotCode.Companion.Result.Invalid>(DotCode.parse("5423", today))
        assertIs<DotCode.Companion.Result.Invalid>(DotCode.parse("0127", today))
        assertIs<DotCode.Companion.Result.Invalid>(DotCode.parse("12a4", today))
        assertIs<DotCode.Companion.Result.Invalid>(DotCode.parse("5321", today), "2021년은 52주까지만 있다")
    }

    @Test
    fun `제조 6년이 지나면 연식 알림 대상이다`() {
        val winter = DotCode.parseOrNull("4220", today)!!
        assertTrue(winter.isOlderThan(6, today))
        val summer = DotCode.parseOrNull("1224", today)!!
        assertTrue(!summer.isOlderThan(6, today))
    }

    @Test
    fun `세트 주행거리는 장착 구간의 합이다`() {
        val summer = TireSet(id = 1, type = TireSetType.SUMMER, size = null, dot = null, createdAtMillis = 0)
        val winter = TireSet(id = 2, type = TireSetType.WINTER, size = null, dot = null, createdAtMillis = 0, initialKm = 8_200.0)
        val mounts = listOf(
            Mount(1, setId = 1, mountOdoKm = 20_000.0, mountAtMillis = 1, unmountOdoKm = 23_700.0, unmountAtMillis = 2),
            Mount(2, setId = 2, mountOdoKm = 23_700.0, mountAtMillis = 3),
        )
        assertEquals(3_700.0, Mileage.setKm(summer, mounts, 23_980.0))
        assertEquals(8_480.0, Mileage.setKm(winter, mounts, 23_980.0))
    }

    @Test
    fun `ODO를 모르는 구간이나 보관 중인 세트에 차량 누적 거리를 더하지 않는다`() {
        val set = TireSet(id = 1, type = TireSetType.SUMMER, size = null, dot = null, createdAtMillis = 0, initialKm = 500.0)
        assertEquals(500.0, Mileage.setKm(set, listOf(Mount(setId = 1, mountOdoKm = null, mountAtMillis = 1)), 80_000.0))
        assertEquals(
            500.0,
            Mileage.setKm(set, listOf(Mount(setId = 1, mountOdoKm = 20_000.0, mountAtMillis = 1, unmountAtMillis = 2)), 80_000.0),
        )
    }

    @Test
    fun `교체나 위치교환 뒤 50km를 넘고 점검 기록이 없으면 너트 점검이다`() {
        val work = TireEvent(id = 7, type = EventType.ROTATION, atMillis = 100, odoKm = 23_500.0, setId = 1)
        assertIs<Mileage.NutStatus.Pending>(Mileage.nutStatus(listOf(work), 23_520.0))
        assertIs<Mileage.NutStatus.Due>(Mileage.nutStatus(listOf(work), 23_550.0))
        val checked = TireEvent(id = 8, type = EventType.NUT_CHECK, atMillis = 200, odoKm = 23_560.0, setId = 1)
        assertNull(Mileage.nutStatus(listOf(work, checked), 23_600.0))
        val refill = TireEvent(id = 9, type = EventType.REFILL, atMillis = 50, odoKm = 23_000.0, setId = 1)
        assertNull(Mileage.nutStatus(listOf(refill), 23_600.0), "보충은 바퀴를 떼지 않는다")
    }

    @Test
    fun `위치교환 방식은 네 바퀴를 빠짐없이 옮긴다`() {
        RotationPattern.entries.forEach { pattern ->
            val destinations = WheelPosition.entries.map(pattern::destinationOf).toSet()
            assertEquals(WheelPosition.entries.toSet(), destinations, pattern.name)
        }
        assertEquals(WheelPosition.REAR_RIGHT, RotationPattern.FRONT_REAR.destinationOf(WheelPosition.FRONT_RIGHT))
    }

    @Test
    fun `공기압 단위는 네 바퀴 최댓값으로 판별해 한 바퀴가 빠져도 바뀌지 않는다`() {
        assertEquals(PressureUnit.KPA, PressureUnit.detect(listOf(250.0, 60.0, 251.0, 249.0)))
        assertEquals(PressureUnit.PSI, PressureUnit.detect(listOf(36.0, 35.5, 9.0, 36.0)))
        assertEquals(PressureUnit.BAR, PressureUnit.detect(listOf(2.5, 2.4, 2.5, 2.5)))
        assertEquals(250.0, PressureUnit.BAR.toKpa(2.5), 1e-9)
    }
}
