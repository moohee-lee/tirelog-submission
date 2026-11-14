package com.tirelog.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.tirelog.app.ui.common.VehiclePressureDiagram
import com.tirelog.app.ui.common.drawVehicleTopView
import com.tirelog.app.ui.common.vehicleWheelBounds
import com.tirelog.app.ui.main.rotationArrowEndpoints
import com.tirelog.app.ui.theme.TirelogTheme
import com.tirelog.app.ui.theme.TirelogColors
import com.tirelog.app.vehicle.PleosVehicleArtwork
import com.tirelog.core.engine.WheelCard
import com.tirelog.core.engine.WheelState
import com.tirelog.core.model.WheelPosition
import com.tirelog.core.model.RotationPattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.roundToInt

class VehiclePressureDiagramTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun shuffledReadingsStayBesideTheirActualWheelPositions() {
        val wheels = listOf(
            wheel(WheelPosition.REAR_RIGHT, 34), wheel(WheelPosition.FRONT_LEFT, 31),
            wheel(WheelPosition.REAR_LEFT, 33), wheel(WheelPosition.FRONT_RIGHT, 32, WheelState.LEAK),
        )
        compose.setContent {
            TirelogTheme { Box(Modifier.width(960.dp)) { VehiclePressureDiagram(wheels) } }
        }
        val fl = compose.onNodeWithTag("pressure-FRONT_LEFT").assertTextContains("31 psi").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("pressure-FRONT_LEFT").assert(hasContentDescription("앞왼쪽, 31 psi", substring = true))
        val fr = compose.onNodeWithTag("pressure-FRONT_RIGHT").assertTextContains("32 psi").assertTextContains("누설 의심").fetchSemanticsNode().boundsInRoot
        val rl = compose.onNodeWithTag("pressure-REAR_LEFT").assertTextContains("33 psi").fetchSemanticsNode().boundsInRoot
        val rr = compose.onNodeWithTag("pressure-REAR_RIGHT").assertTextContains("34 psi").fetchSemanticsNode().boundsInRoot
        assertTrue(fl.right < fr.left)
        assertTrue(rl.right < rr.left)
        assertTrue(fl.bottom < rl.top)
        assertTrue(fr.bottom < rr.top)
        assertTrue(fl.center.x < compose.onNodeWithTag("pressure-vehicle").fetchSemanticsNode().boundsInRoot.center.x)
    }

    @Test
    fun absentWheelKeepsItsPositionAndShowsNoMeasurementInsteadOfZero() {
        compose.setContent {
            TirelogTheme { VehiclePressureDiagram(listOf(wheel(WheelPosition.FRONT_RIGHT, 30))) }
        }
        compose.onNodeWithTag("pressure-FRONT_LEFT").assertTextContains("— psi").assertTextContains("측정 대기")
        compose.onNodeWithTag("pressure-REAR_LEFT").assertTextContains("— psi")
        compose.onNodeWithTag("pressure-REAR_RIGHT").assertTextContains("— psi")
        compose.onNodeWithTag("pressure-FRONT_RIGHT").assertTextContains("30 psi")
    }

    @Test
    fun compactLargeTextKeepsDetailsAndLiveReadingsReachable() {
        val sample = wheel(WheelPosition.REAR_RIGHT, 28, WheelState.RECHECK).copy(liveLine = "지금 38psi · 주행 직후 값")
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                TirelogTheme {
                    Column(Modifier.width(375.dp).verticalScroll(rememberScrollState())) {
                        VehiclePressureDiagram(listOf(sample))
                    }
                }
            }
        }
        val front = compose.onNodeWithTag("pressure-FRONT_LEFT").fetchSemanticsNode().boundsInRoot
        val rear = compose.onNodeWithTag("pressure-REAR_LEFT").fetchSemanticsNode().boundsInRoot
        assertTrue("큰 글씨에서도 앞/뒤 공기압이 겹치면 안 됩니다", front.bottom < rear.top)
        compose.onNodeWithText(sample.line).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("지금 38psi · 주행 직후 값").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun vehicleSilhouetteRemainsVisibleInBothThemes() {
        val dark = mutableStateOf(false)
        var background = androidx.compose.ui.graphics.Color.White
        compose.setContent {
            TirelogTheme(darkTheme = dark.value) {
                background = TirelogColors.Surface
                Box(Modifier.width(960.dp)) { VehiclePressureDiagram(emptyList(), summary = true) }
            }
        }
        fun checkSilhouette() {
            compose.waitForIdle()
            val pixels = compose.onNodeWithTag("pressure-vehicle").captureToImage().toPixelMap()
            var contrastPixels = 0
            var total = 0
            for (x in pixels.width * 44 / 100 until pixels.width * 56 / 100 step 3) {
                for (y in pixels.height / 4 until pixels.height * 3 / 4 step 3) {
                    val color = pixels[x, y]
                    if (kotlin.math.abs(color.red - background.red) + kotlin.math.abs(color.green - background.green) +
                        kotlin.math.abs(color.blue - background.blue) > .12f) contrastPixels++
                    total++
                }
            }
            assertTrue("차량 도형이 배경에 묻히면 안 됩니다", contrastPixels > total / 4)
        }
        checkSilhouette()
        compose.runOnIdle { dark.value = true }
        checkSilhouette()
    }

    @Test
    fun pleosInstallationLoadsAllFourOriginalVectorQuadrants() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(runCatching { context.packageManager.getApplicationInfo(PleosVehicleArtwork.PACKAGE_NAME, 0) }.isSuccess)
        val artwork = PleosVehicleArtwork.load(context)
        assertNotNull("설치된 Pleos TPMS 차량 도형을 읽어야 합니다", artwork)
        assertEquals(WheelPosition.entries.toSet(), artwork!!.quadrants.keys)
    }

    @Test
    fun rotationAnchorsAndArrowTipsMatchRenderedTiresAtPreviewAndFullSize() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(runCatching { context.packageManager.getApplicationInfo(PleosVehicleArtwork.PACKAGE_NAME, 0) }.isSuccess)
        val artwork = requireNotNull(PleosVehicleArtwork.load(context, darkTheme = false))
        val side = mutableStateOf(88.dp)
        var density = 1f
        compose.setContent {
            density = LocalDensity.current.density
            TirelogTheme(darkTheme = false) {
                val palette = TirelogColors
                Canvas(Modifier.size(side.value).testTag("bare-vehicle")) {
                    drawRect(palette.Surface)
                    drawVehicleTopView(artwork, size.width / 2, 0f, size.height, palette)
                }
            }
        }
        for (size in listOf(88.dp, 328.dp)) {
            compose.runOnIdle { side.value = size }
            val pixels = compose.onNodeWithTag("bare-vehicle").captureToImage().toPixelMap()
            fun bounds(position: WheelPosition) = vehicleWheelBounds(position, pixels.width / 2f, 0f,
                pixels.height.toFloat(), usesPleosArtwork = true)
            fun tirePixel(x: Int, y: Int): Boolean {
                val color = pixels[x.coerceIn(0, pixels.width - 1), y.coerceIn(0, pixels.height - 1)]
                return maxOf(color.red, color.green, color.blue) < .25f &&
                    maxOf(color.red, color.green, color.blue) - minOf(color.red, color.green, color.blue) < .06f
            }
            fun assertNearTire(point: Offset, tire: Rect) {
                var closestSquared = Float.POSITIVE_INFINITY
                for (y in tire.top.toInt()..tire.bottom.roundToInt()) {
                    for (x in tire.left.toInt()..tire.right.roundToInt()) {
                        if (tirePixel(x, y)) {
                            val dx = point.x - x
                            val dy = point.y - y
                            closestSquared = minOf(closestSquared, dx * dx + dy * dy)
                        }
                    }
                }
                val tolerance = 5 * density
                assertTrue("화살표 끝이 실제 타이어 가장자리에서 떨어져 있으면 안 됩니다: $size $point",
                    closestSquared <= tolerance * tolerance)
            }
            WheelPosition.entries.forEach { position ->
                val center = bounds(position).center
                assertTrue("타이어 중심 표시가 렌더링된 타이어 안에 있어야 합니다: $size $position",
                    tirePixel(center.x.roundToInt(), center.y.roundToInt()))
            }
            RotationPattern.entries.forEach { pattern ->
                WheelPosition.entries.forEach { position ->
                    val from = bounds(position)
                    val to = bounds(pattern.destinationOf(position))
                    val (start, end) = rotationArrowEndpoints(from, to,
                        (pixels.height * .006f).coerceAtLeast(.75f * density))
                    assertNearTire(start, from)
                    assertNearTire(end, to)
                }
            }
        }
    }

    @Test
    fun crossRotationArrowsAreMirroredAndMeetAtTheWheelLayoutCenter() {
        for (usesPleosArtwork in listOf(false, true)) {
            for (height in listOf(88f, 328f, 640f)) {
                fun tire(position: WheelPosition) = vehicleWheelBounds(position, height / 2, 0f, height, usesPleosArtwork)
                val center = (tire(WheelPosition.FRONT_LEFT).center + tire(WheelPosition.REAR_RIGHT).center) / 2f
                for (pattern in listOf(RotationPattern.X_CROSS, RotationPattern.FORWARD_CROSS, RotationPattern.REARWARD_CROSS)) {
                    val arrows = WheelPosition.entries.mapNotNull { from ->
                        val to = pattern.destinationOf(from)
                        val changesSide = (from.ordinal % 2) != (to.ordinal % 2)
                        val reciprocal = pattern.destinationOf(to) == from
                        if (!changesSide || (reciprocal && from.ordinal > to.ordinal)) null
                        else rotationArrowEndpoints(tire(from), tire(to), height * .006f)
                    }
                    assertEquals(2, arrows.size)
                    arrows.forEach { (start, end) ->
                        val midpoint = (start + end) / 2f
                        assertEquals("$pattern 교차점이 좌우 중앙이어야 합니다", center.x, midpoint.x, .001f)
                        assertEquals("$pattern 교차점이 앞뒤 중앙이어야 합니다", center.y, midpoint.y, .001f)
                    }
                    val (first, second) = arrows
                    assertEquals(center.x * 2, first.first.x + second.first.x, .001f)
                    assertEquals(center.x * 2, first.second.x + second.second.x, .001f)
                    assertEquals(first.first.y, second.first.y, .001f)
                    assertEquals(first.second.y, second.second.y, .001f)
                }
            }
        }
    }

    private fun wheel(position: WheelPosition, psi: Int, state: WheelState = WheelState.NORMAL) = WheelCard(
        position = position, tireId = null, tireLabel = "A", psi = psi, recommendedPsi = 36,
        line = "${position.label} ${psi}psi · 권장 36 · 오늘 07:52 냉간", liveLine = null, state = state,
    )
}
