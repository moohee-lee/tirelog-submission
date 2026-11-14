package com.tirelog.app.ui.main

import android.app.DatePickerDialog
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tirelog.app.R
import com.tirelog.app.ui.common.*
import com.tirelog.app.ui.theme.TirelogColors
import com.tirelog.core.maintenance.DotCode
import com.tirelog.core.catalog.TireLifeGuides
import com.tirelog.core.catalog.TireManufacturers
import com.tirelog.core.model.TireSetType
import java.text.NumberFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** 차량의 넓은 화면에서는 한 줄로, 좁은 화면과 큰 글씨에서는 라벨을 위로 배치한다. */
@Composable
internal fun RegistrationRow(label: String, labelWidth: Dp = 152.dp,
    content: @Composable (Modifier) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth / LocalDensity.current.fontScale >= 640.dp) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(label, Modifier.width(labelWidth), style = MaterialTheme.typography.bodyLarge)
                content(Modifier.weight(1f))
            }
        } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            content(Modifier.fillMaxWidth())
        }
    }
}

@Composable
internal fun TireModelAndTypeFields(form: TireFormState) {
    RegistrationRow("모델명") { modifier ->
        BoxWithConstraints(modifier) {
            val wide = maxWidth / LocalDensity.current.fontScale >= 480.dp
            val fields: @Composable (Modifier, Modifier) -> Unit = { modelModifier, typeModifier ->
                TireModelSelector(form.manufacturer, form.model, form::selectModel, form::enterCustomModel,
                    modelModifier, showLabel = false)
                TireTypeSelector(form.type, form::selectType, typeModifier)
            }
            if (wide) Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                fields(Modifier.weight(1f), Modifier.width(216.dp))
            } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                fields(Modifier.fillMaxWidth(), Modifier.fillMaxWidth())
            }
        }
    }
    if (form.type.isBlank() && form.model.isNotBlank()) Text("타이어 종류를 선택해 주세요.",
        style = MaterialTheme.typography.bodyMedium, color = TirelogColors.Muted)
}

@Composable
private fun TireTypeSelector(value: String, onValue: (String) -> Unit, modifier: Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val current = TireSetType.entries.find { it.name == value }
    Box(modifier) {
        Surface(onClick = { focus.clearFocus(); expanded = true }, enabled = LocalActionsEnabled.current,
            shape = RoundedCornerShape(16.dp), color = TirelogColors.SurfaceHigh,
            modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp).testTag("tire-type-selector")
                .semantics { contentDescription = "타이어 종류, ${current?.label ?: "선택 필요"}"; selected = current != null }) {
            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(current?.label ?: "종류 선택", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                TireLineIcon(TireIcon.CHEVRON)
            }
        }
        DropdownMenu(expanded, { expanded = false }) {
            TireSetType.entries.forEach { type ->
                DropdownMenuItem(text = { Text(type.label, style = MaterialTheme.typography.bodyLarge) },
                    onClick = { expanded = false; onValue(type.name) }, enabled = LocalActionsEnabled.current,
                    modifier = Modifier.heightIn(min = 88.dp).testTag("tire-type-option-${type.name}")
                        .semantics { selected = value == type.name })
            }
        }
    }
}

internal enum class TireHelp(val title: String, val image: Int, val description: String, val tag: String) {
    DOT("제조년식 확인 방법", R.drawable.tire_manufacture_guide,
        "타이어 옆면 DOT 끝의 네 자리 예시 1825. 앞의 18은 생산 18주차, 뒤의 25는 2025년으로, 2025년 18주차 제조를 뜻합니다.", "tire-dot-guide"),
    SIZE("타이어 규격 확인 방법", R.drawable.tire_size_guide,
        "타이어 옆면 규격 예시 225/45R18. 단면 폭 225밀리미터, 편평비 45퍼센트, 휠 지름 18인치입니다.", "tire-size-guide"),
}

