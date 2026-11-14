package com.tirelog.app.ui.main

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.tirelog.app.ui.common.*
import com.tirelog.app.ui.theme.TirelogColors
import com.tirelog.core.catalog.TireManufacturers

/** 목록은 먼저 터치로 탐색하고, 검색창을 누른 경우에만 키보드를 연다. */
@Composable
internal fun TireManufacturerSelector(value: String, onSelect: (String) -> Unit,
    modifier: Modifier = Modifier, showLabel: Boolean = true) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val enabled = LocalActionsEnabled.current
    val label = TireManufacturers.find(value)?.name ?: value.ifBlank { "기타·모름" }
    val iconColor = TirelogColors.OnSurface
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (showLabel) Muted("제조사")
        Surface(onClick = { focusManager.clearFocus(); expanded = true }, enabled = enabled,
            shape = RoundedCornerShape(16.dp), color = TirelogColors.SurfaceHigh,
            modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp).testTag("tire-manufacturer-selector")
                .semantics { contentDescription = "제조사, $label" }) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Canvas(Modifier.padding(start = 12.dp).size(24.dp)) {
                    drawLine(iconColor, Offset(size.width * .2f, size.height * .35f),
                        Offset(size.width * .5f, size.height * .65f), 2.dp.toPx(), StrokeCap.Round)
                    drawLine(iconColor, Offset(size.width * .5f, size.height * .65f),
                        Offset(size.width * .8f, size.height * .35f), 2.dp.toPx(), StrokeCap.Round)
                }
            }
        }
    }
    if (expanded) {
        var query by rememberSaveable { mutableStateOf("") }
        val matches = remember(query) { TireManufacturers.search(query) }
        val resultsState = rememberLazyListState()
        LaunchedEffect(query) { resultsState.scrollToItem(0) }
        fun select(name: String) {
            focusManager.clearFocus()
            onSelect(name)
            expanded = false
        }
        PageDialog("제조사 선택", onDismiss = { expanded = false }, scrollable = false) {
            val searchFocusManager = LocalFocusManager.current
            OutlinedTextField(query, { query = it.take(80) },
                label = { Text("제조사 검색 또는 직접 입력") }, singleLine = true, enabled = enabled,
                textStyle = MaterialTheme.typography.bodyLarge, shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { searchFocusManager.clearFocus() }),
                trailingIcon = if (query.isNotEmpty()) { {
                    TextButton(onClick = { query = "" }, enabled = enabled, modifier = Modifier.heightIn(min = 56.dp)) {
                        Text("지우기")
                    }
                } } else null,
                modifier = Modifier.fillMaxWidth().testTag("tire-manufacturer-search"))
            Muted("타이어 옆면의 제조사·브랜드를 선택하세요. 한글·영문으로 검색할 수 있습니다.")
            LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("tire-manufacturer-results"), state = resultsState,
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (query.isBlank()) item(key = "unknown") {
                    BigButton("기타·모름", { select("") }, Modifier.fillMaxWidth(), selected = value.isBlank())
                }
                items(matches, key = { it.id }) { manufacturer ->
                    BigButton(manufacturer.label, { select(manufacturer.name) }, Modifier.fillMaxWidth(),
                        selected = TireManufacturers.find(value)?.id == manufacturer.id)
                }
                if (query.isNotBlank() && matches.isEmpty()) {
                    item(key = "empty") { Muted("일치하는 제조사가 없습니다. 입력한 이름으로 등록할 수 있습니다.") }
                    item(key = "custom") {
                        BigButton("“${query.trim()}”으로 등록", { select(query.trim()) },
                            Modifier.fillMaxWidth(), primary = true)
                    }
                }
            }
        }
    }
}
