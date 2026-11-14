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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tirelog.app.catalog.TireModelCatalog
import com.tirelog.app.catalog.TireModelCatalogState
import com.tirelog.app.ui.common.*
import com.tirelog.app.ui.theme.TirelogColors
import com.tirelog.core.catalog.TireManufacturers
import com.tirelog.core.catalog.TireModel
import com.tirelog.core.catalog.TireModels
import kotlinx.coroutines.flow.MutableStateFlow

internal val LocalTireModelCatalog = staticCompositionLocalOf<TireModelCatalog?> { null }
private val emptyCatalog = MutableStateFlow(TireModelCatalogState(loaded = true))

@Composable
internal fun TireModelSelector(manufacturer: String, value: String,
    onSelect: (TireModel) -> Unit, onCustom: (String) -> Unit,
    modifier: Modifier = Modifier, showLabel: Boolean = true) {
    val catalog = LocalTireModelCatalog.current
    val state by (catalog?.state ?: emptyCatalog).collectAsStateWithLifecycle()
    val manufacturerId = TireManufacturers.find(manufacturer)?.id
    LaunchedEffect(manufacturerId, catalog) { manufacturerId?.let { catalog?.watch(it) } }
    var expanded by rememberSaveable(manufacturer) { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val enabled = LocalActionsEnabled.current
    val iconColor = TirelogColors.OnSurface
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (showLabel) Muted("모델명(선택)")
        Surface(onClick = { focusManager.clearFocus(); expanded = true }, enabled = enabled,
            shape = RoundedCornerShape(16.dp), color = TirelogColors.SurfaceHigh,
            modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp).testTag("tire-model-selector")
                .semantics { contentDescription = "모델명, ${value.ifBlank { "모름" }}" }) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(value.ifBlank { if (showLabel) "모델 선택 또는 직접 입력" else "모델명 선택" }, style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f))
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
        val matches = remember(state.models, manufacturerId, query) { TireModels.search(state.models, manufacturerId, query) }
        val listState = rememberLazyListState()
        LaunchedEffect(query) { listState.scrollToItem(0) }
        PageDialog("모델 선택", onDismiss = { expanded = false }, scrollable = false) {
            val searchFocus = LocalFocusManager.current
            Muted("제조사 · ${manufacturer.ifBlank { "모름" }}")
            OutlinedTextField(query, { query = it.take(80) }, label = { Text("모델 검색 또는 직접 입력") },
                singleLine = true, enabled = enabled, textStyle = MaterialTheme.typography.bodyLarge,
                shape = RoundedCornerShape(14.dp), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { searchFocus.clearFocus() }),
                trailingIcon = if (query.isNotEmpty()) { {
                    TextButton(onClick = { query = "" }, enabled = enabled, modifier = Modifier.heightIn(min = 56.dp)) { Text("지우기") }
                } } else null,
                modifier = Modifier.fillMaxWidth().testTag("tire-model-search"))
            LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("tire-model-results"), state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (query.isBlank()) item(key = "unknown") {
                    BigButton("모델명 모름", { onCustom(""); expanded = false }, Modifier.fillMaxWidth(), selected = value.isBlank())
                }
                items(matches, key = { it.key }) { model ->
                    BigButton("${model.name} · ${model.type?.label ?: "종류 직접 선택"}", { onSelect(model); expanded = false },
                        Modifier.fillMaxWidth(), selected = model.name == value)
                }
                if (matches.isEmpty()) {
                    item(key = "empty") {
                        Muted(when {
                            !state.loaded -> "모델 목록을 불러오고 있습니다. 이름을 직접 입력할 수도 있습니다."
                            manufacturerId == null -> "제조사를 선택하면 해당 모델 목록이 표시됩니다. 모델명만 직접 입력할 수도 있습니다."
                            query.isBlank() -> "저장된 모델이 없습니다. 모델명을 직접 입력하세요."
                            else -> "일치하는 모델이 없습니다. 입력한 이름으로 등록할 수 있습니다."
                        })
                    }
                    if (query.isNotBlank()) item(key = "custom") {
                        BigButton("입력한 이름으로 등록", { onCustom(query.trim()); expanded = false },
                            Modifier.fillMaxWidth(), primary = true)
                        Muted("직접 입력한 모델은 타이어 종류를 선택해 주세요.")
                    }
                }
            }
        }
    }
}
