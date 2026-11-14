package com.tirelog.app.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.tirelog.app.ui.theme.TirelogColors
import com.tirelog.app.ui.theme.TirelogPalette
import com.tirelog.app.vehicle.PleosVehicleArtwork
import com.tirelog.core.model.WheelPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** 메인·정비·위치교환 설명에서 같은 리소스와 같은 테마를 사용한다. */
@Composable
internal fun rememberVehicleArtwork(): PleosVehicleArtwork? {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val dark = TirelogColors.dark
    val artwork by produceState<PleosVehicleArtwork?>(null, context, configuration, dark) {
        value = withContext(Dispatchers.IO) { PleosVehicleArtwork.load(context, dark) }
    }
    return artwork
}

@Composable
internal fun VehicleFrontLabel() {
    val color = TirelogColors.Muted
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(16.dp).clearAndSetSemantics { }) {
            val arrow = Path().apply {
                moveTo(size.width / 2, 0f)
                lineTo(size.width, size.height * .8f)
                lineTo(0f, size.height * .8f)
                close()
            }
            drawPath(arrow, color)
        }
        Text("차량 앞쪽", Modifier.padding(start = 8.dp), color = color, style = MaterialTheme.typography.bodyMedium)
    }
}

/** 공기압 라벨의 바깥쪽 연결점이 아닌, 렌더링된 타이어 자체의 중심과 크기다. */
internal fun vehicleWheelBounds(
    position: WheelPosition, centerX: Float, top: Float, height: Float, usesPleosArtwork: Boolean,
): Rect {
    val left = position == WheelPosition.FRONT_LEFT || position == WheelPosition.REAR_LEFT
    val axleX = if (usesPleosArtwork) .16f else 23f / 112f
    val axleY = if (usesPleosArtwork) {
        if (position.isFront) .245f else .77f
    } else {
        if (position.isFront) 21f / 112f else 93f / 112f
    }
    val center = Offset(centerX + height * if (left) -axleX else axleX, top + height * axleY)
    val halfWidth = height * if (usesPleosArtwork) .03f else .025f
    val halfHeight = height * if (usesPleosArtwork) .065f else .055f
    return Rect(center.x - halfWidth, center.y - halfHeight, center.x + halfWidth, center.y + halfHeight)
}

/** 네 사분면의 원본 VectorDrawable 또는 공통 기본 도형을 그린다. */
internal fun DrawScope.drawVehicleTopView(
    artwork: PleosVehicleArtwork?, centerX: Float, top: Float, height: Float, palette: TirelogPalette,
) {
    if (artwork != null) {
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            val half = height / 2f
            WheelPosition.entries.forEachIndexed { index, position ->
                native.save()
                native.translate(centerX - half + if (index % 2 == 0) 0f else half, top + if (index < 2) 0f else half)
                val drawable = artwork.quadrants.getValue(position)
                drawable.setBounds(0, 0, half.roundToInt(), half.roundToInt())
                drawable.draw(native)
                native.restore()
            }
        }
    } else {
        // 특정 차종을 의미하지 않는 공통 대체 도형이다.
        val left = centerX - height * .2f
        val body = Size(height * .4f, height)
        val radius = CornerRadius(height * .09f)
        drawRoundRect(palette.SurfaceHigh, Offset(left, top), body, radius)
        drawRoundRect(palette.Muted, Offset(left, top), body, radius, style = Stroke(1.5.dp.toPx()))
        drawRoundRect(palette.Background, Offset(centerX - height * .15f, top + height * .26f),
            Size(height * .3f, height * .16f), CornerRadius(height * .03f))
        drawRoundRect(palette.Background, Offset(centerX - height * .15f, top + height * .73f),
            Size(height * .3f, height * .12f), CornerRadius(height * .03f))
        WheelPosition.entries.forEach { position ->
            val bounds = vehicleWheelBounds(position, centerX, top, height, usesPleosArtwork = false)
            drawRoundRect(palette.Muted, bounds.topLeft, bounds.size, CornerRadius(height * .015f))
        }
    }
}
