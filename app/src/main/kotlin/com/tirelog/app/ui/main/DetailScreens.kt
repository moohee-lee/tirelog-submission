package com.tirelog.app.ui.main

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tirelog.app.ui.card.RefillQuestionCard
import com.tirelog.app.ui.common.AdaptiveColumns
import com.tirelog.app.ui.common.ActionRow
import com.tirelog.app.ui.common.ContentPanel
import com.tirelog.app.ui.common.BigButton
import com.tirelog.app.ui.common.DatePickerField
import com.tirelog.app.ui.common.ChoiceChips
import com.tirelog.app.ui.common.Muted
import com.tirelog.app.ui.common.PendingList
import com.tirelog.app.ui.common.ScreenFrame
import com.tirelog.app.ui.common.SectionTitle
import com.tirelog.app.ui.common.VehiclePressureDiagram
import com.tirelog.app.ui.theme.TirelogColors
import com.tirelog.core.TimeLabels
import com.tirelog.core.engine.TrendData
import com.tirelog.core.engine.TrendPoint
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun Loading() {
    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

/** 선택은 조회만 바꾼다. 장착 변경은 타이어 교체 화면에서만 한다. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(vm: MainViewModel, onBack: () -> Unit, initialSetId: Long? = null,
    initialPosition: com.tirelog.core.model.WheelPosition? = null) {
    val snapshot by vm.dashboard.collectAsStateWithLifecycle()
    val overview by vm.sets.collectAsStateWithLifecycle()
    val mounted by vm.mountedSet.collectAsStateWithLifecycle()
    val tires by vm.allTires.collectAsStateWithLifecycle()
    val readings by vm.latestReadings.collectAsStateWithLifecycle()
    var viewedSet by rememberSaveable(initialSetId) { mutableStateOf(initialSetId) }
    var viewedTire by rememberSaveable(initialSetId, initialPosition) { mutableStateOf<Long?>(null) }
    val zone = vm.session.value?.engine?.zone ?: ZoneId.systemDefault()
    ScreenFrame("타이어 상세", onBack) {
        val rows = overview?.rows ?: return@ScreenFrame Loading()
        val row = rows.firstOrNull { it.set.id == (viewedSet ?: mounted?.id) } ?: rows.firstOrNull()
        if (row == null) { Muted("먼저 내 타이어를 등록해 주세요."); return@ScreenFrame }
        com.tirelog.app.ui.common.SelectionMenu("조회할 타이어", row.set.displayName() + " · ${row.status}",
            rows.map { it.set.id to "${it.set.displayName()} · ${it.status}" }) {
            viewedSet = if (it == mounted?.id) null else it
            viewedTire = null
        }
        val candidates = tires.filter { it.setId == row.set.id }.sortedWith(compareBy({ !it.active }, { it.position.ordinal }))
        val tire = candidates.firstOrNull { it.id == viewedTire } ?: candidates.firstOrNull { it.active && it.position == initialPosition }
            ?: candidates.firstOrNull { it.active }
        if (tire == null) return@ScreenFrame Loading()
        Spacer(Modifier.height(12.dp))
        ChoiceChips(candidates.filter { it.active }.map { it.id to "${it.position.label} · ${it.label}" }, setOf(tire.id), onToggle = { viewedTire = it })
        val previous = candidates.filterNot { it.active }
        if (previous.isNotEmpty()) com.tirelog.app.ui.common.SelectionMenu("이전에 사용한 타이어",
            previous.firstOrNull { it.id == tire.id }?.let { "타이어 ${it.label}" } ?: "과거 기록 선택",
            previous.map { it.id to "타이어 ${it.label} · ${if (it.retired) "폐기" else "보관"}" }) { viewedTire = it }
        val isMounted = row.mounted && tire.active
        val pressureSupported = snapshot?.pressureSupported == true
        val wheel = snapshot?.wheels?.firstOrNull { isMounted && it.tireId == tire.id }
        val last = readings.firstOrNull { it.tireId == tire.id }
        AdaptiveColumns(first = {
        ContentPanel("${tire.position.label} · 타이어 ${tire.label}") {
        if (pressureSupported) {
        Text(if (wheel != null) wheel.displayPsi?.let { "$it psi / 권장 ${wheel.recommendedPsi?.toString() ?: "—"} psi" } ?: "측정 대기"
            else last?.let { "마지막 측정 ${com.tirelog.core.Units.psiRounded(it.kpa)} psi" } ?: "측정 기록 없음",
            style = MaterialTheme.typography.headlineMedium)
        if (wheel != null) {
            if (!wheel.supported) Muted("이 바퀴의 공기압은 차량에서 제공하지 않습니다.")
            Muted("${wheel.measuredLabel} · ${wheel.conditionLabel}")
            Muted("권장값 출처 · ${snapshot?.recommendedSourceLabel ?: "미설정"}")
            Text(wheel.line, style = MaterialTheme.typography.bodyLarge)
            Muted("공기압 상태 판단은 최근 냉간 측정을 기준으로 합니다.")
            wheel.lowPressureKpa?.let { Muted("차량 저압 기준 ${it.toInt()}kPa · 참고값이며 권장 공기압과 다릅니다.") }
        } else {
            last?.let { Muted("${com.tirelog.core.TimeLabels.relative(it.atMillis, snapshot?.nowMillis ?: it.atMillis, zone)} · ${if (it.cold) "냉간 측정" else "비냉간 측정"}") }
            Muted("${if (row.set.retired || tire.retired) "폐기" else "보관"} 중인 타이어의 과거 기록입니다.")
        }
        } else Muted("이 차량은 공기압 수치를 제공하지 않습니다. 타이어 정보와 정비 기록은 계속 관리할 수 있습니다.")
        }
        }, second = {
        ContentPanel("타이어 정보") {
        val info = row.set.registrationInput(tire)
        Text(listOfNotNull(info.manufacturer ?: com.tirelog.core.catalog.TireLifeGuides.find(info.lifeGuideId).label,
            info.tireModel, info.type.label).joinToString(" · "), style = MaterialTheme.typography.bodyLarge)
        Muted("DOT ${tire.dot ?: "모름"}${info.size?.let { " · $it" }.orEmpty()}")
        (row.tireLife + row.previousTireLife).firstOrNull { it.tire.id == tire.id }?.let { TireLifeSummary(it) }
        val guide = com.tirelog.core.catalog.TireLifeGuides.find(info.lifeGuideId)
        LifePolicySummary(guide, info.inspectionKm, info.inspectionMonths)
        }
        })
        Spacer(Modifier.height(16.dp))
        ActionRow {
            if (tire.active && !row.set.retired) BigButton("이 타이어 정보 수정", { vm.open(Dest.EditRegistration(row.set.id, tire.id)) })
            if (pressureSupported) BigButton("추이 보기", { vm.setTrendSet(if (row.mounted) null else row.set.id); vm.open(Dest.Trend) })
            if (isMounted) BigButton("보충·수리 기록", { vm.open(Dest.CheckIn()) })
            if (isMounted) BigButton("정비소에서 보여 주기", { vm.open(Dest.Mechanic) })
        }
        if (isMounted) {
            if (pressureSupported) {
                snapshot?.causeText?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                Muted("최근 7일 중 측정 ${snapshot?.measuredDaysLast7 ?: 0}일")
                if (snapshot?.needsRecommended == true) BigButton("권장 공기압 설정", { vm.open(Dest.Settings) })
            }
            snapshot?.refillQuestion?.let { RefillQuestionCard(it, vm::answerRefill) }
            if (snapshot?.pending?.isNotEmpty() == true) {
                SectionTitle("남은 점검")
                PendingList(snapshot!!.pending)
            }
        }
    }
}

@Composable
internal fun seriesColor(series: com.tirelog.core.engine.TrendSeries): Color =
    TirelogColors.Series[Math.floorMod(series.colorIndex, TirelogColors.Series.size)]

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TrendScreen(vm: MainViewModel, onBack: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val data by vm.trend.collectAsStateWithLifecycle()
    val days by vm.trendDays.collectAsStateWithLifecycle()
    val range by vm.trendRange.collectAsStateWithLifecycle()
    val setId by vm.trendSetId.collectAsStateWithLifecycle()
    val sets by vm.allSets.collectAsStateWithLifecycle()
    val mounted by vm.mountedSet.collectAsStateWithLifecycle()
    var dateDialog by rememberSaveable { mutableStateOf(false) }
    var hidden by rememberSaveable(setId) { mutableStateOf(setOf<Long>()) }
    var showTemperature by rememberSaveable { mutableStateOf(true) }
    var showPrevious by rememberSaveable(setId) { mutableStateOf(false) }
    val zone = vm.session.value?.engine?.zone ?: ZoneId.systemDefault()
    var readingsExpanded by rememberSaveable { mutableStateOf(false) }
    ScreenFrame("공기압 추이", onBack, scrollable = false) {
        if (settings?.pressureSupported == false) {
            Muted("이 차량은 공기압 수치를 제공하지 않습니다.")
            BigButton("타이어 관리", { vm.open(Dest.Sets) })
            return@ScreenFrame
        }
        val d = data ?: return@ScreenFrame Loading()
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val fillChart = maxHeight / LocalDensity.current.fontScale >= 900.dp && !readingsExpanded
            Column(Modifier.fillMaxSize().then(if (fillChart) Modifier else Modifier.verticalScroll(rememberScrollState())),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                com.tirelog.app.ui.common.SelectionMenu("조회할 타이어",
                    sets.firstOrNull { it.id == (setId ?: mounted?.id) }?.displayName() ?: "현재 장착 타이어",
                    sets.map { it.id to it.displayName() + when { it.retired -> " · 폐기"; it.id == mounted?.id -> " · 장착 중"; else -> " · 보관" } }) {
                    vm.setTrendSet(if (it == mounted?.id) null else it)
                }
                ChoiceChips(listOf(7 to "1주", 15 to "15일", 30 to "30일", 90 to "90일", 0 to "직접 지정"),
                    setOf(if (range == null) days else 0), onToggle = { if (it == 0) dateDialog = true else vm.setTrendDays(it) })
                range?.let { Muted("${it.first} ~ ${it.second}") }
                val candidates = d.series.filter { showPrevious || it.active }
                val visible = candidates.filterNot { it.tireId in hidden }
                TrendChart(d, visible, showTemperature, zone,
                    chartModifier = if (fillChart) Modifier.weight(1f) else Modifier.height(480.dp),
                    onReadingsExpanded = { readingsExpanded = it }, additionalActions = {
                        if (d.series.any { !it.active }) BigButton("이전 타이어도 보기", { showPrevious = !showPrevious }, selected = showPrevious)
                    }) {
                    TrendLegend(candidates, hidden, showTemperature,
                        onToggle = { id -> hidden = if (id in hidden) hidden - id else hidden + id },
                        onTemperatureToggle = { showTemperature = !showTemperature })
                }
                Muted("냉간 측정 기준 · 회색 점선은 외기온도 · 범례의 위치는 현재 또는 마지막 장착 위치입니다.")
                Muted("세로선은 정비 기록입니다. 보관 기간과 측정 공백은 선을 이어 그리지 않습니다.")
            }
        }
    }
    if (dateDialog) TrendDateDialog(range ?: (vm.today().minusDays(days.toLong() - 1) to vm.today()), vm.today(),
        onDismiss = { dateDialog = false }, onApply = { from, to -> vm.setTrendRange(from, to); dateDialog = false })
}

/** 범례는 한 줄을 유지한다. 좁은 창에서는 가로로 스크롤해 모든 조작에 접근한다. */
@Composable
internal fun TrendLegend(
    series: List<com.tirelog.core.engine.TrendSeries>, hidden: Set<Long>, showTemperature: Boolean,
    onToggle: (Long) -> Unit, onTemperatureToggle: () -> Unit, modifier: Modifier = Modifier,
) {
    val palette = TirelogColors
    BoxWithConstraints(modifier.fillMaxWidth()) {
        Row(Modifier.horizontalScroll(rememberScrollState()).widthIn(min = maxWidth).testTag("trend-legend"),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.padding(end = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                series.forEach { item ->
                    androidx.compose.material3.FilterChip(
                        selected = item.tireId !in hidden, onClick = { onToggle(item.tireId) },
                        enabled = com.tirelog.app.ui.common.LocalActionsEnabled.current,
                        colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                            selectedContainerColor = palette.SurfaceHigh, selectedLabelColor = palette.OnSurface),
                        leadingIcon = { Box(Modifier.size(14.dp).background(seriesColor(item), RoundedCornerShape(3.dp))) },
                        label = { Text(item.label + if (item.tireId in hidden) " · OFF" else " · ON", maxLines = 1) },
                        modifier = Modifier.heightIn(min = 56.dp).testTag("trend-series-${item.tireId}"),
                    )
                }
            }
            androidx.compose.material3.FilterChip(selected = showTemperature, onClick = onTemperatureToggle,
                enabled = com.tirelog.app.ui.common.LocalActionsEnabled.current,
                colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                    selectedContainerColor = palette.SurfaceHigh, selectedLabelColor = palette.OnSurface),
                leadingIcon = { Canvas(Modifier.size(22.dp, 14.dp)) {
                    drawLine(palette.Muted, Offset(0f, size.height / 2), Offset(size.width, size.height / 2),
                        strokeWidth = 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f)))
                } },
                label = { Text("외기온도 · ${if (showTemperature) "ON" else "OFF"}", maxLines = 1) },
                modifier = Modifier.heightIn(min = 56.dp).testTag("trend-temperature"))
        }
    }
}

