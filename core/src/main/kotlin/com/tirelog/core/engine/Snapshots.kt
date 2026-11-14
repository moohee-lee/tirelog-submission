package com.tirelog.core.engine

import com.tirelog.core.alert.ActiveAlert
import com.tirelog.core.model.AlertType
import com.tirelog.core.model.RotationPattern
import com.tirelog.core.model.TireSet
import com.tirelog.core.model.TireSetType
import com.tirelog.core.model.TireProfile
import com.tirelog.core.model.WheelPosition
import com.tirelog.core.maintenance.TireLife
import com.tirelog.core.catalog.TireLifeGuides

/** 차량이 지금 주는 값(판단에 쓰지 않고 표시만 한다). */
data class LiveReadings(
    val kpaByPosition: Map<WheelPosition, Double> = emptyMap(),
    val atMillis: Long? = null,
    /** 이번 시동에서 이미 달렸으면 true. "주행 직후 값"으로 표시한다. */
    val afterDriving: Boolean = false,
    /** 지금 누적 주행거리. 세트 주행거리·위치교환·너트 점검 계산에 쓴다. */
    val odoKm: Double? = null,
    val pressurePositions: Set<WheelPosition>? = null,
    val pressureTimes: Map<WheelPosition, Long> = emptyMap(),
    val lowPressureKpa: Map<WheelPosition, Double> = emptyMap(),
)

enum class WheelState { WAITING, NORMAL, LOW, REFILL, LEAK, RECHECK }

data class WheelCard(
    val position: WheelPosition,
    val tireId: Long?,
    val tireLabel: String?,
    val psi: Int?,
    val recommendedPsi: Int?,
    /** 예: "31psi · 권장 36 · 5psi 부족 · 오늘 07:52 냉간" */
    val line: String,
    /** 예: "지금 38psi · 주행 직후 값" */
    val liveLine: String?,
    val state: WheelState,
    /** 메인의 최신 측정. 냉간 판정에 쓰는 psi와 분리한다. */
    val displayPsi: Int? = psi,
    val measuredLabel: String = "측정 대기",
    val conditionLabel: String = "냉간 여부 확인 불가",
    val supported: Boolean = true,
    val lowPressureKpa: Double? = null,
)

data class PendingItem(val key: String, val text: String, val type: AlertType?)

data class RefillQuestionView(val text: String, val positions: List<WheelPosition>)

data class DashboardSnapshot(
    val nowMillis: Long,
    val onboardingDone: Boolean,
    val pressureSupported: Boolean,
    val odometerSupported: Boolean,
    val wheels: List<WheelCard>,
    /** 주행 중 한 줄 화면 문구. */
    val headline: String,
    val causeText: String?,
    val pending: List<PendingItem>,
    val alerts: List<ActiveAlert>,
    val refillQuestion: RefillQuestionView?,
    val measuredDaysLast7: Int,
    val mountedSetLabel: String?,
    val rotationRemainingKm: Int?,
    val needsRecommended: Boolean,
    val recommendedSourceLabel: String?,
) {
    companion object {
        fun empty(now: Long) = DashboardSnapshot(
            nowMillis = now, onboardingDone = false, pressureSupported = true, odometerSupported = true,
            wheels = emptyList(), headline = "tirelog", causeText = null, pending = emptyList(),
            alerts = emptyList(), refillQuestion = null, measuredDaysLast7 = 0, mountedSetLabel = null,
            rotationRemainingKm = null, needsRecommended = false, recommendedSourceLabel = null,
        )
    }
}

data class TrendPoint(val atMillis: Long, val value: Double, val segment: Long = 0, val position: WheelPosition? = null)

data class TrendSeries(val tireId: Long, val label: String, val points: List<TrendPoint>, val active: Boolean = true, val colorIndex: Int = 0)

data class TrendMark(val atMillis: Long, val label: String)

data class TrendData(
    val fromMillis: Long,
    val toMillis: Long,
    val series: List<TrendSeries>,
    val temperatures: List<TrendPoint>,
    val marks: List<TrendMark>,
    val measuredDaysLast7: Int,
)

data class MechanicTireRow(
    val position: WheelPosition,
    val tireLabel: String,
    val recentPsi: List<Int>,
    val trendText: String?,
    val lastRefill: String?,
    val lastRepair: String?,
    val flag: String?,
)

data class MechanicCard(
    val header: String,
    val rows: List<MechanicTireRow>,
    val rotation: String,
    val recentEvents: List<String>,
)

data class SetRow(
    val set: TireSet,
    val km: Int,
    val status: String,
    val mounted: Boolean,
    val dotText: String?,
    val olderThanSixYears: Boolean,
    val rotationText: String?,
    val tireLife: List<TireLife.Status> = emptyList(),
    val canDelete: Boolean = false,
    val previousTireLife: List<TireLife.Status> = emptyList(),
    val distanceComplete: Boolean = true,
)

data class SetsOverview(
    val rows: List<SetRow>,
    val history: List<String>,
    val rotationIntervalKm: Int,
)

/** 작업 기록(체크인) 요청. 위치는 작업 전 위치 기준이다. */
data class CheckInRequest(
    val refill: Set<WheelPosition> = emptySet(),
    val repair: Set<WheelPosition> = emptySet(),
    val rotation: RotationPattern? = null,
    val replacements: Map<WheelPosition, String?> = emptyMap(),
    val changeToSetId: Long? = null,
    val nutCheck: Boolean = false,
    val replacementProfiles: Map<WheelPosition, TireProfile> = emptyMap(),
    val retireReplaced: Boolean = false,
) {
    val isEmpty: Boolean
        get() = refill.isEmpty() && repair.isEmpty() && rotation == null && replacements.isEmpty() &&
            changeToSetId == null && !nutCheck
}

data class NewSetInput(
    val type: TireSetType,
    val size: String?,
    val dot: String?,
    val initialKm: Double = 0.0,
    val recommendedFrontKpa: Double? = null,
    val recommendedRearKpa: Double? = null,
    val firstUsedEpochDay: Long? = null,
    val newUntilMounted: Boolean = false,
    val lifeGuideId: String = "app",
    val inspectionKm: Int = TireLifeGuides.find(lifeGuideId).defaultKm,
    val inspectionMonths: Int = TireLifeGuides.find(lifeGuideId).defaultMonths,
    val tireModel: String? = null,
    val manufacturer: String? = null,
    val initialKmKnown: Boolean = true,
) {
    fun profile(firstUsed: Long? = firstUsedEpochDay) = TireProfile(
        manufacturer, tireModel, type, initialKm, initialKmKnown, firstUsed,
        inspectionKm, inspectionMonths, lifeGuideId,
        size,
    )
}
