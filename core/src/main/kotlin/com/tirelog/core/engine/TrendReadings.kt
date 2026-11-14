package com.tirelog.core.engine

import com.tirelog.core.TimeLabels
import com.tirelog.core.model.WheelPosition
import java.time.LocalDate
import java.time.ZoneId

data class DailyTrendReading(
    val date: LocalDate,
    val temperatureC: Double?,
    val pressuresPsi: Map<WheelPosition, Double>,
)

/** 표는 조회 기간의 전체 기록을 날짜별로 모은다. 위치는 각 측정에 저장된 당시 위치다. */
fun TrendData.dailyReadings(zone: ZoneId): List<DailyTrendReading> {
    val pressuresByDay = series.flatMap { it.points }.groupBy { TimeLabels.epochDay(it.atMillis, zone) }
    val temperaturesByDay = temperatures.groupBy { TimeLabels.epochDay(it.atMillis, zone) }
    return (pressuresByDay.keys + temperaturesByDay.keys).sortedDescending().map { day ->
        DailyTrendReading(
            date = LocalDate.ofEpochDay(day),
            temperatureC = temperaturesByDay[day]?.maxByOrNull { it.atMillis }?.value,
            pressuresPsi = pressuresByDay[day].orEmpty().filter { it.position != null }
                .groupBy { requireNotNull(it.position) }
                .mapValues { (_, points) -> points.maxBy { it.atMillis }.value },
        )
    }
}
