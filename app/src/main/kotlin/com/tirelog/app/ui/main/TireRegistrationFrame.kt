package com.tirelog.app.ui.main

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.tirelog.app.ui.common.ScreenFrame

/** 화면 폭을 쓰되 입력 항목은 늘이지 않고, 키보드와 큰 글씨에서는 본문을 스크롤한다. */
@Composable
internal fun TireRegistrationFrame(
    title: String, onBack: () -> Unit, footer: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize().testTag("registration-page")) {
        ScreenFrame(title, onBack, footer = footer, content = content)
    }
}
