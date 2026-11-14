package com.tirelog.core.alert

import com.tirelog.core.Units
import com.tirelog.core.maintenance.DotCode
import com.tirelog.core.maintenance.Mileage
import com.tirelog.core.maintenance.TireLife
import com.tirelog.core.judge.JudgmentRules
import com.tirelog.core.model.AlertState
import com.tirelog.core.model.AlertType
import com.tirelog.core.model.AppSettings
import com.tirelog.core.model.BaselineOrigin
import com.tirelog.core.model.Cause
import com.tirelog.core.model.DayResult
import com.tirelog.core.model.EventType
import com.tirelog.core.model.Mount
import com.tirelog.core.model.Tire
import com.tirelog.core.model.TireDayStatus
import com.tirelog.core.model.TireEvent
import com.tirelog.core.model.TireSet
import com.tirelog.core.model.WheelPosition
import com.tirelog.core.season.Region
import com.tirelog.core.season.SeasonAdvisor
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlin.math.roundToLong

enum class AlertAction(val label: String) {
    NAVIGATE("타이어점 길안내"),
    DETAILS("자세히"),
    LATER("나중에"),
    NUT_DONE("점검했어요"),
    RECORD_ROTATION("기록하기"),
    CONFIRM("확인"),
}

data class ActiveAlert(
    val key: String,
    val type: AlertType,
    val message: String,
    val detail: String? = null,
    val positions: List<WheelPosition> = emptyList(),
    val actions: List<AlertAction>,
    val listText: String,
) {
    /** 음성으로는 첫 문장만 읽는다. */
    val speech: String get() = message.split(". ").first().trimEnd('.') + "."
}

data class AlertInputs(
    val today: LocalDate,
    val dayResult: DayResult?,
    val events: List<TireEvent>,
    val tires: List<Tire>,
    val sets: List<TireSet>,
    val mounts: List<Mount>,
    val odoKm: Double?,
    val settings: AppSettings,
    /** 날짜별 아침 외기온도(그날 첫 냉간 측정). */
    val mornings: List<Pair<LocalDate, Double>>,
    val states: Map<String, AlertState>,
    val nowMillis: Long,
    val zone: ZoneId,
)

object AlertPlanner {
    const val AGE_SNOOZE_DAYS = 30L

    private val resettingEvents = setOf(
        EventType.REFILL, EventType.REPAIR, EventType.ROTATION, EventType.TIRE_REPLACEMENT,
        EventType.SET_CHANGE, EventType.UNRECORDED_RISE,
    )

    fun activeAlerts(inputs: AlertInputs): List<ActiveAlert> {
        val alerts = mutableListOf<ActiveAlert>()
        val mountedSet = mountedSet(inputs.sets, inputs.mounts)
        val activeTires = inputs.tires.filter { it.active && it.setId == mountedSet?.id && inputs.settings.pressurePositions?.contains(it.position) != false }
        if (inputs.settings.pressureSupported) {
            inputs.dayResult?.let { alerts += measurementAlerts(it, inputs, activeTires) }
        }
        nutAlert(inputs)?.let { alerts += it }
        if (mountedSet != null) rotationAlert(mountedSet, inputs)?.let { alerts += it }
        seasonAlert(inputs, mountedSet)?.let { alerts += it }
        alerts += ageAlerts(inputs)
        alerts += lifeAlerts(inputs)
        return alerts
            .filterNot { inputs.states[it.key]?.acknowledged == true }
            .sortedWith(compareBy({ it.type.priority }, { it.type.ordinal }, { it.key }))
    }

    /** 정차 때 띄울 알림 하나. 측정 기반 알림은 그날 측정이 있을 때만 띄운다. */
    fun pickPopup(
        alerts: List<ActiveAlert>,
        states: Map<String, AlertState>,
        todayEpochDay: Long,
        measuredToday: Boolean,
    ): ActiveAlert? = alerts.firstOrNull { alert ->
        val state = states[alert.key]
        val snoozedUntil = state?.snoozedUntilDay
        state?.acknowledged != true &&
            state?.lastShownDay != todayEpochDay &&
            (snoozedUntil == null || todayEpochDay >= snoozedUntil) &&
            (!alert.type.measurementBased || measuredToday)
    }

