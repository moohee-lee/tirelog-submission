package com.tirelog.app.ui.main

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tirelog.app.ui.common.BigButton
import com.tirelog.app.ui.common.ChoiceChips
import com.tirelog.app.ui.common.LocalActionsEnabled
import com.tirelog.app.ui.common.Muted
import com.tirelog.app.ui.common.ScreenFrame
import com.tirelog.app.ui.common.SectionTitle
import com.tirelog.app.ui.theme.TirelogColors
import com.tirelog.core.alert.AlertPlanner
import com.tirelog.core.catalog.TireLifeGuides
import com.tirelog.core.model.TireSet
import com.tirelog.core.maintenance.DotCode
import com.tirelog.core.maintenance.TireLife
import java.time.LocalDate

internal fun firstUseDate(value: String): LocalDate? = runCatching { LocalDate.parse(value) }.getOrNull()

internal fun firstUseError(value: String, today: LocalDate, dot: String?): String? {
    if (value.isBlank()) return null
    val date = firstUseDate(value) ?: return "YYYY-MM-DD 형식으로 입력해 주세요"
    if (date < LocalDate.of(2000, 1, 1) || date > today) return "2000년 이후부터 오늘까지의 날짜를 입력해 주세요"
    val manufactured = DotCode.parseOrNull(dot, today)?.manufactureDate
    return if (manufactured != null && date < manufactured) "첫 사용일은 제조일보다 빠를 수 없습니다" else null
}

