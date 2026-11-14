package com.tirelog.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.tirelog.app.ui.common.*
import com.tirelog.app.ui.main.*
import com.tirelog.app.ui.theme.TirelogTheme
import com.tirelog.core.engine.NewSetInput
import com.tirelog.core.model.TireSetType
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

class RegistrationLayoutUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @After fun finishActivity() { compose.activityRule.scenario.onActivity { it.finish() } }
    private val today = LocalDate.of(2026, 11, 7)
    private val input = NewSetInput(TireSetType.SUMMER, "235/55 R19", "2423", initialKm = 12_345.67,
        manufacturer = "한국타이어", tireModel = "iON evo", firstUsedEpochDay = LocalDate.of(2025, 6, 1).toEpochDay())

    @Composable private fun Form(form: TireFormState) {
        TireRegistrationFrame("타이어 등록", {}, footer = {
            BigButton("저장", {}, primary = true, modifier = Modifier.fillMaxWidth().testTag("registration-save"), enabled = form.valid(today))
        }) {
            Muted("네 바퀴의 공통 정보를 등록합니다. 저장하면 보관 목록에 추가됩니다.")
            TireFormFields(form, today)
        }
    }

    @Test fun wideFormAndNewHelpImagesWorkInBothThemesWithoutChangingInput() {
        val dark = mutableStateOf(false)
        lateinit var form: TireFormState
        compose.setContent { TirelogTheme(darkTheme = dark.value) {
            form = rememberTireForm("layout-images", input)
            Form(form)
        } }
        fun sameRow(vararg tags: String) {
            val bounds = tags.map { compose.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot }
            bounds.drop(1).forEach { assertEquals(bounds.first().top, it.top, 2f) }
            bounds.zipWithNext().forEach { (left, right) -> assertTrue(left.right <= right.left) }
        }
        sameRow("tire-model-selector", "tire-type-selector")
        sameRow("tire-dot-input", "tire-dot-guide-button")
        sameRow("tire-size-width", "tire-size-aspect", "tire-size-rim", "tire-size-guide-button")
        val page = compose.onNodeWithTag("registration-page").fetchSemanticsNode().boundsInRoot
        val save = compose.onNodeWithTag("registration-save").fetchSemanticsNode().boundsInRoot
        val panel = compose.onNodeWithTag("registration-tire-panel").fetchSemanticsNode().boundsInRoot
        assertTrue("저장 버튼은 화면 하단에 고정되어야 합니다", page.bottom - save.bottom < 40f)
        val dot = compose.onNodeWithTag("tire-dot-input").fetchSemanticsNode().boundsInRoot
        val size = compose.onNodeWithTag("tire-size-width").fetchSemanticsNode().boundsInRoot
        assertTrue("서로 관련된 입력 사이에 빈 공간을 늘리지 않아야 합니다", size.top - dot.bottom in 0f..32f)
        val lastAction = compose.onNodeWithText("규격 모름").fetchSemanticsNode().boundsInRoot
        assertTrue("패널은 마지막 동작 아래에 불필요한 여백을 만들지 않아야 합니다", panel.bottom - lastAction.bottom < 48f)
        compose.onNodeWithText("기준 및 출처").assertDoesNotExist()
        compose.onNodeWithText("거리·기간의 기준").assertDoesNotExist()
        compose.onNodeWithTag("first-use-date").assertHasClickAction().assertTextContains("2025-06-01")
        compose.onNodeWithTag("registration-mileage").assertTextContains("12345.67")
        for (night in listOf(false, true)) {
            compose.runOnIdle { dark.value = night }
            val theme = if (night) "night" else "day"
            captureUi("registration-$theme")
            for (help in TireHelp.entries) {
                compose.onNodeWithTag("${help.tag}-button").scrollToIfNeeded().performClick()
                compose.onNodeWithTag(help.tag).assertIsDisplayed()
                compose.onNodeWithContentDescription(help.description).assertExists()
                captureUi("registration-$theme-${help.name.lowercase()}")
                compose.onNodeWithText("닫기").performClick()
                compose.onNodeWithTag(help.tag).assertDoesNotExist()
                compose.runOnIdle { assertEquals(input, form.value()) }
            }
        }
        compose.onNodeWithTag("dot-date-closeup").assertDoesNotExist()
        compose.onNodeWithText("끝의 숫자 4자리만 입력하세요").assertDoesNotExist()
    }

    @Test fun numberFieldsEditInlineAndNextMovesThroughTheFormInBothThemes() {
        val dark = mutableStateOf(false)
        lateinit var form: TireFormState
        compose.setContent { TirelogTheme(darkTheme = dark.value) {
            form = rememberTireForm("inline-editors", input)
            Form(form)
        } }
        for (night in listOf(false, true)) {
            compose.runOnIdle { dark.value = night }
            compose.onNodeWithTag("registration-mileage").scrollToIfNeeded().performClick()
            compose.onNodeWithTag("registration-mileage").assertIsFocused().assert(hasSetTextAction())
            compose.onNodeWithTag("full-page-dialog").assertDoesNotExist()
            compose.onNodeWithTag("registration-mileage").performTextReplacement("25000.5")
            compose.onNodeWithTag("registration-mileage").performImeAction()
            compose.onNodeWithTag("registration-warranty-km").assertIsFocused().performTextReplacement("45000")
            compose.onNodeWithTag("registration-warranty-km").performImeAction()
            compose.onNodeWithTag("registration-warranty-months").assertIsFocused().performTextReplacement("54")
            compose.onNodeWithTag("registration-warranty-months").performImeAction()
            compose.onNodeWithTag("registration-warranty-months").assertIsNotFocused()
            compose.onNodeWithTag("registration-save").assertIsEnabled()
            compose.runOnIdle {
                assertEquals(25000.5, form.value().initialKm, 0.000001)
                assertEquals(45000, form.value().inspectionKm)
                assertEquals(54, form.value().inspectionMonths)
            }
            captureUi("registration-inline-${if (night) "night" else "day"}")
        }
    }

    @Test fun quickChoicesApplyImmediatelyWithoutAConfirmationDialog() {
        lateinit var form: TireFormState
        compose.setContent { TirelogTheme {
            form = rememberTireForm("inline-shortcuts", input)
            Form(form)
        } }
        compose.onNodeWithTag("registration-mileage").performClick()
        compose.onNodeWithTag("registration-mileage-preset-0").scrollToIfNeeded().performClick()
        compose.runOnIdle {
            assertEquals(0.0, form.value().initialKm, 0.0)
            assertTrue(form.value().initialKmKnown)
        }
        compose.onNodeWithTag("registration-warranty-km").scrollToIfNeeded().performClick()
        compose.onNodeWithTag("registration-warranty-km-preset-50000").scrollToIfNeeded().performClick()
        compose.onNodeWithTag("registration-warranty-months").scrollToIfNeeded().performClick()
        compose.onNodeWithTag("registration-warranty-months-shortcuts").assertIsDisplayed()
        compose.waitForIdle()
        captureUi("registration-inline-months-shortcuts")
        compose.onNodeWithTag("registration-warranty-months-preset-48").scrollToIfNeeded().performClick()
        compose.onNodeWithTag("full-page-dialog").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(50000, form.value().inspectionKm)
            assertEquals(48, form.value().inspectionMonths)
        }
    }

    @Test fun firstTapSelectsTheExistingValueSoTypingReplacesIt() {
        lateinit var form: TireFormState
        compose.setContent { TirelogTheme {
            form = rememberTireForm("replace-existing", input)
            Form(form)
        } }
        compose.onNodeWithTag("registration-mileage").performClick().assertIsFocused().performTextInput("32000.5")
        compose.runOnIdle { assertEquals("32000.5", form.km) }
        compose.onNodeWithTag("registration-mileage").performImeAction()
        compose.onNodeWithTag("registration-warranty-km").assertIsFocused().performTextInput("45000")
        compose.runOnIdle { assertEquals("45000", form.limitKm) }
        compose.onNodeWithTag("registration-warranty-km").performImeAction()
        compose.onNodeWithTag("registration-warranty-months").assertIsFocused().performTextInput("54")
        compose.onNodeWithTag("registration-warranty-months-shortcuts").scrollToIfNeeded().assertIsDisplayed()
        compose.waitForIdle()
        captureUi("registration-inline-focused-months")
        compose.onNodeWithTag("registration-warranty-months").performImeAction()
        compose.runOnIdle { assertEquals("54", form.months) }
    }

    @Test fun invalidInlineValuesPreventSavingAndMonthSteppersRespectTheLimit() {
        lateinit var form: TireFormState
        compose.setContent { TirelogTheme {
            form = rememberTireForm("numeric-validation", input)
            Form(form)
        } }
        compose.onNodeWithTag("registration-warranty-months").performClick().performTextReplacement("121")
        compose.onNodeWithTag("registration-save").assertIsNotEnabled()
        compose.onNodeWithContentDescription("보증 기간 줄이기").scrollToIfNeeded().performClick()
        compose.onNodeWithTag("registration-warranty-months").assertTextContains("120")
        compose.onNodeWithContentDescription("보증 기간 늘리기").assertIsNotEnabled()
        compose.onNodeWithTag("registration-warranty-months").performImeAction()
        compose.onNodeWithTag("registration-save").assertIsEnabled()
        compose.runOnIdle { assertEquals(120, form.value().inspectionMonths) }
        compose.onNodeWithTag("registration-warranty-km").scrollToIfNeeded().performClick().performTextReplacement("40000.5")
        compose.onNodeWithTag("registration-warranty-km").performImeAction()
        compose.onNodeWithText("정수로 입력해 주세요.").scrollToIfNeeded().assertIsDisplayed()
        compose.onNodeWithTag("registration-save").assertIsNotEnabled()
        compose.runOnIdle { assertEquals("40000.5", form.limitKm) }
        compose.setRegistrationNumber("registration-warranty-km", "40000")
        compose.onNodeWithTag("registration-save").assertIsEnabled()
    }

    @Test fun inlineEditsAndUnknownMileageSurviveRestorationWithoutChangingTheDate() {
        lateinit var form: TireFormState
        val restoration = StateRestorationTester(compose)
        restoration.setContent { TirelogTheme {
            form = rememberTireForm("numeric-state", input)
            Form(form)
        } }
        compose.setRegistrationNumber("registration-mileage", "25000.5")
        compose.setRegistrationNumber("registration-warranty-km", "45000")
        compose.setRegistrationNumber("registration-warranty-months", "54")
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle {
            assertEquals(25000.5, form.value().initialKm, 0.000001)
            assertEquals(45000, form.value().inspectionKm)
            assertEquals(54, form.value().inspectionMonths)
            assertEquals(input.firstUsedEpochDay, form.value().firstUsedEpochDay)
        }
        compose.onNodeWithTag("registration-mileage").performClick()
        compose.onNodeWithTag("registration-mileage-unknown").scrollToIfNeeded().performClick()
        compose.onNodeWithTag("registration-mileage").assertTextContains("")
        compose.runOnIdle {
            assertFalse(form.value().initialKmKnown)
            assertEquals(input.firstUsedEpochDay, form.value().firstUsedEpochDay)
        }
    }

    @Test fun largeTextReflowsAndDrivingRestrictionsDisableEveryNewControl() {
        val enabled = mutableStateOf(true)
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 2f), LocalActionsEnabled provides enabled.value) {
                TirelogTheme(darkTheme = true) { Box(Modifier.width(375.dp)) { Form(rememberTireForm("narrow", input)) } }
            }
        }
        val tags = listOf("tire-model-selector", "tire-type-selector", "tire-dot-input", "tire-dot-guide-button",
            "tire-size-width", "tire-size-aspect", "tire-size-rim", "tire-size-guide-button", "first-use-date",
            "registration-mileage", "registration-warranty-km", "registration-warranty-months")
        tags.forEach { compose.onNodeWithTag(it).scrollToIfNeeded().assertIsDisplayed() }
        compose.runOnIdle { enabled.value = false }
        tags.forEach { compose.onNodeWithTag(it).assertIsNotEnabled() }
    }
}