@Composable
internal fun TireHelpButton(help: TireHelp) {
    var open by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    Surface(onClick = { focus.clearFocus(); open = true }, enabled = LocalActionsEnabled.current,
        shape = RoundedCornerShape(16.dp), color = TirelogColors.SurfaceHigh,
        modifier = Modifier.size(88.dp).testTag("${help.tag}-button").semantics { contentDescription = help.title }) {
        Box(contentAlignment = Alignment.Center) { Text("?", style = MaterialTheme.typography.titleLarge) }
    }
    if (open) PageDialog(help.title, { open = false }, scrollable = false,
        footer = { BigButton("닫기", { open = false }, Modifier.fillMaxWidth()) }) {
        Image(painterResource(help.image), contentDescription = help.description, contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().weight(1f).testTag(help.tag))
    }
}

@Composable
internal fun RegistrationDotField(value: String, today: LocalDate, onValue: (String) -> Unit) {
    val focus = LocalFocusManager.current
    val error = (DotCode.parse(value, today) as? DotCode.Companion.Result.Invalid)?.reason.takeIf { value.isNotBlank() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RegistrationRow("제조년식") { modifier ->
            Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value, { onValue(it.filter { c -> c in '0'..'9' }.take(4)) },
                    enabled = LocalActionsEnabled.current, singleLine = true, isError = error != null,
                    textStyle = MaterialTheme.typography.bodyLarge, shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                    modifier = Modifier.weight(1f).heightIn(min = 88.dp).testTag("tire-dot-input")
                        .semantics { contentDescription = "제조년식" })
                TireHelpButton(TireHelp.DOT)
            }
        }
        if (error != null) Text(error, color = TirelogColors.Red, style = MaterialTheme.typography.bodyMedium)
    }
}

/** 숫자는 본문에서 바로 입력하고, 저장은 등록 화면의 기본 버튼으로 한 번만 한다. */
internal enum class RegistrationNumber(
    val label: String, val unit: String, val tag: String, val range: IntRange,
    val presets: List<Int>, val integral: Boolean = true, val optional: Boolean = false,
) {
    MILEAGE("현재까지 주행거리", "km", "registration-mileage", 0..1_000_000,
        listOf(0, 10_000, 20_000, 40_000), integral = false, optional = true),
    WARRANTY_KM("보증 거리", "km", "registration-warranty-km", 1_000..200_000,
        listOf(30_000, 40_000, 50_000, 60_000)),
    MONTHS("보증 기간", "개월", "registration-warranty-months", 1..120, listOf(36, 48, 60)),
    ;

    fun error(value: String): String? {
        if (value.isBlank()) return if (optional) null else "값을 입력해 주세요."
        val number = value.toDoubleOrNull()
        return when {
            number == null || !number.isFinite() -> "숫자로 입력해 주세요."
            integral && value.toIntOrNull() == null -> "정수로 입력해 주세요."
            number !in range.first.toDouble()..range.last.toDouble() ->
                "${formatRegistrationNumber(range.first)}~${formatRegistrationNumber(range.last)} $unit 범위로 입력해 주세요."
            else -> null
        }
    }
}

private fun formatRegistrationNumber(value: Number): String = NumberFormat.getNumberInstance(Locale.KOREA).format(value)

/** 표시만 천 단위로 묶고, 저장할 숫자와 커서 위치는 그대로 보존한다. */
internal object RegistrationNumberTransformation : VisualTransformation {
    private val numeric = Regex("[0-9]+(\\.[0-9]*)?")
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        if (!numeric.matches(raw)) return TransformedText(text, OffsetMapping.Identity)
        val integerEnd = raw.indexOf('.').takeIf { it >= 0 } ?: raw.length
        val originalOffsets = IntArray(raw.length + 1)
        val transformedOffsets = mutableListOf(0)
        val formatted = buildString {
            raw.forEachIndexed { index, char ->
                if (index > 0 && index < integerEnd && (integerEnd - index) % 3 == 0) {
                    append(',')
                    transformedOffsets.add(index)
                }
                originalOffsets[index] = length
                append(char)
                transformedOffsets.add(index + 1)
            }
            originalOffsets[raw.length] = length
        }
        return TransformedText(AnnotatedString(formatted), object : OffsetMapping {
            override fun originalToTransformed(offset: Int) = originalOffsets[offset.coerceIn(originalOffsets.indices)]
            override fun transformedToOriginal(offset: Int) = transformedOffsets[offset.coerceIn(transformedOffsets.indices)]
        })
    }
}

