package com.tirelog.core.engine

import com.tirelog.core.TimeLabels
import com.tirelog.core.Units
import com.tirelog.core.alert.ActiveAlert
import com.tirelog.core.alert.AlertAction
import com.tirelog.core.alert.AlertInputs
import com.tirelog.core.alert.AlertPlanner
import com.tirelog.core.catalog.TireLifeGuides
import com.tirelog.core.judge.AssessmentEngine
import com.tirelog.core.judge.TireReading
import com.tirelog.core.maintenance.DotCode
import com.tirelog.core.maintenance.Mileage
import com.tirelog.core.maintenance.TireLife
import com.tirelog.core.model.AlertState
import com.tirelog.core.model.AlertType
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
import com.tirelog.core.model.RefillQuestion
import com.tirelog.core.model.RotationRecord
import com.tirelog.core.model.TireMove
import com.tirelog.core.model.Tire
import com.tirelog.core.model.TireDayStatus
import com.tirelog.core.model.TireEvent
import com.tirelog.core.model.TireSet
import com.tirelog.core.model.TireSetType
import com.tirelog.core.model.TrendState
import com.tirelog.core.model.WheelPosition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * tirelog의 유스케이스 모음. 수집(공기압·기온·ODO) → 판단(4장) → 저장 → 화면용 스냅샷.
 */
class TireLogEngine(private val store: TireLogStore, val zone: ZoneId) {

    private val mutex = Mutex()
    private val _version = MutableStateFlow(0L)

    /** 기록이 바뀔 때마다 오른다. 화면은 이 값을 보고 스냅샷을 다시 만든다. */
    val version: StateFlow<Long> = _version.asStateFlow()

    private suspend fun <T> read(block: suspend () -> T): T = mutex.withLock { block() }

    private suspend fun <T> write(block: suspend () -> T): T = mutex.withLock {
        val result = store.transaction { block() }
        _version.value = _version.value + 1
        result
    }

    fun epochDay(now: Long): Long = TimeLabels.epochDay(now, zone)

    fun today(now: Long): LocalDate = TimeLabels.date(now, zone)

    private fun startOfDay(epochDay: Long): Long =
        LocalDate.ofEpochDay(epochDay).atStartOfDay(zone).toInstant().toEpochMilli()

    // region 컨텍스트

    private class Ctx(
        val settings: AppSettings,
        val sets: List<TireSet>,
        val tires: List<Tire>,
        val mounts: List<Mount>,
    ) {
        val mountedSet: TireSet? = AlertPlanner.mountedSet(sets, mounts)
        val activeTires: List<Tire> = tires.filter { it.active && it.setId == mountedSet?.id }
        val byPosition: Map<WheelPosition, Tire> = activeTires.associateBy { it.position }

        fun recommendedFor(position: WheelPosition): Double? {
            val set = mountedSet
            val override = if (position.isFront) set?.recommendedFrontKpa else set?.recommendedRearKpa
            return override ?: settings.recommended?.forPosition(position)
        }
    }

    private suspend fun ctx() = Ctx(store.settings(), store.sets(), store.tires(), store.mounts())

    // endregion

    // region 설정·초기 설정

    suspend fun settings(): AppSettings = read { store.settings() }

    suspend fun driveState(): DriveState = read { store.driveState() }

    /** 주차 기록은 화면에 영향이 없어 버전을 올리지 않는다. */
    suspend fun saveDriveState(state: DriveState, now: Long? = null) = mutex.withLock {
        store.transaction {
            anchorMountOdometer(state.lastKnownOdoKm, now)
            store.saveDriveState(state)
        }
    }

    private suspend fun anchorMountOdometer(odoKm: Double?, now: Long? = null) {
        if (odoKm == null) return
        require(odoKm.isFinite() && odoKm >= 0) { "주행거리는 0 이상의 값이어야 합니다" }
        val previous = store.driveState().lastKnownOdoKm
        store.mounts().filter { it.isActive }.forEach {
            if (it.mountOdoKm == null || (previous != null && odoKm < previous)) {
                val boundary = maxOf(now ?: it.mountAtMillis, it.mountAtMillis)
                // 누락 구간을 닫고 확인된 값부터 새 구간을 시작한다. 여러 차례 누락되어도 과거 공백이 사라지지 않는다.
                store.saveMount(it.copy(unmountAtMillis = boundary,
                    unmountOdoKm = odoKm.takeIf { _ -> it.mountOdoKm == null },
                    odoGapAtMillis = it.odoGapAtMillis ?: boundary))
                store.saveMount(Mount(setId = it.setId, mountOdoKm = odoKm, mountAtMillis = boundary))
            }
        }
    }

    private suspend fun rememberOdometer(odoKm: Double?, now: Long) {
        if (odoKm == null) return
        anchorMountOdometer(odoKm, now)
        store.saveDriveState(store.driveState().copy(lastKnownOdoKm = odoKm))
    }

    suspend fun updateSettings(transform: (AppSettings) -> AppSettings) = write {
        val settings = transform(store.settings())
        validatePressure(settings.recommended?.frontKpa, settings.recommended?.rearKpa)
        require(settings.rotationIntervalKm > 0) { "위치교환 주기는 0보다 커야 합니다" }
        store.saveSettings(settings)
    }

    data class OnboardingInput(
        val recommended: RecommendedPressure?,
        val setType: TireSetType,
        val setSize: String?,
        val setDot: String?,
        val initialKm: Double = 0.0,
        val rotationIntervalKm: Int = AppSettings.DEFAULT_ROTATION_INTERVAL_KM,
        /** 마지막 위치교환 뒤 달린 거리. 모르면 null(지금부터 센다). */
        val kmSinceLastRotation: Double? = null,
        val regionCode: String? = null,
        val vin: String? = null,
        val pressureSupported: Boolean = true,
        val odometerSupported: Boolean = true,
        val firstUsedEpochDay: Long? = null,
        val newUntilMounted: Boolean = false,
        val lifeGuideId: String = TireLifeGuides.DEFAULT_ID,
        val inspectionKm: Int = TireLifeGuides.find(lifeGuideId).defaultKm,
        val inspectionMonths: Int = TireLifeGuides.find(lifeGuideId).defaultMonths,
        val tireModel: String? = null,
        val manufacturer: String? = null,
        val initialKmKnown: Boolean = true,
        val pressurePositions: Set<WheelPosition>? = null,
        val vehicleGuideId: String? = null,
        val vehicleTireSize: String? = null,
    )

