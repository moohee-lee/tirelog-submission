package com.tirelog.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.tirelog.app.ui.common.LocalActionsEnabled
import com.tirelog.app.ui.main.RotationDialog
import com.tirelog.app.ui.main.RotationHistoryDialog
import com.tirelog.app.ui.theme.TirelogTheme
import com.tirelog.core.engine.RotationHistoryEntry
import com.tirelog.core.engine.RotationHistorySnapshot
import com.tirelog.core.model.RotationPattern
import com.tirelog.core.model.TireMove
import com.tirelog.core.model.WheelPosition
import org.junit.Assert.*
import org.junit.After
import org.junit.Rule
import org.junit.Test

class RotationHistoryUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @After fun finishTestActivity() {
        compose.activityRule.scenario.onActivity { it.finish() }
    }

    @Test fun historySelectionOnlyUpdatesTheChoiceAndApplyRemainsTheOnlyWriteAction() {
        var applied: RotationPattern? = null
        val enabled = mutableStateOf(true)
        compose.setContent {
            CompositionLocalProvider(LocalActionsEnabled provides enabled.value) {
                TirelogTheme(darkTheme = true) { RotationDialog({}, { applied = it }, history = history()) }
            }
        }
        compose.onNodeWithText("최근 위치교환").assertExists()
        compose.onNodeWithTag("rotation-apply").assertIsNotEnabled()
        compose.onNodeWithText("이전 이력 보기").scrollToIfNeeded().performClick()
        compose.onNode(hasText("상세 보기") and hasAnyAncestor(hasTestTag("rotation-history-entry-5")))
            .scrollToIfNeeded().performClick()
        compose.onNodeWithTag("rotation-diagram").scrollToIfNeeded().assertIsDisplayed()
        compose.onNodeWithText("타이어 A · 앞왼쪽 → 뒤오른쪽").scrollToIfNeeded().assertIsDisplayed()
        assertNull(applied)
        compose.onNodeWithText("이 방식 선택").performClick()
        compose.onNodeWithTag("rotation-choice-REARWARD_CROSS").scrollToIfNeeded().assertIsSelected()
        assertNull(applied)
        compose.runOnIdle { enabled.value = false }
        compose.onNodeWithTag("rotation-apply").assertIsNotEnabled()
        compose.runOnIdle { enabled.value = true }
        compose.onNodeWithTag("rotation-apply").performClick()
        assertEquals(RotationPattern.REARWARD_CROSS, applied)
    }

    @Test fun historyPagesOlderRecordsAndSeparatesTheMountedSetFromOtherSets() {
        compose.setContent { TirelogTheme { RotationHistoryDialog(history(), {}, {}) } }
        compose.onNodeWithTag("rotation-history-entry-7").assertDoesNotExist()
        compose.onNodeWithText("이전 기록").performClick()
        compose.onNodeWithTag("rotation-history-entry-1").assertExists()
        compose.onNodeWithTag("rotation-history-entry-6").assertDoesNotExist()
        compose.onNodeWithText("전체 세트").scrollToIfNeeded().performClick()
        compose.onNodeWithTag("rotation-history-entry-7").assertExists()
        compose.onNodeWithText("당시 주행거리 미기록", substring = true).assertExists()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }

    @Test fun unknownLegacyPatternsKeepTheirDescriptionAndCannotBeSelected() {
        val legacy = history().entries.last().copy(pattern = null, moves = emptyList(), detail = "정비소에서 교환")
        var selected: RotationPattern? = null
        compose.setContent {
            TirelogTheme { RotationHistoryDialog(history().copy(entries = listOf(legacy)), {}, { selected = it }) }
        }
        compose.onNodeWithText("상세 보기").scrollToIfNeeded().performClick()
        compose.onNodeWithText("정비소에서 교환").scrollToIfNeeded().assertIsDisplayed()
        compose.onNodeWithTag("rotation-diagram").assertDoesNotExist()
        compose.onNodeWithText("이 방식 선택").assertDoesNotExist()
        assertNull(selected)
    }

    @Test fun enlargedTextKeepsApplyVisibleWhileScrollingChoices() {
        var applied: RotationPattern? = null
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 1.3f)) {
                TirelogTheme { RotationDialog({}, { applied = it }, history = history()) }
            }
        }
        compose.onNodeWithTag("rotation-apply").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag("rotation-choice-REARWARD_CROSS").scrollToIfNeeded().performClick()
        compose.onNodeWithTag("rotation-apply").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("이전 이력 보기").scrollToIfNeeded().assertIsDisplayed()
        compose.onNodeWithTag("rotation-apply").assertIsDisplayed()
        assertNull(applied)
    }

    private fun history() = RotationHistorySnapshot(1, "여름 세트 #1", (1..7).reversed().map { id ->
        val pattern = RotationPattern.entries[(id - 1) % RotationPattern.entries.size]
        RotationHistoryEntry(
            eventId = id.toLong(), setId = if (id == 7) 2 else 1,
            setLabel = if (id == 7) "겨울 세트 #2" else "여름 세트 #1",
            dateLabel = "2026-11-0$id", odoKm = if (id == 7) null else 10_000.0 + id * 100,
            pattern = pattern,
            moves = WheelPosition.entries.mapIndexed { index, from ->
                TireMove(index + 1L, ('A' + index).toString(), from, pattern.destinationOf(from))
            },
            detail = null, sameTires = if (id == 7) null else true,
        )
    })
}