@Composable
internal fun RegistrationNumberField(kind: RegistrationNumber, value: String, onValue: (String) -> Unit,
    modifier: Modifier = Modifier, requester: FocusRequester = remember { FocusRequester() },
    next: FocusRequester? = null, onFocus: (Boolean) -> Unit = {}) {
    val focus = LocalFocusManager.current
    var input by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(value)) }
    var focused by remember { mutableStateOf(false) }
    var touched by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(value) {
        if (value != input.text) input = TextFieldValue(value, TextRange(value.length))
    }
    val error = kind.error(value).takeIf { touched && !focused }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(input, {
            val textChanged = input.text != it.text
            input = it
            if (textChanged) onValue(it.text)
        }, enabled = LocalActionsEnabled.current, singleLine = true,
            placeholder = { Text(if (kind.optional) "모름" else "입력", color = TirelogColors.Muted) },
            suffix = { Text(kind.unit, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyLarge, color = TirelogColors.Muted) },
            visualTransformation = RegistrationNumberTransformation,
            isError = error != null,
            textStyle = MaterialTheme.typography.bodyLarge.copy(textAlign = TextAlign.End),
            shape = RoundedCornerShape(14.dp),
            keyboardOptions = KeyboardOptions(keyboardType = if (kind.integral) KeyboardType.Number else KeyboardType.Decimal,
                imeAction = if (next == null) ImeAction.Done else ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { next?.requestFocus() }, onDone = { focus.clearFocus() }),
            modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp).testTag(kind.tag).focusRequester(requester)
                .onFocusChanged {
                    if (it.isFocused && !focused) {
                        input = input.copy(selection = TextRange(0, input.text.length))
                        touched = true
                    }
                    focused = it.isFocused
                    onFocus(it.isFocused)
                }.semantics { contentDescription = kind.label })
        if (error != null) Text(error, color = TirelogColors.Red, style = MaterialTheme.typography.bodyMedium)
    }
}

