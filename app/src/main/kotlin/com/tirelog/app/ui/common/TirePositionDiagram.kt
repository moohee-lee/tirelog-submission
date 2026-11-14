package com.tirelog.app.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.tirelog.app.ui.theme.TirelogColors
import com.tirelog.core.model.RotationPattern
import com.tirelog.core.model.WheelPosition
import kotlin.math.max
import kotlin.math.roundToInt

/** 네 위치의 실제 타이어 좌표와 카드 연결선을 두 화면에서 공유한다. */
@Composable
internal fun TirePositionDiagram(
    modifier: Modifier = Modifier,
    selected: WheelPosition? = null,
    pattern: RotationPattern? = null,
    showLabels: Boolean = true,
    label: @Composable (WheelPosition) -> Unit,
) {
    val artwork = rememberVehicleArtwork()
    val palette = TirelogColors
    SubcomposeLayout(modifier.fillMaxWidth()) { constraints ->
        val width = constraints.maxWidth
        val sideWidth = (width * .32f).roundToInt()
        val cards = if (showLabels) WheelPosition.entries.map { position ->
            subcompose(position) { label(position) }.single().measure(Constraints.fixedWidth(sideWidth))
        } else emptyList()
        val frontHeight = cards.take(2).maxOfOrNull { it.height } ?: 0
        val rearHeight = cards.drop(2).maxOfOrNull { it.height } ?: 0
        val minimumHeight = max(frontHeight + rearHeight + 96.dp.roundToPx(), (width * .52f).roundToInt())
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else minimumHeight
        // 사분면 벡터의 경계가 반 픽셀에 걸려 틈이 생기지 않도록 맞춘다.
        val carHeight = (minOf(height * .94f, width * if (showLabels) .7f else .92f) / 2).roundToInt() * 2f
        val top = ((height - carHeight) / 2f).roundToInt().toFloat()
        val centerX = (width / 2).toFloat()
        fun tire(position: WheelPosition) = vehicleWheelBounds(position, centerX, top, carHeight, artwork != null)
        val frontY = max(frontHeight / 2f, tire(WheelPosition.FRONT_LEFT).center.y)
        val rearY = minOf(height - rearHeight / 2f, tire(WheelPosition.REAR_LEFT).center.y)
        val canvas = subcompose("vehicle") {
            Canvas(Modifier.testTag(if (pattern == null) "management-vehicle" else "rotation-diagram").semantics {
                contentDescription = (if (artwork != null) "Pleos 차량" else "기본 차량") + "의 위에서 본 도형, 위쪽이 앞" +
                    (pattern?.let { movement -> ". " + WheelPosition.entries.joinToString(". ") {
                        "${it.label}에서 ${movement.destinationOf(it).label}으로 이동"
                    } } ?: "")
            }) {
                drawVehicleTopView(artwork, centerX, top, carHeight, palette)
                if (pattern != null) {
                    val arrows = mutableListOf<MovementArrow>()
                    val reciprocal = WheelPosition.entries.filter { pattern.destinationOf(pattern.destinationOf(it)) == it }
                    WheelPosition.entries.forEach { from ->
                        val to = pattern.destinationOf(from)
                        if (from !in reciprocal || from.ordinal < to.ordinal) {
                            val sameSide = from.ordinal % 2 == to.ordinal % 2
                            if (sameSide && from.isFront != to.isFront && showLabels) {
                                // 같은 쪽 앞뒤 이동은 모든 방식에서 카드 사이의 바깥 경로를 쓴다.
                                val x = if (from.ordinal % 2 == 0) sideWidth * .74f else size.width - sideWidth * .74f
                                val upper = Offset(x, frontY + frontHeight / 2f + 16.dp.toPx())
                                val lower = Offset(x, rearY - rearHeight / 2f - 16.dp.toPx())
                                if (lower.y > upper.y) arrows += MovementArrow(
                                    if (from.isFront) upper else lower, if (from.isFront) lower else upper,
                                    both = from in reciprocal)
                            } else {
                                val (start, end) = tireArrowEndpoints(tire(from), tire(to), 5.dp.toPx(),
                                    centered = pattern != RotationPattern.SIDE_TO_SIDE)
                                arrows += MovementArrow(start, end, both = from in reciprocal,
                                    offsetCrossing = from !in reciprocal && !sameSide && from.isFront != to.isFront)
                            }
                        }
                    }
                    drawMovementArrows(arrows, palette.OnSurface, palette.Surface)
                }
                WheelPosition.entries.forEach { position ->
                    val bounds = tire(position)
                    val chosen = position == selected
                    // 야간 리소스가 타이어를 생략해도 네 장착 위치는 같은 명암으로 보인다.
                    val wheelOutline = bounds.inflate(5.dp.toPx())
                    drawRoundRect(palette.Surface, wheelOutline.topLeft, wheelOutline.size, CornerRadius(bounds.width * .6f))
                    drawRoundRect(palette.OnSurface, bounds.topLeft, bounds.size, CornerRadius(bounds.width / 2))
                    if (chosen) {
                        val outline = bounds.inflate(7.dp.toPx())
                        drawRoundRect(palette.TireSelection, outline.topLeft, outline.size, CornerRadius(bounds.width * .6f))
                        drawRoundRect(palette.OnTireSelection, outline.topLeft, outline.size, CornerRadius(bounds.width * .6f), style = Stroke(2.dp.toPx()))
                        drawRoundRect(palette.OnSurface, bounds.topLeft, bounds.size, CornerRadius(bounds.width / 2))
                    }
                    if (showLabels) {
                        val left = position == WheelPosition.FRONT_LEFT || position == WheelPosition.REAR_LEFT
                        val start = Offset(if (left) sideWidth.toFloat() else size.width - sideWidth, if (position.isFront) frontY else rearY)
                        val end = Offset(if (left) bounds.left else bounds.right, bounds.center.y)
                        val color = if (chosen) palette.OnTireSelection else palette.Muted
                        val path = Path().apply {
                            moveTo(start.x, start.y)
                            lineTo(start.x + (end.x - start.x) * .45f, start.y)
                            lineTo(end.x, end.y)
                        }
                        drawPath(path, color, style = Stroke(if (chosen) 2.dp.toPx() else 1.5.dp.toPx()))
                        drawCircle(palette.Surface, 5.dp.toPx(), end)
                        drawCircle(color, 3.5.dp.toPx(), end)
                    }
                }
            }
        }.single().measure(Constraints.fixed(width, height))
        layout(width, height) {
            canvas.place(0, 0)
            cards.forEachIndexed { index, card ->
                val y = if (index < 2) frontY else rearY
                card.place(if (index % 2 == 0) 0 else width - sideWidth, (y - card.height / 2).roundToInt())
            }
        }
    }
}