    suspend fun completeOnboarding(input: OnboardingInput, now: Long, odoKm: Double?) = write {
        require(!store.settings().onboardingDone) { "초기 설정이 이미 완료되었습니다" }
        validatePressure(input.recommended?.frontKpa, input.recommended?.rearKpa)
        require(input.rotationIntervalKm > 0) { "위치교환 주기는 0보다 커야 합니다" }
        require(input.kmSinceLastRotation == null || (input.kmSinceLastRotation.isFinite() && input.kmSinceLastRotation >= 0)) {
            "마지막 위치교환 이후 거리를 확인해 주세요"
        }
        rememberOdometer(odoKm, now)
        val setId = addSetInternal(
            NewSetInput(
                input.setType, input.setSize, input.setDot, input.initialKm,
                firstUsedEpochDay = input.firstUsedEpochDay, newUntilMounted = input.newUntilMounted,
                inspectionKm = input.inspectionKm, inspectionMonths = input.inspectionMonths,
                lifeGuideId = input.lifeGuideId, tireModel = input.tireModel,
                manufacturer = input.manufacturer, initialKmKnown = input.initialKmKnown,
            ),
            now,
        )
        input.kmSinceLastRotation?.let { since ->
            val set = store.sets().first { it.id == setId }
            store.saveSet(set.copy(kmAtLastRotation = set.initialKm - since))
        }
        store.saveMount(Mount(setId = setId, mountOdoKm = odoKm, mountAtMillis = now,
            odoGapAtMillis = now.takeIf { odoKm == null }))
        startFirstUse(setId, now)
        store.saveSettings(
            store.settings().copy(
                onboardingDone = true,
                recommended = input.recommended,
                rotationIntervalKm = input.rotationIntervalKm,
                regionCode = input.regionCode,
                vin = input.vin,
                pressureSupported = input.pressureSupported,
                odometerSupported = input.odometerSupported,
                pressurePositions = input.pressurePositions,
                vehicleGuideId = input.vehicleGuideId,
                vehicleTireSize = input.vehicleTireSize,
            ),
        )
    }

    suspend fun resetAll() = write { store.clearAll() }

    // endregion

    // region 측정

    suspend fun measuredToday(now: Long): Boolean = read { store.latestDayResult()?.epochDay == epochDay(now) }

    /** 그날 첫 유효 냉간 측정. 측정을 저장하고 4장 규칙으로 판단한다. */
    suspend fun recordColdReading(
        kpaByPosition: Map<WheelPosition, Double>,
        tempC: Double?,
        odoKm: Double?,
        now: Long,
    ): DayResult? = write {
        require(kpaByPosition.values.all { it.isFinite() && it >= 0 }) { "공기압 측정값을 확인해 주세요" }
        rememberOdometer(odoKm, now)
        val c = ctx()
        val day = epochDay(now)
        val existing = store.latestDayResult()
        if (existing != null && existing.epochDay >= day) return@write existing
        val baselines = store.baselines().associateBy { it.tireId }
        val trends = store.trendStates().associateBy { it.tireId }
        val readings = kpaByPosition.mapNotNull { (position, kpa) ->
            val tire = c.byPosition[position] ?: return@mapNotNull null
            TireReading(tire.id, position, c.recommendedFor(position), kpa, baselines[tire.id], trends[tire.id])
        }.sortedBy { it.position.ordinal }
        if (readings.isEmpty()) return@write null

        store.addMeasurements(
            readings.map { Measurement(0, it.tireId, it.position, it.coldKpa, tempC, odoKm, now, day, cold = true) },
        )
        val assessment = AssessmentEngine.assess(day, tempC, readings)
        assessment.tires.forEach {
            store.saveBaseline(it.baselineAfter)
            store.saveTrendState(it.trendAfter)
        }
        val result = DayResult(
            epochDay = day,
            computedAtMillis = now,
            tempC = tempC,
            baselineTempC = assessment.baselineTempC,
            cause = assessment.cause,
            tires = assessment.tires.map { t ->
                TireDayStatus(
                    tireId = t.tireId,
                    position = t.position,
                    kpa = t.coldKpa,
                    recommendedKpa = t.recommendedKpa,
                    needsRefill = t.needsRefill,
                    refillSuspected = t.refillSuspected,
                    correctedLossKpa = t.correctedLossKpa,
                    baselineEpochDay = t.baselineUsed?.epochDay,
                    baselineOrigin = t.baselineUsed?.origin,
                    leakStreak = t.trendAfter.leakStreak,
                    declineStreak = t.trendAfter.declineStreak,
                    leakSuspected = t.leakSuspected,
                    recheckNeeded = t.recheckNeeded,
                )
            },
        )
        store.saveDayResult(result)
        val suspected = assessment.tires.filter { it.refillSuspected }.map { it.tireId }
        store.saveSettings(
            c.settings.copy(refillQuestion = if (suspected.isEmpty()) null else RefillQuestion(day, suspected)),
        )
        result
    }

    /** 냉간이 아닌 값. 표시와 이력에만 쓰고 판단에는 쓰지 않는다. */
    suspend fun recordHotReading(
        kpaByPosition: Map<WheelPosition, Double>,
        tempC: Double?,
        odoKm: Double?,
        now: Long,
    ) = write {
        require(kpaByPosition.values.all { it.isFinite() && it >= 0 }) { "공기압 측정값을 확인해 주세요" }
        rememberOdometer(odoKm, now)
        val c = ctx()
        val day = epochDay(now)
        val list = kpaByPosition.mapNotNull { (position, kpa) ->
            val tire = c.byPosition[position] ?: return@mapNotNull null
            Measurement(0, tire.id, position, kpa, tempC, odoKm, now, day, cold = false)
        }
        if (list.isNotEmpty()) store.addMeasurements(list)
    }

    /** 급격한 저하는 참고 측정·이력만 남기며 냉간 추세나 알림을 갱신하지 않는다. */
    suspend fun recordPressureDrop(drop: com.tirelog.core.judge.PressureDrop, tempC: Double?, odoKm: Double?) = write {
        val c = ctx()
        val tire = c.byPosition[drop.position]?.takeIf { it.id == drop.tireId } ?: return@write
        if (!c.settings.pressureSupported || c.settings.pressurePositions?.contains(drop.position) == false) return@write
        val lastIdentityChange = maxOf(c.mounts.filter { it.isActive }.maxOfOrNull { it.mountAtMillis } ?: 0L,
            store.events().filter { it.type.wheelOff && tire.id in it.tireIds }.maxOfOrNull { it.atMillis } ?: 0L)
        if (drop.before.atMillis <= lastIdentityChange) return@write
        require(drop.after.atMillis > drop.before.atMillis && listOf(drop.before.kpa, drop.after.kpa).all { it.isFinite() && it >= 0 })
        store.addMeasurements(listOf(drop.before, drop.after).map {
            Measurement(tireId = tire.id, position = drop.position, kpa = it.kpa, tempC = tempC,
                odoKm = odoKm.takeIf { _ -> it == drop.after }, atMillis = it.atMillis, epochDay = epochDay(it.atMillis), cold = false)
        })
        store.addEvent(TireEvent(type = EventType.RAPID_PRESSURE_DROP, atMillis = drop.after.atMillis,
            odoKm = odoKm, setId = tire.setId, tireIds = listOf(tire.id),
            detail = "${tire.display()} · ${(drop.after.atMillis - drop.before.atMillis) / 1_000}초 동안 " +
                "${drop.before.kpa.roundToInt()} → ${drop.after.kpa.roundToInt()}kPa · 주행 중 참고 기록"))
    }

