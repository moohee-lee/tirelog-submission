package com.tirelog.app.ui.common

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tirelog.app.ui.theme.TirelogColors
import com.tirelog.core.alert.ActiveAlert
import com.tirelog.core.alert.AlertAction
import com.tirelog.core.engine.PendingItem
import com.tirelog.core.engine.WheelCard
import com.tirelog.core.model.AlertType

val LocalActionsEnabled = staticCompositionLocalOf { true }

@Composable
fun <T> SelectionMenu(label: String, value: String, options: List<Pair<T, String>>, onSelect: (T) -> Unit) {
    val focusManager = LocalFocusManager.current
    var expanded by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Muted(label)
        androidx.compose.foundation.layout.Box {
            Surface(onClick = { focusManager.clearFocus(); expanded = true }, enabled = LocalActionsEnabled.current,
                shape = RoundedCornerShape(16.dp), color = TirelogColors.SurfaceHigh,
                modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp).semantics { contentDescription = "$label, $value" }) {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(value, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text("⌄", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 12.dp))
                }
            }
            androidx.compose.material3.DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (id, text) ->
                    androidx.compose.material3.DropdownMenuItem(text = { Text(text, style = MaterialTheme.typography.bodyLarge) },
                        onClick = { expanded = false; onSelect(id) }, modifier = Modifier.heightIn(min = 56.dp))
                }
            }
        }
    }
}

@Composable
fun AppDialog(
    title: String,
    onDismiss: () -> Unit,
    maxWidth: Dp = 640.dp,
    footer: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val enabled = LocalActionsEnabled.current
    Dialog(onDismissRequest = { if (enabled) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = maxWidth).fillMaxWidth(0.94f).heightIn(max = 1000.dp),
            shape = RoundedCornerShape(24.dp), color = TirelogColors.Surface, contentColor = TirelogColors.OnSurface) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
                if (footer != null) {
                    HorizontalDivider(color = TirelogColors.Outline)
                    footer()
                }
            }
        }
    }
}

/** 정비 작업·이력 조회는 앱의 가용 영역 전체를 쓰고, 확인 팝업만 작은 대화상자로 둔다. */
@Composable
fun PageDialog(
    title: String,
    onDismiss: () -> Unit,
    footer: (@Composable () -> Unit)? = null,
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val enabled = LocalActionsEnabled.current
    Dialog(onDismissRequest = { if (enabled) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).testTag("full-page-dialog"),
            color = TirelogColors.Background, contentColor = TirelogColors.OnSurface) {
            ScreenFrame(title, onDismiss, scrollable = scrollable, footer = footer, content = content)
        }
    }
}

