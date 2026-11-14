package com.tirelog.core.maintenance

import com.tirelog.core.model.EventType
import com.tirelog.core.model.Mount
import com.tirelog.core.model.Tire
import com.tirelog.core.model.TireEvent
import com.tirelog.core.model.TireSet
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** 거리·달력 기간에 따른 점검 예약 기준. 실제 마모량이나 안전 잔여 수명을 예측하지 않는다. */
object TireLife {
    const val NOTICE_RATIO = 0.9
    const val NOTICE_DAYS = 90L
    const val SNOOZE_DAYS = 30L
    private const val DAY_MILLIS = 86_400_000L

    enum class Stage { TRACKING, SOON, DUE }

    data class Status(
        val tire: Tire,
        val recordedKm: Double?,
        val recordedMountedDays: Long,
        val firstUsed: LocalDate?,
        val elapsedMonths: Long?,
        val remainingKm: Double?,
        val remainingDays: Long?,
        val stage: Stage,
        val mileageStage: Stage,
        val timeStage: Stage,
        val totalKmKnown: Boolean = true,
    )

    fun evaluate(
        set: TireSet,
        tire: Tire,
        mounts: List<Mount>,
        events: List<TireEvent>,
        odoKm: Double?,
        now: Long,
        zone: ZoneId,
    ): Status {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val replacement = events.filter {
            it.type == EventType.TIRE_REPLACEMENT && tire.id in it.tireIds && it.atMillis <= now
        }.maxByOrNull { it.atMillis }
        val start = replacement?.atMillis
        val profile = tire.profile
        val firstUsed = if (profile != null) profile.firstUsedEpochDay?.let(LocalDate::ofEpochDay) else replacement?.let { Instant.ofEpochMilli(it.atMillis).atZone(zone).toLocalDate() }
            ?: set.firstUsedEpochDay?.let(LocalDate::ofEpochDay)
        val kmKnown = profile?.initialKmKnown ?: if (replacement == null) set.initialKmKnown else true
        val inspectionKm = profile?.inspectionKm ?: set.inspectionKm
        val inspectionMonths = profile?.inspectionMonths ?: set.inspectionMonths
        var km = profile?.initialKm ?: if (replacement == null) set.initialKm else 0.0
        var complete = tire.active || tire.removedAtMillis != null
        var mountedMillis = 0L
        val mountedUntil = minOf(now, tire.removedAtMillis ?: now)
        mounts.filter { it.setId == set.id && it.mountAtMillis <= mountedUntil }.forEach { mount ->
            val endTime = minOf(mount.unmountAtMillis ?: mountedUntil, mountedUntil)
            val startTime = maxOf(mount.mountAtMillis, start ?: mount.mountAtMillis)
            if (endTime < startTime || (start != null && !mount.isActive && endTime <= start)) return@forEach
            if (mount.odoGapAtMillis?.let { gap -> gap in startTime..endTime && (start == null || gap > start) } == true) complete = false
            mountedMillis += (endTime - startTime).coerceAtLeast(0)
            val from = if (start != null && start >= mount.mountAtMillis) replacement.odoKm else mount.mountOdoKm
            val to = if (tire.removedAtMillis != null && endTime == tire.removedAtMillis) tire.removedOdoKm
                else if (mount.isActive) odoKm else mount.unmountOdoKm
            if (from == null || to == null || !from.isFinite() || !to.isFinite() || to < from) {
                complete = false
            } else {
                km += to - from
            }
        }
        val recordedKm = km.takeIf { complete }
        val remainingKm = recordedKm?.takeIf { kmKnown }?.let { inspectionKm - it }
        val remainingDays = firstUsed?.let {
            ChronoUnit.DAYS.between(today, it.plusMonths(inspectionMonths.toLong()))
        }
        val mileageStage = when {
            recordedKm == null -> Stage.TRACKING
            recordedKm >= inspectionKm -> Stage.DUE
            recordedKm >= inspectionKm * NOTICE_RATIO -> Stage.SOON
            else -> Stage.TRACKING
        }
        val timeStage = when {
            remainingDays == null -> Stage.TRACKING
            remainingDays <= 0 -> Stage.DUE
            remainingDays <= NOTICE_DAYS -> Stage.SOON
            else -> Stage.TRACKING
        }
        // 2/29 → 2/28처럼 월말이 보정된 기념일에도 도달 판정과 표시 개월 수를 맞춘다.
        val elapsedMonths = firstUsed?.let {
            val months = ChronoUnit.MONTHS.between(it.withDayOfMonth(1), today.withDayOfMonth(1)).coerceAtLeast(0)
            if (it.plusMonths(months).isAfter(today)) (months - 1).coerceAtLeast(0) else months
        }
        return Status(
            tire, recordedKm, mountedMillis / DAY_MILLIS, firstUsed,
            elapsedMonths,
            remainingKm, remainingDays, maxOf(mileageStage, timeStage), mileageStage, timeStage, kmKnown,
        )
    }
}
