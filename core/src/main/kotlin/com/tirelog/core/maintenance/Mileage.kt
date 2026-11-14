package com.tirelog.core.maintenance

import com.tirelog.core.model.EventType
import com.tirelog.core.model.Mount
import com.tirelog.core.model.TireEvent
import com.tirelog.core.model.TireSet

object Mileage {
    const val NUT_CHECK_KM = 50.0
    const val ROTATION_NOTICE_BEFORE_KM = 1_000.0

    /** 세트 누적 km = 앱 사용 전 km + 장착 구간 ODO 차이의 합. */
    fun setKm(set: TireSet, mounts: List<Mount>, currentOdoKm: Double?): Double =
        set.initialKm + mounts.filter { it.setId == set.id }.sumOf { m ->
            val start = m.mountOdoKm ?: return@sumOf 0.0
            val end = if (m.isActive) currentOdoKm ?: start else m.unmountOdoKm ?: start
            (end - start).coerceAtLeast(0.0)
        }

    fun rotationProgressKm(set: TireSet, mounts: List<Mount>, currentOdoKm: Double?): Double =
        setKm(set, mounts, currentOdoKm) - set.kmAtLastRotation

    fun distanceComplete(set: TireSet, mounts: List<Mount>, currentOdoKm: Double?): Boolean =
        mounts.filter { it.setId == set.id }.all { m ->
            val end = if (m.isActive) currentOdoKm else m.unmountOdoKm
            m.odoGapAtMillis == null && m.mountOdoKm != null && end != null && end.isFinite() && end >= m.mountOdoKm
        }

    /** 마지막 위치교환 뒤의 확인된 구간만 센다. 그 전 공백은 새 위치교환 주기에 영향을 주지 않는다. */
    fun rotationProgressOrNull(set: TireSet, mounts: List<Mount>, currentOdoKm: Double?, events: List<TireEvent>): Double? {
        val rotation = events.lastOrNull { it.type == EventType.ROTATION && it.setId == set.id }
            ?: return if (distanceComplete(set, mounts, currentOdoKm)) rotationProgressKm(set, mounts, currentOdoKm) else null
        val startOdo = rotation.odoKm ?: return null
        var total = 0.0
        for (m in mounts.filter { it.setId == set.id && (it.unmountAtMillis == null || it.unmountAtMillis > rotation.atMillis) }) {
            if (m.odoGapAtMillis?.let { it > rotation.atMillis } == true) return null
            val from = if (m.mountAtMillis <= rotation.atMillis) startOdo else m.mountOdoKm ?: return null
            val to = (if (m.isActive) currentOdoKm else m.unmountOdoKm) ?: return null
            if (!to.isFinite() || to < from) return null
            total += to - from
        }
        return total
    }

    sealed interface NutStatus {
        val workEvent: TireEvent

        data class Pending(override val workEvent: TireEvent, val remainingKm: Double) : NutStatus
        data class Due(override val workEvent: TireEvent) : NutStatus
    }

    /** 교체·위치교환 뒤 50km를 달렸는데 너트 점검 기록이 없으면 점검 대상이다. */
    fun nutStatus(events: List<TireEvent>, currentOdoKm: Double?): NutStatus? {
        val work = events
            .filter { it.type.wheelOff }
            .maxByOrNull { it.atMillis } ?: return null
        val checked = events.any { it.type == EventType.NUT_CHECK && it.atMillis >= work.atMillis }
        val workOdo = work.odoKm ?: return null
        if (checked || currentOdoKm == null || currentOdoKm < workOdo) return null
        val due = workOdo + NUT_CHECK_KM
        return if (currentOdoKm >= due) NutStatus.Due(work) else NutStatus.Pending(work, due - currentOdoKm)
    }
}
