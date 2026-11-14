package com.tirelog.app.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.assertIsNotFocused
import androidx.test.platform.app.InstrumentationRegistry

/** 넓은 창의 고정 배치와 좁은 창의 스크롤 배치에 같은 검증을 적용한다. */
internal fun SemanticsNodeInteraction.scrollToIfNeeded(): SemanticsNodeInteraction {
    var ancestor = fetchSemanticsNode().parent
    while (ancestor != null) {
        if (ancestor.config.contains(SemanticsActions.ScrollBy)) return performScrollTo()
        ancestor = ancestor.parent
    }
    return this
}

internal fun captureUi(name: String) {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    automation.waitForIdle(350, 5000)
    automation.executeShellCommand("screencap -p /data/local/tmp/tirelog-$name.png").use { descriptor ->
        java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
    }
}

internal fun ComposeTestRule.enterTireModel(name: String) {
    onNodeWithTag("tire-model-selector").scrollToIfNeeded().performClick()
    onNodeWithTag("tire-model-search").performTextReplacement(name)
    onNodeWithTag("tire-model-search").performImeAction()
    onNodeWithText("입력한 이름으로 등록").performClick()
}

internal fun ComposeTestRule.selectTireType(label: String) {
    onNodeWithTag("tire-type-selector").scrollToIfNeeded().performClick()
    val type = com.tirelog.core.model.TireSetType.entries.single { it.label == label }
    onNodeWithTag("tire-type-option-${type.name}").performClick()
}

internal fun ComposeTestRule.setRegistrationNumber(tag: String, value: String) {
    onNodeWithTag(tag).scrollToIfNeeded().performClick()
    onNodeWithTag(tag).performTextReplacement(value)
    onNodeWithTag(tag).performImeAction()
    onNodeWithTag(tag).assertIsNotFocused()
}
