package com.tirelog.core

import com.tirelog.core.model.TireSetType
import com.tirelog.core.season.Region
import com.tirelog.core.season.SeasonAdvisor
import com.tirelog.core.season.SeasonCalendar
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SeasonAdvisorTest {
    @Test fun `봄 예고 기간에도 실제 사흘 연속 온난 조건이 우선한다`() {
        val today = LocalDate.of(2027, 3, 1)
        val mornings = (0..2).map { today.minusDays(it.toLong()) to 10.0 }
        assertTrue(SeasonAdvisor.evaluate(today, Region.SEOUL, TireSetType.WINTER, mornings)!!.key.endsWith(":WARM"))
        val gaps = listOf(today to 10.0, today.minusDays(2) to 10.0, today.minusDays(4) to 10.0)
        assertTrue(SeasonAdvisor.evaluate(today, Region.SEOUL, TireSetType.WINTER, gaps)!!.key.endsWith(":PREVIEW"))
    }

    @Test
    fun `서울은 평년 11월 중순에 7도 아래로 내려가고 3월 중순에 올라간다`() {
        assertEquals(LocalDate.of(2026, 11, 17), SeasonCalendar.dateOf(2026, SeasonCalendar.autumnCrossDay(Region.SEOUL)!!))
        assertEquals(LocalDate.of(2027, 3, 19), SeasonCalendar.dateOf(2027, SeasonCalendar.springCrossDay(Region.SEOUL)!!))
    }

    @Test
    fun `제주는 해를 넘겨 내려가고 서귀포는 7도 아래로 내려가지 않는다`() {
        assertEquals(LocalDate.of(2027, 1, 2), SeasonCalendar.dateOf(2026, SeasonCalendar.autumnCrossDay(Region.JEJU)!!))
        assertNull(SeasonCalendar.autumnCrossDay(Region.SEOGWIPO))
    }

    @Test
    fun `통과 30일 전부터 겨울 세트 교체 예약을 알린다`() {
        val before = SeasonAdvisor.evaluate(LocalDate.of(2026, 10, 17), Region.SEOUL, TireSetType.SUMMER, emptyList())
        assertNull(before)
        val notice = SeasonAdvisor.evaluate(LocalDate.of(2026, 10, 18), Region.SEOUL, TireSetType.SUMMER, emptyList())
        assertNotNull(notice)
        assertTrue(notice.message.startsWith("평년 기준 11/17쯤"))
        assertEquals(TireSetType.WINTER, notice.targetType)
    }

    @Test
    fun `아침 기온이 사흘 7도 아래면 다시 알리고 지역이 없어도 동작한다`() {
        val mornings = listOf(
            LocalDate.of(2026, 12, 5) to 3.0,
            LocalDate.of(2026, 12, 6) to -1.0,
            LocalDate.of(2026, 12, 7) to -10.0,
        )
        val notice = SeasonAdvisor.evaluate(LocalDate.of(2026, 12, 7), null, TireSetType.SUMMER, mornings)
        assertNotNull(notice)
        assertTrue(notice.key.endsWith(":COLD"))
        val twoDays = SeasonAdvisor.evaluate(LocalDate.of(2026, 12, 7), null, TireSetType.SUMMER, mornings.drop(1))
        assertNull(twoDays)
    }

    @Test
    fun `겨울 세트나 사계절 세트를 끼운 동안에는 겨울 알림이 없다`() {
        val mornings = (5..7).map { LocalDate.of(2026, 12, it) to -5.0 }
        assertNull(SeasonAdvisor.evaluate(LocalDate.of(2026, 12, 7), Region.SEOUL, TireSetType.WINTER, mornings))
        assertNull(SeasonAdvisor.evaluate(LocalDate.of(2026, 12, 7), Region.SEOUL, TireSetType.ALL_SEASON, mornings))
        assertNull(SeasonAdvisor.evaluate(LocalDate.of(2026, 12, 7), Region.SEOUL, TireSetType.ALL_WEATHER, mornings))
    }

    @Test
    fun `봄에는 겨울 세트를 낀 운전자에게 여름 세트 교체를 알린다`() {
        val notice = SeasonAdvisor.evaluate(LocalDate.of(2027, 2, 20), Region.SEOUL, TireSetType.WINTER, emptyList())
        assertNotNull(notice)
        assertEquals(TireSetType.SUMMER, notice.targetType)
    }
}
