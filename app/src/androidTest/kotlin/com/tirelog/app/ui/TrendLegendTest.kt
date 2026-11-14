package com.tirelog.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.tirelog.app.ui.main.TrendLegend
import com.tirelog.app.ui.theme.TirelogTheme
import com.tirelog.core.engine.TrendSeries
import com.tirelog.core.model.WheelPosition
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TrendLegendTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val series = WheelPosition.entries.mapIndexed { index, position ->
        TrendSeries(index + 1L, position.label, emptyList(), colorIndex = index)
    }

    @Test fun tiresAlignLeftAndTemperatureAlignsRightOnOneRow() {
        val hidden = mutableStateOf(emptySet<Long>())
        val temperature = mutableStateOf(true)
        compose.setContent {
            TirelogTheme {
                Box(Modifier.width(1120.dp).testTag("legend-window")) {
                    TrendLegend(series, hidden.value, temperature.value,
                        onToggle = { id -> hidden.value = if (id in hidden.value) hidden.value - id else hidden.value + id },
                        onTemperatureToggle = { temperature.value = !temperature.value })
                }
            }
        }
        val container = compose.onNodeWithTag("legend-window").fetchSemanticsNode().boundsInRoot
        val wheels = series.map { compose.onNodeWithTag("trend-series-${it.tireId}").fetchSemanticsNode().boundsInRoot }
        val ambient = compose.onNodeWithTag("trend-temperature").fetchSemanticsNode().boundsInRoot
        assertEquals(container.left, wheels.first().left, 2f)
        assertEquals(container.right, ambient.right, 2f)
        wheels.forEach { assertEquals(ambient.center.y, it.center.y, 2f) }
        wheels.zipWithNext().forEach { (a, b) -> assertTrue(a.right < b.left) }
        assertTrue(wheels.last().right < ambient.left)
        compose.onNodeWithText("앞왼쪽 · ON").performClick()
        compose.onNodeWithText("앞왼쪽 · OFF").assertIsNotSelected()
        assertEquals(setOf(1L), hidden.value)
        compose.onNodeWithText("외기온도 · ON").performClick()
        compose.onNodeWithText("외기온도 · OFF").assertIsNotSelected()
        assertFalse(temperature.value)
        compose.activityRule.scenario.onActivity { it.finish() }
    }

    @Test fun narrowLargeTextCanReachEveryToggleWithoutWrappingTheRow() {
        val hidden = mutableStateOf(emptySet<Long>())
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                TirelogTheme {
                    Box(Modifier.width(375.dp)) {
                        TrendLegend(series, hidden.value, true,
                            onToggle = { id -> hidden.value = hidden.value + id }, onTemperatureToggle = {})
                    }
                }
            }
        }
        series.forEach { item ->
            compose.onNodeWithTag("trend-series-${item.tireId}").performScrollTo().assertIsDisplayed().performClick().assertIsNotSelected()
        }
        compose.onNodeWithTag("trend-temperature").performScrollTo().assertIsDisplayed()
        assertEquals(series.map { it.tireId }.toSet(), hidden.value)
        compose.activityRule.scenario.onActivity { it.finish() }
    }
}