    /** "공기를 보충하셨나요?" 응답. 감지한 측정을 새 기준으로 삼는다. */
    suspend fun answerRefillQuestion(refilled: Boolean, now: Long) = write {
        val settings = store.settings()
        val question = settings.refillQuestion ?: return@write
        val c = ctx()
        val dayMeasurements = store.measurementsSince(startOfDay(question.epochDay))
            .filter { it.cold && it.epochDay == question.epochDay }
        val origin = if (refilled) BaselineOrigin.REFILL else BaselineOrigin.UNRECORDED_RISE
        question.tireIds.forEach { id ->
            val m = dayMeasurements.filter { it.tireId == id }.minByOrNull { it.atMillis }
            store.saveBaseline(
                if (m != null) Baseline(id, m.kpa, m.tempC, question.epochDay, origin) else Baseline.pending(id, origin),
            )
            store.saveTrendState(TrendState(id, 0, 0, question.epochDay))
        }
        store.addEvent(
            TireEvent(
                type = if (refilled) EventType.REFILL else EventType.UNRECORDED_RISE,
                atMillis = now,
                odoKm = store.driveState().lastKnownOdoKm,
                setId = c.mountedSet?.id,
                tireIds = question.tireIds,
                detail = describeTires(question.tireIds, c.tires) + " · 압력 상승 감지",
            ),
        )
        store.saveSettings(settings.copy(refillQuestion = null))
    }

    // endregion

    // region 세트·작업 기록

    suspend fun addSet(input: NewSetInput, now: Long): Long = write { addSetInternal(input, now) }

    private suspend fun addSetInternal(input: NewSetInput, now: Long): Long {
        validateRegistration(input, now)
        require(input.initialKm.isFinite() && input.initialKm >= 0) { "누적 주행거리는 0 이상이어야 합니다" }
        validatePressure(input.recommendedFrontKpa, input.recommendedRearKpa)
        validateDot(input.dot, now)
        validateLife(input.initialKm, input.firstUsedEpochDay, input.newUntilMounted, input.inspectionKm, input.inspectionMonths, input.dot, now)
        val setId = store.saveSet(
            TireSet(
                type = input.type,
                size = input.size,
                dot = input.dot,
                createdAtMillis = now,
                initialKm = input.initialKm,
                kmAtLastRotation = input.initialKm,
                recommendedFrontKpa = input.recommendedFrontKpa,
                recommendedRearKpa = input.recommendedRearKpa,
                firstUsedEpochDay = input.firstUsedEpochDay,
                newUntilMounted = input.newUntilMounted,
                inspectionKm = input.inspectionKm,
                inspectionMonths = input.inspectionMonths,
                lifeGuideId = TireLifeGuides.find(input.lifeGuideId).id,
                tireModel = input.tireModel?.trim()?.take(80)?.ifEmpty { null },
                manufacturer = input.manufacturer?.trim()?.take(80)?.ifEmpty { null },
                initialKmKnown = input.initialKmKnown,
            ),
        )
        WheelPosition.entries.forEachIndexed { i, position ->
            store.saveTire(Tire(setId = setId, label = LABELS[i].toString(), position = position, dot = input.dot))
        }
        return setId
    }

    suspend fun retireSet(setId: Long) = write {
        val c = ctx()
        require(c.mountedSet?.id != setId) { "장착 중인 타이어는 폐기할 수 없습니다" }
        c.sets.firstOrNull { it.id == setId }?.let { store.saveSet(it.copy(retired = true)) }
    }

    suspend fun deleteSet(setId: Long) = write {
        val c = ctx()
        require(c.mountedSet?.id != setId) { "장착 중에는 삭제할 수 없습니다" }
        val ids = c.tires.filter { it.setId == setId }.map { it.id }.toSet()
        require(c.mounts.none { it.setId == setId } && store.events().none { it.setId == setId } &&
            store.measurementsSince(Long.MIN_VALUE).none { it.tireId in ids }) {
            "사용 이력이 있는 타이어는 폐기로 표시해 기록을 보존해 주세요"
        }
        store.deleteUnusedSet(setId)
    }

    suspend fun updateRegistration(setId: Long, input: NewSetInput, now: Long) = write {
        val set = store.sets().firstOrNull { it.id == setId && !it.retired }
            ?: throw IllegalArgumentException("수정할 타이어를 선택해 주세요")
        validateRegistration(input, now)
        require(!input.newUntilMounted || store.mounts().none { it.setId == setId }) { "사용한 타이어는 새 타이어로 바꿀 수 없습니다" }
        store.saveSet(set.copy(type = input.type, size = input.size, dot = input.dot,
            initialKm = input.initialKm, initialKmKnown = input.initialKmKnown,
            kmAtLastRotation = set.kmAtLastRotation + input.initialKm - set.initialKm,
            manufacturer = input.manufacturer?.trim()?.take(80)?.ifBlank { null },
            tireModel = input.tireModel?.trim()?.take(80)?.ifBlank { null },
            firstUsedEpochDay = input.firstUsedEpochDay, newUntilMounted = input.newUntilMounted,
            inspectionKm = input.inspectionKm, inspectionMonths = input.inspectionMonths,
            lifeGuideId = input.lifeGuideId,
            recommendedFrontKpa = input.recommendedFrontKpa, recommendedRearKpa = input.recommendedRearKpa,
        ))
        store.tires().filter { it.setId == setId && it.active && it.profile == null }.forEach {
            store.saveTire(it.copy(dot = input.dot))
        }
    }

    suspend fun updateTire(tireId: Long, input: NewSetInput, now: Long) = write {
        validateRegistration(input, now)
        val tire = store.tires().firstOrNull { it.id == tireId && it.active && !it.retired }
            ?: throw IllegalArgumentException("수정할 타이어를 선택해 주세요")
        require(store.sets().any { it.id == tire.setId && !it.retired }) { "폐기된 타이어는 수정할 수 없습니다" }
        store.saveTire(tire.copy(dot = input.dot, profile = input.profile()))
    }

    private fun validateRegistration(input: NewSetInput, now: Long) {
        validateDot(input.dot, now)
        validatePressure(input.recommendedFrontKpa, input.recommendedRearKpa)
        validateLife(input.initialKm, input.firstUsedEpochDay, input.newUntilMounted,
            input.inspectionKm, input.inspectionMonths, input.dot, now)
        require(input.initialKmKnown || input.initialKm == 0.0) { "기존 거리를 모르면 기록 시작 거리는 0으로 설정해 주세요" }
    }

    /** 등록·장착·기존 타이어 처리까지 하나의 저장 작업으로 끝낸다. */
    suspend fun replaceTires(
        positions: Set<WheelPosition>, newTires: NewSetInput?, existingSetId: Long?,
        discardOld: Boolean, now: Long, odoKm: Double?,
    ) = write {
        val previous = ctx().mountedSet ?: throw IllegalArgumentException("먼저 내 타이어를 등록해 주세요")
        require(positions.isNotEmpty()) { "교체한 바퀴를 선택해 주세요" }
        require(odoKm == null || (odoKm.isFinite() && odoKm >= 0)) { "차량 주행거리를 확인해 주세요" }
        require((newTires == null) != (existingSetId == null)) { "새 타이어 또는 보관 타이어를 선택해 주세요" }
        newTires?.let { validateRegistration(it, now) }
        val all = positions.size == WheelPosition.entries.size
        if (existingSetId != null) {
            require(all) { "보관 세트는 네 바퀴를 함께 장착합니다" }
            require(store.sets().any { it.id == existingSetId && !it.retired && it.id != previous.id }) {
                "장착할 수 있는 보관 타이어를 선택해 주세요"
            }
        }
        val events = if (all) {
            val target = existingSetId ?: addSetInternal(requireNotNull(newTires), now)
            checkInInternal(CheckInRequest(changeToSetId = target), now, odoKm)
        } else {
            val input = requireNotNull(newTires)
            checkInInternal(CheckInRequest(
                replacements = positions.associateWith { input.dot },
                replacementProfiles = positions.associateWith { input.profile(if (input.newUntilMounted) epochDay(now) else input.firstUsedEpochDay) },
                retireReplaced = discardOld,
            ), now, odoKm)
        }
        if (all && discardOld) store.saveSet(previous.copy(retired = true))
        events
    }