@Composable
internal fun FirstUseFields(value: String, onValue: (String) -> Unit, today: LocalDate, dot: String?, enabled: Boolean = true) {
    val error = if (enabled) firstUseError(value, today, dot) else null
    val context = LocalContext.current
    val earliest = maxOf(LocalDate.of(2000, 1, 1), DotCode.parseOrNull(dot, today)?.manufactureDate ?: LocalDate.of(2000, 1, 1))
    val date = firstUseDate(value)
    com.tirelog.app.ui.common.DatePickerField("첫 사용일", date, {
        val initial = (date ?: today).coerceIn(earliest, today)
        android.app.DatePickerDialog(context, { _, y, m, d -> onValue(LocalDate.of(y, m + 1, d).toString()) },
            initial.year, initial.monthValue - 1, initial.dayOfMonth).apply {
            datePicker.minDate = earliest.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            datePicker.maxDate = today.atTime(23, 59).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.show()
    }, Modifier.testTag("first-use-date"), enabled = enabled)
    if (value.isNotBlank()) BigButton("첫 사용일 모름", { onValue("") }, enabled = enabled)
    Muted(error ?: "모르면 기간 판단만 보류합니다. 거리·DOT 기준의 점검은 유지합니다.")
}

@Composable
internal fun NewTireChoice(isNew: Boolean, onNew: (Boolean) -> Unit) {
    ChoiceChips(
        listOf(false to "사용하던 타이어", true to "새 타이어(첫 장착부터 기록)"),
        setOf(isNew), onToggle = onNew,
    )
    if (isNew) Muted("첫 장착을 기록하면 사용 시작일을 자동으로 저장합니다.")
}

/** 등록과 수정 화면이 같은 기준 불러오기·출처·수동 수정 흐름을 사용한다. */
@Composable
internal fun LifePolicyFields(
    guideId: String, onGuide: (String) -> Unit,
    model: String, onModel: (String) -> Unit,
    km: String, onKm: (String) -> Unit,
    months: String, onMonths: (String) -> Unit,
) {
    val guide = TireLifeGuides.find(guideId)
    SectionTitle("제조사 기준 불러오기")
    Muted("제조사를 고르면 확인된 일반 기준을 채웁니다. 값은 아래에서 수정할 수 있습니다.")
    ChoiceChips(TireLifeGuides.entries.map { it.id to it.label }, setOf(guide.id), onToggle = { id ->
        val selected = TireLifeGuides.find(id)
        onGuide(id)
        onKm(selected.defaultKm.toString())
        onMonths(selected.defaultMonths.toString())
    })
    OutlinedTextField(model, { onModel(it.take(80)) }, label = { Text("제품명(선택)") },
        supportingText = { Text("제품명은 기록용입니다. 제품별 수명값이 없으면 제조사 일반 안내 또는 앱 기본값을 사용합니다.") },
        singleLine = true, enabled = LocalActionsEnabled.current, modifier = Modifier.fillMaxWidth())
    Muted(guide.explanation)
    if (guide.sourceUrl != null) Muted("국내 공식 안내 · ${TireLifeGuides.VERIFIED_ON} 확인 · 앱에 저장된 기준")
    val kmValue = km.toIntOrNull()
    val monthValue = months.toIntOrNull()
    NumberField("점검 기준 거리(km)", km, onKm, kmValue != null && kmValue in 1_000..200_000, "1,000~200,000km로 입력해 주세요")
    LifePolicyMeaning(distance = true)
    kmValue?.let { LifePolicySourceNote(guide, it, distance = true) }
    NumberField("첫 사용 후 점검 기간(개월)", months, onMonths, monthValue != null && monthValue in 1..120, "1~120개월로 입력해 주세요")
    LifePolicyMeaning(distance = false)
    monthValue?.let {
        Muted("현재 설정 · ${TireLifeGuides.periodLabel(it)}")
        LifePolicySourceNote(guide, it, distance = false)
    }
    Muted("마일리지 보증거리와 실제 수명은 다릅니다. 차량·타이어 제조사 안내와 정비소 의견에 맞춰 조정하세요.")
}

internal fun lifePolicyReady(km: String, months: String): Boolean =
    km.toIntOrNull()?.let { it in 1_000..200_000 } == true && months.toIntOrNull()?.let { it in 1..120 } == true

@Composable
fun TireLifeSummary(status: TireLife.Status) {
    val distance = status.recordedKm?.let { "${if (status.totalKmKnown) "누적" else "앱 기록"} ${AlertPlanner.formatKm(it)}km" } ?: "거리 확인 필요"
    val period = status.elapsedMonths?.let { "사용 ${it}개월" } ?: "첫 사용일 미입력"
    val text = when (status.stage) {
        TireLife.Stage.DUE -> "교체 점검 시기"
        TireLife.Stage.SOON -> "교체 점검 예고"
        TireLife.Stage.TRACKING -> "기록 중"
    }
    Text("${status.tire.label} · ${status.tire.position.label} · $text", style = MaterialTheme.typography.titleMedium,
        color = if (status.stage == TireLife.Stage.TRACKING) TirelogColors.OnSurface else TirelogColors.Amber)
    Text("$distance · $period", style = MaterialTheme.typography.bodyLarge)
    if (!status.totalKmKnown) Muted("등록 전 거리를 몰라 실제 총사용 거리와 다를 수 있습니다.")
    val remaining = listOfNotNull(
        status.remainingKm?.let { "${AlertPlanner.formatKm(it.coerceAtLeast(0.0))}km" },
        status.remainingDays?.let { "${it.coerceAtLeast(0)}일" },
    ).joinToString(" / ")
    if (remaining.isNotEmpty()) Muted("설정한 점검 기준까지 $remaining")
    Muted("앱 기록 장착 ${status.recordedMountedDays}일 · 사용 기간은 보관 포함")
}

@Composable
fun LifeSettingsScreen(vm: MainViewModel, setId: Long, onBack: () -> Unit) {
    val allSets by vm.allSets.collectAsStateWithLifecycle()
    val set = allSets.firstOrNull { it.id == setId }
    ScreenFrame("교체 점검 설정", onBack) {
        if (set == null) return@ScreenFrame Loading()
        var firstUse by rememberSaveable(setId) { mutableStateOf(set.firstUsedEpochDay?.let { LocalDate.ofEpochDay(it).toString() } ?: "") }
        var initialKm by rememberSaveable(setId) { mutableStateOf(set.initialKm.toString()) }
        var limitKm by rememberSaveable(setId) { mutableStateOf(set.inspectionKm.toString()) }
        var months by rememberSaveable(setId) { mutableStateOf(set.inspectionMonths.toString()) }
        var guideId by rememberSaveable(setId) { mutableStateOf(set.lifeGuideId) }
        var model by rememberSaveable(setId) { mutableStateOf(set.tireModel ?: "") }
        val distance = initialKm.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
        val km = limitKm.toIntOrNull()?.takeIf { it in 1_000..200_000 }
        val time = months.toIntOrNull()?.takeIf { it in 1..120 }
        val dateError = firstUseError(firstUse, vm.today(), set.dot)
        SectionTitle(set.title)
        Muted("거리와 기간 중 먼저 도달한 기준으로 알립니다. 실제 잔여 수명이나 안전 보증값은 아닙니다.")
        SectionTitle("기존 타이어 사용 기록")
        NumberField("앱 사용 전 누적 거리(km)", initialKm, { initialKm = it }, distance != null, "0 이상의 거리를 입력해 주세요")
        FirstUseFields(firstUse, { firstUse = it }, vm.today(), set.dot)
        Muted("부분 교체한 타이어는 교체 기록일부터 따로 계산합니다. 기존 타이어의 첫 사용일을 입력하세요.")
        Muted("앱 사용 전 거리를 모르면 0으로 두세요. 이후 기록된 거리만 계산하므로 총사용 거리와 다를 수 있습니다.")
        SectionTitle("교체 점검 기준")
        LifePolicyFields(guideId, { guideId = it }, model, { model = it }, limitKm, { limitKm = it }, months, { months = it })
        Muted("거리 90% 또는 기간 90일 전부터 예고합니다. 보관 중에도 기간은 계속 계산하며 제조 6년 알림은 별도로 유지합니다.")
        BigButton("저장", {
            vm.updateLifeSettings(setId, requireNotNull(distance), firstUseDate(firstUse)?.toEpochDay(), requireNotNull(km), requireNotNull(time), guideId, model, onBack)
        }, primary = true, enabled = distance != null && km != null && time != null && dateError == null)
    }
}

@Composable
private fun NumberField(label: String, value: String, onValue: (String) -> Unit, valid: Boolean, error: String) {
    OutlinedTextField(value, onValue, label = { Text(label) }, singleLine = true,
        isError = !valid, supportingText = { if (!valid) Text(error) },
        enabled = LocalActionsEnabled.current, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth())
}