@Composable
fun BigButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    selected: Boolean? = null,
) {
    val shape = RoundedCornerShape(18.dp)
    val focusManager = LocalFocusManager.current
    val click = { focusManager.clearFocus(); onClick() }
    val interactive = enabled && LocalActionsEnabled.current
    val buttonModifier = modifier.heightIn(min = 88.dp)
        .then(if (selected != null) Modifier.semantics { this.selected = selected } else Modifier)
    val label: @Composable RowScope.() -> Unit = {
        if (selected != null) {
            val color = LocalContentColor.current
            Canvas(Modifier.size(20.dp).clearAndSetSemantics { }) {
                if (selected) {
                    drawLine(color, Offset(size.width * .15f, size.height * .5f), Offset(size.width * .4f, size.height * .75f),
                        strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                    drawLine(color, Offset(size.width * .4f, size.height * .75f), Offset(size.width * .85f, size.height * .2f),
                        strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                }
            }
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
    if (primary) {
        Button(
            onClick = click,
            enabled = interactive,
            modifier = buttonModifier,
            shape = shape,
            colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color(0xFF44464D), contentColor = androidx.compose.ui.graphics.Color.White),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
            content = label,
        )
    } else {
        Button(
            onClick = click, enabled = interactive, modifier = buttonModifier, shape = shape,
            colors = ButtonDefaults.buttonColors(containerColor = if (selected == true) TirelogColors.Selection else TirelogColors.SurfaceHigh,
                contentColor = TirelogColors.OnSurface),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
            content = label,
        )
    }
}

/** 같은 작업 문맥의 버튼은 한 행에 놓고, 실제 너비가 부족할 때만 줄바꿈한다. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ActionRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = TirelogColors.Primary,
        modifier = modifier.padding(top = 20.dp, bottom = 10.dp).semantics { heading() },
    )
}

@Composable
fun Muted(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = TirelogColors.Muted, modifier = modifier)
}

/** 차량 화면의 가용 너비를 모두 쓰고 제목·작업 버튼을 고정한다. 본문만 필요할 때 스크롤한다. */
@Composable
fun ScreenFrame(
    title: String,
    onBack: () -> Unit,
    scrollable: Boolean = true,
    footer: (@Composable () -> Unit)? = null,
    actions: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val interactive = LocalActionsEnabled.current
    BackHandler { if (interactive) onBack() }
    Column(Modifier.fillMaxSize().background(TirelogColors.Background).testTag("screen-frame")) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) {
            if (maxWidth / LocalDensity.current.fontScale < 280.dp) {
                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        ScreenBackButton(onBack)
                        actions?.invoke()
                    }
                    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ScreenBackButton(onBack)
                    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() })
                    actions?.invoke()
                }
            }
        }
        val inner = Modifier.weight(1f).fillMaxWidth()
        Column((if (scrollable) inner.verticalScroll(rememberScrollState()) else inner)
            .padding(horizontal = 24.dp, vertical = 8.dp).testTag("screen-content"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            content()
            Spacer(Modifier.height(16.dp))
        }
        if (footer != null) {
            HorizontalDivider(color = TirelogColors.Outline)
            Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp)) { footer() }
        }
    }
}

/** 충분한 폭에서만 두 열로 배치하고 큰 글씨에서는 한 열로 되돌린다. */
@Composable
fun AdaptiveColumns(
    modifier: Modifier = Modifier,
    firstWeight: Float = 1f,
    first: @Composable () -> Unit,
    second: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        if (maxWidth / LocalDensity.current.fontScale >= 840.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(firstWeight), verticalArrangement = Arrangement.spacedBy(12.dp)) { first() }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) { second() }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) { first(); second() }
        }
    }
}

@Composable
fun ContentPanel(title: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = TirelogColors.Surface) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            title?.let { Text(it, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() }) }
            content()
        }
    }
}

@Composable
private fun ScreenBackButton(onBack: () -> Unit) {
    TextButton(onClick = onBack, enabled = LocalActionsEnabled.current, modifier = Modifier.heightIn(min = 80.dp)) {
        Text("← 뒤로", style = MaterialTheme.typography.labelLarge, color = TirelogColors.Primary)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChoiceChips(
    options: List<Pair<T, String>>,
    selected: Set<T>,
    onToggle: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    FlowRow(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = value in selected,
                enabled = LocalActionsEnabled.current,
                modifier = Modifier.heightIn(min = 72.dp),
                shape = RoundedCornerShape(14.dp),
                onClick = { focusManager.clearFocus(); onToggle(value) },
                label = { Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 10.dp)) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = TirelogColors.Selection,
                    selectedLabelColor = TirelogColors.OnSurface,
                ),
            )
        }
    }
}

@Composable
fun Stepper(
    label: String,
    value: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    modifier: Modifier = Modifier,
    minusEnabled: Boolean = true,
    plusEnabled: Boolean = true,
) {
    Column(modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = onMinus,
                enabled = minusEnabled && LocalActionsEnabled.current,
                modifier = Modifier.size(64.dp).semantics { contentDescription = "$label 줄이기" },
                shape = CircleShape,
                contentPadding = PaddingValues(0.dp),
            ) {
                Text("−", fontSize = 28.sp)
            }
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp).semantics { contentDescription = "$label $value" },
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            OutlinedButton(
                onClick = onPlus,
                enabled = plusEnabled && LocalActionsEnabled.current,
                modifier = Modifier.size(64.dp).semantics { contentDescription = "$label 늘리기" },
                shape = CircleShape,
                contentPadding = PaddingValues(0.dp),
            ) {
                Text("+", fontSize = 28.sp)
            }
        }
    }
}