    suspend fun updateSet(set: TireSet) = write { store.saveSet(set) }

    suspend fun updateLifeSettings(
        setId: Long, initialKm: Double, firstUsedEpochDay: Long?, inspectionKm: Int, inspectionMonths: Int, now: Long,
        lifeGuideId: String? = null, tireModel: String? = null,
    ) = write {
        val set = store.sets().firstOrNull { it.id == setId && !it.retired }
            ?: throw IllegalArgumentException("사용 중인 세트를 선택해 주세요")
        validateLife(initialKm, firstUsedEpochDay, false, inspectionKm, inspectionMonths, set.dot, now)
        // 과거 거리 보정이 위치교환 이후 거리까지 바꾸지 않도록 기준점도 함께 이동한다.
        store.saveSet(set.copy(
            initialKm = initialKm,
            kmAtLastRotation = set.kmAtLastRotation + initialKm - set.initialKm,
            firstUsedEpochDay = firstUsedEpochDay,
            newUntilMounted = set.newUntilMounted && firstUsedEpochDay == null && initialKm == 0.0,
            inspectionKm = inspectionKm,
            inspectionMonths = inspectionMonths,
            lifeGuideId = lifeGuideId?.let { TireLifeGuides.find(it).id } ?: set.lifeGuideId,
            tireModel = tireModel?.trim()?.take(80)?.ifEmpty { null },
        ))
    }

    private fun validateLife(initialKm: Double, firstUsed: Long?, isNew: Boolean, km: Int, months: Int, dot: String?, now: Long) {
        require(initialKm.isFinite() && initialKm >= 0) { "누적 주행거리는 0 이상이어야 합니다" }
        require(km in 1_000..200_000 && months in 1..120) { "점검 기준은 1,000~200,000km, 1~120개월로 입력해 주세요" }
        require(!isNew || (initialKm == 0.0 && firstUsed == null)) { "새 타이어는 과거 거리와 사용일을 입력하지 않습니다" }
        firstUsed?.let {
            val date = runCatching { LocalDate.ofEpochDay(it) }.getOrNull()
            require(date != null && date >= LocalDate.of(2000, 1, 1) && date <= today(now)) { "첫 사용일을 확인해 주세요" }
            val manufactured = DotCode.parseOrNull(dot, today(now))?.manufactureDate
            require(manufactured == null || !date.isBefore(manufactured)) { "첫 사용일은 제조일보다 빠를 수 없습니다" }
        }
    }

    private suspend fun startFirstUse(setId: Long, now: Long) {
        val set = store.sets().first { it.id == setId }
        if (set.newUntilMounted) store.saveSet(set.copy(firstUsedEpochDay = epochDay(now), newUntilMounted = false))
    }

    /** 작업 기록. 같은 작업의 바퀴 위치는 작업 전 위치 기준이다. */
    suspend fun checkIn(request: CheckInRequest, now: Long, odoKm: Double?): List<TireEvent> = write {
        checkInInternal(request, now, odoKm)
    }

