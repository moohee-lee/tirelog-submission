package com.tirelog.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.tirelog.app.ui.common.LocalActionsEnabled
import com.tirelog.app.ui.main.RotationDialog
import com.tirelog.app.ui.main.RotationPatternPicker
import com.tirelog.app.ui.theme.TirelogTheme
import com.tirelog.core.model.RotationPattern
import org.junit.Assert.*
import org.junit.After
import org.junit.Rule
import org.junit.Test

class RotationControlsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @After fun finish() { compose.activityRule.scenario.onActivity { it.finish() } }

    @Test fun choicesUpdateOneLargeDiagramAndOnlyApplyWritesTheSelection() {
        var applied: RotationPattern? = null
        compose.setContent { TirelogTheme(darkTheme = false) { RotationDialog({}, { applied = it }) } }
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithTag("rotation-apply").assertIsNotEnabled()
        RotationPattern.entries.forEach { pattern ->
            compose.onNodeWithTag("rotation-choice-${pattern.name}").scrollToIfNeeded().assertIsNotSelected().performClick().assertIsSelected()
            compose.onNodeWithTag("rotation-preview-title").assertTextEquals(pattern.label)
            compose.onAllNodesWithTag("rotation-diagram").assertCountEquals(1)
            compose.onNodeWithTag("rotation-diagram").scrollToIfNeeded().assertIsDisplayed()
                .assert(hasContentDescription("앞왼쪽에서 ${pattern.destinationOf(com.tirelog.core.model.WheelPosition.FRONT_LEFT).label}으로 이동", substring = true))
            captureUi("rotation-${pattern.name.lowercase()}-arrows")
            assertNull(applied)
        }
        compose.onNodeWithTag("rotation-apply").assertTextContains("앞타이어 교차 적용").performClick()
        assertEquals(RotationPattern.REARWARD_CROSS, applied)
    }

    @Test fun narrowLargeTextKeepsChoicesReachableAndDrivingLockBlocksSelection() {
        val enabled = mutableStateOf(true)
        val selected = mutableStateOf<RotationPattern?>(null)
        var density = 1f
        compose.setContent {
            density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 2f), LocalActionsEnabled provides enabled.value) {
                TirelogTheme {
                    Column(Modifier.width(375.dp).verticalScroll(rememberScrollState())) {
                        RotationPatternPicker(selected.value, { selected.value = it })
                    }
                }
            }
        }
        RotationPattern.entries.forEach { pattern ->
            val button = compose.onNodeWithTag("rotation-choice-${pattern.name}").scrollToIfNeeded().assertIsDisplayed()
            val bounds = button.fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.right <= 375 * density + 1)
            assertTrue(bounds.height >= 88 * density - 1)
            button.performClick().assertIsSelected()
            assertEquals(pattern, selected.value)
        }
        compose.runOnIdle { enabled.value = false }
        compose.onNodeWithTag("rotation-choice-FRONT_REAR").scrollToIfNeeded().assertIsNotEnabled().performTouchInput { click() }
        assertEquals(RotationPattern.REARWARD_CROSS, selected.value)
    }

    @Test fun nightLayoutKeepsTheDiagramVisibleAndCancelDoesNotApplyAChosenPattern() {
        var dismissed = false
        var applied: RotationPattern? = null
        compose.setContent { TirelogTheme(darkTheme = true) { RotationDialog({ dismissed = true }, { applied = it }) } }
        compose.onNodeWithTag("rotation-choice-X_CROSS").scrollToIfNeeded().performClick()
        compose.onNodeWithTag("rotation-diagram").assertIsDisplayed()
        captureUi("rotation-night")
        compose.onNodeWithText("취소").performClick()
        assertTrue(dismissed)
        assertNull(applied)
    }
}
