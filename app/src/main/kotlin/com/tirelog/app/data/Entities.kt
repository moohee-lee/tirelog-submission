package com.tirelog.app.data

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "settings")
data class SettingsEntity(
    @PrimaryKey val id: Int = 1,
    val onboardingDone: Boolean,
    val recFrontKpa: Double?,
    val recRearKpa: Double?,
    val recSource: String?,
    val rotationIntervalKm: Int,
    val regionCode: String?,
    val vin: String?,
    val shopName: String?,
    val shopLat: Double?,
    val shopLon: Double?,
    val shopPoiId: String?,
    val shopPoiSubId: String?,
    val shopAddress: String?,
    val pressureSupported: Boolean,
    val odometerSupported: Boolean,
    val refillDay: Long?,
    val refillTireIds: String?,
    val pressurePositions: String? = null,
    val vehicleGuideId: String? = null,
    val vehicleTireSize: String? = null,
)

@Entity(tableName = "drive_state")
data class DriveStateEntity(
    @PrimaryKey val id: Int = 1,
    val lastStopAt: Long?,
    val lastStopOdo: Double?,
    val lastKnownOdo: Double?,
)

@Entity(tableName = "tire_set")
data class TireSetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long,
    val type: String,
    val size: String?,
    val dot: String?,
    val createdAt: Long,
    val retired: Boolean,
    val initialKm: Double,
    val kmAtLastRotation: Double,
    val recFrontKpa: Double?,
    val recRearKpa: Double?,
    val firstUsedEpochDay: Long? = null,
    @ColumnInfo(defaultValue = "0") val newUntilMounted: Boolean = false,
    @ColumnInfo(defaultValue = "40000") val inspectionKm: Int = 40_000,
    @ColumnInfo(defaultValue = "60") val inspectionMonths: Int = 60,
    @ColumnInfo(defaultValue = "'app'") val lifeGuideId: String = "app",
    val tireModel: String? = null,
    val manufacturer: String? = null,
    @ColumnInfo(defaultValue = "1") val initialKmKnown: Boolean = true,
)

data class TireProfileEntity(
    val manufacturer: String?, val tireModel: String?, val type: String,
    val initialKm: Double, val initialKmKnown: Boolean, val firstUsedEpochDay: Long?,
    val inspectionKm: Int, val inspectionMonths: Int, val lifeGuideId: String,
    val size: String? = null,
)

@Entity(tableName = "tire", indices = [Index("setId")])
data class TireEntity(
    @PrimaryKey(autoGenerate = true) val id: Long,
    val setId: Long,
    val label: String,
    val position: String,
    val dot: String?,
    val active: Boolean,
    @Embedded(prefix = "profile_") val profile: TireProfileEntity? = null,
    @ColumnInfo(defaultValue = "0") val retired: Boolean = false,
    val removedAtMillis: Long? = null,
    val removedOdoKm: Double? = null,
    @ColumnInfo(defaultValue = "0") val colorIndex: Int = 0,
)

@Entity(tableName = "mount")
data class MountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long,
    val setId: Long,
    val mountOdo: Double?,
    val mountAt: Long,
    val unmountOdo: Double?,
    val unmountAt: Long?,
    val odoGapAtMillis: Long? = null,
)

@Entity(tableName = "event", indices = [Index("atMillis")])
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long,
    val type: String,
    val atMillis: Long,
    val odo: Double?,
    val setId: Long?,
    val tireIds: String,
    val detail: String?,
    val rotationJson: String? = null,
    @ColumnInfo(defaultValue = "''") val previousTireIds: String = "",
)

@Entity(tableName = "measurement", indices = [Index("tireId"), Index("atMillis")])
data class MeasurementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long,
    val tireId: Long,
    val position: String,
    val kpa: Double,
    val tempC: Double?,
    val odo: Double?,
    val atMillis: Long,
    val epochDay: Long,
    val cold: Boolean,
)

@Entity(tableName = "baseline")
data class BaselineEntity(
    @PrimaryKey val tireId: Long,
    val kpa: Double?,
    val tempC: Double?,
    val epochDay: Long?,
    val origin: String,
)

@Entity(tableName = "trend_state")
data class TrendStateEntity(
    @PrimaryKey val tireId: Long,
    val leakStreak: Int,
    val declineStreak: Int,
    val lastEvalDay: Long?,
)

@Entity(tableName = "day_result")
data class DayResultEntity(
    @PrimaryKey val epochDay: Long,
    val computedAt: Long,
    val tempC: Double?,
    val baselineTempC: Double?,
    val cause: String,
)

@Entity(tableName = "tire_day_status", primaryKeys = ["epochDay", "tireId"])
data class TireDayStatusEntity(
    val epochDay: Long,
    val tireId: Long,
    val position: String,
    val kpa: Double,
    val recommendedKpa: Double?,
    val needsRefill: Boolean,
    val refillSuspected: Boolean,
    val correctedLossKpa: Double?,
    val baselineEpochDay: Long?,
    val baselineOrigin: String?,
    val leakStreak: Int,
    val declineStreak: Int,
    val leakSuspected: Boolean,
    val recheckNeeded: Boolean,
)

@Entity(tableName = "alert_state")
data class AlertStateEntity(
    @PrimaryKey val key: String,
    val lastShownDay: Long?,
    val snoozedUntilDay: Long?,
    val acknowledged: Boolean,
)