/** 교차 이동은 두 타이어의 중심선을 사용하고, 좌우 교환은 기존 간격을 유지한다. */
internal fun tireArrowEndpoints(from: Rect, to: Rect, clearance: Float, centered: Boolean = true): Pair<Offset, Offset> {
    val delta = to.center - from.center
    val unit = delta / delta.getDistance()
    val lane = if (centered) Offset.Zero else
        Offset(-unit.y, unit.x) * (minOf(from.width, to.width, from.height, to.height) * .275f)
    fun edge(rect: Rect, direction: Offset): Offset {
        val origin = rect.center + lane
        val x = when {
            direction.x > 0 -> (rect.right - origin.x) / direction.x
            direction.x < 0 -> (rect.left - origin.x) / direction.x
            else -> Float.POSITIVE_INFINITY
        }
        val y = when {
            direction.y > 0 -> (rect.bottom - origin.y) / direction.y
            direction.y < 0 -> (rect.top - origin.y) / direction.y
            else -> Float.POSITIVE_INFINITY
        }
        return origin + direction * (minOf(x, y) + clearance)
    }
    return edge(from, unit) to edge(to, -unit)
}

private data class MovementArrow(
    val start: Offset, val end: Offset, val both: Boolean, val offsetCrossing: Boolean = false,
)

private fun DrawScope.drawMovementArrows(
    arrows: List<MovementArrow>, color: androidx.compose.ui.graphics.Color,
    surface: androidx.compose.ui.graphics.Color,
) {
    val shafts = Path()
    val heads = Path()
    val width = 16.dp.toPx()
    // 단방향은 직선·대각선에 같은 화살촉을 쓰고, 가장 짧은 경로에 맞춰 함께 축소한다.
    val oneWayHeadLength = minOf(32.dp.toPx(), arrows.filterNot { it.both }
        .minOfOrNull { (it.end - it.start).getDistance() * .4f } ?: Float.POSITIVE_INFINITY)
    arrows.forEach { arrow ->
        val delta = arrow.end - arrow.start
        val unit = delta / delta.getDistance()
        val normal = Offset(-unit.y, unit.x)
        val headLength = if (arrow.both) minOf(32.dp.toPx(), delta.getDistance() * .22f) else oneWayHeadLength
        val halfWidth = headLength * .75f
        // 확정 시안: 평행 이동으로 선 길이를 유지하고 교차점만 앞뒤 간격의 2% 보정한다.
        // 앞타이어 교차는 위로, 뒷타이어 교차는 아래로 이동한다.
        val crossingShift = if (arrow.offsetCrossing) Offset(delta.x * .02f, 0f) else Offset.Zero
        val tip = arrow.end + crossingShift
        val tailInset = if (arrow.both) headLength * .7f else 0f
        val lineStart = arrow.start + crossingShift + unit * tailInset
        val lineEnd = tip - unit * headLength * .7f
        shafts.moveTo(lineStart.x, lineStart.y)
        shafts.lineTo(lineEnd.x, lineEnd.y)
        fun head(point: Offset, direction: Offset) {
            heads.apply {
                moveTo(point.x, point.y)
                val a = point - direction * headLength + normal * halfWidth
                val b = point - direction * headLength - normal * halfWidth
                lineTo(a.x, a.y); lineTo(b.x, b.y); close()
            }
        }
        head(tip, unit)
        if (arrow.both) head(arrow.start, -unit)
    }
    // 윤곽을 먼저 합쳐 그려 중앙에 흰 틈이나 선의 앞뒤 관계가 생기지 않게 한다.
    drawPath(shafts, surface, style = Stroke(width + 5.dp.toPx(), cap = StrokeCap.Butt))
    drawPath(shafts, color, style = Stroke(width, cap = StrokeCap.Butt))
    drawPath(heads, color)
}
