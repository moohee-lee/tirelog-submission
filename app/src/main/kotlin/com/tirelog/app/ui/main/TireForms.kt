package com.tirelog.app.ui.main

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tirelog.app.ui.common.*
import com.tirelog.app.ui.theme.TirelogColors
import com.tirelog.core.catalog.TireLifeGuides
import com.tirelog.core.catalog.TireManufacturers
import com.tirelog.core.catalog.TireModel
import com.tirelog.core.engine.NewSetInput
import com.tirelog.core.maintenance.DotCode
import com.tirelog.core.model.Tire
import com.tirelog.core.model.TireSet
import com.tirelog.core.model.TireSetType
import com.tirelog.core.Units
import java.time.LocalDate

internal fun TireSet.registrationInput(tire: Tire? = null): NewSetInput {
    val p = tire?.profile
    return NewSetInput(
        type = p?.type ?: type, size = if (p == null) size else p.size, dot = if (tire == null) dot else tire.dot,
        initialKm = p?.initialKm ?: initialKm, initialKmKnown = p?.initialKmKnown ?: initialKmKnown,
        manufacturer = if (p == null) manufacturer else p.manufacturer, tireModel = if (p == null) tireModel else p.tireModel,
        firstUsedEpochDay = if (p == null) firstUsedEpochDay else p.firstUsedEpochDay,
        lifeGuideId = p?.lifeGuideId ?: lifeGuideId, inspectionKm = p?.inspectionKm ?: inspectionKm,
        inspectionMonths = p?.inspectionMonths ?: inspectionMonths,
        recommendedFrontKpa = recommendedFrontKpa, recommendedRearKpa = recommendedRearKpa,
        newUntilMounted = tire == null && newUntilMounted,
    )
}

internal fun TireSet.displayName(): String = listOfNotNull(
    manufacturer ?: TireLifeGuides.find(lifeGuideId).label.takeUnless { lifeGuideId == "app" },
    tireModel, "${type.label} 타이어 #$id",
).joinToString(" · ")

@Stable
internal class TireFormState(input: NewSetInput, typeInitiallyConfirmed: Boolean = true) {
    var guideId by mutableStateOf(input.lifeGuideId)
    var manufacturer by mutableStateOf(input.manufacturer ?: TireLifeGuides.find(input.lifeGuideId)
        .takeUnless { it.id == TireLifeGuides.DEFAULT_ID }?.label?.substringBefore(" ·").orEmpty())
    var model by mutableStateOf(input.tireModel ?: "")
    var modelTypeAutomatic by mutableStateOf(false)
    var type by mutableStateOf(if (typeInitiallyConfirmed) input.type.name else "")
    var manualType by mutableStateOf(typeInitiallyConfirmed)
    var dot by mutableStateOf(input.dot ?: "")
    var usage by mutableStateOf(if (input.newUntilMounted) "new" else if (input.initialKmKnown) "used" else "unknown")
    var km by mutableStateOf(input.initialKm.toString())
    var firstUse by mutableStateOf(input.firstUsedEpochDay?.let { LocalDate.ofEpochDay(it).toString() } ?: "")
    var limitKm by mutableStateOf(input.inspectionKm.toString())
    var months by mutableStateOf(input.inspectionMonths.toString())
    var size by mutableStateOf(input.size ?: "")
    var customPressure by mutableStateOf(input.recommendedFrontKpa != null)
    var frontPsi by mutableStateOf(input.recommendedFrontKpa?.let { Units.kpaToPsi(it).toString() } ?: "36")
    var rearPsi by mutableStateOf(input.recommendedRearKpa?.let { Units.kpaToPsi(it).toString() } ?: "36")
    var advanced by mutableStateOf(false)

    fun selectManufacturer(name: String) {
        val entry = TireManufacturers.find(name)
        val previous = TireManufacturers.find(manufacturer)?.id ?: manufacturer.trim().lowercase(java.util.Locale.ROOT)
        val next = entry?.id ?: name.trim().lowercase(java.util.Locale.ROOT)
        if (previous != next) {
            model = ""
            if (!manualType) type = ""
            modelTypeAutomatic = false
        }
        manufacturer = entry?.name ?: name.trim()
        val guides = entry?.lifeGuideIds ?: listOf(TireLifeGuides.DEFAULT_ID)
        selectLifeGuide(guideId.takeIf { it in guides } ?: guides.first())
    }

