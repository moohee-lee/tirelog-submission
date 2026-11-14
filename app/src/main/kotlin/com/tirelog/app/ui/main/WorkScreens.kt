package com.tirelog.app.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tirelog.app.ui.common.BigButton
import com.tirelog.app.ui.common.ActionRow
import com.tirelog.app.ui.common.ChoiceChips
import com.tirelog.app.ui.common.Muted
import com.tirelog.app.ui.common.LocalActionsEnabled
import com.tirelog.app.ui.common.ScreenFrame
import com.tirelog.app.ui.common.SectionTitle
import com.tirelog.app.ui.common.Stepper
import com.tirelog.app.ui.theme.TirelogColors
import com.tirelog.core.Units
import com.tirelog.core.alert.AlertPlanner
import com.tirelog.core.catalog.VehicleCatalog
import com.tirelog.core.catalog.TireLifeGuides
import com.tirelog.core.model.TireSet
import com.tirelog.core.engine.CheckInRequest
import com.tirelog.core.engine.NewSetInput
import com.tirelog.core.maintenance.DotCode
import com.tirelog.core.model.RotationPattern
import com.tirelog.core.model.Tire
import com.tirelog.core.model.TireSetType
import com.tirelog.core.model.WheelPosition
import java.time.LocalDate

@Composable
fun SetsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val overview by vm.sets.collectAsStateWithLifecycle()
    TireManagementScreen(overview, onBack, onRegister = { vm.open(Dest.AddSet) },
        onEdit = { setId, tireId -> vm.open(Dest.EditRegistration(setId, tireId)) },
        onDetails = { setId, position -> vm.open(Dest.Inspect(setId, position)) },
        onRetire = vm::retireSet, onDelete = vm::deleteSet)
}

@Composable
fun DotField(value: String, unknown: Boolean, today: LocalDate, onValue: (String) -> Unit, onUnknown: (Boolean) -> Unit) {
    val focusManager = LocalFocusManager.current
    val result = if (!unknown && value.isNotEmpty()) DotCode.parse(value, today) else null
    val error = (result as? DotCode.Companion.Result.Invalid)?.reason
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TireDotGuide(initiallyExpanded = value.isBlank())
        OutlinedTextField(
            value = value,
            onValueChange = { text ->
                val digits = text.filter { it in '0'..'9' }.take(4)
                onUnknown(digits.isBlank())
                onValue(digits)
            },
            enabled = LocalActionsEnabled.current,
            label = { Text("DOT 4자리 (예: 2423)") },
            singleLine = true,
            isError = error != null,
            supportingText = {
                Text(
                    error ?: (result as? DotCode.Companion.Result.Valid)?.code?.let { "${it.display} 제조" }
                        ?: "모르면 비워 두세요. 앞 2자리는 제조 주, 뒤 2자리는 연도입니다.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            modifier = Modifier.fillMaxWidth().testTag("tire-dot-input"),
            textStyle = MaterialTheme.typography.titleLarge,
        )
        ChoiceChips(listOf(true to "제조 연식 모름"), if (unknown) setOf(true) else emptySet(), onToggle = {
            focusManager.clearFocus()
            onValue("")
            onUnknown(true)
        })
    }
}

fun dotReady(value: String, unknown: Boolean, today: LocalDate): Boolean =
    unknown || DotCode.parse(value, today) is DotCode.Companion.Result.Valid

/** 공통 등록 폼. 저장만 하며 실제 장착은 교체 화면에서 한다. */
@Composable
fun AddSetScreen(vm: MainViewModel, onBack: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val form = rememberTireForm("add")
    val allowPressure = settings?.pressureSupported == true
    TireRegistrationFrame("타이어 등록", onBack, footer = {
        BigButton("저장", { vm.addSet(form.value(), onBack) }, primary = true,
            enabled = form.valid(vm.today()), modifier = Modifier.fillMaxWidth().testTag("registration-save"))
    }) {
        Muted("네 바퀴의 공통 정보를 등록합니다. 저장하면 보관 목록에 추가됩니다.")
        TireFormFields(form, vm.today(), allowSetPressure = allowPressure)
    }
}

private enum class Work(val label: String) {
    REFILL("보충"), REPAIR("수리"), ROTATION("위치교환"), REPLACE("타이어 교체"), SET_CHANGE("세트 교체"), NUT("너트 점검")
}

@Composable
private fun WheelPicker(tires: Map<WheelPosition, Tire>, selected: Set<WheelPosition>, onToggle: (WheelPosition) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            listOf(WheelPosition.FRONT_LEFT, WheelPosition.FRONT_RIGHT),
            listOf(WheelPosition.REAR_LEFT, WheelPosition.REAR_RIGHT),
        ).forEach { row ->
            ChoiceChips(
                row.map { it to (it.label + (tires[it]?.let { t -> " · 타이어 ${t.label}" } ?: "")) },
                selected,
                onToggle = onToggle,
            )
        }
    }
}

