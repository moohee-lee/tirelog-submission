package com.tirelog.app.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import com.tirelog.app.R
import com.tirelog.app.ui.theme.TirelogColors
import androidx.compose.ui.unit.dp
import java.time.LocalDate

/** 차량에서는 날짜를 타이핑하지 않는다. 라벨·값·아이콘을 포함한 전체 영역이 하나의 버튼이다. */
@Composable
fun DatePickerField(label: String, value: LocalDate?, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val focusManager = LocalFocusManager.current
    Surface(
        onClick = { focusManager.clearFocus(); onClick() },
        enabled = enabled && LocalActionsEnabled.current,
        modifier = modifier.fillMaxWidth().heightIn(min = 96.dp),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, TirelogColors.Outline),
        color = TirelogColors.Surface,
    ) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, style = MaterialTheme.typography.bodyMedium, color = TirelogColors.Muted)
                Text(value?.toString() ?: "날짜 선택 · 모름", style = MaterialTheme.typography.bodyLarge)
            }
            Icon(painterResource(R.drawable.ic_calendar), contentDescription = null,
                tint = TirelogColors.OnSurface, modifier = Modifier.size(28.dp))
        }
    }
}
