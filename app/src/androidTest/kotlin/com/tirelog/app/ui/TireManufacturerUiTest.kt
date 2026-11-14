package com.tirelog.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.tirelog.app.ui.common.BigButton
import com.tirelog.app.ui.common.LocalActionsEnabled
import com.tirelog.app.ui.common.ScreenFrame
import com.tirelog.app.ui.main.TireFormFields
import com.tirelog.app.ui.main.rememberTireForm
import com.tirelog.app.ui.theme.TirelogTheme
import com.tirelog.core.engine.NewSetInput
import com.tirelog.core.model.TireSetType
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

class TireManufacturerUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @After fun finishActivity() { compose.activityRule.scenario.onActivity { it.finish() } }
    private val today = LocalDate.of(2026, 11, 7)

    @Test fun listOpensWithoutKeyboardFocusAndCanScrollToAnInternationalManufacturer() {
        var saved: NewSetInput? = null
        compose.setContent { TirelogTheme {
            val form = rememberTireForm("manufacturer-list", NewSetInput(TireSetType.ALL_SEASON, null, null))
            ScreenFrame("내 타이어 등록", {}) {
                TireFormFields(form, today)
                BigButton("등록하고 시작", { saved = form.value() })
            }
        } }
        compose.onNodeWithTag("tire-manufacturer-selector").performClick()
        compose.onNodeWithTag("tire-manufacturer-search").assertIsNotFocused()
        compose.onNodeWithTag("tire-manufacturer-results")
            .performScrollToNode(hasText("요코하마 · Yokohama"))
        compose.onNodeWithTag("tire-manufacturer-search").performTextReplacement("m")
        compose.onNodeWithText("Mabor").assertIsDisplayed()
        compose.onNodeWithTag("tire-manufacturer-search").performTextReplacement("요코하마")
        compose.onNodeWithText("요코하마 · Yokohama").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("제조사, 요코하마").assertIsDisplayed()
        compose.onNodeWithText("등록하고 시작").scrollToIfNeeded().performClick()
        assertEquals("요코하마", requireNotNull(saved).manufacturer)
    }

    @Test fun typingFiltersEnglishAndKoreanAndSelectionSavesTheCanonicalName() {
        var saved: NewSetInput? = null
        compose.setContent { TirelogTheme {
            val form = rememberTireForm("manufacturer-search", NewSetInput(TireSetType.ALL_SEASON, null, null))
            ScreenFrame("내 타이어 등록", {}) {
                TireFormFields(form, today)
                BigButton("등록하고 시작", { saved = form.value() })
            }
        } }
        compose.onNodeWithTag("tire-manufacturer-selector").performClick()
        compose.onNodeWithTag("tire-manufacturer-search").performTextReplacement("miCH")
        compose.onNodeWithTag("tire-manufacturer-search").performImeAction()
        compose.onNodeWithTag("tire-manufacturer-search").assertIsNotFocused()
        compose.onNodeWithText("미쉐린 · Michelin").assertIsDisplayed()
        compose.onNodeWithText("금호타이어 · Kumho").assertDoesNotExist()
        compose.onNodeWithText("“miCH”으로 등록").assertDoesNotExist()
        compose.onNodeWithTag("tire-manufacturer-search").performTextReplacement("금호")
        compose.onNodeWithText("미쉐린 · Michelin").assertDoesNotExist()
        compose.onNodeWithText("금호타이어 · Kumho").assertIsDisplayed().performClick()
        compose.onNodeWithText("등록하고 시작").scrollToIfNeeded().performClick()
        assertEquals("금호타이어", requireNotNull(saved).manufacturer)
        assertEquals("app", requireNotNull(saved).lifeGuideId)
    }

    @Test fun unmatchedNameAndOpenSearchSurviveStateRestorationAndSaveAsEntered() {
        var saved: NewSetInput? = null
        val restoration = StateRestorationTester(compose)
        restoration.setContent { TirelogTheme(darkTheme = true) {
            val form = rememberTireForm("manufacturer-custom", NewSetInput(TireSetType.ALL_SEASON, null, null))
            ScreenFrame("내 타이어 등록", {}) {
                TireFormFields(form, today)
                BigButton("등록하고 시작", { saved = form.value() })
            }
        } }
        compose.onNodeWithTag("tire-manufacturer-selector").performClick()
        compose.onNodeWithTag("tire-manufacturer-search").performTextReplacement("  한빛 타이어  ")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("tire-manufacturer-search").assertTextContains("  한빛 타이어  ")
        compose.onNodeWithText("“한빛 타이어”으로 등록").assertIsDisplayed().performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithContentDescription("제조사, 한빛 타이어").assertIsDisplayed()
        compose.onNodeWithText("등록하고 시작").scrollToIfNeeded().performClick()
        assertEquals("한빛 타이어", requireNotNull(saved).manufacturer)
    }

    @Test fun dismissingSearchKeepsTheExistingManufacturerAndCustomPolicy() {
        var saved: NewSetInput? = null
        compose.setContent { TirelogTheme {
            val form = rememberTireForm("manufacturer-cancel", NewSetInput(TireSetType.ALL_SEASON, null, null,
                manufacturer = "미쉐린", lifeGuideId = "michelin", inspectionKm = 45000, inspectionMonths = 55))
            ScreenFrame("타이어 정보 수정", {}) {
                TireFormFields(form, today, editing = true)
                BigButton("저장", { saved = form.value() })
            }
        } }
        compose.onNodeWithTag("tire-manufacturer-selector").performClick()
        compose.onNodeWithTag("tire-manufacturer-search").performTextReplacement("다른 이름")
        compose.onNode(hasText("뒤로", substring = true) and hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithContentDescription("제조사, 미쉐린").assertIsDisplayed()
        compose.onNodeWithText("저장").scrollToIfNeeded().performClick()
        assertEquals("미쉐린", requireNotNull(saved).manufacturer)
        assertEquals(45000, requireNotNull(saved).inspectionKm)
        assertEquals(55, requireNotNull(saved).inspectionMonths)
    }

    @Test fun drivingRestrictionsDisableAnOpenSearchAndCustomSelection() {
        val enabled = mutableStateOf(true)
        compose.setContent { TirelogTheme {
            CompositionLocalProvider(LocalActionsEnabled provides enabled.value) {
                val form = rememberTireForm("manufacturer-restricted", NewSetInput(TireSetType.ALL_SEASON, null, null))
                ScreenFrame("내 타이어 등록", {}) { TireFormFields(form, today) }
            }
        } }
        compose.onNodeWithTag("tire-manufacturer-selector").performClick()
        compose.onNodeWithTag("tire-manufacturer-search").performTextReplacement("한빛 타이어")
        compose.runOnIdle { enabled.value = false }
        compose.onNodeWithTag("tire-manufacturer-search").assertIsNotEnabled()
        compose.onNodeWithText("“한빛 타이어”으로 등록").assertIsNotEnabled()
    }
}
