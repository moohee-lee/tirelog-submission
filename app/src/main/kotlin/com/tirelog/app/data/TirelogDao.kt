package com.tirelog.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert

@Dao
interface TirelogDao {
    @Query("SELECT * FROM settings WHERE id = 1")
    suspend fun settings(): SettingsEntity?

    @Upsert
    suspend fun saveSettings(entity: SettingsEntity)

    @Query("SELECT * FROM drive_state WHERE id = 1")
    suspend fun driveState(): DriveStateEntity?

    @Upsert
    suspend fun saveDriveState(entity: DriveStateEntity)

    @Query("SELECT * FROM tire_set ORDER BY id")
    suspend fun sets(): List<TireSetEntity>

    @Insert
    suspend fun insertSet(entity: TireSetEntity): Long

    @Update
    suspend fun updateSet(entity: TireSetEntity)

    @Query("SELECT * FROM tire ORDER BY id")
    suspend fun tires(): List<TireEntity>

    @Insert
    suspend fun insertTire(entity: TireEntity): Long

    @Update
    suspend fun updateTire(entity: TireEntity)

    @Query("SELECT * FROM mount ORDER BY id")
    suspend fun mounts(): List<MountEntity>

    @Insert
    suspend fun insertMount(entity: MountEntity): Long

    @Update
    suspend fun updateMount(entity: MountEntity)

    @Query("SELECT * FROM event ORDER BY atMillis, id")
    suspend fun events(): List<EventEntity>

    @Insert
    suspend fun insertEvent(entity: EventEntity): Long

    @Insert
    suspend fun insertMeasurements(entities: List<MeasurementEntity>)

    @Query("SELECT * FROM measurement WHERE atMillis >= :fromMillis ORDER BY atMillis, id")
    suspend fun measurementsSince(fromMillis: Long): List<MeasurementEntity>

    @Query(
        """
        SELECT m.* FROM measurement m
        INNER JOIN (
            SELECT tireId, MAX(atMillis) AS maxAt FROM measurement
            WHERE (:coldOnly = 0 OR cold = 1) GROUP BY tireId
        ) latest ON m.tireId = latest.tireId AND m.atMillis = latest.maxAt
        WHERE (:coldOnly = 0 OR m.cold = 1)
        """,
    )
    suspend fun latestPerTire(coldOnly: Boolean): List<MeasurementEntity>

    @Query("SELECT * FROM baseline")
    suspend fun baselines(): List<BaselineEntity>

    @Upsert
    suspend fun saveBaseline(entity: BaselineEntity)

    @Query("SELECT * FROM trend_state")
    suspend fun trendStates(): List<TrendStateEntity>

    @Upsert
    suspend fun saveTrendState(entity: TrendStateEntity)

    @Query("SELECT * FROM day_result ORDER BY epochDay DESC LIMIT 1")
    suspend fun latestDayResult(): DayResultEntity?

    @Query("SELECT * FROM tire_day_status WHERE epochDay = :epochDay")
    suspend fun dayStatuses(epochDay: Long): List<TireDayStatusEntity>

    @Upsert
    suspend fun saveDayResult(entity: DayResultEntity)

    @Upsert
    suspend fun saveDayStatuses(entities: List<TireDayStatusEntity>)

    @Query("SELECT * FROM alert_state")
    suspend fun alertStates(): List<AlertStateEntity>

    @Upsert
    suspend fun saveAlertState(entity: AlertStateEntity)

    @Query("DELETE FROM settings") suspend fun clearSettings()
    @Query("DELETE FROM tire_set WHERE id = :setId") suspend fun deleteSet(setId: Long)
    @Query("DELETE FROM tire WHERE setId = :setId") suspend fun deleteTiresOfSet(setId: Long)
    @Query("DELETE FROM drive_state") suspend fun clearDriveState()
    @Query("DELETE FROM tire_set") suspend fun clearSets()
    @Query("DELETE FROM tire") suspend fun clearTires()
    @Query("DELETE FROM mount") suspend fun clearMounts()
    @Query("DELETE FROM event") suspend fun clearEvents()
    @Query("DELETE FROM measurement") suspend fun clearMeasurements()
    @Query("DELETE FROM baseline") suspend fun clearBaselines()
    @Query("DELETE FROM trend_state") suspend fun clearTrendStates()
    @Query("DELETE FROM day_result") suspend fun clearDayResults()
    @Query("DELETE FROM tire_day_status") suspend fun clearDayStatuses()
    @Query("DELETE FROM alert_state") suspend fun clearAlertStates()
}