    private suspend fun checkInInternal(request: CheckInRequest, now: Long, odoKm: Double?): List<TireEvent> {
        require(!request.isEmpty) { "기록할 작업을 골라 주세요" }
        require(!request.nutCheck || (request.rotation == null && request.replacements.isEmpty() && request.changeToSetId == null)) {
            "너트 점검은 타이어 작업 뒤 50km를 달린 후 별도로 기록해 주세요"
        }
        request.replacements.values.forEach { validateDot(it, now) }
        require(request.replacementProfiles.keys.all { it in request.replacements }) { "교체 위치를 확인해 주세요" }
        request.replacementProfiles.forEach { (position, p) ->
            validateLife(p.initialKm, p.firstUsedEpochDay, false, p.inspectionKm, p.inspectionMonths, request.replacements[position], now)
            require(p.initialKmKnown || p.initialKm == 0.0) { "기존 거리 입력을 확인해 주세요" }
        }
        rememberOdometer(odoKm, now)
        val c = ctx()
        require(c.mountedSet != null) { "먼저 초기 설정을 완료해 주세요" }
        request.changeToSetId?.let { targetId ->
            require(c.sets.any { it.id == targetId && !it.retired && it.id != c.mountedSet.id }) {
                "장착 중이거나 폐기된 세트로 바꿀 수 없습니다"
            }
        }
        val odo = odoKm
        val created = mutableListOf<TireEvent>()
        val origins = mutableMapOf<Long, BaselineOrigin>()
        fun mark(id: Long, origin: BaselineOrigin) {
            val current = origins[id]
            if (current == null || precedence(origin) < precedence(current)) origins[id] = origin
        }
        suspend fun add(event: TireEvent) {
            val id = store.addEvent(event)
            created += event.copy(id = id)
        }

        val repairTargets = request.repair.mapNotNull { c.byPosition[it] }
        val refillTargets = request.refill.mapNotNull { c.byPosition[it] }
        var mounted = c.mountedSet
        var tires = c.tires

        request.changeToSetId?.takeIf { it != mounted?.id }?.let { targetId ->
            val target = c.sets.firstOrNull { it.id == targetId && !it.retired } ?: return@let
            c.mounts.filter { it.isActive }.forEach {
                store.saveMount(it.copy(unmountOdoKm = odo, unmountAtMillis = now,
                    odoGapAtMillis = if (odo == null || (it.mountOdoKm != null && odo < it.mountOdoKm)) now else it.odoGapAtMillis))
            }
            store.saveMount(Mount(setId = targetId, mountOdoKm = odo, mountAtMillis = now,
                odoGapAtMillis = now.takeIf { odo == null }))
            startFirstUse(targetId, now)
            ensureTires(target, tires)
            tires = store.tires()
            val targetTires = tires.filter { it.active && it.setId == targetId }
            targetTires.forEach { mark(it.id, BaselineOrigin.SET_CHANGE) }
            add(
                TireEvent(
                    type = EventType.SET_CHANGE, atMillis = now, odoKm = odo, setId = targetId,
                    tireIds = targetTires.map { it.id },
                    detail = "${mounted?.title ?: "세트 없음"} → ${target.title}",
                    previousTireIds = c.activeTires.map { it.id },
                ),
            )
            mounted = target
        }

        val set = mounted
        if (set != null && request.replacements.isNotEmpty()) {
            request.replacements.forEach { (position, dot) ->
                val current = tires.filter { it.active && it.setId == set.id }
                val old = current.firstOrNull { it.position == position }
                if (old != null) {
                    val history = store.events()
                    val priorReplacement = history.any { it.type == EventType.TIRE_REPLACEMENT && old.id in it.tireIds }
                    val oldLife = TireLife.evaluate(set, old, store.mounts(), history, odo, now, zone)
                    val profile = old.profile ?: com.tirelog.core.model.TireProfile(set.manufacturer, set.tireModel, set.type,
                        if (priorReplacement) 0.0 else set.initialKm, if (priorReplacement) true else set.initialKmKnown,
                        oldLife.firstUsed?.toEpochDay(), set.inspectionKm, set.inspectionMonths, set.lifeGuideId, set.size)
                    store.saveTire(old.copy(active = false, retired = request.retireReplaced, profile = profile,
                        removedAtMillis = now, removedOdoKm = odo))
                }
                val used = tires.filter { it.setId == set.id }.map { it.label }.toSet()
                val label = LABELS.map { it.toString() }.firstOrNull { it !in used } ?: "${position.ordinal + 1}"
                val newId = store.saveTire(Tire(setId = set.id, label = label, position = position, dot = dot,
                    profile = request.replacementProfiles[position], colorIndex = old?.colorIndex ?: position.ordinal))
                tires = store.tires()
                mark(newId, BaselineOrigin.REPLACEMENT)
                add(
                    TireEvent(
                        type = EventType.TIRE_REPLACEMENT, atMillis = now, odoKm = odo, setId = set.id,
                        tireIds = listOf(newId),
                        detail = "${position.label} 타이어 ${old?.label ?: "-"} → $label",
                        previousTireIds = listOfNotNull(old?.id),
                    ),
                )
            }
        }

        if (set != null && request.rotation != null) {
            val pattern = request.rotation
            val current = tires.filter { it.active && it.setId == set.id }
            val moves = current.sortedBy { it.position.ordinal }.map { t ->
                val to = pattern.destinationOf(t.position)
                store.saveTire(t.copy(position = to))
                mark(t.id, BaselineOrigin.ROTATION)
                TireMove(t.id, t.label, t.position, to)
            }
            tires = store.tires()
            val latestSet = store.sets().first { it.id == set.id }
            val km = Mileage.setKm(latestSet, store.mounts(), odo)
            store.saveSet(latestSet.copy(kmAtLastRotation = km))
            add(
                TireEvent(
                    type = EventType.ROTATION, atMillis = now, odoKm = odo, setId = set.id,
                    tireIds = current.map { it.id },
                    detail = "${pattern.label} · ${moves.joinToString(", ") { "${it.tireLabel} ${it.from.label}→${it.to.label}" }}",
                    rotation = RotationRecord(pattern, moves),
                ),
            )
        }

        if (repairTargets.isNotEmpty()) {
            repairTargets.forEach { mark(it.id, BaselineOrigin.REPAIR) }
            add(
                TireEvent(
                    type = EventType.REPAIR, atMillis = now, odoKm = odo, setId = c.mountedSet?.id,
                    tireIds = repairTargets.map { it.id },
                    detail = repairTargets.joinToString(", ") { it.display() },
                ),
            )
        }

        if (refillTargets.isNotEmpty()) {
            refillTargets.forEach { mark(it.id, BaselineOrigin.REFILL) }
            add(
                TireEvent(
                    type = EventType.REFILL, atMillis = now, odoKm = odo, setId = c.mountedSet?.id,
                    tireIds = refillTargets.map { it.id },
                    detail = if (refillTargets.size >= 4) "네 바퀴" else refillTargets.joinToString(", ") { it.display() },
                ),
            )
        }

        if (request.nutCheck) {
            add(TireEvent(type = EventType.NUT_CHECK, atMillis = now, odoKm = odo, setId = set?.id))
        }

        origins.forEach { (id, origin) ->
            store.saveBaseline(Baseline.pending(id, origin))
            store.saveTrendState(TrendState(id))
        }
        c.settings.refillQuestion?.let { question ->
            val activeIds = tires.filter { it.active && it.setId == set?.id }.map { it.id }.toSet()
            val remaining = question.tireIds.filter { it in activeIds && it !in origins }
            store.saveSettings(store.settings().copy(refillQuestion = remaining.takeIf { it.isNotEmpty() }?.let {
                question.copy(tireIds = it)
            }))
        }
        if (odo != null) store.saveDriveState(store.driveState().copy(lastKnownOdoKm = odo))
        return created
    }

    private suspend fun ensureTires(set: TireSet, tires: List<Tire>) {
        val existing = tires.filter { it.active && it.setId == set.id }
        val used = tires.filter { it.setId == set.id }.map { it.label }.toMutableSet()
        WheelPosition.entries.filter { p -> existing.none { it.position == p } }.forEach { position ->
            val label = LABELS.map { it.toString() }.first { it !in used }
            used += label
            store.saveTire(Tire(setId = set.id, label = label, position = position, dot = set.dot))
        }
    }

    private fun precedence(origin: BaselineOrigin): Int = when (origin) {
        BaselineOrigin.REPAIR -> 0
        BaselineOrigin.REPLACEMENT -> 1
        BaselineOrigin.REFILL -> 2
        BaselineOrigin.ROTATION -> 3
        BaselineOrigin.SET_CHANGE -> 4
        else -> 5
    }

    private fun validatePressure(front: Double?, rear: Double?) {
        require((front == null) == (rear == null)) { "앞·뒤 권장 공기압을 모두 입력해 주세요" }
        require(listOfNotNull(front, rear).all { it.isFinite() && it > 0 }) { "권장 공기압은 0보다 커야 합니다" }
    }

    private fun validateDot(dot: String?, now: Long) {
        if (dot == null) return
        val result = DotCode.parse(dot, today(now))
        require(result is DotCode.Companion.Result.Valid) {
            (result as DotCode.Companion.Result.Invalid).reason
        }
    }

    // endregion

    // region 알림

    private suspend fun mornings(now: Long): List<Pair<LocalDate, Double>> =
        store.measurementsSince(startOfDay(epochDay(now) - 6))
            .filter { it.cold && it.tempC != null }
            .groupBy { it.epochDay }
            .map { (day, list) -> LocalDate.ofEpochDay(day) to list.minBy { it.atMillis }.tempC!! }

    private suspend fun alertInputs(now: Long, odoKm: Double?, c: Ctx) = AlertInputs(
        nowMillis = now,
        zone = zone,
        today = today(now),
        dayResult = store.latestDayResult(),
        events = store.events(),
        tires = c.tires,
        sets = c.sets,
        mounts = c.mounts,
        odoKm = odoKm.takeIf { c.settings.odometerSupported },
        settings = c.settings,
        mornings = mornings(now),
        states = store.alertStates().associateBy { it.key },
    )

    suspend fun activeAlerts(now: Long, odoKm: Double?): List<ActiveAlert> = read {
        AlertPlanner.activeAlerts(alertInputs(now, odoKm, ctx()))
    }

