package com.tirelog.core.engine

import com.tirelog.core.TimeLabels
import com.tirelog.core.model.EventType
import com.tirelog.core.model.RotationPattern
import com.tirelog.core.model.Tire
import com.tirelog.core.model.TireEvent
import com.tirelog.core.model.TireMove
import com.tirelog.core.model.TireSet
import java.time.ZoneId

data class RotationHistoryEntry(
    val eventId: Long,
    val setId: Long?,
    val setLabel: String,
    val dateLabel: String,
    val odoKm: Double?,
    val pattern: RotationPattern?,
    val moves: List<TireMove>,
    val detail: String?,
    /** null이면 다른 세트이거나 당시 타이어 ID를 확인할 수 없다. */
    val sameTires: Boolean?,
)

data class RotationHistorySnapshot(
    val mountedSetId: Long?,
    val mountedSetLabel: String?,
    /** 최근 30건 정비 요약과 독립적으로 모든 위치교환을 최신순으로 조회한다. */
    val entries: List<RotationHistoryEntry>,
) {
    val latestForMountedSet: RotationHistoryEntry?
        get() = mountedSetId?.let { id -> entries.firstOrNull { it.setId == id } }
}

internal fun rotationHistorySnapshot(
    mountedSet: TireSet?,
    sets: List<TireSet>,
    activeTires: List<Tire>,
    events: List<TireEvent>,
    zone: ZoneId,
): RotationHistorySnapshot {
    fun label(set: TireSet) = "${set.title} #${set.id}"
    val currentIds = activeTires.map { it.id }.toSet()
    val entries = events.filter { it.type == EventType.ROTATION }
        .sortedWith(compareByDescending<TireEvent> { it.atMillis }.thenByDescending { it.id })
        .map { event ->
            // 이전 버전의 설명은 보존하되, 현재 타이어 위치로 과거 이동을 복원하지 않는다.
            val legacyLabel = event.detail?.substringBefore(" · ")?.trim()
            val pattern = event.rotation?.pattern ?: RotationPattern.entries.firstOrNull { it.label == legacyLabel }
            val recordedIds = event.tireIds.toSet()
            RotationHistoryEntry(
                eventId = event.id,
                setId = event.setId,
                setLabel = sets.firstOrNull { it.id == event.setId }?.let(::label)
                    ?: event.setId?.let { "세트 #$it" } ?: "세트 미기록",
                dateLabel = TimeLabels.date(event.atMillis, zone).toString(),
                odoKm = event.odoKm,
                pattern = pattern,
                moves = event.rotation?.moves.orEmpty(),
                detail = event.detail,
                sameTires = when {
                    mountedSet == null || event.setId != mountedSet.id || recordedIds.isEmpty() -> null
                    recordedIds == currentIds -> true
                    recordedIds.any { it !in currentIds } -> false
                    else -> null // 일부 ID만 남은 과거 기록으로 구성 변경을 단정하지 않는다.
                },
            )
        }
    return RotationHistorySnapshot(mountedSet?.id, mountedSet?.let(::label), entries)
}
