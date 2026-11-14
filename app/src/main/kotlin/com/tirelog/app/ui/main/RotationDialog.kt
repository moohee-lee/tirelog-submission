package com.tirelog.app.ui.main

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tirelog.app.ui.common.*
import com.tirelog.app.ui.theme.TirelogColors
import com.tirelog.core.engine.RotationHistorySnapshot
import com.tirelog.core.model.RotationPattern
import com.tirelog.core.model.WheelPosition

private val diagramLabels = WheelPosition.entries.associateWith { ('A' + it.ordinal).toString() }

/** 방식 선택·이력 조회는 기록하지 않고 적용만 실제 위치를 변경한다. */
@Composable
fun RotationDialog(
    onDismiss: () -> Unit,
    onApply: (RotationPattern) -> Unit,
    error: String? = null,
    history: RotationHistorySnapshot? = null,
    tireLabels: Map<WheelPosition, String> = diagramLabels,
) = TireWorkspaceTheme {
    RotationWorkspace(onDismiss, onApply, error, history, tireLabels)
}

@Composable
private fun RotationWorkspace(
    onDismiss: () -> Unit, onApply: (RotationPattern) -> Unit, error: String?,
    history: RotationHistorySnapshot?, tireLabels: Map<WheelPosition, String>,
) {
    var selected by rememberSaveable { mutableStateOf<RotationPattern?>(null) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    if (showHistory) {
        RotationHistoryDialog(history, onDismiss = { showHistory = false }, onSelect = {
            selected = it
            showHistory = false
        })
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val scale = LocalDensity.current.fontScale
        val wide = maxWidth / scale >= 1000.dp && maxHeight / scale >= 800.dp
        val spaciousFooter = maxWidth / scale >= 700.dp
        PageDialog("위치교환", onDismiss, scrollable = !wide, footer = {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TireActionButton("취소", onDismiss, Modifier.weight(1f))
                TireActionButton(selected?.let { "${it.label} 적용" } ?: "적용", { selected?.let(onApply) },
                    Modifier.weight(if (spaciousFooter) 2.75f else 1f).testTag("rotation-apply"),
                    primary = true, enabled = selected != null)
            }
        }) {
            error?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = TirelogColors.Red) }
            RotationHistorySummary(history, onShowHistory = { showHistory = true })
            RotationPatternPicker(selected, onSelect = { selected = it },
                modifier = if (wide) Modifier.weight(1f) else Modifier, fillHeight = wide, tireLabels = tireLabels)
        }
    }
}

