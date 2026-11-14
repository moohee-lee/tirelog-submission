package com.tirelog.app.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tirelog.app.ui.common.ActionRow
import com.tirelog.app.ui.common.AdaptiveColumns
import com.tirelog.app.ui.common.PageDialog
import com.tirelog.app.ui.common.BigButton
import com.tirelog.app.ui.common.ChoiceChips
import com.tirelog.app.ui.common.Muted
import com.tirelog.app.ui.common.LocalActionsEnabled
import com.tirelog.app.ui.common.SectionTitle
import com.tirelog.app.ui.common.TireLineIcon
import com.tirelog.app.ui.common.TireIcon
import com.tirelog.app.ui.theme.TirelogColors
import com.tirelog.core.alert.AlertPlanner
import com.tirelog.core.engine.RotationHistoryEntry
import com.tirelog.core.engine.RotationHistorySnapshot
import com.tirelog.core.model.RotationPattern

/** 현재 세트의 기록을 선택 화면에서 바로 확인한다. 조회는 기록을 변경하지 않는다. */
@Composable
internal fun RotationHistorySummary(history: RotationHistorySnapshot?, onShowHistory: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = TirelogColors.SurfaceHigh, contentColor = TirelogColors.OnSurface,
        modifier = Modifier.fillMaxWidth().testTag("rotation-history-summary")) {
        BoxWithConstraints(Modifier.padding(16.dp)) {
            val summary: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("최근 위치교환", style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.semantics { heading() })
                    val latest = history?.latestForMountedSet
                    when {
                        history == null -> Muted("이력을 불러오는 중입니다.")
                        latest == null -> Muted("현재 세트의 위치교환 기록이 없습니다.")
                        else -> {
                            Text("${latest.dateLabel} · ${latest.pattern?.label ?: "교환 방식 미기록"}",
                                style = MaterialTheme.typography.bodyLarge)
                            Muted("${latest.setLabel} · ${odometerLabel(latest)}")
                            TireIdentityNotice(latest, history.mountedSetId)
                        }
                    }
                }
            }
            if (maxWidth / LocalDensity.current.fontScale >= 620.dp) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Column(Modifier.weight(1f)) { summary() }
                    HistoryButton("이전 이력 보기", onShowHistory, enabled = history != null)
                }
            } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                summary()
                HistoryButton("이전 이력 보기", onShowHistory, enabled = history != null)
            }
        }
    }
}

