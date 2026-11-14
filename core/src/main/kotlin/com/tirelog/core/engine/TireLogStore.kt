package com.tirelog.core.engine

import com.tirelog.core.model.AlertState
import com.tirelog.core.model.AppSettings
import com.tirelog.core.model.Baseline
import com.tirelog.core.model.DayResult
import com.tirelog.core.model.DriveState
import com.tirelog.core.model.Measurement
import com.tirelog.core.model.Mount
import com.tirelog.core.model.Tire
import com.tirelog.core.model.TireEvent
import com.tirelog.core.model.TireSet
import com.tirelog.core.model.TrendState

/** 모든 기록의 저장소. 앱은 Room으로, 테스트는 메모리로 구현한다. */
interface TireLogStore {
    suspend fun <T> transaction(block: suspend () -> T): T

    suspend fun settings(): AppSettings
    suspend fun saveSettings(settings: AppSettings)

    suspend fun driveState(): DriveState
    suspend fun saveDriveState(state: DriveState)

    suspend fun sets(): List<TireSet>
    suspend fun saveSet(set: TireSet): Long
    suspend fun deleteUnusedSet(setId: Long)

    suspend fun tires(): List<Tire>
    suspend fun saveTire(tire: Tire): Long

    suspend fun mounts(): List<Mount>
    suspend fun saveMount(mount: Mount): Long

    suspend fun events(): List<TireEvent>
    suspend fun addEvent(event: TireEvent): Long

    suspend fun addMeasurements(measurements: List<Measurement>)
    suspend fun measurementsSince(fromMillis: Long): List<Measurement>

    /** 타이어별 가장 최근 측정. cold=true면 냉간 측정만 본다. */
    suspend fun latestMeasurementPerTire(coldOnly: Boolean): List<Measurement>

    suspend fun baselines(): List<Baseline>
    suspend fun saveBaseline(baseline: Baseline)

    suspend fun trendStates(): List<TrendState>
    suspend fun saveTrendState(state: TrendState)

    suspend fun latestDayResult(): DayResult?
    suspend fun saveDayResult(result: DayResult)

    suspend fun alertStates(): List<AlertState>
    suspend fun saveAlertState(state: AlertState)

    suspend fun clearAll()
}

class InMemoryTireLogStore : TireLogStore {
    private var settings = AppSettings()
    private var driveState = DriveState()
    private val sets = linkedMapOf<Long, TireSet>()
    private val tires = linkedMapOf<Long, Tire>()
    private val mounts = linkedMapOf<Long, Mount>()
    private val events = linkedMapOf<Long, TireEvent>()
    private val measurements = mutableListOf<Measurement>()
    private val baselines = linkedMapOf<Long, Baseline>()
    private val trends = linkedMapOf<Long, TrendState>()
    private val dayResults = sortedMapOf<Long, DayResult>()
    private val alertStates = linkedMapOf<String, AlertState>()
    private var nextId = 1L

    private fun newId() = nextId++

    override suspend fun <T> transaction(block: suspend () -> T): T = block()
    override suspend fun settings() = settings
    override suspend fun saveSettings(settings: AppSettings) { this.settings = settings }
    override suspend fun driveState() = driveState
    override suspend fun saveDriveState(state: DriveState) { driveState = state }
    override suspend fun sets() = sets.values.toList()
    override suspend fun deleteUnusedSet(setId: Long) {
        sets.remove(setId)
        tires.entries.removeAll { it.value.setId == setId }
    }
    override suspend fun saveSet(set: TireSet): Long {
        val id = if (set.id == 0L) newId() else set.id
        sets[id] = set.copy(id = id)
        return id
    }
    override suspend fun tires() = tires.values.toList()
    override suspend fun saveTire(tire: Tire): Long {
        val id = if (tire.id == 0L) newId() else tire.id
        tires[id] = tire.copy(id = id)
        return id
    }
    override suspend fun mounts() = mounts.values.toList()
    override suspend fun saveMount(mount: Mount): Long {
        val id = if (mount.id == 0L) newId() else mount.id
        mounts[id] = mount.copy(id = id)
        return id
    }
    override suspend fun events() = events.values.sortedBy { it.atMillis }
    override suspend fun addEvent(event: TireEvent): Long {
        val id = newId()
        events[id] = event.copy(id = id)
        return id
    }
    override suspend fun addMeasurements(measurements: List<Measurement>) {
        measurements.forEach { this.measurements += it.copy(id = newId()) }
    }
    override suspend fun measurementsSince(fromMillis: Long) =
        measurements.filter { it.atMillis >= fromMillis }.sortedBy { it.atMillis }
    override suspend fun latestMeasurementPerTire(coldOnly: Boolean) = measurements
        .filter { !coldOnly || it.cold }
        .groupBy { it.tireId }
        .map { (_, list) -> list.maxBy { it.atMillis } }
    override suspend fun baselines() = baselines.values.toList()
    override suspend fun saveBaseline(baseline: Baseline) { baselines[baseline.tireId] = baseline }
    override suspend fun trendStates() = trends.values.toList()
    override suspend fun saveTrendState(state: TrendState) { trends[state.tireId] = state }
    override suspend fun latestDayResult() = dayResults.values.maxByOrNull { it.epochDay }
    override suspend fun saveDayResult(result: DayResult) { dayResults[result.epochDay] = result }
    override suspend fun alertStates() = alertStates.values.toList()
    override suspend fun saveAlertState(state: AlertState) { alertStates[state.key] = state }
    override suspend fun clearAll() {
        settings = AppSettings(); driveState = DriveState()
        sets.clear(); tires.clear(); mounts.clear(); events.clear(); measurements.clear()
        baselines.clear(); trends.clear(); dayResults.clear(); alertStates.clear()
    }
}
