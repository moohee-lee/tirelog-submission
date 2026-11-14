package com.tirelog.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.tirelog.app.ui.main.TrendChart
import com.tirelog.app.ui.main.TrendReadingsTable
import com.tirelog.app.ui.theme.TirelogTheme
import com.tirelog.core.engine.*
import com.tirelog.core.model.WheelPosition
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class TrendReadingsTableTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val zone = ZoneId.of("Asia/Seoul")
    private val recent = LocalDate.of(2026, 10, 12).atTime(7, 0).atZone(zone).toInstant().toEpochMilli()
    private val old = recent - 86_400_000
    private val positions = listOf(WheelPosition.FRONT_RIGHT, WheelPosition.FRONT_LEFT, WheelPosition.REAR_RIGHT, WheelPosition.REAR_LEFT)
    private val data = TrendData(old, recent + 86_400_000,
        positions.mapIndexed { index, position ->
            TrendSeries(index + 1L, position.label, buildList {
                if (index == 0) add(TrendPoint(old, 32.0, position = position))
                add(TrendPoint(recent, 36.0 - index, position = position))
            })
        }, listOf(TrendPoint(recent, 28.0)), emptyList(), 2)

    @Test fun eachDateShowsTemperatureAndFourWheelsInTheRequestedColumnOrder() {
        compose.setContent { TirelogTheme { Column(Modifier.width(1120.dp)) { TrendReadingsTable(data, zone) } } }
        val headers = listOf("날짜", "외기온도", "앞오른쪽", "앞왼쪽", "뒤오른쪽", "뒤왼쪽")
        val values = listOf("2026-10-12", "28℃", "36 psi", "35 psi", "34 psi", "33 psi")
        val bounds = values.map { compose.onNodeWithText(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot }
        headers.zip(bounds).forEach { (header, value) ->
            val column = compose.onNodeWithText(header).fetchSemanticsNode().boundsInRoot
            assertEquals(column.left, value.left, 2f)
            assertEquals(column.right, value.right, 2f)
        }
        bounds.zipWithNext().forEach { (a, b) ->
            assertTrue(a.right <= b.left)
            assertEquals(a.center.y, b.center.y, 2f)
        }
        val latestRow = compose.onNodeWithTag("readings-2026-10-12").fetchSemanticsNode().boundsInRoot
        val oldRow = compose.onNodeWithTag("readings-2026-10-11").fetchSemanticsNode().boundsInRoot
        assertTrue(latestRow.bottom <= oldRow.top)
        compose.onAllNodes(hasText("—") and hasAnyAncestor(hasTestTag("readings-2026-10-11"))).assertCountEquals(4)
        compose.activityRule.scenario.onActivity { it.finish() }
    }

    @Test fun tableKeepsTheCompleteRecordWhenGraphSeriesAreHidden() {
        compose.setContent {
            TirelogTheme {
                Column(Modifier.width(1120.dp).verticalScroll(rememberScrollState())) {
                    TrendChart(data, emptyList(), false, zone)
                }
            }
        }
        compose.onNodeWithText("측정값 목록 보기").performScrollTo().performClick()
        compose.onNodeWithText("28℃").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("36 psi").assertIsDisplayed()
        compose.onNodeWithText("33 psi").assertIsDisplayed()
        compose.activityRule.scenario.onActivity { it.finish() }
    }

    @Test fun narrowLargeTextScrollsTheColumnsTogetherInsteadOfWrappingCells() {
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                TirelogTheme {
                    Column(Modifier.width(375.dp).verticalScroll(rememberScrollState())) {
                        TrendReadingsTable(data, zone)
                    }
                }
            }
        }
        compose.onNodeWithText("뒤왼쪽").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("33 psi").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("2026-10-12").performScrollTo().assertIsDisplayed()
        compose.activityRule.scenario.onActivity { it.finish() }
    }
}