/** 5건씩 조회해 이력이 오래 쌓여도 끝없이 긴 목록을 만들지 않는다. */
@Composable
internal fun RotationHistoryDialog(
    history: RotationHistorySnapshot?,
    onDismiss: () -> Unit,
    onSelect: (RotationPattern) -> Unit,
) {
    var currentSetOnly by rememberSaveable(history?.mountedSetId) { mutableStateOf(history?.mountedSetId != null) }
    var page by rememberSaveable { mutableIntStateOf(0) }
    var detailId by rememberSaveable { mutableStateOf<Long?>(null) }
    val detail = history?.entries?.firstOrNull { it.eventId == detailId }
    val back: () -> Unit = { if (detailId != null) detailId = null else onDismiss() }
    val entries = history?.entries.orEmpty().filter { !currentSetOnly || it.setId == history?.mountedSetId }
    val lastPage = ((entries.size - 1) / HISTORY_PAGE_SIZE).coerceAtLeast(0)
    val visiblePage = page.coerceIn(0, lastPage)
    val offset = visiblePage * HISTORY_PAGE_SIZE

    PageDialog(if (detail == null) "위치교환 이력" else "이전 위치교환 상세", back, footer = {
            if (detail != null) {
                ActionRow {
                    BigButton("목록으로", { detailId = null })
                    detail.pattern?.let { pattern -> BigButton("이 방식 선택", { onSelect(pattern) }, primary = true) }
                }
            } else {
                if (entries.size > HISTORY_PAGE_SIZE) {
                    ActionRow {
                        BigButton("최근 기록", { page = visiblePage - 1 }, enabled = visiblePage > 0)
                        BigButton("이전 기록", { page = visiblePage + 1 }, enabled = visiblePage < lastPage)
                        BigButton("닫기", onDismiss)
                    }
                } else BigButton("닫기", onDismiss)
            }
        }) {
        if (detail != null) {
            Text("${detail.dateLabel} · ${detail.pattern?.label ?: "교환 방식 미기록"}", style = MaterialTheme.typography.titleMedium)
            Muted("${detail.setLabel} · ${odometerLabel(detail)}")
            TireIdentityNotice(detail, history?.mountedSetId)
            val paths: @Composable () -> Unit = {
                SectionTitle("당시 타이어 이동")
                if (detail.moves.isEmpty()) {
                    Muted("타이어별 이동 정보가 없습니다. 저장된 설명을 확인하세요.")
                    Text(detail.detail ?: "이동 경로 미기록", style = MaterialTheme.typography.bodyLarge)
                } else {
                    detail.moves.forEach { move ->
                        Text("타이어 ${move.tireLabel} · ${move.from.label} → ${move.to.label}",
                            style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Muted("이전 기록을 참고해 실제로 교환한 방식을 선택하세요.")
            }
            val pattern = detail.pattern
            if (pattern != null) AdaptiveColumns(first = {
                RotationVehicleDiagram(pattern, tireLabels = detail.moves.associate { it.from to it.tireLabel })
            }, second = paths)
            else paths()
        } else {
            Muted("장착 중: ${history?.mountedSetLabel ?: "세트 없음"}")
            ChoiceChips(listOf(true to "현재 세트", false to "전체 세트"), setOf(currentSetOnly), onToggle = {
                currentSetOnly = it
                page = 0
            })
            if (history == null) Muted("이력을 불러오는 중입니다.")
            else if (entries.isEmpty()) {
                Text("위치교환 기록이 없습니다.", style = MaterialTheme.typography.bodyLarge)
                Muted("이번 교환 방식을 선택한 뒤 적용하면 날짜와 타이어별 이동이 여기에 저장됩니다.")
            } else Muted("${offset + 1}–${minOf(offset + HISTORY_PAGE_SIZE, entries.size)} / ${entries.size}건 · 최신순")
            entries.drop(offset).take(HISTORY_PAGE_SIZE).forEach { entry ->
                BoxWithConstraints(Modifier.fillMaxWidth().testTag("rotation-history-entry-${entry.eventId}")) {
                    val info: @Composable () -> Unit = {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("${entry.dateLabel} · ${entry.pattern?.label ?: "교환 방식 미기록"}",
                                style = MaterialTheme.typography.titleMedium, color = TirelogColors.OnSurface)
                            Muted("${entry.setLabel} · ${odometerLabel(entry)}")
                            TireIdentityNotice(entry, history?.mountedSetId)
                        }
                    }
                    if (maxWidth / LocalDensity.current.fontScale >= 620.dp) {
                        Row(Modifier.heightIn(min = 104.dp).padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            Column(Modifier.weight(1f)) { info() }
                            HistoryButton("상세 보기", { detailId = entry.eventId })
                        }
                    } else Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        info()
                        HistoryButton("상세 보기", { detailId = entry.eventId })
                    }
                }
                HorizontalDivider(color = TirelogColors.Outline)
            }
        }
    }
}

@Composable
private fun HistoryButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    OutlinedButton(onClick, enabled = enabled && LocalActionsEnabled.current,
        modifier = Modifier.heightIn(min = 88.dp), shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = TirelogColors.OnSurface)) {
        TireLineIcon(TireIcon.CLOCK, Modifier.padding(end = 12.dp))
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun TireIdentityNotice(entry: RotationHistoryEntry, mountedSetId: Long?) {
    when {
        entry.setId != mountedSetId -> Muted("현재 장착 세트와 다른 세트의 기록입니다.")
        entry.sameTires == false -> Muted("당시와 현재의 타이어 구성이 다릅니다.")
        entry.sameTires == null -> Muted("당시 타이어 구성을 확인할 수 없습니다.")
    }
}

private fun odometerLabel(entry: RotationHistoryEntry): String =
    entry.odoKm?.let { "당시 주행거리 ${AlertPlanner.formatKm(it)}km" } ?: "당시 주행거리 미기록"

private const val HISTORY_PAGE_SIZE = 5