/** 다섯 개의 큰 선택 영역과 선택에 즉시 연동되는 하나의 도식을 제공한다. */
@Composable
internal fun RotationPatternPicker(
    selected: RotationPattern?, onSelect: (RotationPattern) -> Unit,
    modifier: Modifier = Modifier, fillHeight: Boolean = false,
    tireLabels: Map<WheelPosition, String> = diagramLabels,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val wide = maxWidth / LocalDensity.current.fontScale >= 950.dp
        val menu: @Composable (Modifier) -> Unit = { pane ->
            Surface(pane.testTag("rotation-pattern-menu"), shape = RoundedCornerShape(24.dp), color = TirelogColors.Surface) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("교환 방식", style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 12.dp).semantics { heading() })
                    RotationPattern.entries.forEach { pattern ->
                        val chosen = selected == pattern
                        Surface(onClick = { onSelect(pattern) }, enabled = LocalActionsEnabled.current,
                            color = if (chosen) TirelogColors.Selection else TirelogColors.Surface,
                            contentColor = TirelogColors.OnSurface, shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(if (chosen) 2.dp else 1.dp,
                                if (chosen) TirelogColors.OnSurface else TirelogColors.Outline),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp)
                                .then(if (fillHeight) Modifier.weight(1f) else Modifier)
                                .testTag("rotation-choice-${pattern.name}").semantics { this.selected = chosen }) {
                            Box(Modifier.padding(horizontal = 28.dp, vertical = 16.dp), contentAlignment = Alignment.CenterStart) {
                                Text(pattern.label, style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
            }
        }
        val preview: @Composable (Modifier) -> Unit = { pane ->
            RotationPreview(selected ?: RotationPattern.FRONT_REAR, pane, fillHeight, tireLabels)
        }
        if (wide) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            menu(Modifier.weight(1f).then(if (fillHeight) Modifier.fillMaxHeight() else Modifier))
            preview(Modifier.weight(2.2f).then(if (fillHeight) Modifier.fillMaxHeight() else Modifier))
        } else Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            menu(Modifier.fillMaxWidth())
            preview(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun RotationPreview(pattern: RotationPattern, modifier: Modifier, fillHeight: Boolean, tireLabels: Map<WheelPosition, String>) {
    Surface(modifier.testTag("rotation-preview-panel"), shape = RoundedCornerShape(24.dp), color = TirelogColors.Surface) {
        Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(pattern.label, style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("rotation-preview-title").semantics { heading() })
            Text(if (pattern == RotationPattern.FRONT_REAR) "같은 쪽의 앞바퀴와 뒷바퀴를 바꿉니다." else pattern.description,
                style = MaterialTheme.typography.bodyMedium, color = TirelogColors.Muted)
            RotationVehicleDiagram(pattern, if (fillHeight) Modifier.weight(1f) else Modifier, tireLabels, fillHeight)
            HorizontalDivider(color = TirelogColors.Outline)
            RotationMoveLegend(pattern, tireLabels)
        }
    }
}

/** 화살표와 이동 요약은 기록에 쓰는 destinationOf에서 도출한다. */
@Composable
fun RotationVehicleDiagram(
    pattern: RotationPattern, modifier: Modifier = Modifier,
    tireLabels: Map<WheelPosition, String> = diagramLabels, fillHeight: Boolean = false,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val labels = maxWidth / LocalDensity.current.fontScale >= 680.dp
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            VehicleFrontLabel()
            TirePositionDiagram(if (fillHeight) Modifier.weight(1f) else Modifier.heightIn(min = 440.dp), pattern = pattern, showLabels = labels) { position ->
                Surface(shape = RoundedCornerShape(16.dp), color = TirelogColors.Surface,
                    border = BorderStroke(1.dp, TirelogColors.Outline)) {
                    Box(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        TireIdentity(position, tireLabels[position] ?: "—")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RotationMoveLegend(pattern: RotationPattern, labels: Map<WheelPosition, String>) {
    val moves = WheelPosition.entries.filter { from ->
        val to = pattern.destinationOf(from)
        pattern.destinationOf(to) != from || from.ordinal < to.ordinal
    }
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        moves.forEach { from ->
            val to = pattern.destinationOf(from)
            val reciprocal = pattern.destinationOf(to) == from
            val description = when (pattern) {
                RotationPattern.FRONT_REAR -> if (from == WheelPosition.FRONT_LEFT) "왼쪽 앞뒤" else "오른쪽 앞뒤"
                RotationPattern.SIDE_TO_SIDE -> if (from.isFront) "앞쪽 좌우" else "뒤쪽 좌우"
                else -> "${from.label} ${if (reciprocal) "↔" else "→"} ${to.label}"
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Surface(shape = RoundedCornerShape(12.dp), color = TirelogColors.Selection) {
                    Text("${labels[from] ?: "—"} ${if (reciprocal) "↔" else "→"} ${labels[to] ?: "—"}",
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.titleMedium)
                }
                Text(description, style = MaterialTheme.typography.bodyMedium, color = TirelogColors.Muted)
            }
        }
    }
}

internal fun rotationArrowEndpoints(from: Rect, to: Rect, clearance: Float): Pair<Offset, Offset> =
    tireArrowEndpoints(from, to, clearance)
