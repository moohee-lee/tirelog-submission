package com.tirelog.app.data

import androidx.room.withTransaction
import com.tirelog.core.engine.TireLogStore
import com.tirelog.core.model.AlertState
import com.tirelog.core.model.AppSettings
import com.tirelog.core.model.Baseline
import com.tirelog.core.model.BaselineOrigin
import com.tirelog.core.model.Cause
import com.tirelog.core.model.DayResult
import com.tirelog.core.model.DriveState
import com.tirelog.core.model.EventType
import com.tirelog.core.model.Measurement
import com.tirelog.core.model.Mount
import com.tirelog.core.model.RecommendedPressure
import com.tirelog.core.model.RecommendedSource
import com.tirelog.core.model.RefillQuestion
import com.tirelog.core.model.SavedShop
import com.tirelog.core.model.Tire
import com.tirelog.core.model.TireDayStatus
import com.tirelog.core.model.TireEvent
import com.tirelog.core.model.TireSet
import com.tirelog.core.model.TireSetType
import com.tirelog.core.model.TireProfile
import com.tirelog.core.model.TrendState
import com.tirelog.core.model.WheelPosition

class RoomTireLogStore(private val db: TirelogDatabase) : TireLogStore {
    private val dao = db.dao()

    override suspend fun <T> transaction(block: suspend () -> T): T = db.withTransaction { block() }

    override suspend fun settings(): AppSettings = dao.settings()?.toModel() ?: AppSettings()

    override suspend fun saveSettings(settings: AppSettings) = dao.saveSettings(settings.toEntity())

    override suspend fun driveState(): DriveState =
        dao.driveState()?.let { DriveState(it.lastStopAt, it.lastStopOdo, it.lastKnownOdo) } ?: DriveState()

    override suspend fun saveDriveState(state: DriveState) =
        dao.saveDriveState(DriveStateEntity(1, state.lastStopAtMillis, state.lastStopOdoKm, state.lastKnownOdoKm))

    override suspend fun sets(): List<TireSet> = dao.sets().map {
        TireSet(
            it.id, TireSetType.valueOf(it.type), it.size, it.dot, it.createdAt, it.retired, it.initialKm,
            it.kmAtLastRotation, it.recFrontKpa, it.recRearKpa,
            it.firstUsedEpochDay, it.newUntilMounted, it.inspectionKm, it.inspectionMonths,
            it.lifeGuideId, it.tireModel, it.manufacturer, it.initialKmKnown,
        )
    }

    override suspend fun saveSet(set: TireSet): Long {
        val entity = TireSetEntity(
            set.id, set.type.name, set.size, set.dot, set.createdAtMillis, set.retired, set.initialKm,
            set.kmAtLastRotation, set.recommendedFrontKpa, set.recommendedRearKpa,
            set.firstUsedEpochDay, set.newUntilMounted, set.inspectionKm, set.inspectionMonths,
            set.lifeGuideId, set.tireModel, set.manufacturer, set.initialKmKnown,
        )
        return if (set.id == 0L) dao.insertSet(entity) else set.id.also { dao.updateSet(entity) }
    }

    override suspend fun tires(): List<Tire> = dao.tires().map {
        Tire(it.id, it.setId, it.label, WheelPosition.valueOf(it.position), it.dot, it.active,
            it.profile?.let { p -> TireProfile(p.manufacturer, p.tireModel, TireSetType.valueOf(p.type), p.initialKm,
                p.initialKmKnown, p.firstUsedEpochDay, p.inspectionKm, p.inspectionMonths, p.lifeGuideId, p.size) }, it.retired, it.removedAtMillis, it.removedOdoKm, it.colorIndex)
    }

