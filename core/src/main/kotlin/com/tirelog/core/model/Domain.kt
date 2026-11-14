package com.tirelog.core.model

enum class TireSetType(val label: String) {
    ALL_WEATHER("올웨더"),
    ALL_SEASON("올시즌"),
    SUMMER("썸머"),
    WINTER("윈터"),
}

data class TireSet(
    val id: Long = 0,
    val type: TireSetType,
    val size: String?,
    val dot: String?,
    val createdAtMillis: Long,
    val retired: Boolean = false,
    /** 앱을 쓰기 전 누적 주행거리(선택 입력). */
    val initialKm: Double = 0.0,
    /** 세트 누적 km 기준, 마지막 위치교환 시점. */
    val kmAtLastRotation: Double = 0.0,
    /** 세트별 권장값. 없으면 차량 권장값을 따른다. */
    val recommendedFrontKpa: Double? = null,
    val recommendedRearKpa: Double? = null,
    /** 첫 사용일부터 보관 기간을 포함해 센다. 모르면 null. */
    val firstUsedEpochDay: Long? = null,
    /** 새 타이어로 등록한 세트만 첫 장착 때 사용 시작일을 자동 기록한다. */
    val newUntilMounted: Boolean = false,
    val inspectionKm: Int = DEFAULT_INSPECTION_KM,
    val inspectionMonths: Int = DEFAULT_INSPECTION_MONTHS,
    val lifeGuideId: String = "app",
    /** 제품명은 식별용이다. 검증된 제품별 수명 데이터가 없으면 제조사 일반 안내만 적용한다. */
    val tireModel: String? = null,
    val manufacturer: String? = null,
    /** false면 initialKm은 기록 시작점일 뿐 실제 총사용 거리로 표시하지 않는다. */
    val initialKmKnown: Boolean = true,
) {
    val title: String get() = "${type.label} 세트"

    companion object {
        // 수명 보증값이 아니라 사용자가 조정할 수 있는 교체 점검 기준.
        const val DEFAULT_INSPECTION_KM = 40_000
        const val DEFAULT_INSPECTION_MONTHS = 60
    }
}

/** 한 짝만 다른 제품이거나 부분 교체했을 때의 개별 정보. 없으면 세트 공통 정보를 따른다. */
data class TireProfile(
    val manufacturer: String? = null,
    val tireModel: String? = null,
    val type: TireSetType = TireSetType.ALL_SEASON,
    val initialKm: Double = 0.0,
    val initialKmKnown: Boolean = true,
    val firstUsedEpochDay: Long? = null,
    val inspectionKm: Int = TireSet.DEFAULT_INSPECTION_KM,
    val inspectionMonths: Int = TireSet.DEFAULT_INSPECTION_MONTHS,
    val lifeGuideId: String = "app",
    val size: String? = null,
)

data class Tire(
    val id: Long = 0,
    val setId: Long,
    val label: String,
    /** 세트가 보관 중이면 마지막으로 끼웠던 위치. */
    val position: WheelPosition,
    val dot: String? = null,
    val active: Boolean = true,
    val profile: TireProfile? = null,
    val retired: Boolean = false,
    val removedAtMillis: Long? = null,
    val removedOdoKm: Double? = null,
    /** 위치교환 후에도 유지. 교체 타이어는 기존 타이어의 표시색을 이어 받는다. */
    val colorIndex: Int = position.ordinal,
) {
    fun display(): String = "${position.label}(타이어 $label)"
}

data class Mount(
    val id: Long = 0,
    val setId: Long,
    val mountOdoKm: Double?,
    val mountAtMillis: Long,
    val unmountOdoKm: Double? = null,
    val unmountAtMillis: Long? = null,
    /** ODO를 확인하지 못했거나 역전된 마지막 시각. 뒤늦게 값을 받아도 과거 공백을 지우지 않는다. */
    val odoGapAtMillis: Long? = null,
) {
    val isActive: Boolean get() = unmountAtMillis == null
}

enum class EventType(val label: String, val wheelOff: Boolean) {
    REFILL("보충", false),
    REPAIR("수리", false),
    ROTATION("위치교환", true),
    TIRE_REPLACEMENT("타이어 교체", true),
    SET_CHANGE("세트 교체", true),
    NUT_CHECK("너트 점검", false),
    UNRECORDED_RISE("기록 없는 상승", false),
    RAPID_PRESSURE_DROP("주행 중 압력 저하", false),
}

/** 위치가 다시 바뀌거나 타이어가 교체돼도 당시 이동을 그대로 남긴다. */
data class TireMove(
    val tireId: Long,
    val tireLabel: String,
    val from: WheelPosition,
    val to: WheelPosition,
)

data class RotationRecord(val pattern: RotationPattern, val moves: List<TireMove>)

