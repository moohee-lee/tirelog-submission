package com.tirelog.core

import com.tirelog.core.engine.*
import com.tirelog.core.model.WheelPosition
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.*

class TrendReadingsTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private fun time(date: String, hour: Int = 7) = LocalDate.parse(date).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
    private fun data(series: List<TrendSeries>, temperatures: List<TrendPoint> = emptyList()) =
        TrendData(0, Long.MAX_VALUE, series, temperatures, emptyList(), 0)

    @Test fun `날짜별로 네 바퀴와 온도를 모으고 최신 날짜부터 표시한다`() {
        val old = time("2026-10-11")
        val recent = time("2026-10-12")
        val rows = data(WheelPosition.entries.mapIndexed { index, position ->
            TrendSeries(index.toLong(), position.label, listOf(
                TrendPoint(old, 31.0 + index, position = position),
                TrendPoint(recent, 35.0 + index, position = position),
            ))
        }, listOf(TrendPoint(old, 26.0), TrendPoint(recent, 28.0))).dailyReadings(zone)
        assertEquals(listOf(LocalDate.parse("2026-10-12"), LocalDate.parse("2026-10-11")), rows.map { it.date })
        assertEquals(28.0, rows[0].temperatureC)
        assertEquals(26.0, rows[1].temperatureC)
        assertEquals(36.0, rows[0].pressuresPsi[WheelPosition.FRONT_RIGHT])
        assertEquals(33.0, rows[1].pressuresPsi[WheelPosition.REAR_LEFT])
        assertEquals(4, rows[0].pressuresPsi.size)
    }

    @Test fun `위치교환 전 기록은 측정 당시 위치에 두고 같은 날 마지막 값만 쓴다`() {
        val old = time("2026-10-11")
        val recent = time("2026-10-12")
        val rows = data(listOf(TrendSeries(1, "뒤오른쪽", listOf(
            TrendPoint(old, 32.0, position = WheelPosition.FRONT_LEFT),
            TrendPoint(recent, 33.0, position = WheelPosition.REAR_RIGHT),
            TrendPoint(time("2026-10-12", 9), 35.0, position = WheelPosition.REAR_RIGHT),
        )))).dailyReadings(zone)
        assertEquals(mapOf(WheelPosition.REAR_RIGHT to 35.0), rows[0].pressuresPsi)
        assertEquals(mapOf(WheelPosition.FRONT_LEFT to 32.0), rows[1].pressuresPsi)
        assertNull(rows[0].temperatureC)
        assertFalse(rows[0].pressuresPsi.containsKey(WheelPosition.FRONT_RIGHT))
    }

    @Test fun `지역 날짜로 묶고 누락과 실제 영 값을 구분한다`() {
        val midnight = LocalDate.parse("2026-10-12").atStartOfDay(zone).toInstant().toEpochMilli()
        val rows = data(listOf(TrendSeries(2, "앞오른쪽", listOf(
            TrendPoint(midnight - 1, 34.0, position = WheelPosition.FRONT_RIGHT),
            TrendPoint(midnight, 0.0, position = WheelPosition.FRONT_RIGHT),
        ))), listOf(TrendPoint(midnight, 0.0), TrendPoint(time("2026-10-13"), 12.0))).dailyReadings(zone)
        assertEquals(listOf("2026-10-13", "2026-10-12", "2026-10-11"), rows.map { it.date.toString() })
        assertEquals(emptyMap(), rows[0].pressuresPsi)
        assertEquals(0.0, rows[1].temperatureC)
        assertEquals(0.0, rows[1].pressuresPsi[WheelPosition.FRONT_RIGHT])
        assertNull(rows[2].temperatureC)
    }
}
