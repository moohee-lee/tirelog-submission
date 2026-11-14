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
import com.tirelog.app.ui.common.BigButton
import com.tirelog.app.ui.common.LocalActionsEnabled
import com.tirelog.app.ui.main.RotationDialog
import com.tirelog.app.ui.main.TireManagementActions
import com.tirelog.app.ui.main.TrendChart
import com.tirelog.app.ui.main.TrendDateDialog
import com.tirelog.app.ui.theme.TirelogTheme
import com.tirelog.core.engine.TrendData
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ActionGroupingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun managementActionsShareOneRowAndTogglesKeepTheirSizeAndState() {
        val retired = mutableStateOf(false)
        val history = mutableStateOf(false)
        var registrations = 0
        compose.setContent {
            TirelogTheme {
                Column(Modifier.width(1120.dp)) {
                    TireManagementActions(retired.value, history.value, { registrations++ },
                        { retired.value = !retired.value }, { history.value = !history.value })
                }
            }
        }
        val labels = listOf("타이어 등록", "폐기 기록", "정비 이력")
        val before = labels.map { compose.onNodeWithText(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot }
        before.zipWithNext().forEach { (a, b) ->
            assertEquals(a.center.y, b.center.y, 2f)
            assertTrue(a.right < b.left)
        }
        compose.onNodeWithText("폐기 기록").assertIsNotSelected().performClick().assertIsSelected()
        compose.onNodeWithText("정비 이력").assertIsNotSelected().performClick().assertIsSelected()
        assertTrue(retired.value)
        assertTrue(history.value)
        labels.forEachIndexed { i, label ->
            assertEquals(before[i], compose.onNodeWithText(label).fetchSemanticsNode().boundsInRoot)
        }
        compose.onNodeWithText("타이어 등록").performClick()
        assertEquals(1, registrations)
        compose.activityRule.scenario.onActivity { it.finish() }
    }

    @Test fun narrowLargeTextWrapsWithoutClippingAndDisabledActionsStayBlocked() {
        val enabled = mutableStateOf(true)
        var clicks = 0
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 2f), LocalActionsEnabled provides enabled.value) {
                TirelogTheme {
                    Column(Modifier.width(375.dp).verticalScroll(rememberScrollState())) {
                        TireManagementActions(false, false, { clicks++ }, { clicks++ }, { clicks++ })
                    }
                }
            }
        }
        listOf("타이어 등록", "폐기 기록", "정비 이력").forEach { label ->
            compose.onNodeWithText(label).scrollToIfNeeded().assertIsDisplayed().performClick()
        }
        assertEquals(3, clicks)
        compose.runOnIdle { enabled.value = false }
        compose.onNodeWithText("정비 이력").assertIsNotEnabled().performTouchInput { click() }
        assertEquals(3, clicks)
        compose.activityRule.scenario.onActivity { it.finish() }
    }

    @Test fun dateAndRotationDialogsGroupTheirPrimaryAndCancelActions() {
        val dialog = mutableStateOf("date")
        val today = LocalDate.of(2026, 10, 31)
        compose.setContent {
            TirelogTheme {
                if (dialog.value == "date") TrendDateDialog(today.minusDays(6) to today, today,
                    { dialog.value = "rotation" }, { _, _ -> })
                else RotationDialog({}, {})
            }
        }
        sameRow("기간 적용", "취소")
        compose.onNodeWithText("취소").performClick()
        sameRow("취소", "적용", scrollTo = false)
        compose.onNodeWithTag("rotation-choice-FRONT_REAR").scrollToIfNeeded().performClick()
        sameRow("취소", "앞뒤 교환 적용", scrollTo = false)
        compose.activityRule.scenario.onActivity { it.finish() }
    }

    @Test fun trendOptionsRemainGroupedAboveTheExpandedReadings() {
        val zone = ZoneId.of("Asia/Seoul")
        val today = LocalDate.of(2026, 10, 31).atStartOfDay(zone).toInstant().toEpochMilli()
        val data = TrendData(today - 86_400_000, today, emptyList(), emptyList(), emptyList(), 0)
        compose.setContent {
            TirelogTheme {
                Column(Modifier.width(1120.dp).verticalScroll(rememberScrollState())) {
                    TrendChart(data, emptyList(), false, zone,
                        additionalActions = { BigButton("이전 타이어도 보기", {}, selected = false) })
                }
            }
        }
        sameRow("측정값 목록 보기", "이전 타이어도 보기")
        compose.onNodeWithText("측정값 목록 보기").scrollToIfNeeded().performClick()
        sameRow("측정값 목록 접기", "이전 타이어도 보기")
        val actions = compose.onNodeWithText("측정값 목록 접기").fetchSemanticsNode().boundsInRoot
        val table = compose.onNodeWithTag("readings-header").fetchSemanticsNode().boundsInRoot
        assertTrue(actions.bottom < table.top)
        compose.activityRule.scenario.onActivity { it.finish() }
    }

    private fun sameRow(first: String, second: String, scrollTo: Boolean = true) {
        if (scrollTo) compose.onNodeWithText(first).scrollToIfNeeded()
        val a = compose.onNodeWithText(first).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val b = compose.onNodeWithText(second).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals(a.center.y, b.center.y, 2f)
        assertTrue(a.right < b.left)
    }
}