    fun mountedSet(sets: List<TireSet>, mounts: List<Mount>): TireSet? {
        val mount = mounts.filter { it.isActive }.maxByOrNull { it.mountAtMillis } ?: return null
        return sets.firstOrNull { it.id == mount.setId }
    }

    private fun measurementAlerts(result: DayResult, inputs: AlertInputs, activeTires: List<Tire>): List<ActiveAlert> {
        val activeIds = activeTires.map { it.id }.toSet()
        val later = inputs.events.filter { it.atMillis > result.computedAtMillis && it.type in resettingEvents }
        fun resolved(tireId: Long) = tireId !in activeIds || later.any { tireId in it.tireIds }
        fun positionOf(status: TireDayStatus) = activeTires.firstOrNull { it.id == status.tireId }?.position ?: status.position

        val out = mutableListOf<ActiveAlert>()
        val mounted = mountedSet(inputs.sets, inputs.mounts)
        val open = result.tires.filterNot { resolved(it.tireId) }.map { status ->
            val position = positionOf(status)
            val recommended = (if (position.isFront) mounted?.recommendedFrontKpa else mounted?.recommendedRearKpa)
                ?: inputs.settings.recommended?.forPosition(position)
            status.copy(
                recommendedKpa = recommended,
                needsRefill = recommended != null && status.kpa <= recommended * JudgmentRules.REFILL_NEEDED_RATIO + 1e-9,
            )
        }

        open.filter { it.leakSuspected }.forEach { status ->
            val position = positionOf(status)
            val shortfall = if (status.needsRefill) shortfallText(status)?.let { " · $it · 보충 필요" } ?: "" else ""
            out += ActiveAlert(
                key = "LEAK:${status.tireId}",
                type = AlertType.LEAK,
                message = "${position.subject} 다른 바퀴보다 빨리 빠집니다. 점검을 권합니다.",
                detail = lossText(status, result) + shortfall,
                positions = listOf(position),
                actions = listOf(AlertAction.NAVIGATE, AlertAction.DETAILS, AlertAction.LATER),
                listText = "누설 의심 · ${position.label}",
            )
        }

        val refill = open.filter { it.needsRefill }
        if (refill.isNotEmpty()) {
            val positions = refill.map(::positionOf).sortedBy { it.ordinal }
            val allFour = positions.size >= 4
            val prefix = when (result.cause) {
                Cause.TEMPERATURE -> "기온이 내려가 압력이 낮아진 것으로 보입니다. "
                Cause.NATURAL -> "네 바퀴가 함께 조금씩 줄었습니다. "
                else -> ""
            }
            val body = when {
                allFour -> "권장값보다 낮아 보충이 필요합니다."
                positions.size == 1 -> "${positions.first().label} ${shortfallText(refill.first()) ?: "부족"}. 공기를 채워 주세요."
                else -> "${positions.joinToString("·") { it.label }} 공기압이 권장값보다 낮습니다. 공기를 채워 주세요."
            }
            val listText = if (allFour) "보충 필요 · 네 바퀴" else {
                "보충 필요 · " + refill.joinToString(", ") { "${positionOf(it).label} ${shortfallText(it) ?: ""}".trim() }
            }
            out += ActiveAlert(
                key = "REFILL_NEEDED",
                type = AlertType.REFILL_NEEDED,
                message = prefix + body,
                detail = if (result.cause == Cause.UNDETERMINED) Cause.UNDETERMINED.label else null,
                positions = positions,
                actions = listOf(AlertAction.DETAILS, AlertAction.LATER),
                listText = listText,
            )
        }

        open.filter { it.recheckNeeded }.forEach { status ->
            val position = positionOf(status)
            val action = if (status.baselineOrigin == BaselineOrigin.REPAIR) "수리" else "보충"
            out += ActiveAlert(
                key = "RECHECK:${status.tireId}",
                type = AlertType.RECHECK,
                message = "$action 후에도 ${position.subject} 계속 빠집니다. 점검을 권합니다.",
                detail = lossText(status, result),
                positions = listOf(position),
                actions = listOf(AlertAction.NAVIGATE, AlertAction.LATER),
                listText = "재점검 필요 · ${position.label}",
            )
        }
        return out
    }

