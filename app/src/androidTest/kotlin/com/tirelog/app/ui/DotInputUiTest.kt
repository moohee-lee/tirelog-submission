package com.tirelog.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.tirelog.app.ui.common.*
import com.tirelog.app.ui.main.DotSelector
import com.tirelog.app.ui.main.rememberTireForm
import com.tirelog.app.ui.theme.TirelogTheme
import com.tirelog.core.engine.NewSetInput
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

class DotInputUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @After fun finishActivity() { compose.activityRule.scenario.onActivity { it.finish() } }
    private val today = LocalDate.of(2026, 11, 7)

    @Composable private fun Form(save: (NewSetInput) -> Unit = {}) {
        val form = rememberTireForm("dot-input", NewSetInput(com.tirelog.core.model.TireSetType.ALL_SEASON, null, null))
        ScreenFrame("내 타이어 등록", {}) {
            DotSelector(form.dot, today) { form.dot = it }
            BigButton("저장", { save(form.value()) }, enabled = form.valid(today))
        }
    }

    @Test fun acceptsOnlyFourDigitsKeepsLeadingZeroAndDoneClosesInput() {
        var saved: NewSetInput? = null
        compose.setContent { TirelogTheme { Form { saved = it } } }
        compose.onNodeWithTag("tire-dot-guide").assertDoesNotExist()
        compose.onNodeWithTag("tire-dot-guide-button").performClick()
        compose.onNodeWithTag("tire-dot-guide").assertExists()
        compose.onNodeWithText("끝의 숫자 4자리만 입력하세요").assertDoesNotExist()
        compose.onNodeWithTag("dot-date-closeup").assertDoesNotExist()
        compose.onNodeWithText("닫기").performClick()
        compose.onNodeWithTag("tire-dot-input").assertIsEnabled().performTextReplacement("A0123B9")
        compose.onNodeWithTag("tire-dot-input").assertTextContains("0123").performImeAction()
        compose.onNodeWithTag("tire-dot-input").assertIsNotFocused()
        compose.onNodeWithText("저장").performClick()
        assertEquals("0123", requireNotNull(saved).dot)
        compose.onNodeWithText("제조 연도").assertDoesNotExist()
        compose.onNodeWithText("제조 주").assertDoesNotExist()
    }

    @Test fun guideCanBeReopenedForAnExistingCodeWithoutChangingItsValue() {
        var dot by mutableStateOf("2423")
        compose.setContent { TirelogTheme {
            ScreenFrame("제조일자", {}) { DotSelector(dot, today) { dot = it } }
        } }
        compose.onNodeWithTag("tire-dot-guide").assertDoesNotExist()
        compose.onNodeWithTag("tire-dot-guide-button").performClick()
        compose.onNodeWithTag("tire-dot-guide").assertExists()
        compose.onNodeWithText("닫기").performClick()
        compose.onNodeWithTag("tire-dot-guide").assertDoesNotExist()
        assertEquals("2423", dot)
    }

    @Test fun invalidWeeksIncompleteCodesAndFutureDatesCannotBeSaved() {
        compose.setContent { TirelogTheme { Form() } }
        for ((input, error) in listOf(
            "123" to "숫자 4자리로 입력해 주세요",
            "0023" to "주는 01~53이어야 합니다",
            "5423" to "주는 01~53이어야 합니다",
            "5321" to "2021년에는 53주가 없습니다",
            "0127" to "미래 날짜입니다",
        )) {
            compose.onNodeWithTag("tire-dot-input").performTextReplacement(input)
            compose.onNodeWithText(error).assertIsDisplayed()
            compose.onNodeWithText("저장").assertIsNotEnabled()
        }
        compose.onNodeWithTag("tire-dot-input").performTextReplacement("2423")
        compose.onNodeWithText("저장").assertIsEnabled()
    }

    @Test fun choosingUnknownClearsTheCodeAndAllowsDirectEntryAgain() {
        var saved: NewSetInput? = null
        compose.setContent { TirelogTheme(darkTheme = true) { Form { saved = it } } }
        compose.onNodeWithTag("tire-dot-input").performTextReplacement("2423")
        compose.onNodeWithTag("tire-dot-input").performTextReplacement("")
        compose.onNodeWithTag("tire-dot-input").assertIsEnabled()
        compose.onNodeWithText("저장").performClick()
        assertNull(requireNotNull(saved).dot)
        compose.onNodeWithTag("tire-dot-input").performTextReplacement("0123")
        compose.onNodeWithTag("tire-dot-input").assertTextContains("0123")
        compose.onNodeWithText("저장").performClick()
        assertEquals("0123", requireNotNull(saved).dot)
    }

    @Test fun unfinishedInputSurvivesRestorationAndRemainsInvalidUntilCompleted() {
        var saved: NewSetInput? = null
        val restoration = StateRestorationTester(compose)
        restoration.setContent { TirelogTheme { Form { saved = it } } }
        compose.onNodeWithTag("tire-dot-input").performTextReplacement("24")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("tire-dot-input").assertTextContains("24")
        compose.onNodeWithText("저장").assertIsNotEnabled()
        compose.onNodeWithTag("tire-dot-input").performTextInputSelection(TextRange(2))
        compose.onNodeWithTag("tire-dot-input").performTextInput("23")
        compose.onNodeWithText("저장").performClick()
        assertEquals("2423", requireNotNull(saved).dot)
    }

    @Test fun drivingRestrictionsDisableInputAndUnknownSelection() {
        compose.setContent { TirelogTheme {
            CompositionLocalProvider(LocalActionsEnabled provides false) { Form() }
        } }
        compose.onNodeWithTag("tire-dot-input").assertIsNotEnabled()
        compose.onNodeWithTag("tire-dot-guide-button").assertIsNotEnabled()
    }
}
