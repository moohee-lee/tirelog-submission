package com.tirelog.app.ui.main

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.unit.dp
import com.tirelog.app.ui.common.LocalActionsEnabled
import com.tirelog.app.ui.common.Muted
import com.tirelog.app.ui.theme.TirelogColors

internal data class TireSizeParts(val width: String = "", val aspect: String = "", val rim: String = "") {
    val empty get() = width.isEmpty() && aspect.isEmpty() && rim.isEmpty()
    val complete get() = width.length == 3 && listOf(width, aspect, rim).all { (it.toIntOrNull() ?: 0) > 0 }
    val text get() = if (empty) "" else "$width/$aspect R$rim"

    companion object {
        private val pattern = Regex("([0-9]{0,3})\\s*/\\s*([0-9]{0,3})\\s*Z?R\\s*([0-9]{0,2})", RegexOption.IGNORE_CASE)
        fun parse(value: String): TireSizeParts? {
            if (value.isBlank()) return TireSizeParts()
            val match = pattern.matchEntire(value.trim()) ?: return null
            return TireSizeParts(match.groupValues[1], match.groupValues[2], match.groupValues[3])
        }
    }
}

/** 기존 자유 입력 규격은 수정 전까지 보존하고, 새 숫자 입력은 세 항목을 완성해야 저장한다. */
internal fun tireSizeError(value: String): String? = TireSizeParts.parse(value)?.let {
    if (it.empty || it.complete) null else "단면 폭(3자리)·편평비·인치를 모두 0보다 큰 숫자로 입력하거나, 모두 비워 주세요."
}

@Composable
internal fun TireSizeFields(value: String, onValue: (String) -> Unit) {
    val focus = LocalFocusManager.current
    val parsed = TireSizeParts.parse(value)
    val parts = parsed ?: TireSizeParts()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (parsed == null) Muted("기존 규격: $value · 아래 숫자를 입력하면 새 규격으로 바뀝니다.")
        RegistrationRow("타이어 규격") { modifier ->
            Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                BoxWithConstraints(Modifier.weight(1f)) {
                    val fields: @Composable (Modifier) -> Unit = { fieldModifier ->
                        TireSizeNumber("단면 폭", "mm", parts.width, "195", 3, "tire-size-width", fieldModifier, ImeAction.Next) {
                            onValue(parts.copy(width = it).text)
                        }
                        TireSizeNumber("편평비", "%", parts.aspect, "65", 3, "tire-size-aspect", fieldModifier, ImeAction.Next) {
                            onValue(parts.copy(aspect = it).text)
                        }
                        TireSizeNumber("휠 인치", "인치", parts.rim, "15", 2, "tire-size-rim", fieldModifier, ImeAction.Done) {
                            onValue(parts.copy(rim = it).text)
                        }
                    }
                    if (maxWidth / LocalDensity.current.fontScale >= 440.dp) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { fields(Modifier.weight(1f)) }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { fields(Modifier.fillMaxWidth()) }
                    }
                }
                TireHelpButton(TireHelp.SIZE)
            }
        }
        val error = tireSizeError(value)
        if (error != null) Text(error, color = TirelogColors.Red, style = MaterialTheme.typography.bodyMedium)
        else if (parts.complete) Muted("규격 ${parts.text}")
        if (value.isNotBlank()) TextButton(onClick = { focus.clearFocus(); onValue("") },
            enabled = LocalActionsEnabled.current, modifier = Modifier.heightIn(min = 56.dp)) { Text("규격 모름") }
    }
}

@Composable
private fun TireSizeNumber(label: String, unit: String, value: String, example: String, length: Int,
    tag: String, modifier: Modifier, action: ImeAction, onValue: (String) -> Unit) {
    val focus = LocalFocusManager.current
    OutlinedTextField(value, { onValue(it.filter { c -> c in '0'..'9' }.take(length)) },
        label = { Text(label) }, placeholder = { Text(example) }, suffix = { Text(unit) },
        enabled = LocalActionsEnabled.current, singleLine = true, textStyle = MaterialTheme.typography.bodyLarge,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = action),
        keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Next) }, onDone = { focus.clearFocus() }),
        modifier = modifier.heightIn(min = 88.dp).testTag(tag))
}

