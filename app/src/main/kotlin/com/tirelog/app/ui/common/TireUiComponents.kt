package com.tirelog.app.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tirelog.app.ui.theme.TirelogColors

internal enum class TireIcon { ADD, DELETE, HISTORY, EDIT, CLOCK, MORE, CHEVRON }

/** 두 작업 화면은 시안의 큰 글자 위계를 사용하고 기존 화면의 서체는 유지한다. */
@Composable
internal fun TireWorkspaceTheme(content: @Composable () -> Unit) {
    val type = MaterialTheme.typography
    MaterialTheme(typography = type.copy(
        titleLarge = type.titleLarge.copy(fontSize = 48.sp, lineHeight = 60.sp),
        titleMedium = type.titleMedium.copy(fontSize = 36.sp, lineHeight = 46.sp),
        bodyMedium = type.bodyMedium.copy(fontSize = 28.sp, lineHeight = 38.sp),
        labelLarge = type.labelLarge.copy(fontSize = 34.sp, lineHeight = 46.sp),
    ), content = content)
}

/** 같은 굵기의 선 아이콘을 사용하고 터치 영역은 버튼 전체로 유지한다. */
@Composable
internal fun TireLineIcon(icon: TireIcon, modifier: Modifier = Modifier) {
    val color = LocalContentColor.current
    Canvas(modifier.size(32.dp).clearAndSetSemantics { }) {
        val stroke = Stroke(2.dp.toPx(), cap = StrokeCap.Round)
        fun point(x: Float, y: Float) = Offset(size.width * x, size.height * y)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(color, point(x1, y1), point(x2, y2), stroke.width, StrokeCap.Round)
        fun rect(x: Float, y: Float, w: Float, h: Float, radius: Float = .06f) =
            drawRoundRect(color, point(x, y), Size(size.width * w, size.height * h),
                CornerRadius(size.width * radius), style = stroke)
        when (icon) {
            TireIcon.ADD -> { line(.5f, .12f, .5f, .88f); line(.12f, .5f, .88f, .5f) }
            TireIcon.DELETE -> {
                line(.16f, .25f, .84f, .25f); rect(.26f, .25f, .48f, .65f)
                rect(.38f, .1f, .24f, .15f); line(.43f, .4f, .43f, .75f); line(.57f, .4f, .57f, .75f)
            }
            TireIcon.HISTORY -> {
                rect(.18f, .18f, .64f, .74f); rect(.34f, .08f, .32f, .18f)
                line(.33f, .45f, .67f, .45f); line(.33f, .62f, .6f, .62f)
            }
            TireIcon.EDIT -> {
                drawPath(Path().apply {
                    moveTo(size.width * .14f, size.height * .66f)
                    lineTo(size.width * .69f, size.height * .11f)
                    lineTo(size.width * .9f, size.height * .32f)
                    lineTo(size.width * .35f, size.height * .87f)
                    lineTo(size.width * .11f, size.height * .9f); close()
                }, color, style = stroke)
                line(.59f, .22f, .79f, .42f)
            }
            TireIcon.CLOCK -> {
                drawCircle(color, size.minDimension * .4f, style = stroke)
                line(.5f, .23f, .5f, .5f); line(.5f, .5f, .7f, .56f)
            }
            TireIcon.MORE -> listOf(.2f, .5f, .8f).forEach { drawCircle(color, 2.5.dp.toPx(), point(.5f, it)) }
            TireIcon.CHEVRON -> { line(.2f, .38f, .5f, .67f); line(.5f, .67f, .8f, .38f) }
        }
    }
}

@Composable
internal fun TireActionButton(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    icon: TireIcon? = null, primary: Boolean = false, selected: Boolean? = null, enabled: Boolean = true,
) {
    Button(onClick, enabled = enabled && LocalActionsEnabled.current,
        modifier = modifier.heightIn(min = 104.dp)
            .then(if (selected != null) Modifier.semantics { this.selected = selected } else Modifier),
        shape = RoundedCornerShape(18.dp),
        border = if (selected == true) BorderStroke(2.dp, TirelogColors.OnSurface) else null,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (primary) MaterialTheme.colorScheme.inverseSurface else if (selected == true) TirelogColors.Selection else TirelogColors.SurfaceHigh,
            contentColor = if (primary) MaterialTheme.colorScheme.inverseOnSurface else TirelogColors.OnSurface),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp)) {
        if (icon != null) {
            TireLineIcon(icon)
            Spacer(Modifier.width(16.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}