    /** 정차(P단, 조작 제한 해제) 때 띄울 알림 하나. */
    suspend fun nextPopup(now: Long, odoKm: Double?): ActiveAlert? = read {
        val inputs = alertInputs(now, odoKm, ctx())
        val day = epochDay(now)
        AlertPlanner.pickPopup(
            AlertPlanner.activeAlerts(inputs),
            inputs.states,
            day,
            measuredToday = inputs.dayResult?.epochDay == day,
        )
    }

    suspend fun markShown(key: String, now: Long) = write {
        val state = store.alertStates().firstOrNull { it.key == key } ?: AlertState(key)
        store.saveAlertState(state.copy(lastShownDay = epochDay(now)))
    }

    suspend fun respond(alert: ActiveAlert, action: AlertAction, now: Long, odoKm: Double?) {
        when (action) {
            AlertAction.NUT_DONE -> checkIn(CheckInRequest(nutCheck = true), now, odoKm)
            AlertAction.CONFIRM -> write {
                val state = store.alertStates().firstOrNull { it.key == alert.key } ?: AlertState(alert.key)
                val day = epochDay(now)
                store.saveAlertState(
                    if (alert.type == AlertType.AGE || alert.type == AlertType.LIFE_DUE || alert.type == AlertType.LIFE_SOON) {
                        val delay = if (alert.type == AlertType.AGE) AlertPlanner.AGE_SNOOZE_DAYS else TireLife.SNOOZE_DAYS
                        state.copy(lastShownDay = day, snoozedUntilDay = day + delay)
                    } else {
                        state.copy(lastShownDay = day, acknowledged = true)
                    },
                )
            }
            else -> Unit
        }
    }

    // endregion

    // region 화면 스냅샷

    suspend fun dashboard(now: Long, live: LiveReadings = LiveReadings()): DashboardSnapshot = read {
        val c = ctx()
        val s = c.settings
        if (!s.onboardingDone) return@read DashboardSnapshot.empty(now)
        val inputs = alertInputs(now, live.odoKm, c)
        val alerts = AlertPlanner.activeAlerts(inputs)
        val latestCold = store.latestMeasurementPerTire(coldOnly = true).associateBy { it.tireId }
        val latestReadings = store.latestMeasurementPerTire(coldOnly = false).associateBy { it.tireId }
        val baselines = store.baselines().associateBy { it.tireId }
        val identityEvents = store.events().filter { it.type.wheelOff }
        val today = epochDay(now)

        val wheels = WheelPosition.entries.map { position ->
            val tire = c.byPosition[position]
            val cold = tire?.let { latestCold[it.id] }
            val rec = c.recommendedFor(position)
            val supported = s.pressureSupported && ((live.pressurePositions ?: s.pressurePositions)?.contains(position) != false)
            val line = when {
                !supported -> "이 바퀴의 공기압 미지원"
                cold == null -> "측정 대기"
                else -> buildString {
                    append("${Units.psiRounded(cold.kpa)}psi")
                    if (rec != null) {
                        append(" · 권장 ${Units.psiRounded(rec)}")
                        diffText(rec - cold.kpa)?.let { append(" · $it") }
                    } else {
                        append(" · 권장값 확인 필요")
                    }
                    append(" · ${TimeLabels.relative(cold.atMillis, now, zone)} 냉간")
                }
            }
            val liveKpa = live.kpaByPosition[position]
            val liveAt = if (live.pressureTimes.isEmpty()) live.atMillis else live.pressureTimes[position]
            val liveLine = if (supported && liveKpa != null && liveAt != null &&
                now - liveAt in 0..90_000 &&
                (cold == null || liveAt > cold.atMillis + 60_000)
            ) {
                "지금 ${Units.psiRounded(liveKpa)}psi · ${if (live.afterDriving) "주행 직후 값" else "참고값"}"
            } else {
                null
            }
            val state = when {
                !supported || cold == null -> WheelState.WAITING
                alerts.any { it.type == AlertType.LEAK && position in it.positions } -> WheelState.LEAK
                alerts.any { it.type == AlertType.REFILL_NEEDED && position in it.positions } -> WheelState.REFILL
                alerts.any { it.type == AlertType.RECHECK && position in it.positions } -> WheelState.RECHECK
                rec == null || today - cold.epochDay > 1 || baselines[tire?.id]?.isPending == true -> WheelState.WAITING
                Units.kpaToPsi(rec - cold.kpa).roundToInt() > 0 -> WheelState.LOW
                else -> WheelState.NORMAL
            }
            val identityAt = maxOf(c.mounts.filter { it.isActive }.maxOfOrNull { it.mountAtMillis } ?: 0L,
                identityEvents.filter { tire?.id in it.tireIds }.maxOfOrNull { it.atMillis } ?: 0L)
            val current = liveKpa?.takeIf { liveAt != null && liveAt > identityAt && now - liveAt in 0..90_000 }
            val last = tire?.let { latestReadings[it.id] }
            val measuredAt = if (current != null) liveAt else last?.atMillis
            val isCold = if (current != null) cold != null && liveAt != null && abs(liveAt - cold.atMillis) <= 60_000 else last?.cold == true
            WheelCard(
                position = position,
                tireId = tire?.id,
                tireLabel = tire?.label,
                psi = cold?.takeIf { supported }?.let { Units.psiRounded(it.kpa) },
                recommendedPsi = rec?.takeIf { supported }?.let { Units.psiRounded(it) },
                line = line,
                liveLine = liveLine,
                state = state,
                displayPsi = if (supported) current?.let(Units::psiRounded) ?: last?.let { Units.psiRounded(it.kpa) } else null,
                measuredLabel = measuredAt?.let { TimeLabels.relative(it, now, zone) } ?: "측정 대기",
                conditionLabel = when {
                    measuredAt == null -> "냉간 여부 확인 불가"
                    isCold -> "냉간 측정"
                    current != null && !live.afterDriving -> "냉간 여부 확인 불가"
                    else -> "비냉간 측정"
                },
                supported = supported,
                lowPressureKpa = live.lowPressureKpa[position].takeIf { supported },
            )
        }

        val day = inputs.dayResult
        val causeText = day?.takeIf { s.pressureSupported && today - it.epochDay <= 1 }?.let { result ->
            val label = if (result.epochDay == today) "오늘" else "어제"
            when (result.cause) {
                Cause.TEMPERATURE -> "$label 판단 · 기온 영향으로 보입니다(기준 ${tempText(result.baselineTempC)} → ${tempText(result.tempC)})"
                Cause.NATURAL -> "$label 판단 · 네 바퀴가 함께 조금씩 줄었습니다"
                Cause.LEAK -> alerts.firstOrNull { it.type == AlertType.LEAK }?.let { "$label 판단 · ${it.message.substringBefore(". ")}" }
                Cause.UNDETERMINED -> "$label 판단 · 원인 판단 보류(측정이 부족합니다)"
                Cause.NONE -> null
            }
        }

        val measuredDays = store.measurementsSince(startOfDay(today - 6)).filter { it.cold }.map { it.epochDay }.distinct().size
        val set = c.mountedSet
        val odo = inputs.odoKm
        val setKm = set?.let { Mileage.setKm(it, c.mounts, odo) }
        val rotationRemaining = if (set != null && s.odometerSupported) {
            Mileage.rotationProgressOrNull(set, c.mounts, odo, inputs.events)?.let { (s.rotationIntervalKm - it).roundToInt() }
        } else {
            null
        }

        val pending = mutableListOf<PendingItem>()
        alerts.forEach { pending += PendingItem(it.key, it.listText, it.type) }
        (Mileage.nutStatus(inputs.events, odo) as? Mileage.NutStatus.Pending)?.let {
            pending += PendingItem("NUT_PENDING", "너트 점검 · ${it.remainingKm.roundToInt()}km 뒤 안내", null)
        }
        if (rotationRemaining != null && rotationRemaining in 1..Mileage.ROTATION_NOTICE_BEFORE_KM.toInt()) {
            pending += PendingItem("ROTATION_SOON", "위치교환까지 ${AlertPlanner.formatKm(rotationRemaining.toDouble())}km", null)
        }
        val needsRecommended = s.pressureSupported && WheelPosition.entries.any { c.recommendedFor(it) == null }
        if (needsRecommended) pending += PendingItem("RECOMMENDED", "권장값 확인 필요", null)
        if (s.pressureSupported && latestCold.isEmpty()) {
            pending += PendingItem("WAITING", "첫 냉간 측정 대기 · 3시간 이상 세워 둔 뒤 출발하면 잽니다", null)
        }

        val question = s.refillQuestion?.takeIf { s.pressureSupported }?.let { q ->
            val positions = q.tireIds.mapNotNull { id -> c.activeTires.firstOrNull { it.id == id }?.position }
                .sortedBy { it.ordinal }
            val text = if (positions.size >= 4 || positions.isEmpty()) {
                "공기를 보충하셨나요?"
            } else {
                "${positions.joinToString("·") { it.label }}에 공기를 보충하셨나요?"
            }
            RefillQuestionView(text, positions)
        }

        val mountedLabel = set?.let {
            if (Mileage.distanceComplete(it, c.mounts, odo)) "${it.title} ${if (it.initialKmKnown) "" else "앱 기록 "}${AlertPlanner.formatKm(setKm ?: 0.0)}km"
            else "${it.title} · 거리 확인 필요"
        }
        val headline = headline(s.pressureSupported, wheels, alerts, mountedLabel, rotationRemaining, latestCold, now)

        DashboardSnapshot(
            nowMillis = now,
            onboardingDone = true,
            pressureSupported = s.pressureSupported,
            odometerSupported = s.odometerSupported,
            wheels = wheels,
            headline = headline,
            causeText = causeText,
            pending = pending,
            alerts = alerts,
            refillQuestion = question,
            measuredDaysLast7 = measuredDays,
            mountedSetLabel = mountedLabel,
            rotationRemainingKm = rotationRemaining,
            needsRecommended = needsRecommended,
            recommendedSourceLabel = if (set?.recommendedFrontKpa != null || set?.recommendedRearKpa != null) "타이어별 사용자 설정" else s.recommended?.source?.label,
        )
    }

