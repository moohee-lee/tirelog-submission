package com.tirelog.app.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tirelog.app.ui.theme.TirelogColors
import com.tirelog.app.vehicle.PleosVehicleArtwork
import com.tirelog.core.engine.WheelCard
import com.tirelog.core.engine.WheelState
import com.tirelog.core.model.WheelPosition
import kotlin.math.max
import kotlin.math.roundToInt

private val wheelOrder = listOf(
    WheelPosition.FRONT_LEFT, WheelPosition.FRONT_RIGHT,
    WheelPosition.REAR_LEFT, WheelPosition.REAR_RIGHT,
)

/** 차량 앞쪽을 위로 고정하고, 목록 순서 대신 실제 바퀴 위치로 수치를 연결한다. */
@Composable
fun VehiclePressureDiagram(wheels: List<WheelCard>, modifier: Modifier = Modifier, large: Boolean = false,
    summary: Boolean = false, onWheelClick: ((WheelPosition) -> Unit)? = null, fillHeight: Boolean = false) {
    val artwork = rememberVehicleArtwork()
    val byPosition = wheels.associateBy { it.position }
    Surface(modifier, shape = RoundedCornerShape(24.dp), color = TirelogColors.Surface) {
        BoxWithConstraints((if (fillHeight) Modifier.fillMaxSize() else Modifier).padding(24.dp)) {
            // 큰 글씨/좁은 창에서는 상세 설명을 도형 아래로 옮겨 겹침 없이 모두 읽을 수 있게 한다.
            val compact = maxWidth / LocalDensity.current.fontScale < 600.dp ||
                (fillHeight && maxHeight / LocalDensity.current.fontScale < 600.dp)
            Column(if (fillHeight) Modifier.fillMaxSize() else Modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                VehicleFrontLabel()
                PressureMap(byPosition, artwork, compact, large, summary, onWheelClick,
                    if (fillHeight) Modifier.weight(1f).fillMaxWidth() else Modifier.fillMaxWidth())
                if (!summary) Text(
                    if (artwork != null) "Pleos 차량 도형 · 위에서 본 모습" else "기본 차량 도형 · 위에서 본 모습",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TirelogColors.Muted,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                if (compact && !summary) {
                    wheelOrder.forEach { position ->
                        byPosition[position]?.let { WheelTile(it, Modifier.fillMaxWidth()) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PressureMap(
    wheels: Map<WheelPosition, WheelCard>,
    artwork: PleosVehicleArtwork?,
    compact: Boolean,
    large: Boolean,
    summary: Boolean,
    onWheelClick: ((WheelPosition) -> Unit)?,
    modifier: Modifier,
) {
    val palette = TirelogColors
    SubcomposeLayout(modifier) { constraints ->
        val width = constraints.maxWidth
        val gap = (if (compact) 8.dp else 24.dp).roundToPx()
        val boundedHeight = constraints.hasBoundedHeight && constraints.minHeight > 0
        val carWidth = if (boundedHeight) minOf(width * .34f, constraints.maxHeight * .46f)
            else (width * if (compact) 0.24f else 0.28f).coerceAtMost((if (large) 320.dp else 240.dp).toPx())
        val sideWidth = ((width - carWidth) / 2 - gap).roundToInt().coerceAtLeast(1)
        val readings = wheelOrder.map { position ->
            subcompose(position) {
                PressureReading(position, wheels[position], compact, large, summary, onWheelClick)
            }.single().measure(Constraints.fixedWidth(sideWidth))
        }
        val frontHeight = max(readings[0].height, readings[1].height)
        val rearHeight = max(readings[2].height, readings[3].height)
        val carHeight = carWidth * 2f
        val height = max(if (boundedHeight) constraints.maxHeight else carHeight.roundToInt(), frontHeight + rearHeight + 48.dp.roundToPx())
        val carTop = (height - carHeight) / 2f
        val frontY = frontHeight / 2f
        val rearY = height - rearHeight / 2f
        val background = subcompose("vehicle") {
            Canvas(
                Modifier.testTag("pressure-vehicle").semantics {
                    contentDescription = if (artwork != null) "Pleos 차량의 위에서 본 도형, 위쪽이 앞" else "기본 차량의 위에서 본 도형, 위쪽이 앞"
                },
            ) {
                val centerX = size.width / 2f
                drawVehicleTopView(artwork, centerX, carTop, carHeight, palette)
                wheelOrder.forEachIndexed { index, position ->
                    val left = index % 2 == 0
                    val front = index < 2
                    val tire = vehicleWheelBounds(position, centerX, carTop, carHeight, artwork != null).center
                    val label = Offset(if (left) sideWidth + gap / 3f else size.width - sideWidth - gap / 3f, if (front) frontY else rearY)
                    val color = palette.forState(wheels[position]?.state ?: WheelState.WAITING)
                    drawLine(color.copy(alpha = 0.65f), tire, label, strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
                    drawCircle(color, 4.dp.toPx(), tire)
                }
            }
        }.single().measure(Constraints.fixed(width, height))
        layout(width, height) {
            background.place(0, 0)
            readings.forEachIndexed { index, reading ->
                val y = if (index < 2) frontY else rearY
                reading.place(if (index % 2 == 0) 0 else width - sideWidth, (y - reading.height / 2).roundToInt())
            }
        }
    }
}

@Composable
private fun PressureReading(position: WheelPosition, card: WheelCard?, compact: Boolean, large: Boolean,
    summary: Boolean, onWheelClick: ((WheelPosition) -> Unit)?) {
    val onLeft = position == WheelPosition.FRONT_LEFT || position == WheelPosition.REAR_LEFT
    val textAlign = if (onLeft) TextAlign.End else TextAlign.Start
    val state = card?.state ?: WheelState.WAITING
    val color = TirelogColors.forState(state)
    val status = if (card?.supported == false) "공기압 미지원" else when (state) {
        WheelState.WAITING -> if (card?.displayPsi == null) "측정 대기" else "판단 대기"
        WheelState.NORMAL -> "정상"
        WheelState.LOW -> "공기압 낮음"
        WheelState.REFILL -> "보충 필요"
        WheelState.LEAK -> "누설 의심"
        WheelState.RECHECK -> "재점검 필요"
    }
    Column(
        Modifier.testTag("pressure-${position.name}")
            .then(if (onWheelClick != null) Modifier.clickable(onClickLabel = "${position.label} 상세 보기") { onWheelClick(position) } else Modifier)
            .padding(vertical = 8.dp).semantics(mergeDescendants = true) {
            contentDescription = listOfNotNull(
                position.label, (if (summary) card?.displayPsi else card?.psi)?.let { "$it psi" } ?: "측정값 없음", status,
                card?.tireLabel?.let { "타이어 $it" }, card?.line, card?.liveLine,
            ).joinToString(", ")
        },
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = if (onLeft) Alignment.End else Alignment.Start,
    ) {
        Text(position.label, style = MaterialTheme.typography.bodyLarge, color = TirelogColors.Muted, textAlign = textAlign)
        // 한 Text로 두어 글씨 크기가 커져도 수치와 단위의 기준선이 어긋나지 않는다.
        Text(
            buildAnnotatedString {
                append((if (summary) card?.displayPsi else card?.psi)?.toString() ?: "—")
                withStyle(SpanStyle(fontSize = 24.sp, fontWeight = FontWeight.Normal, color = TirelogColors.Muted)) {
                    append(" psi")
                }
            },
            fontSize = if (compact) 40.sp else if (large && summary) 80.sp else if (summary) 56.sp else if (large) 72.sp else 64.sp,
            fontWeight = FontWeight.Bold,
            color = color,
            textAlign = textAlign,
        )
        if (summary && card?.supported != false) Text(card?.recommendedPsi?.let { "권장 $it psi" } ?: "권장값 미설정", style = MaterialTheme.typography.bodyMedium, color = TirelogColors.Muted, textAlign = textAlign)
        Text(status, style = MaterialTheme.typography.bodyMedium, color = color, textAlign = textAlign)
        if (!compact && !summary) {
            card?.tireLabel?.let { Text("타이어 $it", style = MaterialTheme.typography.bodyMedium, color = TirelogColors.Muted, textAlign = textAlign) }
            Text(card?.line ?: "공기압 값을 기다리고 있습니다", style = MaterialTheme.typography.bodyMedium, textAlign = textAlign)
            card?.liveLine?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = TirelogColors.Muted, textAlign = textAlign) }
        }
    }
}