data class TireEvent(
    val id: Long = 0,
    val type: EventType,
    val atMillis: Long,
    val odoKm: Double?,
    val setId: Long?,
    val tireIds: List<Long> = emptyList(),
    val detail: String? = null,
    val rotation: RotationRecord? = null,
    /** 교체·세트 교체 전 타이어. tireIds에는 작업 후 영향을 받는 타이어를 저장한다. */
    val previousTireIds: List<Long> = emptyList(),
)

data class Measurement(
    val id: Long = 0,
    val tireId: Long,
    val position: WheelPosition,
    val kpa: Double,
    val tempC: Double?,
    val odoKm: Double?,
    val atMillis: Long,
    val epochDay: Long,
    val cold: Boolean,
)

enum class BaselineOrigin { FIRST, REFILL, REPAIR, ROTATION, SET_CHANGE, REPLACEMENT, UNRECORDED_RISE }

/** 타이어별 추세 기준. kpa가 null이면 리셋된 상태로, 다음 유효 냉간 측정을 기다린다. */
data class Baseline(
    val tireId: Long,
    val kpa: Double?,
    val tempC: Double?,
    val epochDay: Long?,
    val origin: BaselineOrigin,
) {
    val isPending: Boolean get() = kpa == null || epochDay == null

    companion object {
        fun pending(tireId: Long, origin: BaselineOrigin) = Baseline(tireId, null, null, null, origin)
    }
}

data class TrendState(
    val tireId: Long,
    val leakStreak: Int = 0,
    val declineStreak: Int = 0,
    val lastEvalDay: Long? = null,
)

enum class Cause(val label: String) {
    TEMPERATURE("기온 영향"),
    NATURAL("자연 감소"),
    LEAK("누설 의심"),
    UNDETERMINED("원인 판단 보류"),
    NONE(""),
}

data class TireDayStatus(
    val tireId: Long,
    val position: WheelPosition,
    val kpa: Double,
    val recommendedKpa: Double?,
    val needsRefill: Boolean,
    val refillSuspected: Boolean,
    val correctedLossKpa: Double?,
    val baselineEpochDay: Long?,
    val baselineOrigin: BaselineOrigin?,
    val leakStreak: Int,
    val declineStreak: Int,
    val leakSuspected: Boolean,
    val recheckNeeded: Boolean,
)

/** 그날 첫 유효 냉간 측정의 판단 결과. */
data class DayResult(
    val epochDay: Long,
    val computedAtMillis: Long,
    val tempC: Double?,
    val baselineTempC: Double?,
    val cause: Cause,
    val tires: List<TireDayStatus>,
)

enum class RecommendedSource(val label: String) {
    VEHICLE_DEFAULT("차종 기본값"),
    LABEL_CONFIRMED("라벨 확인"),
    USER_INPUT("사용자 입력"),
}

data class RecommendedPressure(
    val frontKpa: Double,
    val rearKpa: Double,
    val source: RecommendedSource,
) {
    fun forPosition(position: WheelPosition): Double = if (position.isFront) frontKpa else rearKpa
}

data class RefillQuestion(val epochDay: Long, val tireIds: List<Long>)

data class SavedShop(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val poiId: String?,
    val poiSubId: String?,
    val address: String?,
)

data class AppSettings(
    val onboardingDone: Boolean = false,
    val recommended: RecommendedPressure? = null,
    val rotationIntervalKm: Int = DEFAULT_ROTATION_INTERVAL_KM,
    val regionCode: String? = null,
    val vin: String? = null,
    val favoriteShop: SavedShop? = null,
    val pressureSupported: Boolean = true,
    val odometerSupported: Boolean = true,
    val refillQuestion: RefillQuestion? = null,
    val pressurePositions: Set<WheelPosition>? = null,
    val vehicleGuideId: String? = null,
    val vehicleTireSize: String? = null,
) {
    companion object {
        const val DEFAULT_ROTATION_INTERVAL_KM = 10_000
    }
}

/** 냉간 판정에 쓰는 주차 기록. 앱이 다시 켜져도 이어 쓰도록 저장한다. */
data class DriveState(
    val lastStopAtMillis: Long? = null,
    val lastStopOdoKm: Double? = null,
    val lastKnownOdoKm: Double? = null,
)

enum class AlertType(val priority: Int, val measurementBased: Boolean, val title: String) {
    LEAK(1, true, "누설 의심"),
    REFILL_NEEDED(2, true, "보충 필요"),
    RECHECK(3, true, "재점검 필요"),
    NUT_CHECK(4, false, "너트 점검"),
    ROTATION(5, false, "위치교환"),
    SEASON(5, false, "시즌 교체"),
    AGE(5, false, "제조 6년"),
    LIFE_DUE(5, false, "교체 점검 시기"),
    LIFE_SOON(6, false, "교체 점검 예고"),
}

data class AlertState(
    val key: String,
    val lastShownDay: Long? = null,
    val snoozedUntilDay: Long? = null,
    val acknowledged: Boolean = false,
)