    private fun headline(
        pressureSupported: Boolean,
        wheels: List<WheelCard>,
        alerts: List<ActiveAlert>,
        mountedLabel: String?,
        rotationRemaining: Int?,
        latestCold: Map<Long, Measurement>,
        now: Long,
    ): String {
        if (!pressureSupported) {
            val rotation = rotationRemaining?.let {
                if (it > 0) "위치교환까지 ${AlertPlanner.formatKm(it.toDouble())}km" else "위치교환 시기"
            }
            return listOfNotNull(rotation, mountedLabel).joinToString(" · ").ifEmpty { "tirelog" }
        }
        val leak = alerts.firstOrNull { it.type == AlertType.LEAK }
        val recheck = alerts.firstOrNull { it.type == AlertType.RECHECK }
        return when {
            leak != null -> "${leak.positions.firstOrNull()?.label ?: ""} 점검 권장 · 정차 후 안내".trim()
            alerts.any { it.type == AlertType.REFILL_NEEDED } -> "공기압 부족 · 정차 후 안내"
            recheck != null -> "${recheck.positions.firstOrNull()?.label ?: ""} 재점검 권장 · 정차 후 안내".trim()
            wheels.all { it.psi == null } -> "공기압 측정 대기"
            else -> {
                val latest = latestCold.values.maxByOrNull { it.atMillis }
                val values = "공기압 " + wheels.joinToString("·") { it.psi?.toString() ?: "-" } + "psi"
                val tail = if (alerts.isNotEmpty()) {
                    "정차 후 안내 ${alerts.size}건"
                } else {
                    latest?.let { "${TimeLabels.relative(it.atMillis, now, zone)} 냉간" }
                }
                listOfNotNull(values, tail).joinToString(" · ")
            }
        }
    }

    /** 공기압 추이(MVP 추가). [setId]가 없으면 지금 끼운 세트를 그린다. */
    suspend fun trend(now: Long, days: Int, setId: Long? = null): TrendData =
        trendRange(now, now - days.coerceIn(1, 3650) * DAY_MILLIS, now, setId)

    suspend fun trendRange(now: Long, from: Long, to: Long, setId: Long? = null): TrendData = read {
        require(from <= to && to <= now) { "조회 기간을 확인해 주세요" }
        val c = ctx()
        val cold = store.measurementsSince(from).filter { it.cold && it.atMillis <= to }
        val targetSet = setId ?: c.mountedSet?.id
        val setTires = c.tires.filter { it.setId == targetSet && (it.active || cold.any { m -> m.tireId == it.id }) }
        val ids = setTires.map { it.id }.toSet()
        val mounts = c.mounts.filter { it.setId == targetSet }.sortedBy { it.mountAtMillis }
        fun segmentOf(m: Measurement) = mounts.lastOrNull { it.mountAtMillis <= m.atMillis &&
            (it.unmountAtMillis == null || m.atMillis <= it.unmountAtMillis) }?.id ?: 0
        val series = setTires.sortedWith(compareBy({ !it.active }, { it.position.ordinal })).map { tire ->
            TrendSeries(
                tireId = tire.id,
                // 현재 타이어는 위치만으로 구분한다. 같은 위치의 과거 타이어를 함께 볼 때만 식별명을 남긴다.
                label = tire.position.label + if (!tire.active) " · 타이어 ${tire.label} (이전)" else "",
                points = cold.filter { it.tireId == tire.id }.sortedBy { it.atMillis }
                    .map { m -> TrendPoint(m.atMillis, Units.kpaToPsi(m.kpa), segmentOf(m), m.position) },
                active = tire.active,
                colorIndex = tire.colorIndex,
            )
        }
        val temperatures = cold.filter { it.tireId in ids }.groupBy { it.epochDay }
            .mapNotNull { (_, list) -> list.minBy { it.atMillis }.let { m -> m.tempC?.let { TrendPoint(m.atMillis, it, segmentOf(m)) } } }
            .sortedBy { it.atMillis }
        val marks = store.events()
            .filter { it.atMillis in from..to && it.type != EventType.NUT_CHECK && (it.setId == null || it.setId == targetSet) }
            .groupBy { epochDay(it.atMillis) }
            .map { (_, list) -> TrendMark(list.first().atMillis, list.map { it.type.label }.distinct().joinToString("·")) }
        val today = epochDay(now)
        val measuredDays = store.measurementsSince(startOfDay(today - 6)).filter { it.cold }.map { it.epochDay }.distinct().size
        TrendData(from, to, series, temperatures, marks, measuredDays)
    }

