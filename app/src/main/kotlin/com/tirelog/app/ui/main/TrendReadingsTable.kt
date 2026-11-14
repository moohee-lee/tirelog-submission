package com.tirelog.app.ui.main

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tirelog.app.ui.common.Muted
import com.tirelog.app.ui.theme.TirelogColors
import com.tirelog.core.engine.TrendData
import com.tirelog.core.engine.dailyReadings
import com.tirelog.core.model.WheelPosition
import java.time.ZoneId
import kotlin.math.roundToInt

private val readingPositions = listOf(
    WheelPosition.FRONT_RIGHT, WheelPosition.FRONT_LEFT,
    WheelPosition.REAR_RIGHT, WheelPosition.REAR_LEFT,
)
private val readingHeaders = listOf("날짜", "외기온도") + readingPositions.map { it.label }

@Composable
internal fun TrendReadingsTable(data: TrendData, zone: ZoneId, modifier: Modifier = Modifier) {
    val rows = remember(data, zone) { data.dailyReadings(zone) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Muted("냉간 측정 · 위치는 측정 당시 기준 · —는 측정값 없음")
        Surface(shape = RoundedCornerShape(16.dp), color = TirelogColors.Surface,
            border = BorderStroke(1.dp, TirelogColors.Outline), modifier = Modifier.fillMaxWidth()) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val tableWidth = maxOf(maxWidth, 900.dp * LocalDensity.current.fontScale)
                Column(Modifier.horizontalScroll(rememberScrollState()).width(tableWidth).testTag("readings-table")) {
                    ReadingTableRow(readingHeaders, header = true, modifier = Modifier.testTag("readings-header"))
                    if (rows.isEmpty()) {
                        Muted("선택한 기간에 냉간 측정 기록이 없습니다.", Modifier.padding(24.dp))
                    } else rows.forEach { row ->
                        HorizontalDivider(color = TirelogColors.Outline.copy(alpha = .6f))
                        val cells = listOf(row.date.toString(), row.temperatureC?.let(::formatTemp) ?: "—") +
                            readingPositions.map { position -> row.pressuresPsi[position]?.let { "${it.roundToInt()} psi" } ?: "—" }
                        ReadingTableRow(cells, header = false, modifier = Modifier.testTag("readings-${row.date}"))
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadingTableRow(cells: List<String>, header: Boolean, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().background(if (header) TirelogColors.SurfaceHigh else TirelogColors.Surface),
        verticalAlignment = Alignment.CenterVertically) {
        cells.forEachIndexed { index, value ->
            Text(value, maxLines = 1, style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
                textAlign = if (index == 0) TextAlign.Start else TextAlign.End,
                modifier = Modifier.weight(if (index == 0) 1.5f else 1f)
                    .padding(horizontal = 16.dp, vertical = 16.dp)
                    .then(if (header) Modifier.semantics { heading() } else Modifier))
        }
    }
}