    private fun nutAlert(inputs: AlertInputs): ActiveAlert? {
        if (!inputs.settings.odometerSupported) return null
        val status = Mileage.nutStatus(inputs.events, inputs.odoKm) as? Mileage.NutStatus.Due ?: return null
        return ActiveAlert(
            key = "NUT:${status.workEvent.id}",
            type = AlertType.NUT_CHECK,
            message = "타이어 작업 뒤 50km를 달렸습니다. 휠 너트 조임을 점검하세요.",
            actions = listOf(AlertAction.NUT_DONE, AlertAction.LATER),
            listText = "너트 점검 · 작업 뒤 50km 지남",
        )
    }

    private fun rotationAlert(set: TireSet, inputs: AlertInputs): ActiveAlert? {
        if (!inputs.settings.odometerSupported) return null
        val progress = Mileage.rotationProgressOrNull(set, inputs.mounts, inputs.odoKm, inputs.events) ?: return null
        val interval = inputs.settings.rotationIntervalKm.toDouble()
        if (progress < interval) return null
        val over = (progress - interval).roundToLong()
        return ActiveAlert(
            key = "ROTATION:${set.id}:${set.kmAtLastRotation.roundToLong()}",
            type = AlertType.ROTATION,
            message = "위치교환 시기입니다(지난 교환 뒤 ${formatKm(progress)}km).",
            actions = listOf(AlertAction.RECORD_ROTATION, AlertAction.LATER),
            listText = if (over > 0) "위치교환 시기 · ${formatKm(over.toDouble())}km 지남" else "위치교환 시기",
        )
    }

    private fun seasonAlert(inputs: AlertInputs, mountedSet: TireSet?): ActiveAlert? {
        val notice = SeasonAdvisor.evaluate(
            today = inputs.today,
            region = Region.fromCode(inputs.settings.regionCode),
            mountedType = mountedSet?.type,
            mornings = inputs.mornings,
        ) ?: return null
        if (notice.key.endsWith(":PREVIEW")) {
            val measuredKey = notice.key.removeSuffix(":PREVIEW") + if (notice.targetType == com.tirelog.core.model.TireSetType.WINTER) ":COLD" else ":WARM"
            if (inputs.states[measuredKey]?.acknowledged == true) return null
        }
        return ActiveAlert(
            key = notice.key,
            type = AlertType.SEASON,
            message = notice.message,
            actions = listOf(AlertAction.CONFIRM),
            listText = "시즌 교체 · ${notice.targetType.label} 세트",
        )
    }

    private fun ageAlerts(inputs: AlertInputs): List<ActiveAlert> = inputs.sets
        .filterNot { it.retired }
        .mapNotNull { set ->
            val aged = inputs.tires.filter { it.active && it.setId == set.id }.mapNotNull tireCode@{ tire ->
                val code = DotCode.parseOrNull(tire.dot, inputs.today) ?: return@tireCode null
                if (code.isOlderThan(DotCode.AGE_ALERT_YEARS, inputs.today)) tire to code else null
            }
            if (aged.isEmpty()) return@mapNotNull null
            ActiveAlert(
                key = "AGE:${set.id}:${aged.map { it.first.id }.sorted().joinToString(",")}",
                type = AlertType.AGE,
                message = (if (aged.size == 4) set.title else "${set.title} 타이어 ${aged.joinToString("·") { it.first.label }}") +
                    "가 제조 6년을 넘었습니다. 타이어 점검을 권합니다.",
                detail = aged.joinToString(" / ") { "${it.first.label}: DOT ${it.second.text} · ${it.second.display} 제조" },
                actions = listOf(AlertAction.CONFIRM),
                listText = "제조 6년 지남 · ${set.title}",
            )
        }