    override suspend fun saveTire(tire: Tire): Long {
        val entity = TireEntity(tire.id, tire.setId, tire.label, tire.position.name, tire.dot, tire.active,
            tire.profile?.let { p -> TireProfileEntity(p.manufacturer, p.tireModel, p.type.name, p.initialKm,
                p.initialKmKnown, p.firstUsedEpochDay, p.inspectionKm, p.inspectionMonths, p.lifeGuideId, p.size) }, tire.retired, tire.removedAtMillis, tire.removedOdoKm, tire.colorIndex)
        return if (tire.id == 0L) dao.insertTire(entity) else tire.id.also { dao.updateTire(entity) }
    }

    override suspend fun mounts(): List<Mount> = dao.mounts().map {
        Mount(it.id, it.setId, it.mountOdo, it.mountAt, it.unmountOdo, it.unmountAt, it.odoGapAtMillis)
    }

    override suspend fun saveMount(mount: Mount): Long {
        val entity = MountEntity(mount.id, mount.setId, mount.mountOdoKm, mount.mountAtMillis, mount.unmountOdoKm, mount.unmountAtMillis, mount.odoGapAtMillis)
        return if (mount.id == 0L) dao.insertMount(entity) else mount.id.also { dao.updateMount(entity) }
    }

    override suspend fun events(): List<TireEvent> = dao.events().map {
        TireEvent(it.id, EventType.valueOf(it.type), it.atMillis, it.odo, it.setId, it.tireIds.toIds(), it.detail,
            rotation = RotationRecordJson.decode(it.rotationJson), previousTireIds = it.previousTireIds.toIds())
    }

    override suspend fun addEvent(event: TireEvent): Long = dao.insertEvent(
        EventEntity(0, event.type.name, event.atMillis, event.odoKm, event.setId, event.tireIds.joinToString(","), event.detail,
            rotationJson = RotationRecordJson.encode(event.rotation), previousTireIds = event.previousTireIds.joinToString(",")),
    )

    override suspend fun addMeasurements(measurements: List<Measurement>) = dao.insertMeasurements(
        measurements.map {
            MeasurementEntity(0, it.tireId, it.position.name, it.kpa, it.tempC, it.odoKm, it.atMillis, it.epochDay, it.cold)
        },
    )

    override suspend fun measurementsSince(fromMillis: Long): List<Measurement> =
        dao.measurementsSince(fromMillis).map { it.toModel() }

    override suspend fun latestMeasurementPerTire(coldOnly: Boolean): List<Measurement> =
        dao.latestPerTire(coldOnly).map { it.toModel() }.distinctBy { it.tireId }

    override suspend fun baselines(): List<Baseline> = dao.baselines().map {
        Baseline(it.tireId, it.kpa, it.tempC, it.epochDay, BaselineOrigin.valueOf(it.origin))
    }

    override suspend fun saveBaseline(baseline: Baseline) = dao.saveBaseline(
        BaselineEntity(baseline.tireId, baseline.kpa, baseline.tempC, baseline.epochDay, baseline.origin.name),
    )

    override suspend fun trendStates(): List<TrendState> = dao.trendStates().map {
        TrendState(it.tireId, it.leakStreak, it.declineStreak, it.lastEvalDay)
    }

    override suspend fun saveTrendState(state: TrendState) =
        dao.saveTrendState(TrendStateEntity(state.tireId, state.leakStreak, state.declineStreak, state.lastEvalDay))

    override suspend fun latestDayResult(): DayResult? {
        val day = dao.latestDayResult() ?: return null
        val statuses = dao.dayStatuses(day.epochDay).map {
            TireDayStatus(
                it.tireId, WheelPosition.valueOf(it.position), it.kpa, it.recommendedKpa, it.needsRefill,
                it.refillSuspected, it.correctedLossKpa, it.baselineEpochDay,
                it.baselineOrigin?.let(BaselineOrigin::valueOf), it.leakStreak, it.declineStreak,
                it.leakSuspected, it.recheckNeeded,
            )
        }.sortedBy { it.position.ordinal }
        return DayResult(day.epochDay, day.computedAt, day.tempC, day.baselineTempC, Cause.valueOf(day.cause), statuses)
    }

