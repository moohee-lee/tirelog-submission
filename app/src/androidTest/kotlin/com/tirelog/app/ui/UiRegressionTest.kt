package com.tirelog.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.tirelog.app.ui.common.AlertPopup
import com.tirelog.app.ui.common.Stepper
import com.tirelog.app.ui.main.DotField
import com.tirelog.app.ui.theme.TirelogTheme
import com.tirelog.core.alert.ActiveAlert
import com.tirelog.core.alert.AlertAction
import com.tirelog.core.model.AlertType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

class UiRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun rotationHelpRemainsReachableWithLargeTextAndDoesNotApplyUntilConfirmed() {
        var result: com.tirelog.core.model.RotationPattern? = null
        compose.setContent {
            var visible by remember { mutableStateOf(true) }
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                TirelogTheme {
                    if (visible) com.tirelog.app.ui.main.RotationDialog({ visible = false }, { result = it; visible = false })
                }
            }
        }
        compose.onNodeWithTag("rotation-choice-X_CROSS").scrollToIfNeeded().performClick()
        compose.onNodeWithText("차량 앞쪽").scrollToIfNeeded().assertIsDisplayed()
        assertEquals(null, result)
        assertEquals(null, result)
        compose.onNodeWithTag("rotation-apply").performClick()
        assertEquals(com.tirelog.core.model.RotationPattern.X_CROSS, result)
        // Pleos embeds activities in a task view. Finish the foreground host directly,
        // as the app does, before ActivityScenario's empty-activity teardown takes focus.
        compose.activityRule.scenario.onActivity { it.finish() }
    }

    @Test
    fun stepperFitsNarrowScreenWithLargeTextAndNamesItsControls() {
        var increased = false
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 1.8f)) {
                TirelogTheme {
                    Box(Modifier.width(280.dp)) {
                        Stepper("앞 권장 공기압", "1psi", {}, { increased = true }, minusEnabled = false)
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("앞 권장 공기압 줄이기").assertIsNotEnabled().assertIsDisplayed()
        compose.onNodeWithContentDescription("앞 권장 공기압 늘리기").assertIsDisplayed().performClick()
        assertEquals(true, increased)
    }

    @Test
    fun incompleteDotExplainsHowToFinish() {
        compose.setContent {
            TirelogTheme {
                DotField("123", false, LocalDate.of(2026, 10, 29), {}, {})
            }
        }
        compose.onNodeWithText("숫자 4자리로 입력해 주세요").assertIsDisplayed()
        compose.onNodeWithText("제조 연식 모름").assertIsDisplayed()
    }

    @Test
    fun longAlertKeepsEveryActionReachableWithLargeText() {
        var result: AlertAction? = null
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                TirelogTheme {
                    AlertPopup(
                        ActiveAlert(
                            "LEAK:1", AlertType.LEAK,
                            "앞오른쪽이 다른 바퀴보다 빨리 빠집니다. 점검을 권합니다.",
                            detail = "기온 보정 후에도 압력이 낮아지고 있습니다. 가까운 타이어점에서 확인해 주세요.",
                            actions = listOf(AlertAction.NAVIGATE, AlertAction.DETAILS, AlertAction.LATER),
                            listText = "누설 의심",
                        ),
                        emptyList(),
                    ) { result = it }
                }
            }
        }
        compose.onNodeWithText("타이어점 길안내").scrollToIfNeeded().assertIsDisplayed()
        compose.onNodeWithText("자세히").scrollToIfNeeded().assertIsDisplayed()
        compose.onNodeWithText("나중에").scrollToIfNeeded().performClick()
        assertEquals(AlertAction.LATER, result)
    }
}