    fun selectModel(entry: TireModel) {
        require(entry.manufacturerId == TireManufacturers.find(manufacturer)?.id)
        model = entry.name
        type = entry.type?.name.orEmpty()
        modelTypeAutomatic = entry.type != null
        manualType = false
    }

    fun enterCustomModel(name: String) {
        model = name.trim()
        if (!manualType) type = ""
        modelTypeAutomatic = false
    }

    fun selectType(value: String) {
        type = value
        manualType = true
        modelTypeAutomatic = false
    }

    fun selectMileage(value: String) {
        km = value
        usage = when {
            value.isBlank() -> "unknown"
            usage == "new" && value.toDoubleOrNull() == 0.0 && firstUse.isBlank() -> "new"
            else -> "used"
        }
    }

    fun selectFirstUse(value: String) {
        firstUse = value
        if (usage == "new" && value.isNotBlank()) usage = if (km.isBlank()) "unknown" else "used"
    }

    fun selectLifeGuide(id: String) {
        if (id == guideId) return
        val guide = TireLifeGuides.find(id)
        guideId = guide.id
        limitKm = guide.defaultKm.toString()
        months = guide.defaultMonths.toString()
    }

    fun value(): NewSetInput = NewSetInput(
        type = TireSetType.valueOf(type), size = size.trim().ifBlank { null }, dot = dot.ifBlank { null },
        initialKm = if (usage == "used") km.toDoubleOrNull() ?: 0.0 else 0.0,
        initialKmKnown = usage != "unknown", newUntilMounted = usage == "new",
        firstUsedEpochDay = if (usage == "new") null else firstUseDate(firstUse)?.toEpochDay(),
        lifeGuideId = guideId, manufacturer = manufacturer.trim().ifBlank { null }, tireModel = model.trim().ifBlank { null },
        inspectionKm = limitKm.toIntOrNull() ?: TireSet.DEFAULT_INSPECTION_KM,
        inspectionMonths = months.toIntOrNull() ?: TireSet.DEFAULT_INSPECTION_MONTHS,
        recommendedFrontKpa = if (customPressure) frontPsi.toDoubleOrNull()?.let(Units::psiToKpa) else null,
        recommendedRearKpa = if (customPressure) rearPsi.toDoubleOrNull()?.let(Units::psiToKpa) else null,
    )

    fun valid(today: LocalDate): Boolean = TireSetType.entries.any { it.name == type } &&
        (dot.isBlank() || DotCode.parse(dot, today) is DotCode.Companion.Result.Valid) &&
        tireSizeError(size) == null &&
        (usage != "used" || km.isNotBlank() && RegistrationNumber.MILEAGE.error(km) == null) &&
        (usage == "new" || firstUseError(firstUse, today, dot.ifBlank { null }) == null) &&
        RegistrationNumber.WARRANTY_KM.error(limitKm) == null && RegistrationNumber.MONTHS.error(months) == null &&
        (!customPressure || listOf(frontPsi, rearPsi).all { it.toDoubleOrNull()?.let { v -> v.isFinite() && v > 0 && v <= 100 } == true })

    companion object {
        val Saver = listSaver<TireFormState, String>(
            save = { listOf(it.guideId, it.manufacturer, it.model, it.type, it.dot, it.usage, it.km,
                it.firstUse, it.limitKm, it.months, it.size, it.advanced.toString(), it.customPressure.toString(), it.frontPsi, it.rearPsi,
                it.modelTypeAutomatic.toString(), it.manualType.toString()) },
            restore = { values -> TireFormState(NewSetInput(TireSetType.ALL_SEASON, null, null)).apply {
                guideId = values[0]; manufacturer = values[1]; model = values[2]; type = values[3]
                dot = values[4]; usage = values[5]; km = values[6]; firstUse = values[7]
                limitKm = values[8]; months = values[9]; size = values[10]; advanced = values[11].toBoolean()
                customPressure = values.getOrNull(12)?.toBoolean() ?: false
                frontPsi = values.getOrNull(13) ?: "36"; rearPsi = values.getOrNull(14) ?: "36"
                modelTypeAutomatic = values.getOrNull(15)?.toBoolean() ?: false
                manualType = values.getOrNull(16)?.toBoolean() ?: !modelTypeAutomatic
            } },
        )
    }
}