/** 타이어 옆면의 표기를 직접 옮겨 적을 수 있도록 예시 숫자와 입력 항목을 연결한 안내 도식. */
@Composable
internal fun TireSizeGuide() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("tire-size-guide")) {
        Muted("타이어 옆면에 적힌 숫자를 확인하세요. 아래는 195/65 R15의 예시입니다.")
        TireSidewallExample(
            listOf("195", "65", "15"),
            listOf("단면 폭\n타이어 너비", "편평비\n옆면 높이 비율", "휠 인치\n휠 지름"),
            listOf("단면 폭", "편평비", "휠 인치"),
            "규격 예시 195/65 R15. 단면 폭은 195밀리미터, 편평비는 65퍼센트, 휠 지름은 15인치입니다.",
            sizeSeparators = true,
        )
    }
}

@Composable
internal fun TireSidewallExample(numbers: List<String>, labels: List<String>, compactLabels: List<String>,
    description: String, sizeSeparators: Boolean = false) {
    val fontScale = LocalDensity.current.fontScale
    val lineColor = TirelogColors.Muted
    BoxWithConstraints(Modifier.fillMaxWidth().height(220.dp * fontScale).clipToBounds()
        .semantics { contentDescription = description }) {
        val compact = maxWidth / fontScale < 360.dp
        val numberStyle = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.titleLarge
        Canvas(Modifier.fillMaxSize()) {
            drawOval(Color(0xFF303235), topLeft = Offset(-size.width * .15f, size.height * .38f),
                size = Size(size.width * 1.3f, size.height * 2f))
            drawOval(Color(0xFF55585E), topLeft = Offset(-size.width * .15f, size.height * .44f),
                size = Size(size.width * 1.3f, size.height * 2f), style = Stroke(2.dp.toPx()))
            for (index in 0..18) {
                val x = (index + .5f) / 19f
                val relativeX = (x - .5f) / .65f
                val edge = size.height * (1.38f - kotlin.math.sqrt(1f - relativeX * relativeX))
                drawLine(Color(0xFF141619), Offset(size.width * x, edge + 2.dp.toPx()),
                    Offset(size.width * x + 4.dp.toPx(), edge + 9.dp.toPx()), 3.dp.toPx())
            }
            drawOval(Color(0xFF62656D), topLeft = Offset(-size.width * .1f, size.height * .88f),
                size = Size(size.width * 1.2f, size.height * 1.8f))
            drawOval(Color(0xFF969AA3), topLeft = Offset(-size.width * .1f, size.height * .9f),
                size = Size(size.width * 1.2f, size.height * 1.8f), style = Stroke(3.dp.toPx()))
            listOf(1f / 6, .5f, 5f / 6).forEach { x ->
                val relativeX = (x - .5f) / .65f
                val edge = size.height * (1.38f - kotlin.math.sqrt(1f - relativeX * relativeX))
                drawLine(lineColor, Offset(size.width * x, size.height * .35f),
                    Offset(size.width * x, edge), 2.dp.toPx())
                drawLine(Color.White, Offset(size.width * x, edge),
                    Offset(size.width * x, size.height * .58f), 2.dp.toPx())
            }
        }
        Row(Modifier.fillMaxWidth()) {
            (if (compact) compactLabels else labels).forEach {
                Text(it, modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium, color = TirelogColors.OnSurface)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = maxHeight * .61f)) {
            numbers.forEach {
                Text(it, modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
                    style = numberStyle, color = Color.White, maxLines = 1, softWrap = false)
            }
        }
        if (sizeSeparators) {
            Text("/", Modifier.offset(x = maxWidth / 3 - 8.dp, y = maxHeight * .61f),
                style = numberStyle, color = Color.White)
            Text("R", Modifier.offset(x = maxWidth * (2f / 3) - 10.dp, y = maxHeight * .61f),
                style = numberStyle, color = Color.White)
        }
    }
}
