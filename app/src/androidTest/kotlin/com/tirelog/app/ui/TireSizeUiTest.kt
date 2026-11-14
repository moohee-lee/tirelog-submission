package com.tirelog.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
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

class TireSizeUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @After fun finishActivity() { compose.activityRule.scenario.onActivity { it.finish() } }
    private val today = LocalDate.of(2026, 11, 7)

    @Composable private fun Form(initial: String? = null, save: (NewSetInput) -> Unit = {}) {
        val form = rememberTireForm("size", NewSetInput(TireSetType.ALL_SEASON, initial, null))
        ScreenFrame("타이어 정보", {}) {
            TireSizeFields(form.size) { form.size = it }
            BigButton("저장", { save(form.value()) }, enabled = form.valid(today))
        }
    }

    @Test fun guideMapsSidewallNumbersToFieldsAndNumericEntryBuildsTheSize() {
        var saved: NewSetInput? = null
        compose.setContent { TirelogTheme { Form { saved = it } } }
        compose.onNodeWithTag("tire-size-guide").assertDoesNotExist()
        compose.onNodeWithTag("tire-size-guide-button").performClick()
        compose.onNodeWithTag("tire-size-guide").assertExists()
        compose.onNodeWithText("닫기").performClick()
        compose.onNodeWithTag("tire-size-width").performScrollTo().performTextReplacement("a2359")
        compose.onNodeWithTag("tire-size-width").performImeAction()
        compose.onNodeWithTag("tire-size-aspect").assertIsFocused().performTextReplacement("45%")
        compose.onNodeWithTag("tire-size-aspect").performImeAction()
        compose.onNodeWithTag("tire-size-rim").assertIsFocused().performTextReplacement("R18x")
        compose.onNodeWithTag("tire-size-rim").performImeAction()
        compose.onNodeWithTag("tire-size-rim").assertIsNotFocused()
        compose.onNodeWithText("규격 235/45 R18").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("저장").performScrollTo().performClick()
        assertEquals("235/45 R18", requireNotNull(saved).size)
    }

    @Test fun incompleteSizeSurvivesRestorationAndMustBeCompletedOrCleared() {
        var saved: NewSetInput? = null
        val restoration = StateRestorationTester(compose)
        restoration.setContent { TirelogTheme(darkTheme = true) { Form { saved = it } } }
        compose.onNodeWithTag("tire-size-width").performScrollTo().performTextReplacement("195")
        compose.onNodeWithText("저장").performScrollTo().assertIsNotEnabled()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("tire-size-width").performScrollTo().assertTextContains("195")
        compose.onNodeWithTag("tire-size-aspect").performScrollTo().performTextReplacement("65")
        compose.onNodeWithTag("tire-size-rim").performScrollTo().performTextReplacement("15")
        compose.onNodeWithText("저장").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("규격 모름").performScrollTo().performClick()
        compose.onNodeWithText("저장").performScrollTo().performClick()
        assertNull(requireNotNull(saved).size)
    }

    @Test fun vehicleDefaultsFillTheNumericFieldsAndOlderFormatsRemainUntilEdited() {
        val initial = mutableStateOf("235/45R18")
        var saved: NewSetInput? = null
        compose.setContent { TirelogTheme {
            val form = rememberTireForm("external-size", NewSetInput(TireSetType.ALL_SEASON, null, null))
            LaunchedEffect(initial.value) { form.size = initial.value }
            ScreenFrame("타이어 정보", {}) {
                TireSizeFields(form.size) { form.size = it }
                BigButton("저장", { saved = form.value() })
            }
        } }
        compose.onNodeWithTag("tire-size-width").performScrollTo().assertTextContains("235")
        compose.onNodeWithTag("tire-size-aspect").performScrollTo().assertTextContains("45")
        compose.onNodeWithTag("tire-size-rim").performScrollTo().assertTextContains("18")
        compose.runOnIdle { initial.value = "35x12.50R17" }
        compose.onNodeWithText("저장").performScrollTo().performClick()
        assertEquals("35x12.50R17", requireNotNull(saved).size)
        compose.onNodeWithText("규격 모름").performScrollTo().performClick()
        compose.onNodeWithText("저장").performScrollTo().performClick()
        assertNull(requireNotNull(saved).size)
    }

    @Test fun largeTextNarrowLayoutKeepsGuideAndAllFieldsReachableAndDrivingDisablesThem() {
        val enabled = mutableStateOf(true)
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 1.8f), LocalActionsEnabled provides enabled.value) {
                TirelogTheme { Box(Modifier.width(375.dp)) { Form() } }
            }
        }
        compose.onNodeWithTag("tire-size-guide-button").performScrollTo().assertExists()
        for (tag in listOf("tire-size-width", "tire-size-aspect", "tire-size-rim")) {
            compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
        }
        compose.runOnIdle { enabled.value = false }
        compose.onNodeWithTag("tire-size-guide-button").assertIsNotEnabled()
        for (tag in listOf("tire-size-width", "tire-size-aspect", "tire-size-rim")) compose.onNodeWithTag(tag).assertIsNotEnabled()
    }
}