    suspend fun mechanicCard(now: Long, odoKm: Double?): MechanicCard = read {
        val c = ctx()
        val set = c.mountedSet
        val odo = odoKm ?: store.driveState().lastKnownOdoKm
        val header = set?.let {
            val km = if (Mileage.distanceComplete(it, c.mounts, odoKm)) "${AlertPlanner.formatKm(Mileage.setKm(it, c.mounts, odo))}km" else "거리 확인 필요"
            listOfNotNull("${it.title} $km", it.size, it.dot?.let { d -> "DOT $d" }).joinToString(" · ")
        } ?: "장착 세트 없음"
        val cold = store.measurementsSince(startOfDay(epochDay(now) - 13)).filter { it.cold }
        val day = store.latestDayResult()
        val events = store.events()
        val alerts = AlertPlanner.activeAlerts(alertInputs(now, odo, c))
        val rows = c.activeTires.sortedBy { it.position.ordinal }.map { tire ->
            val recent = cold.filter { it.tireId == tire.id }.groupBy { it.epochDay }
                .map { it.value.minBy { m -> m.atMillis } }.sortedBy { it.atMillis }.takeLast(7)
                .map { Units.psiRounded(it.kpa) }
            val status = day?.tires?.firstOrNull { it.tireId == tire.id }
            val trendText = status?.let { AlertPlanner.lossText(it, day) }?.takeIf { it.isNotBlank() }
            fun lastOf(type: EventType) = events.lastOrNull { it.type == type && tire.id in it.tireIds }
                ?.let { "${TimeLabels.monthDay(it.atMillis, zone)}${it.odoKm?.let { o -> " · ${AlertPlanner.formatKm(o)}km" } ?: ""}" }
            val flag = when {
                alerts.any { it.type == AlertType.LEAK && tire.position in it.positions } -> "누설 의심"
                alerts.any { it.type == AlertType.RECHECK && tire.position in it.positions } -> "재점검 필요"
                alerts.any { it.type == AlertType.REFILL_NEEDED && tire.position in it.positions } -> "보충 필요"
                else -> null
            }
            MechanicTireRow(tire.position, tire.label, recent, trendText, lastOf(EventType.REFILL), lastOf(EventType.REPAIR), flag)
        }
        val rotation = events.lastOrNull { it.type == EventType.ROTATION && it.setId == set?.id }?.let {
            "마지막 위치교환 ${TimeLabels.monthDay(it.atMillis, zone)}${it.odoKm?.let { o -> " · ${AlertPlanner.formatKm(o)}km" } ?: ""}"
        } ?: "위치교환 기록 없음(앱 사용 전 기록 없음)"
        val recentEvents = events.takeLast(5).reversed().map { historyLine(it) }
        MechanicCard(header, rows, rotation, recentEvents)
    }

    suspend fun setsOverview(now: Long, odoKm: Double?): SetsOverview = read {
        val c = ctx()
        val odo = odoKm ?: store.driveState().lastKnownOdoKm
        val todayDate = today(now)
        val events = store.events()
        val rows = c.sets
            .sortedWith(compareBy<TireSet>({ it.retired }, { it.id != c.mountedSet?.id }, { it.createdAtMillis }))
            .map { set ->
                val mounted = set.id == c.mountedSet?.id
                val code = DotCode.parseOrNull(set.dot, todayDate)
                val rotationText = if (mounted && c.settings.odometerSupported) {
                    val remaining = Mileage.rotationProgressOrNull(set, c.mounts, odoKm, events)?.let { c.settings.rotationIntervalKm - it }
                    when {
                        remaining == null -> "위치교환 거리 확인 필요"
                        remaining > 0 -> "위치교환까지 ${AlertPlanner.formatKm(remaining)}km"
                        else -> "위치교환 시기 · ${AlertPlanner.formatKm(-remaining)}km 지남"
                    }
                } else {
                    null
                }
                SetRow(
                    set = set,
                    km = Mileage.setKm(set, c.mounts, odo).roundToInt(),
                    status = when {
                        set.retired -> "폐기"
                        mounted -> "장착 중"
                        else -> "보관"
                    },
                    mounted = mounted,
                    dotText = code?.let { "DOT ${it.text} · ${it.display}" },
                    olderThanSixYears = c.tires.any { it.active && it.setId == set.id &&
                        DotCode.parseOrNull(it.dot, todayDate)?.isOlderThan(DotCode.AGE_ALERT_YEARS, todayDate) == true },
                    rotationText = rotationText,
                    tireLife = c.tires.filter { it.active && it.setId == set.id }.sortedBy { it.position.ordinal }.map {
                        TireLife.evaluate(set, it, c.mounts, events, odoKm, now, zone)
                    },
                    canDelete = !mounted && c.mounts.none { it.setId == set.id } && events.none { it.setId == set.id },
                    previousTireLife = c.tires.filter { !it.active && it.setId == set.id }.map {
                        TireLife.evaluate(set, it, c.mounts, events, odo, now, zone)
                    },
                    distanceComplete = Mileage.distanceComplete(set, c.mounts, odoKm),
                )
            }
        val history = store.events().reversed().take(30).map { historyLine(it) }
        SetsOverview(rows, history, c.settings.rotationIntervalKm)
    }

    suspend fun sets(): List<TireSet> = read { store.sets() }

    suspend fun tires(): List<Tire> = read { store.tires() }

    suspend fun latestReadings(): List<Measurement> = read { store.latestMeasurementPerTire(false) }

    suspend fun mountedTires(): Map<WheelPosition, Tire> = read { ctx().byPosition }

    suspend fun mountedSet(): TireSet? = read { ctx().mountedSet }

    suspend fun rotationHistory(): RotationHistorySnapshot = read {
        val c = ctx()
        rotationHistorySnapshot(c.mountedSet, c.sets, c.activeTires, store.events(), zone)
    }

    // endregion

    private fun historyLine(event: TireEvent): String {
        val date = TimeLabels.monthDay(event.atMillis, zone)
        val parts = listOfNotNull(
            date,
            event.type.label,
            event.detail,
            event.odoKm?.let { "${AlertPlanner.formatKm(it)}km" },
        )
        return parts.joinToString(" · ")
    }

    private fun describeTires(ids: List<Long>, tires: List<Tire>): String {
        val list = ids.mapNotNull { id -> tires.firstOrNull { it.id == id } }
        return if (list.size >= 4) "네 바퀴" else list.joinToString(", ") { it.display() }
    }

    private fun diffText(diffKpa: Double): String? {
        val psi = Units.kpaToPsi(diffKpa).roundToInt()
        return when {
            psi > 0 -> "${psi}psi 부족"
            psi < 0 -> "${-psi}psi 높음"
            else -> null
        }
    }

    private fun tempText(tempC: Double?): String {
        if (tempC == null) return "-"
        val v = tempC.roundToInt()
        return if (v < 0) "−${abs(v)}℃" else "${v}℃"
    }

    companion object {
        const val DAY_MILLIS = 86_400_000L
        private const val LABELS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    }
}