/** S4 작업 기록(체크인). 바퀴 위치는 작업 전 위치 기준이다. */
@Composable
fun CheckInScreen(vm: MainViewModel, preset: CheckInPreset, onBack: () -> Unit, toast: (String) -> Unit) {
    val tires by vm.mountedTires.collectAsStateWithLifecycle()
    val sets by vm.allSets.collectAsStateWithLifecycle()
    val mounted by vm.mountedSet.collectAsStateWithLifecycle()
    val rotationHistory by vm.rotationHistory.collectAsStateWithLifecycle()
    val today = remember { vm.today() }
    var works by rememberSaveable(preset) {
        mutableStateOf(
            when (preset) {
                CheckInPreset.REFILL -> setOf(Work.REFILL)
                CheckInPreset.ROTATION -> setOf(Work.ROTATION)
                CheckInPreset.NONE -> emptySet()
            },
        )
    }
    var refill by rememberSaveable { mutableStateOf(WheelPosition.entries.toSet()) }
    var repair by rememberSaveable { mutableStateOf(setOf<WheelPosition>()) }
    var pattern by rememberSaveable { mutableStateOf(RotationPattern.FRONT_REAR) }
    var showRotationHistory by rememberSaveable { mutableStateOf(false) }
    var replace by rememberSaveable { mutableStateOf(setOf<WheelPosition>()) }
    var replaceDot by rememberSaveable { mutableStateOf("") }
    var replaceDotUnknown by rememberSaveable { mutableStateOf(false) }
    var target by rememberSaveable { mutableStateOf<Long?>(null) }
    val candidates = sets.filter { !it.retired && it.id != mounted?.id }

    fun toggle(set: Set<WheelPosition>, p: WheelPosition) = if (p in set) set - p else set + p

    ScreenFrame("작업 기록", onBack) {
        Muted("오늘 한 작업을 모두 고르세요. 기록하면 해당 타이어의 추세 기준을 새로 잡습니다.")
        SectionTitle("작업")
        ChoiceChips(Work.entries.map { it to it.label }, works, onToggle = { w -> works = if (w in works) works - w else works + w })

        if (Work.REFILL in works) {
            SectionTitle("보충한 바퀴")
            WheelPicker(tires, refill) { refill = toggle(refill, it) }
        }
        if (Work.REPAIR in works) {
            SectionTitle("수리한 바퀴(작업 전 위치)")
            WheelPicker(tires, repair) { repair = toggle(repair, it) }
        }
        if (Work.ROTATION in works) {
            RotationHistorySummary(rotationHistory, onShowHistory = { showRotationHistory = true })
            SectionTitle("교환 방식")
            RotationPatternPicker(pattern, onSelect = { pattern = it }, tireLabels = tires.mapValues { it.value.label })
            Muted(pattern.description)
            tires.entries.sortedBy { it.key.ordinal }.forEach { (position, tire) ->
                Text("타이어 ${tire.label}  ${position.label} → ${pattern.destinationOf(position).label}", style = MaterialTheme.typography.bodyLarge)
            }
        }
        if (Work.REPLACE in works) {
            SectionTitle("새 타이어로 바꾼 바퀴")
            WheelPicker(tires, replace) { replace = toggle(replace, it) }
            DotField(replaceDot, replaceDotUnknown, today, { replaceDot = it }, { replaceDotUnknown = it })
        }
        if (Work.SET_CHANGE in works) {
            SectionTitle("새로 낀 세트")
            if (candidates.isEmpty()) {
                Muted("바꿔 낄 세트가 없습니다. 먼저 세트를 추가하세요.")
            } else {
                ChoiceChips(
                    candidates.map { it.id to listOfNotNull(it.title, it.dot?.let { d -> "DOT $d" }).joinToString(" · ") },
                    setOfNotNull(target),
                    onToggle = { target = it },
                )
            }
            BigButton("세트 추가", { vm.open(Dest.AddSet) })
        }
        if (Work.NUT in works) {
            SectionTitle("너트 점검")
            Muted("교체·위치교환 뒤 50km를 달린 다음 휠 너트 조임을 다시 확인했으면 기록하세요.")
        }

        val nutConflict = Work.NUT in works && works.any { it in setOf(Work.ROTATION, Work.REPLACE, Work.SET_CHANGE) }
        val valid = works.isNotEmpty() &&
            mounted != null && !nutConflict &&
            (Work.REFILL !in works || refill.isNotEmpty()) &&
            (Work.REPAIR !in works || repair.isNotEmpty()) &&
            (Work.REPLACE !in works || (replace.isNotEmpty() && dotReady(replaceDot, replaceDotUnknown, today))) &&
            (Work.SET_CHANGE !in works || candidates.any { it.id == target })
        Spacer(Modifier.height(20.dp))
        if (!valid) Muted(
            when {
                mounted == null -> "장착 세트를 불러오는 중입니다. 초기 설정이 끝났는지 확인해 주세요."
                nutConflict -> "너트 점검은 타이어 작업 뒤 50km를 달린 후 별도로 기록해 주세요."
                works.isEmpty() -> "기록할 작업을 하나 이상 골라 주세요."
                Work.SET_CHANGE in works && candidates.none { it.id == target } -> "새로 장착한 세트를 골라 주세요."
                Work.REPLACE in works && !dotReady(replaceDot, replaceDotUnknown, today) -> "새 타이어의 DOT를 입력하거나 '제조 연식 모름'을 골라 주세요."
                else -> "각 작업에 해당하는 바퀴를 하나 이상 골라 주세요."
            },
        )
        BigButton(
            "저장",
            {
                val request = CheckInRequest(
                    refill = if (Work.REFILL in works) refill else emptySet(),
                    repair = if (Work.REPAIR in works) repair else emptySet(),
                    rotation = if (Work.ROTATION in works) pattern else null,
                    replacements = if (Work.REPLACE in works) {
                        replace.associateWith { if (replaceDotUnknown) null else replaceDot }
                    } else {
                        emptyMap()
                    },
                    changeToSetId = if (Work.SET_CHANGE in works) target else null,
                    nutCheck = Work.NUT in works,
                )
                vm.checkIn(request) { message ->
                    toast(message)
                    onBack()
                }
            },
            primary = true,
            enabled = valid,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (showRotationHistory) {
        RotationHistoryDialog(rotationHistory, onDismiss = { showRotationHistory = false }, onSelect = {
            pattern = it
            showRotationHistory = false
        })
    }
}

/** S5 정비사 증상 카드(MVP 추가). 정비소에서 보여 줄 한 화면. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MechanicScreen(vm: MainViewModel, onBack: () -> Unit) {
    val card by vm.mechanic.collectAsStateWithLifecycle()
    ScreenFrame("정비사 증상 카드", onBack) {
        val c = card ?: return@ScreenFrame Loading()
        Text(c.header, style = MaterialTheme.typography.titleLarge)
        Muted("차량이 매일 아침 잰 냉간 공기압 기록입니다(기온 보정 포함). 진단을 대신하지 않습니다.")
        Spacer(Modifier.height(12.dp))
        c.rows.forEach { row ->
            Surface(shape = RoundedCornerShape(18.dp), color = TirelogColors.Surface, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${row.position.label} · 타이어 ${row.tireLabel}", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        row.flag?.let {
                            Text(it, style = MaterialTheme.typography.titleMedium, color = if (it == "누설 의심") TirelogColors.Red else TirelogColors.Amber)
                        }
                    }
                    Text(
                        if (row.recentPsi.isEmpty()) "최근 냉간 측정 없음" else "최근 ${row.recentPsi.size}회 " + row.recentPsi.joinToString(" → ") { "$it" } + " psi",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    row.trendText?.let { Text("기온 보정 후 $it", style = MaterialTheme.typography.bodyLarge) }
                    Muted(
                        listOfNotNull(
                            row.lastRefill?.let { "마지막 보충 $it" },
                            row.lastRepair?.let { "마지막 수리 $it" },
                        ).joinToString(" · ").ifEmpty { "보충·수리 기록 없음" },
                    )
                }
            }
        }
        SectionTitle("위치교환")
        Text(c.rotation, style = MaterialTheme.typography.bodyLarge)
        SectionTitle("최근 작업")
        if (c.recentEvents.isEmpty()) Muted("기록 없음")
        c.recentEvents.forEach { Text(it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 2.dp)) }
        Spacer(Modifier.height(16.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigButton("작업 기록", { vm.open(Dest.CheckIn()) }, primary = true)
        }
    }
}
