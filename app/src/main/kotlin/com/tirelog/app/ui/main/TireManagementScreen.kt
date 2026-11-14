package com.tirelog.app.ui.main

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.tirelog.app.ui.common.*
import com.tirelog.app.ui.theme.TirelogColors
import com.tirelog.core.alert.AlertPlanner
import com.tirelog.core.engine.SetRow
import com.tirelog.core.engine.SetsOverview
import com.tirelog.core.maintenance.TireLife
import com.tirelog.core.model.WheelPosition

/** 선택은 조회만 바꾸며 장착·주행 기록을 변경하지 않는다. */
@Composable
internal fun TireManagementScreen(
    overview: SetsOverview?, onBack: () -> Unit, onRegister: () -> Unit,
    onEdit: (Long, Long?) -> Unit, onDetails: (Long, WheelPosition?) -> Unit,
    onRetire: (Long) -> Unit, onDelete: (Long) -> Unit,
) = TireWorkspaceTheme {
    TireManagementPage(overview, onBack, onRegister, onEdit, onDetails, onRetire, onDelete)
}

@Composable
private fun TireManagementPage(
    overview: SetsOverview?, onBack: () -> Unit, onRegister: () -> Unit,
    onEdit: (Long, Long?) -> Unit, onDetails: (Long, WheelPosition?) -> Unit,
    onRetire: (Long) -> Unit, onDelete: (Long) -> Unit,
) {
    var viewedSet by rememberSaveable { mutableStateOf<Long?>(null) }
    var showRetired by rememberSaveable { mutableStateOf(false) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var confirm by rememberSaveable { mutableStateOf<String?>(null) }
    val rows = overview?.rows.orEmpty().filter { showRetired || !it.set.retired }
    val row = rows.firstOrNull { it.set.id == viewedSet } ?: rows.firstOrNull { it.mounted } ?: rows.firstOrNull()
    var viewedTire by rememberSaveable(row?.set?.id) { mutableStateOf<Long?>(null) }
    val life = row?.tireLife?.firstOrNull { it.tire.id == viewedTire } ?: row?.tireLife?.minByOrNull { it.tire.position.ordinal }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val scale = LocalDensity.current.fontScale
        val wide = maxWidth / scale >= 1120.dp && maxHeight / scale >= 800.dp
        ScreenFrame("타이어 관리", onBack, scrollable = !wide, footer = {
            TireManagementActions(showRetired, showHistory, onRegister,
                {
                    showRetired = !showRetired
                    if (showRetired) overview?.rows?.firstOrNull { it.set.retired }?.let { viewedSet = it.set.id }
                }, { showHistory = true })
        }, actions = {
            Box {
                Surface(onClick = { more = true }, enabled = row != null && LocalActionsEnabled.current,
                    shape = RoundedCornerShape(18.dp), color = TirelogColors.SurfaceHigh,
                    modifier = Modifier.size(80.dp).semantics { contentDescription = "타이어 관리 더보기" }) {
                    Box(contentAlignment = Alignment.Center) { TireLineIcon(TireIcon.MORE) }
                }
                DropdownMenu(more, { more = false }) {
                    row?.let { current ->
                        ManagementMenuItem("상세 보기") { more = false; onDetails(current.set.id, life?.tire?.position) }
                        ManagementMenuItem("세트 공통 정보 수정", !current.set.retired) { more = false; onEdit(current.set.id, null) }
                        ManagementMenuItem("폐기", !current.mounted && !current.set.retired) { more = false; confirm = "retire" }
                        ManagementMenuItem("삭제", current.canDelete) { more = false; confirm = "delete" }
                        if (!current.canDelete) Text(if (current.mounted) "장착 중에는 삭제할 수 없습니다." else "사용 이력이 있어 기록을 보존합니다.",
                            Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = TirelogColors.Muted)
                    }
                }
            }
        }) {
            when {
                overview == null -> Loading()
                row == null -> ContentPanel("등록된 타이어가 없습니다") { Muted("타이어를 등록하면 장착 위치와 사용량을 함께 확인할 수 있습니다.") }
                else -> {
                    TireSetSelector(row, rows, showRetired, onSelect = { viewedSet = it })
                    if (showRetired && overview.rows.none { it.set.retired }) Muted("폐기한 타이어가 없습니다.")
                    val map: @Composable (Modifier) -> Unit = { modifier ->
                        TirePositionPanel(row, life, { viewedTire = it }, modifier, fillHeight = wide)
                    }
                    val detail: @Composable (Modifier) -> Unit = { modifier ->
                        SelectedTirePanel(row, life, { life?.let { onEdit(row.set.id, it.tire.id) } }, modifier, fillHeight = wide)
                    }
                    if (wide) Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        map(Modifier.weight(1.85f).fillMaxHeight())
                        detail(Modifier.weight(1f).fillMaxHeight())
                    } else {
                        map(Modifier.fillMaxWidth())
                        detail(Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
    if (showHistory) PageDialog("정비 이력", { showHistory = false }, footer = { BigButton("닫기", { showHistory = false }) }) {
        if (overview?.history.isNullOrEmpty()) Muted("아직 정비 기록이 없습니다.")
        overview?.history?.forEach {
            Text(it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 8.dp))
            HorizontalDivider(color = TirelogColors.Outline)
        }
    }
    if (confirm != null && row != null) {
        val deleting = confirm == "delete"
        AppDialog(if (deleting) "등록 정보 삭제" else "타이어 폐기", { confirm = null }) {
            Text(row.set.displayName(), style = MaterialTheme.typography.bodyLarge)
            Muted(if (deleting) "사용 이력이 없는 등록 정보를 삭제합니다." else "기본 목록에서 제외하고 과거 측정·정비 기록은 유지합니다.")
            ActionRow {
                BigButton(if (deleting) "삭제" else "폐기로 표시", {
                    if (deleting) onDelete(row.set.id) else onRetire(row.set.id)
                    confirm = null
                }, primary = true, enabled = if (deleting) row.canDelete else !row.mounted && !row.set.retired)
                BigButton("취소", { confirm = null })
            }
        }
    }
}

@Composable
private fun ManagementMenuItem(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(text, style = MaterialTheme.typography.bodyLarge) }, onClick = onClick,
        enabled = enabled && LocalActionsEnabled.current, modifier = Modifier.heightIn(min = 88.dp))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TireManagementActions(
    showRetired: Boolean, showHistory: Boolean,
    onRegister: () -> Unit, onRetiredToggle: () -> Unit, onHistoryToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val wide = maxWidth / LocalDensity.current.fontScale >= 900.dp
        val actions: @Composable (Modifier) -> Unit = { button ->
            TireActionButton("타이어 등록", onRegister, button, TireIcon.ADD, primary = true)
            TireActionButton("폐기 기록", onRetiredToggle, button, TireIcon.DELETE, selected = showRetired)
            TireActionButton("정비 이력", onHistoryToggle, button, TireIcon.HISTORY, selected = showHistory)
        }
        if (wide) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) { actions(Modifier.weight(1f)) }
        else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { actions(Modifier.fillMaxWidth()) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TireSetSelector(row: SetRow, rows: List<SetRow>, showRetired: Boolean, onSelect: (Long) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Surface(onClick = { open = true }, enabled = LocalActionsEnabled.current,
            color = TirelogColors.Surface, shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 116.dp).testTag("management-set-selector")) {
            Row(Modifier.padding(horizontal = 28.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    Text(row.set.displayName(), style = MaterialTheme.typography.titleLarge)
                    TireStatusBadge(row.status)
                    val status = when {
                        row.tireLife.any { it.stage == TireLife.Stage.DUE } -> "교체 점검 시기"
                        row.tireLife.any { it.stage == TireLife.Stage.SOON } -> "교체 점검 예고"
                        else -> "모두 기록 중"
                    }
                    Muted("타이어 ${row.tireLife.size}개 · $status${if (showRetired) " · 폐기 기록 포함" else ""}")
                }
                TireLineIcon(TireIcon.CHEVRON)
            }
        }
        DropdownMenu(open, { open = false }) {
            rows.forEach { choice -> ManagementMenuItem("${choice.set.displayName()} · ${choice.status}") {
                open = false; onSelect(choice.set.id)
            } }
        }
    }
}

@Composable
private fun TirePositionPanel(row: SetRow, selected: TireLife.Status?, onSelect: (Long) -> Unit, modifier: Modifier, fillHeight: Boolean) {
    Surface(modifier.testTag("management-position-panel"), color = TirelogColors.Surface, shape = RoundedCornerShape(24.dp)) {
        BoxWithConstraints(Modifier.padding(24.dp)) {
            val diagram = maxWidth / LocalDensity.current.fontScale >= 600.dp
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("장착 위치 · 누적 주행", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                if (diagram) {
                    VehicleFrontLabel()
                    TirePositionDiagram(if (fillHeight) Modifier.weight(1f) else Modifier.heightIn(min = 500.dp), selected?.tire?.position) { position ->
                        TireMileageCard(position, row.tireLife.firstOrNull { it.tire.position == position }, selected?.tire?.id, onSelect)
                    }
                } else WheelPosition.entries.forEach { position ->
                    TireMileageCard(position, row.tireLife.firstOrNull { it.tire.position == position }, selected?.tire?.id, onSelect)
                }
            }
        }
    }
}

@Composable
private fun TireMileageCard(position: WheelPosition, life: TireLife.Status?, selectedId: Long?, onSelect: (Long) -> Unit) {
    val chosen = life != null && life.tire.id == selectedId
    Surface(onClick = { life?.let { onSelect(it.tire.id) } }, enabled = life != null && LocalActionsEnabled.current,
        color = if (chosen) TirelogColors.TireSelection else TirelogColors.Surface,
        contentColor = TirelogColors.OnSurface, shape = RoundedCornerShape(20.dp),
        border = BorderStroke(if (chosen) 3.dp else 1.dp, if (chosen) TirelogColors.OnTireSelection else TirelogColors.Outline),
        modifier = Modifier.fillMaxWidth().heightIn(min = 168.dp).testTag("management-tire-${position.name}")
            .semantics { selected = chosen }) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TireIdentity(position, life?.tire?.label ?: "—", chosen)
            TireDistance(life?.recordedKm, large = true)
            if (life?.totalKmKnown == false) Text("앱 기록 거리", style = MaterialTheme.typography.bodySmall, color = TirelogColors.Muted)
        }
    }
}