@Composable
fun WheelTile(card: WheelCard, modifier: Modifier = Modifier, large: Boolean = false) {
    val color = TirelogColors.forState(card.state)
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = TirelogColors.Surface,
        border = BorderStroke(if (card.state == com.tirelog.core.engine.WheelState.NORMAL) 1.dp else 2.dp, color.copy(alpha = 0.7f)),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                card.position.label + (card.tireLabel?.let { " · 타이어 $it" } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
                color = TirelogColors.Muted,
            )
            Text(
                card.psi?.let { "$it" } ?: "—",
                style = if (large) MaterialTheme.typography.displayLarge else MaterialTheme.typography.headlineMedium,
                color = color,
                modifier = Modifier.semantics { contentDescription = card.psi?.let { "$it psi" } ?: "측정값 없음" },
            )
            when (card.state) {
                com.tirelog.core.engine.WheelState.LEAK -> "누설 의심"
                com.tirelog.core.engine.WheelState.RECHECK -> "재점검 필요"
                else -> null
            }?.let { Text(it, color = color, style = MaterialTheme.typography.bodyMedium) }
            Text(card.line, style = MaterialTheme.typography.bodyMedium, color = TirelogColors.OnSurface)
            card.liveLine?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = TirelogColors.Muted) }
        }
    }
}

@Composable
fun PendingList(items: List<PendingItem>, modifier: Modifier = Modifier, limit: Int = Int.MAX_VALUE) {
    if (items.isEmpty()) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.take(limit).forEach { item ->
            val color = when (item.type) {
                AlertType.LEAK -> TirelogColors.Red
                AlertType.REFILL_NEEDED, AlertType.RECHECK -> TirelogColors.Amber
                null -> TirelogColors.Muted
                else -> TirelogColors.Primary
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).background(color, CircleShape))
                Spacer(Modifier.width(12.dp))
                Text(item.text, style = MaterialTheme.typography.bodyLarge)
            }
        }
        if (items.size > limit) Muted("외 ${items.size - limit}건")
    }
}

/** 정차 알림 하나. 버튼은 알림마다 2~3개. */
@Composable
fun AlertPopup(alert: ActiveAlert, wheels: List<WheelCard>, onAction: (AlertAction) -> Unit) {
    Dialog(
        onDismissRequest = { onAction(AlertAction.LATER) },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = TirelogColors.SurfaceHigh,
            contentColor = TirelogColors.OnSurface,
            modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(0.9f),
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val accent = when (alert.type) {
                    AlertType.LEAK -> TirelogColors.Red
                    AlertType.REFILL_NEEDED, AlertType.RECHECK -> TirelogColors.Amber
                    else -> TirelogColors.Primary
                }
                Text(alert.type.title, style = MaterialTheme.typography.titleMedium, color = accent)
                Text(alert.message, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                alert.detail?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = TirelogColors.Muted) }
                wheels.filter { it.position in alert.positions }.forEach { wheel ->
                    Text("${wheel.position.label} ${wheel.line}", style = MaterialTheme.typography.bodyLarge)
                }
                Spacer(Modifier.size(8.dp))
                ActionButtons(alert, onAction)
            }

        }
    }
}

@Composable
private fun ActionButtons(alert: ActiveAlert, onAction: (AlertAction) -> Unit) {
    ActionRow {
        alert.actions.forEachIndexed { index, action ->
            BigButton(action.label, { onAction(action) }, primary = index == 0 && action != AlertAction.LATER)
        }
    }
}