/** 현재 입력 항목의 바로 아래에서 자주 쓰는 값만 제안한다. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RegistrationNumberShortcuts(kind: RegistrationNumber, value: String, onValue: (String) -> Unit) {
    val focus = LocalFocusManager.current
    val enabled = LocalActionsEnabled.current
    FlowRow(Modifier.fillMaxWidth().testTag("${kind.tag}-shortcuts"),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        kind.presets.forEach { preset ->
            OutlinedButton(onClick = { focus.clearFocus(); onValue(preset.toString()) }, enabled = enabled,
                modifier = Modifier.heightIn(min = 88.dp).testTag("${kind.tag}-preset-$preset"),
                shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(horizontal = 18.dp)) {
                Text("${formatRegistrationNumber(preset)} ${kind.unit}", style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (kind.optional) TextButton(onClick = { focus.clearFocus(); onValue("") }, enabled = enabled,
            modifier = Modifier.heightIn(min = 88.dp).testTag("${kind.tag}-unknown")) {
            Text("모름", style = MaterialTheme.typography.bodyMedium)
        }
        if (kind == RegistrationNumber.MONTHS) {
            val current = value.toIntOrNull()
            for (delta in listOf(-1, 1)) OutlinedButton(
                onClick = { onValue(((current ?: kind.range.first) + delta).coerceIn(kind.range).toString()) },
                enabled = enabled && (current == null || if (delta < 0) current > kind.range.first else current < kind.range.last),
                modifier = Modifier.size(88.dp).semantics { contentDescription = "보증 기간 ${if (delta < 0) "줄이기" else "늘리기"}" },
                shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(0.dp)) {
                Text(if (delta < 0) "−" else "+", style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
internal fun RegistrationFirstUseField(form: TireFormState, today: LocalDate) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val actionsEnabled by rememberUpdatedState(LocalActionsEnabled.current)
    val date = firstUseDate(form.firstUse)
    val error = firstUseError(form.firstUse, today, form.dot.ifBlank { null })
    val earliest = maxOf(LocalDate.of(2000, 1, 1), DotCode.parseOrNull(form.dot, today)?.manufactureDate ?: LocalDate.of(2000, 1, 1))
    val open = {
        focus.clearFocus()
        val initial = (date ?: today).coerceIn(earliest, today)
        DatePickerDialog(context, { _, y, m, d -> if (actionsEnabled) form.selectFirstUse(LocalDate.of(y, m + 1, d).toString()) },
            initial.year, initial.monthValue - 1, initial.dayOfMonth).apply {
            datePicker.minDate = earliest.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            datePicker.maxDate = today.atTime(23, 59).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            setButton(android.content.DialogInterface.BUTTON_NEUTRAL, "모름") { _, _ -> if (actionsEnabled) form.selectFirstUse("") }
        }.show()
    }
    Surface(onClick = open, enabled = LocalActionsEnabled.current, color = TirelogColors.Surface,
        modifier = Modifier.fillMaxWidth().testTag("first-use-date").semantics { contentDescription = "첫 장착일, ${date ?: "미입력"}" }) {
        RegistrationRow("첫 장착일", labelWidth = 256.dp) { modifier ->
            Surface(shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, TirelogColors.Outline), color = TirelogColors.Surface,
                modifier = modifier.heightIn(min = 88.dp)) {
                Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(date?.toString() ?: "yyyy-mm-dd", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
                        color = if (date == null) TirelogColors.Muted else TirelogColors.OnSurface)
                    Icon(painterResource(R.drawable.ic_calendar), null, Modifier.size(28.dp))
                }
            }
        }
    }
    if (error != null) Text(error, color = TirelogColors.Red, style = MaterialTheme.typography.bodyMedium)
}

@Composable
internal fun RegistrationUsageFields(form: TireFormState, today: LocalDate) {
    val mileage = remember { FocusRequester() }
    val distance = remember { FocusRequester() }
    val months = remember { FocusRequester() }
    var active by remember { mutableStateOf<RegistrationNumber?>(null) }
    fun focused(kind: RegistrationNumber, hasFocus: Boolean) {
        if (hasFocus) active = kind else if (active == kind) active = null
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { RegistrationFirstUseField(form, today) }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RegistrationRow("현재까지 주행거리", labelWidth = 256.dp) { modifier ->
                RegistrationNumberField(RegistrationNumber.MILEAGE, if (form.usage == "unknown") "" else form.km,
                    form::selectMileage, modifier, mileage, distance) { focused(RegistrationNumber.MILEAGE, it) }
            }
            if (active == RegistrationNumber.MILEAGE)
                RegistrationNumberShortcuts(RegistrationNumber.MILEAGE, form.km, form::selectMileage)
        }
        HorizontalDivider(color = TirelogColors.Outline)
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("보증 정보", style = MaterialTheme.typography.titleMedium)
            val guides = TireManufacturers.find(form.manufacturer)?.lifeGuideIds.orEmpty()
            if (guides.size > 1) SelectionMenu("기준 선택", TireLifeGuides.find(form.guideId).label,
                guides.map { it to TireLifeGuides.find(it).label }, form::selectLifeGuide)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val fields: @Composable (Modifier) -> Unit = { modifier ->
                    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("거리", style = MaterialTheme.typography.bodyLarge)
                        RegistrationNumberField(RegistrationNumber.WARRANTY_KM, form.limitKm, { form.limitKm = it },
                            Modifier.weight(1f), distance, months) { focused(RegistrationNumber.WARRANTY_KM, it) }
                    }
                    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("기간", style = MaterialTheme.typography.bodyLarge)
                        RegistrationNumberField(RegistrationNumber.MONTHS, form.months, { form.months = it },
                            Modifier.weight(1f), months) { focused(RegistrationNumber.MONTHS, it) }
                    }
                }
                if (maxWidth / LocalDensity.current.fontScale >= 640.dp) {
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) { fields(Modifier.weight(1f)) }
                } else Column(verticalArrangement = Arrangement.spacedBy(16.dp)) { fields(Modifier.fillMaxWidth()) }
            }
            when (active) {
                RegistrationNumber.WARRANTY_KM -> RegistrationNumberShortcuts(RegistrationNumber.WARRANTY_KM, form.limitKm) { form.limitKm = it }
                RegistrationNumber.MONTHS -> RegistrationNumberShortcuts(RegistrationNumber.MONTHS, form.months) { form.months = it }
                else -> Unit
            }
            Muted("입력값은 교체 점검 알림 기준으로 사용합니다.")
        }
    }
}
