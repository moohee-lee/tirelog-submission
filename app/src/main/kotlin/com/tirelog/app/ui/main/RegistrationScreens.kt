package com.tirelog.app.ui.main

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tirelog.app.ui.common.*
import com.tirelog.core.engine.NewSetInput
import com.tirelog.core.model.TireSetType
import com.tirelog.core.model.WheelPosition

@Composable
fun EditRegistrationScreen(vm: MainViewModel, setId: Long, tireId: Long?, onBack: () -> Unit) {
    val sets by vm.allSets.collectAsStateWithLifecycle()
    val tires by vm.allTires.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val set = sets.firstOrNull { it.id == setId }
    val tire = tires.firstOrNull { it.id == tireId }
    val title = if (tireId == null) "타이어 정보 수정" else "${tire?.position?.label.orEmpty()} 정보 수정"
    if (set == null || (tireId != null && tire == null)) {
        ScreenFrame(title, onBack) { Loading() }
        return
    }
    val form = rememberTireForm("edit:$setId:$tireId", set.registrationInput(tire))
    val allowPressure = tireId == null && settings?.pressureSupported == true
    TireRegistrationFrame(title, onBack, footer = {
        BigButton("저장", { vm.saveRegistration(setId, tireId, form.value(), onBack) }, Modifier.fillMaxWidth(),
            primary = true, enabled = form.valid(vm.today()))
    }) {
        Muted(if (tire == null) "공통 정보를 수정합니다. 개별 수정한 타이어의 정보는 유지됩니다." else "타이어 ${tire.label} 한 개의 정보만 수정합니다. 장착 위치는 바뀌지 않습니다.")
        TireFormFields(form, vm.today(), editing = !set.newUntilMounted || tireId != null,
            allowSetPressure = allowPressure)
    }
}

@Composable
fun ReplacementScreen(vm: MainViewModel, onBack: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val sets by vm.allSets.collectAsStateWithLifecycle()
    val mounted by vm.mountedSet.collectAsStateWithLifecycle()
    var mode by rememberSaveable { mutableStateOf("new") }
    var positions by rememberSaveable { mutableStateOf(WheelPosition.entries.toSet()) }
    var selectedSet by rememberSaveable { mutableStateOf<Long?>(null) }
    var discard by rememberSaveable { mutableStateOf(false) }
    val form = rememberTireForm("replacement", NewSetInput(TireSetType.ALL_SEASON, null, null, newUntilMounted = true), requireTypeSelection = true)
    val stored = sets.filter { !it.retired && it.id != mounted?.id }
    ScreenFrame("타이어 교체", onBack) {
        Text("교체한 타이어를 기록하세요", style = MaterialTheme.typography.titleMedium)
        Muted("완료하면 등록 정보와 현재 장착 상태를 함께 저장합니다.")
        SectionTitle("장착할 타이어")
        ChoiceChips(listOf("new" to "새로 등록", "stored" to "보관 타이어 장착"), setOf(mode), onToggle = { mode = it })
        if (mode == "new") {
            SectionTitle("교체한 위치")
            ChoiceChips(WheelPosition.entries.map { it to it.label }, positions, onToggle = {
                positions = if (it in positions) positions - it else positions + it
            })
            Spacer(Modifier.height(16.dp))
            TireFormFields(form, vm.today(), allowSetPressure = positions.size == 4 && settings?.pressureSupported == true)
        } else {
            Muted("등록된 네 바퀴를 함께 장착하며 이전 누적 거리를 이어 갑니다.")
            if (stored.isEmpty()) Muted("보관 중인 타이어가 없습니다. 새로 등록을 선택해 주세요.")
            else SelectionMenu("보관 타이어", stored.firstOrNull { it.id == selectedSet }?.displayName() ?: "타이어 선택",
                stored.map { it.id to it.displayName() }) { selectedSet = it }
        }
        SectionTitle("기존 타이어 처리")
        ChoiceChips(listOf(false to "보관", true to "폐기"), setOf(discard), onToggle = { discard = it })
        Muted(if (discard) "폐기해도 과거 측정·정비 기록은 유지됩니다." else "사용 기록을 보존합니다. 보관 세트는 나중에 다시 장착할 수 있습니다.")
        val valid = mounted != null && if (mode == "new") positions.isNotEmpty() && form.valid(vm.today()) else stored.any { it.id == selectedSet }
        Spacer(Modifier.height(20.dp))
        BigButton("교체 완료", {
            vm.replaceTires(if (mode == "new") positions else WheelPosition.entries.toSet(),
                if (mode == "new") form.value() else null, if (mode == "stored") selectedSet else null, discard, onBack)
        }, Modifier.fillMaxWidth(), primary = true, enabled = valid)
    }
}