    override suspend fun saveDayResult(result: DayResult) {
        dao.saveDayResult(DayResultEntity(result.epochDay, result.computedAtMillis, result.tempC, result.baselineTempC, result.cause.name))
        dao.saveDayStatuses(
            result.tires.map {
                TireDayStatusEntity(
                    result.epochDay, it.tireId, it.position.name, it.kpa, it.recommendedKpa, it.needsRefill,
                    it.refillSuspected, it.correctedLossKpa, it.baselineEpochDay, it.baselineOrigin?.name,
                    it.leakStreak, it.declineStreak, it.leakSuspected, it.recheckNeeded,
                )
            },
        )
    }

    override suspend fun alertStates(): List<AlertState> = dao.alertStates().map {
        AlertState(it.key, it.lastShownDay, it.snoozedUntilDay, it.acknowledged)
    }

    override suspend fun saveAlertState(state: AlertState) =
        dao.saveAlertState(AlertStateEntity(state.key, state.lastShownDay, state.snoozedUntilDay, state.acknowledged))

    override suspend fun clearAll() {
        dao.clearSettings(); dao.clearDriveState(); dao.clearSets(); dao.clearTires(); dao.clearMounts()
        dao.clearEvents(); dao.clearMeasurements(); dao.clearBaselines(); dao.clearTrendStates()
        dao.clearDayResults(); dao.clearDayStatuses(); dao.clearAlertStates()
    }

    override suspend fun deleteUnusedSet(setId: Long) {
        dao.deleteTiresOfSet(setId)
        dao.deleteSet(setId)
    }

    private fun MeasurementEntity.toModel() =
        Measurement(id, tireId, WheelPosition.valueOf(position), kpa, tempC, odo, atMillis, epochDay, cold)

    private fun String.toIds(): List<Long> = split(',').mapNotNull { it.trim().toLongOrNull() }

    private fun SettingsEntity.toModel() = AppSettings(
        onboardingDone = onboardingDone,
        recommended = if (recFrontKpa != null && recRearKpa != null && recSource != null) {
            RecommendedPressure(recFrontKpa, recRearKpa, RecommendedSource.valueOf(recSource))
        } else {
            null
        },
        rotationIntervalKm = rotationIntervalKm,
        regionCode = regionCode,
        vin = vin,
        favoriteShop = if (shopName != null && shopLat != null && shopLon != null) {
            SavedShop(shopName, shopLat, shopLon, shopPoiId, shopPoiSubId, shopAddress)
        } else {
            null
        },
        pressureSupported = pressureSupported,
        odometerSupported = odometerSupported,
        refillQuestion = if (refillDay != null && refillTireIds != null) RefillQuestion(refillDay, refillTireIds.toIds()) else null,
        pressurePositions = pressurePositions?.split(',')?.mapNotNull { name -> WheelPosition.entries.firstOrNull { it.name == name } }?.toSet(),
        vehicleGuideId = vehicleGuideId,
        vehicleTireSize = vehicleTireSize,
    )

    private fun AppSettings.toEntity() = SettingsEntity(
        id = 1,
        onboardingDone = onboardingDone,
        recFrontKpa = recommended?.frontKpa,
        recRearKpa = recommended?.rearKpa,
        recSource = recommended?.source?.name,
        rotationIntervalKm = rotationIntervalKm,
        regionCode = regionCode,
        vin = vin,
        shopName = favoriteShop?.name,
        shopLat = favoriteShop?.latitude,
        shopLon = favoriteShop?.longitude,
        shopPoiId = favoriteShop?.poiId,
        shopPoiSubId = favoriteShop?.poiSubId,
        shopAddress = favoriteShop?.address,
        pressureSupported = pressureSupported,
        odometerSupported = odometerSupported,
        refillDay = refillQuestion?.epochDay,
        refillTireIds = refillQuestion?.tireIds?.joinToString(","),
        pressurePositions = pressurePositions?.sortedBy { it.ordinal }?.joinToString(",") { it.name },
        vehicleGuideId = vehicleGuideId,
        vehicleTireSize = vehicleTireSize,
    )
}
