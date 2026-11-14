package com.tirelog.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.tirelog.app.ui.card.CardUiState
import com.tirelog.app.ui.card.DrivingOverview
import com.tirelog.app.ui.card.FullCard
import com.tirelog.app.ui.common.BigButton
import com.tirelog.app.ui.common.ScreenFrame
import com.tirelog.app.ui.main.RotationDialog
import com.tirelog.app.ui.main.TrendChart
import com.tirelog.app.ui.theme.TirelogTheme
import com.tirelog.core.engine.*
import com.tirelog.core.model.WheelPosition
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.ZoneId

class VehicleViewportTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @After fun finishActivity() { compose.activityRule.scenario.onActivity { it.finish() } }

    @Test fun pageContentUsesTheWholeWindowAndButtonsHaveLargeTouchTargets() {
        var density = 1f
        compose.setContent {
            density = LocalDensity.current.density
            TirelogTheme {
                Box(Modifier.fillMaxSize().testTag("viewport")) {
                    ScreenFrame("전체 화면", {}) {
                        Box(Modifier.fillMaxWidth().height(80.dp).testTag("page-width"))
                        BigButton("작업", {})
                    }
                }
            }
        }
        awaitViewport()
        val window = compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        val content = compose.onNodeWithTag("page-width").fetchSemanticsNode().boundsInRoot
        assertTrue(content.width >= window.width - 50 * density)
        assertTrue(compose.onNodeWithText("작업").fetchSemanticsNode().boundsInRoot.height >= 88 * density - 1)
    }

    @Test fun rotationTaskFillsTheWindowAndItsApplyActionStaysVisible() {
        compose.setContent {
            TirelogTheme {
                Box(Modifier.fillMaxSize().testTag("viewport"))
                RotationDialog({}, {})
            }
        }
        awaitViewport()
        val window = compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        val page = compose.onNodeWithTag("full-page-dialog").fetchSemanticsNode().boundsInRoot
        assertTrue(page.width >= window.width * .95f)
        assertTrue(page.height >= window.height * .9f)
        compose.onNodeWithTag("rotation-choice-REARWARD_CROSS").scrollToIfNeeded().performClick()
        compose.onNodeWithTag("rotation-apply").assertIsDisplayed().assertIsEnabled()
    }

    @Test fun homePressureUsesMostOfTheHeightWithFourReachableActions() {
        compose.setContent {
            TirelogTheme {
                Box(Modifier.fillMaxSize().testTag("viewport")) {
                    FullCard(CardUiState.Ready(snapshot(), false, true, null), {}, false, {}, {})
                }
            }
        }
        awaitViewport()
        val window = compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        val panel = compose.onNodeWithTag("home-pressure-panel").fetchSemanticsNode().boundsInRoot
        assertTrue(panel.width >= window.width * .5f)
        assertTrue(panel.height >= window.height * .65f)
        listOf("타이어 관리", "추이 보기", "타이어 교체", "위치교환").forEach {
            compose.onNodeWithText(it).assertIsDisplayed().assertHasClickAction()
        }
    }

    @Test fun drivingKeepsAllFourPressuresVisibleWithoutEditingActions() {
        compose.setContent { TirelogTheme { DrivingOverview(snapshot()) } }
        WheelPosition.entries.forEach { compose.onNodeWithTag("pressure-${it.name}").assertIsDisplayed() }
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
    }

    @Test fun chartExpandsIntoAvailableHeight() {
        compose.setContent {
            TirelogTheme {
                Column(Modifier.fillMaxSize().testTag("viewport")) {
                    TrendChart(TrendData(0, 1, emptyList(), emptyList(), emptyList(), 0), emptyList(), false,
                        ZoneId.of("Asia/Seoul"), chartModifier = Modifier.weight(1f))
                }
            }
        }
        awaitViewport()
        val window = compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        assertTrue(compose.onNodeWithTag("trend-chart").fetchSemanticsNode().boundsInRoot.height >= window.height * .65f)
    }

    private fun awaitViewport() {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("viewport").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
    }

    private fun snapshot() = DashboardSnapshot.empty(0).copy(onboardingDone = true,
        headline = "공기압 기록 중", wheels = WheelPosition.entries.map {
            WheelCard(it, it.ordinal + 1L, ('A' + it.ordinal).toString(), 36, 36,
                "오늘 냉간 측정", null, WheelState.NORMAL)
        })
}