@Composable
internal fun rememberTireForm(key: Any?, initial: NewSetInput? = null, requireTypeSelection: Boolean = initial == null): TireFormState =
    rememberSaveable(key, saver = TireFormState.Saver) {
        TireFormState(initial ?: NewSetInput(TireSetType.ALL_SEASON, null, null, initialKmKnown = false),
            typeInitiallyConfirmed = !requireTypeSelection)
    }

@Composable
internal fun TireFormFields(form: TireFormState, today: LocalDate, editing: Boolean = false,
    allowSetPressure: Boolean = false, modifier: Modifier = Modifier) {
    AdaptiveColumns(modifier = modifier, first = {
        RegistrationPanel("타이어 정보", "registration-tire-panel") {
            RegistrationRow("제조사") { modifier ->
                TireManufacturerSelector(form.manufacturer, form::selectManufacturer, modifier, showLabel = false)
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { TireModelAndTypeFields(form) }
            DotSelector(form.dot, today) { form.dot = it }
            TireSizeFields(form.size) { form.size = it }
        }
    }, second = {
        RegistrationPanel("사용 정보", "registration-usage-panel") {
            RegistrationUsageFields(form, today)
            if (allowSetPressure) {
                TextButton(onClick = { form.advanced = !form.advanced }, enabled = LocalActionsEnabled.current,
                    modifier = Modifier.heightIn(min = 56.dp)) {
                    Text(if (form.advanced) "추가 설정 접기" else "추가 설정 · 권장 공기압", style = MaterialTheme.typography.bodyLarge)
                }
                if (form.advanced) {
                    Text("이 세트의 권장 공기압", style = MaterialTheme.typography.bodyLarge)
                    ChoiceChips(listOf(false to "차량 공통값 사용", true to "세트별 입력"), setOf(form.customPressure),
                        onToggle = { form.customPressure = it })
                    if (form.customPressure) {
                        NumberSelector("세트 앞 권장 공기압", form.frontPsi, { form.frontPsi = it }, 1..100, 1,
                            listOf(32, 34, 36, 38, 40), "psi", 36)
                        NumberSelector("세트 뒤 권장 공기압", form.rearPsi, { form.rearPsi = it }, 1..100, 1,
                            listOf(32, 34, 36, 38, 40), "psi", 36)
                        Muted("차량 라벨·설명서의 냉간 권장값을 확인하세요. 이 세트에서는 차량 공통값보다 우선합니다.")
                    }
                }
            }
        }
    })
}

@Composable
private fun RegistrationPanel(title: String, tag: String,
    content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth().testTag(tag), shape = RoundedCornerShape(24.dp), color = TirelogColors.Surface) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            Column(verticalArrangement = Arrangement.spacedBy(20.dp), content = content)
        }
    }
}

@Composable
internal fun DotSelector(value: String, today: LocalDate, onValue: (String) -> Unit) {
    RegistrationDotField(value, today, onValue)
}

@Composable
internal fun FormText(label: String, value: String, onValue: (String) -> Unit, hint: String? = null, error: String? = null, number: Boolean = false) {
    val focusManager = LocalFocusManager.current
    OutlinedTextField(value, onValue, label = { Text(label) }, singleLine = true,
        enabled = LocalActionsEnabled.current, isError = error != null,
        supportingText = if (error != null || hint != null) { { Text(error ?: hint.orEmpty()) } } else null,
        textStyle = MaterialTheme.typography.bodyLarge, shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
        keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Decimal else KeyboardType.Text, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }), modifier = Modifier.fillMaxWidth())
}
