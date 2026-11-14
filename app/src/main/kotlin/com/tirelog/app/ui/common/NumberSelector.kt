package com.tirelog.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import java.text.NumberFormat
import java.util.Locale

/** 차량의 숫자 입력은 자주 쓰는 값 선택과 증감 버튼으로 받는다. 저장된 세부 값은 그대로 보존한다. */
@Composable
fun NumberSelector(
    label: String, value: String, onValue: (String) -> Unit,
    range: IntRange, step: Int, presets: List<Int>, unit: String,
    defaultValue: Int = presets.firstOrNull() ?: range.first,
) {
    val amount = value.toDoubleOrNull()?.takeIf { it.isFinite() }
    fun update(next: Double) {
        val clamped = next.coerceIn(range.first.toDouble(), range.last.toDouble())
        onValue(if (clamped % 1.0 == 0.0) clamped.toLong().toString() else clamped.toString())
    }
    Stepper(label, amount?.let { "${NumberFormat.getNumberInstance(Locale.KOREA).format(it)}$unit" } ?: "선택 필요",
        onMinus = { update(amount?.minus(step) ?: defaultValue.toDouble()) },
        onPlus = { update(amount?.plus(step) ?: defaultValue.toDouble()) },
        minusEnabled = amount == null || amount > range.first,
        plusEnabled = amount == null || amount < range.last,
        modifier = Modifier.testTag("number-$label"))
    ChoiceChips(presets.map { it to "${NumberFormat.getIntegerInstance(Locale.KOREA).format(it)}$unit" },
        presets.filter { it.toDouble() == amount }.toSet(), onToggle = { update(it.toDouble()) })
}
