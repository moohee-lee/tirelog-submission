package com.tirelog.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.tirelog.app.ui.common.BigButton
import com.tirelog.app.ui.common.ScreenFrame
import com.tirelog.app.ui.main.TireFormFields
import com.tirelog.app.ui.main.rememberTireForm
import com.tirelog.app.ui.theme.TirelogColors
import com.tirelog.app.ui.theme.TirelogPalette
import com.tirelog.app.ui.theme.TirelogTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

class PleosLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun formReflowsWithoutLosingInputAndLargeTextActionsStayReachable() {
        val width = mutableStateOf(1120.dp)
        val scale = mutableStateOf(1f)
        var saved = false
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, scale.value)) {
                TirelogTheme(darkTheme = false) {
                    val form = rememberTireForm("layout")
                    Box(Modifier.width(width.value)) {
                        ScreenFrame("타이어 등록", {}) {
                            TireFormFields(form, LocalDate.of(2026, 10, 31))
                            BigButton("저장", { saved = true })
                        }
                    }
                }
            }
        }
        val info = compose.onNodeWithText("타이어 정보").fetchSemanticsNode().boundsInRoot
        val usage = compose.onNodeWithText("사용 정보").fetchSemanticsNode().boundsInRoot
        assertTrue("넓은 창에서는 두 영역이 나란히 배치되어야 합니다", info.right < usage.left)
        assertEquals(info.top, usage.top, 2f)
        compose.enterTireModel("유지할 타이어")
        compose.runOnIdle { width.value = 375.dp; scale.value = 2f }
        compose.onNodeWithTag("tire-model-selector").performScrollTo().assertTextContains("유지할 타이어")
        compose.onNodeWithText("사용 정보").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("registration-mileage").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("저장").performScrollTo().assertIsDisplayed().performClick()
        assertTrue(saved)
        compose.activityRule.scenario.onActivity { it.finish() }
    }

    @Test fun textAndChartColorsRemainLegibleInDayAndNightThemes() {
        val dark = mutableStateOf(false)
        var palette: TirelogPalette? = null
        compose.setContent { TirelogTheme(darkTheme = dark.value) { palette = TirelogColors } }
        fun check() {
            val p = requireNotNull(palette)
            for (background in listOf(p.Background, p.Surface, p.SurfaceHigh)) {
                for (foreground in listOf(p.OnSurface, p.Muted, p.Red, p.Amber, p.Yellow, p.Green, p.Orange)) {
                    assertTrue("문자 대비가 부족합니다: $foreground / $background", contrast(foreground, background) >= 4.5)
                }
            }
            p.Series.forEach { assertTrue("차트 선의 대비가 부족합니다", contrast(it, p.Surface) >= 3.0) }
        }
        compose.runOnIdle { check(); dark.value = true }
        compose.runOnIdle { check() }
        compose.activityRule.scenario.onActivity { it.finish() }
    }

    private fun contrast(a: Color, b: Color): Double {
        val first = a.luminance().toDouble()
        val second = b.luminance().toDouble()
        return (maxOf(first, second) + .05) / (minOf(first, second) + .05)
    }
}