@Composable
internal fun TireIdentity(position: WheelPosition, label: String, chosen: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Surface(shape = RoundedCornerShape(12.dp), color = if (chosen) TirelogColors.OnTireSelection else TirelogColors.Selection,
            contentColor = if (chosen) MaterialTheme.colorScheme.surface else TirelogColors.OnSurface) {
            Text(label, Modifier.padding(horizontal = 14.dp, vertical = 2.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Text(position.label, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun TireDistance(km: Double?, large: Boolean = false) {
    val number = km?.let { AlertPlanner.formatKm(it.coerceAtLeast(0.0)) } ?: "—"
    val type = MaterialTheme.typography
    val measurer = rememberTextMeasurer()
    BoxWithConstraints {
        val unitWidth = if (km == null) 0 else measurer.measure(AnnotatedString("km"), type.titleMedium).size.width
        val room = with(LocalDensity.current) { maxWidth.toPx() - 8.dp.toPx() } - unitWidth
        val styles = if (large) listOf(type.displayLarge, type.displayMedium, type.headlineMedium) else listOf(type.headlineMedium)
        val style = styles.firstOrNull { measurer.measure(AnnotatedString(number), it, softWrap = false).size.width <= room } ?: styles.last()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(number, Modifier.alignByBaseline(), style = style, fontWeight = FontWeight.Bold)
            if (km != null) Text("km", Modifier.alignByBaseline(), style = type.titleMedium)
        }
    }
}

@Composable
private fun TireStatusBadge(text: String) {
    Surface(shape = RoundedCornerShape(14.dp), color = TirelogColors.Selection) {
        Text(text, Modifier.padding(horizontal = 20.dp, vertical = 4.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun SelectedTirePanel(row: SetRow, life: TireLife.Status?, onEdit: () -> Unit, modifier: Modifier, fillHeight: Boolean) {
    Surface(modifier.testTag("management-detail-panel"), color = TirelogColors.Surface, shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (life == null) { Muted("타이어 정보가 없습니다."); return@Column }
            Column(if (fillHeight) Modifier.weight(1f).verticalScroll(rememberScrollState()) else Modifier,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("선택한 타이어", style = MaterialTheme.typography.bodyMedium, color = TirelogColors.Muted)
                val stage = when (life.stage) {
                    TireLife.Stage.TRACKING -> "기록 중"
                    TireLife.Stage.SOON -> "교체 점검 예고"
                    TireLife.Stage.DUE -> "교체 점검 시기"
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${life.tire.label} · ${life.tire.position.label}", style = MaterialTheme.typography.titleLarge,
                        color = TirelogColors.OnTireSelection, modifier = Modifier.testTag("management-selected-tire"))
                    TireStatusBadge(stage)
                }
                Text(if (life.totalKmKnown) "누적 주행" else "앱 기록 주행", style = MaterialTheme.typography.bodyMedium, color = TirelogColors.Muted)
                TireDistance(life.recordedKm, large = true)
                if (life.recordedKm == null) Muted("거리 확인 필요 · 주행거리 누락 구간이 있습니다.")
                if (!life.totalKmKnown) Muted("등록 전 거리를 몰라 실제 총사용 거리와 다를 수 있습니다.")
                HorizontalDivider(color = TirelogColors.Outline)
                Text("설정한 점검 기준까지", style = MaterialTheme.typography.bodyMedium, color = TirelogColors.Muted)
                TireDistance(life.remainingKm, large = true)
                life.remainingDays?.let { Text("기간 기준 ${it.coerceAtLeast(0)}일", style = MaterialTheme.typography.bodySmall, color = TirelogColors.Muted) }
                HorizontalDivider(color = TirelogColors.Outline)
                TireInfoRow("첫 사용일", life.firstUsed?.toString() ?: "미입력")
                TireInfoRow("앱 기록 장착", "${life.recordedMountedDays}일")
                TireInfoRow("DOT", life.tire.dot ?: "모름")
            }
            TireActionButton("정보 수정", onEdit, Modifier.fillMaxWidth(), TireIcon.EDIT, enabled = !row.set.retired && life.tire.active)
        }
    }
}

@Composable
private fun TireInfoRow(label: String, value: String) {
    BoxWithConstraints {
        if (maxWidth / LocalDensity.current.fontScale >= 340.dp) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(value, style = MaterialTheme.typography.bodyMedium)
        } else Column {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
    }
    HorizontalDivider(color = TirelogColors.Outline.copy(alpha = .6f))
}