@Composable
internal fun TrendDateDialog(initial: Pair<java.time.LocalDate, java.time.LocalDate>, today: java.time.LocalDate,
    onDismiss: () -> Unit, onApply: (java.time.LocalDate, java.time.LocalDate) -> Unit) {
    var fromDay by rememberSaveable { mutableStateOf(initial.first.toEpochDay()) }
    var toDay by rememberSaveable { mutableStateOf(initial.second.toEpochDay()) }
    val from = java.time.LocalDate.ofEpochDay(fromDay)
    val to = java.time.LocalDate.ofEpochDay(toDay)
    val valid = !from.isAfter(to) && !to.isAfter(today)
    val context = androidx.compose.ui.platform.LocalContext.current
    fun pick(value: java.time.LocalDate, update: (Long) -> Unit) {
        android.app.DatePickerDialog(context, { _, y, m, d -> update(java.time.LocalDate.of(y, m + 1, d).toEpochDay()) },
            value.year, value.monthValue - 1, value.dayOfMonth).apply {
            datePicker.maxDate = today.atTime(23, 59).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.show()
    }
    com.tirelog.app.ui.common.AppDialog("조회 기간 지정", onDismiss) {
        DatePickerField("시작일", from, { pick(from) { fromDay = it } }, Modifier.testTag("trend-start-date"))
        DatePickerField("종료일", to, { pick(to) { toDay = it } }, Modifier.testTag("trend-end-date"))
        if (!valid) Muted("시작일은 종료일보다 늦을 수 없습니다. 오늘까지의 날짜를 선택해 주세요.")
        ActionRow {
            BigButton("기간 적용", { onApply(from, to) }, primary = true, enabled = valid)
            BigButton("취소", onDismiss)
        }
    }
}

@Composable
internal fun TrendChart(data: TrendData, visible: List<com.tirelog.core.engine.TrendSeries>, showTemperature: Boolean, zone: ZoneId,
    chartModifier: Modifier = Modifier.height(360.dp), onReadingsExpanded: (Boolean) -> Unit = {},
    additionalActions: @Composable () -> Unit = {}, legend: @Composable () -> Unit = {}) {
    var selected by remember(data.fromMillis, data.toMillis, visible.map { it.tireId }) { mutableStateOf<Pair<Long, TrendPoint>?>(null) }
    var showReadings by rememberSaveable { mutableStateOf(false) }
    val measurer = rememberTextMeasurer()
    val palette = TirelogColors
    val allPsi = data.series.flatMap { it.points.map { p -> p.value } }
    val minPsi = (allPsi.minOrNull() ?: 30.0) - 2
    val maxPsi = (allPsi.maxOrNull() ?: 40.0) + 2
    val temps = data.temperatures
    val minTemp = (temps.minOfOrNull { it.value } ?: 0.0) - 3
    val maxTemp = (temps.maxOfOrNull { it.value } ?: 20.0) + 3
    val span = (data.toMillis - data.fromMillis).coerceAtLeast(1L).toFloat()
    val labelStyle = MaterialTheme.typography.bodySmall.copy(color = palette.Muted)
    val dayFormat = DateTimeFormatter.ofPattern("M/d")
    Muted(if (showTemperature) "왼쪽 공기압(psi) · 오른쪽 외기온도(℃)" else "공기압(psi)")
    Surface(chartModifier.fillMaxWidth().testTag("trend-chart"), shape = RoundedCornerShape(18.dp), color = TirelogColors.Surface) {
        Canvas(Modifier.fillMaxSize().semantics {
            contentDescription = "냉간 공기압 추이. 표시 중: ${visible.joinToString { it.label }}. 외기온도 ${if (showTemperature) "표시" else "숨김"}."
        }.padding(start = 72.dp, end = 72.dp, top = 32.dp, bottom = 44.dp)
            .pointerInput(data, visible.map { it.tireId }) {
                detectTapGestures { tap ->
                    selected = visible.flatMap { series -> series.points.map { series.tireId to it } }.minByOrNull { (_, p) ->
                        abs((p.atMillis - data.fromMillis) / span * size.width - tap.x) +
                            abs(size.height - ((p.value - minPsi) / (maxPsi - minPsi) * size.height).toFloat() - tap.y)
                    }
                }
            }) {
            fun label(text: String, x: Float, y: Float, style: TextStyle = labelStyle) = drawText(measurer.measure(text, style), topLeft = Offset(x, y))
            fun xOf(millis: Long) = (millis - data.fromMillis) / span * size.width
            fun yOf(psi: Double) = size.height - ((psi - minPsi) / (maxPsi - minPsi) * size.height).toFloat()
            fun yOfTemp(t: Double) = size.height - ((t - minTemp) / (maxTemp - minTemp) * size.height).toFloat()
            for (i in 0..4) {
                val psi = minPsi + (maxPsi - minPsi) * i / 4
                val y = yOf(psi)
                drawLine(palette.Outline.copy(alpha = .45f), Offset(0f, y), Offset(size.width, y))
                label("${psi.roundToInt()}", -64.dp.toPx(), y - 10f)
                if (showTemperature && temps.isNotEmpty()) label(formatTemp(minTemp + (maxTemp - minTemp) * i / 4), size.width + 5f, y - 10f)
            }
            for (i in 0..3) {
                val millis = data.fromMillis + (span * i / 3).toLong()
                label(Instant.ofEpochMilli(millis).atZone(zone).format(dayFormat), xOf(millis) - 16f, size.height + 8f)
            }
            data.marks.sortedBy { it.atMillis }.forEach { mark ->
                val x = xOf(mark.atMillis)
                drawLine(palette.Muted.copy(alpha = .5f), Offset(x, 0f), Offset(x, size.height),
                    strokeWidth = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
            }
            if (showTemperature) {
                temps.zipWithNext().forEach { (a, b) ->
                    if (a.segment == b.segment && b.atMillis - a.atMillis <= 2 * 86_400_000L) drawLine(palette.Muted,
                        Offset(xOf(a.atMillis), yOfTemp(a.value)), Offset(xOf(b.atMillis), yOfTemp(b.value)),
                        strokeWidth = 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
                }
                temps.forEach { drawCircle(palette.Muted, 3f, Offset(xOf(it.atMillis), yOfTemp(it.value))) }
            }
            visible.forEach { series ->
                val color = palette.Series[Math.floorMod(series.colorIndex, palette.Series.size)]
                series.points.zipWithNext().forEach { (a, b) ->
                    if (a.segment == b.segment && b.atMillis - a.atMillis <= 2 * 86_400_000L) drawLine(color,
                        Offset(xOf(a.atMillis), yOf(a.value)), Offset(xOf(b.atMillis), yOf(b.value)), strokeWidth = 4f,
                        pathEffect = if (series.active) null else PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
                }
                series.points.forEach { drawCircle(color, 5f, Offset(xOf(it.atMillis), yOf(it.value))) }
            }
            selected?.second?.let { drawCircle(palette.OnSurface, 10f, Offset(xOf(it.atMillis), yOf(it.value)), style = Stroke(3f)) }
        }
    }
    legend()
    if (visible.all { it.points.isEmpty() } && (!showTemperature || temps.isEmpty())) Muted(
        if (visible.isEmpty() && !showTemperature) "표시할 그래프를 아래에서 켜 주세요." else "선택한 기간에 냉간 측정 기록이 없습니다.")
    if (showTemperature && temps.isEmpty()) Muted("선택한 기간에 외기온도 기록이 없습니다.")
    selected?.let { (id, p) ->
        val series = visible.firstOrNull { it.tireId == id }
        val temp = if (showTemperature) temps.minByOrNull { abs(it.atMillis - p.atMillis) }
            ?.takeIf { TimeLabels.epochDay(it.atMillis, zone) == TimeLabels.epochDay(p.atMillis, zone) } else null
        Text("${Instant.ofEpochMilli(p.atMillis).atZone(zone).format(DateTimeFormatter.ofPattern("M/d HH:mm"))} · ${series?.label} · ${p.value.roundToInt()} psi" +
            (p.position?.let { " · 측정 당시 ${it.label}" } ?: "") + (temp?.let { " · ${formatTemp(it.value)}" } ?: ""), style = MaterialTheme.typography.bodyLarge)
    }
    if (data.marks.isNotEmpty()) Muted(data.marks.joinToString(" / ") { "${TimeLabels.monthDay(it.atMillis, zone)} ${it.label}" })
    ActionRow {
        BigButton(if (showReadings) "측정값 목록 접기" else "측정값 목록 보기", { showReadings = !showReadings; onReadingsExpanded(showReadings) }, selected = showReadings)
        additionalActions()
    }
    if (showReadings) TrendReadingsTable(data, zone)
}

fun formatTemp(c: Double): String {
    val v = c.roundToInt()
    return if (v < 0) "−${-v}℃" else "${v}℃"
}