    private fun lifeAlerts(inputs: AlertInputs): List<ActiveAlert> = inputs.sets
        .filterNot { it.retired }.flatMap { set ->
            val statuses = inputs.tires.filter { it.active && it.setId == set.id }.map {
                TireLife.evaluate(set, it, inputs.mounts, inputs.events,
                    inputs.odoKm.takeIf { inputs.settings.odometerSupported }, inputs.nowMillis, inputs.zone)
            }.filter { it.stage != TireLife.Stage.TRACKING }
            statuses.groupBy { it.stage }.map { (stage, tires) ->
                val due = stage == TireLife.Stage.DUE
                val type = if (due) AlertType.LIFE_DUE else AlertType.LIFE_SOON
                val reasons = tires.sortedBy { it.tire.id }.joinToString(",") {
                    "${it.tire.id}-${it.mileageStage}-${it.timeStage}-${it.tire.profile?.inspectionKm ?: set.inspectionKm}-${it.tire.profile?.inspectionMonths ?: set.inspectionMonths}"
                }
                val labels = tires.joinToString("·") { it.tire.label }
                ActiveAlert(
                    key = "LIFE:${set.id}:${set.inspectionKm}:${set.inspectionMonths}:$reasons",
                    type = type,
                    message = if (due) "${set.title} 타이어 $labels 교체 점검 시기입니다. 마모와 손상을 확인하고 교체 여부를 점검하세요."
                        else "${set.title} 타이어 $labels 교체 점검 시기가 다가옵니다. 미리 점검을 예약하세요.",
                    detail = tires.joinToString(" / ") { status ->
                        val distance = status.recordedKm?.let { "${if (status.totalKmKnown) "누적" else "앱 기록"} ${formatKm(it)}km" } ?: "거리 확인 필요"
                        val time = status.elapsedMonths?.let { "사용 ${it}개월(보관 포함)" } ?: "첫 사용일 미입력"
                        val km = status.tire.profile?.inspectionKm ?: set.inspectionKm
                        val months = status.tire.profile?.inspectionMonths ?: set.inspectionMonths
                        "${status.tire.label}: $distance · $time · 점검 기준 ${formatKm(km.toDouble())}km / ${months}개월"
                    } + " · 실제 잔여 수명은 아닙니다.",
                    positions = if (set.id == mountedSet(inputs.sets, inputs.mounts)?.id) tires.map { it.tire.position } else emptyList(),
                    actions = listOf(AlertAction.NAVIGATE, AlertAction.CONFIRM),
                    listText = "${type.title} · ${set.title} $labels",
                )
            }
        }

    fun shortfallText(status: TireDayStatus): String? {
        val rec = status.recommendedKpa ?: return null
        val psi = Units.kpaToPsi(rec - status.kpa).roundToInt()
        return if (psi > 0) "${psi}psi 부족" else null
    }

    /** "보충 뒤 3일 −12kPa(약 1.7psi) · 다른 바퀴 평균 −1kPa" */
    fun lossText(status: TireDayStatus, result: DayResult): String {
        val loss = status.correctedLossKpa ?: return ""
        val days = status.baselineEpochDay?.let { result.epochDay - it } ?: 0
        val prefix = when (status.baselineOrigin) {
            BaselineOrigin.REFILL -> "보충 뒤 ${days}일"
            BaselineOrigin.REPAIR -> "수리 뒤 ${days}일"
            else -> "최근 ${days}일"
        }
        val others = result.tires.filter { it.tireId != status.tireId }.mapNotNull { it.correctedLossKpa }
        val othersText = if (others.isEmpty()) "" else " · 다른 바퀴 평균 ${signedLoss(others.average())}"
        return "$prefix ${signedLoss(loss)}(약 ${Units.psiOneDecimal(kotlin.math.abs(loss))}psi)$othersText"
    }

    private fun signedLoss(lossKpa: Double): String {
        val v = lossKpa.roundToInt()
        return when {
            v > 0 -> "−${v}kPa"
            v < 0 -> "+${-v}kPa"
            else -> "0kPa"
        }
    }

    fun formatKm(km: Double): String = String.format(java.util.Locale.KOREA, "%,d", km.roundToLong())
}
