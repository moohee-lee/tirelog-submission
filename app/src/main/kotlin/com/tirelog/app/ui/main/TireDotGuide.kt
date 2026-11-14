package com.tirelog.app.ui.main

import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.tirelog.app.R
import com.tirelog.app.ui.common.LocalActionsEnabled
import com.tirelog.app.ui.common.Muted

/** 실제 옆면 표기 형태를 재현한 안내 이미지이며, 예시 숫자를 입력값으로 채우지 않는다. */
@Composable
internal fun TireDotGuide(initiallyExpanded: Boolean) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { expanded = !expanded }, enabled = LocalActionsEnabled.current,
            modifier = Modifier.heightIn(min = 56.dp).testTag("dot-guide-toggle")) {
            Text(if (expanded) "DOT 숫자 찾는 방법 접기" else "DOT 숫자 찾는 방법")
        }
        if (expanded) Column(Modifier.testTag("tire-dot-guide"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val image = ImageBitmap.imageResource(R.drawable.tire_dot_sidewall)
            Muted("타이어 옆면에서 DOT로 시작하는 영문·숫자 줄을 찾으세요.")
            DotSidewallPhoto(image)
            Text("끝의 숫자 4자리만 입력하세요", style = MaterialTheme.typography.bodyLarge)
            DotDateCloseup(image, Modifier.align(Alignment.CenterHorizontally))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("24", style = MaterialTheme.typography.titleLarge)
                    Text("앞 2자리 · 만든 주", style = MaterialTheme.typography.bodyMedium)
                    Muted("그해 24번째 주")
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("23", style = MaterialTheme.typography.titleLarge)
                    Text("뒤 2자리 · 만든 연도", style = MaterialTheme.typography.bodyMedium)
                    Muted("2023년")
                }
            }
            Muted("예: 2423 → 2023년 24주 제조. 위 이미지는 설명용 예시이니 실제 타이어의 숫자를 입력하세요.")
        }
    }
}

// 같은 이미지의 날짜 표기 영역. 원본 숫자를 그대로 확대해 실제 위치와 입력값을 연결한다.
private const val DateLeft = .69f
private const val DateTop = .43f
private const val DateWidth = .23f
private const val DateHeight = .18f

@Composable
private fun DotSidewallPhoto(image: ImageBitmap) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Image(image,
            contentDescription = "타이어 옆면 표기 예시. 바깥쪽 트레드와 안쪽 휠 사이의 DOT 코드 끝에 2423이 새겨져 있습니다. 주황색 테두리가 입력할 네 자리입니다.",
            contentScale = ContentScale.Fit,
            modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth().aspectRatio(image.width.toFloat() / image.height)
                .clip(RoundedCornerShape(16.dp))
                .testTag("dot-sidewall-photo").drawWithContent {
                    drawContent()
                    drawRoundRect(Color(0xFFFFB74D),
                        topLeft = Offset(size.width * DateLeft, size.height * DateTop),
                        size = Size(size.width * DateWidth, size.height * DateHeight),
                        cornerRadius = CornerRadius(10.dp.toPx()), style = Stroke(3.dp.toPx()))
                })
    }
}

@Composable
private fun DotDateCloseup(image: ImageBitmap, modifier: Modifier = Modifier) {
    val source = IntSize((image.width * DateWidth).toInt(), (image.height * DateHeight).toInt())
    Canvas(modifier.widthIn(max = 240.dp).fillMaxWidth().aspectRatio(source.width.toFloat() / source.height)
        .clip(RoundedCornerShape(12.dp)).testTag("dot-date-closeup")
        .semantics { contentDescription = "옆면의 끝 네 자리 확대: 2423. 24는 제조 주, 23은 제조 연도입니다." }) {
        drawImage(image, srcOffset = IntOffset((image.width * DateLeft).toInt(), (image.height * DateTop).toInt()),
            srcSize = source, dstSize = IntSize(size.width.toInt(), size.height.toInt()))
    }
}
