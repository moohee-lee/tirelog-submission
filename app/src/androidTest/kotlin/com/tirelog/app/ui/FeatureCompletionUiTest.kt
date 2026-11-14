package com.tirelog.app.ui

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.tirelog.app.TirelogApp
import com.tirelog.app.ui.main.MainActivity
import com.tirelog.app.vehicle.DemoVehicleGateway
import com.tirelog.core.engine.TireLogEngine
import com.tirelog.core.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class FeatureCompletionUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as TirelogApp
    private fun seed(onboard: Boolean = true): com.tirelog.app.Session {
        app.container.switchMode(true)
        val s = requireNotNull(app.container.session.value)
        runBlocking {
            s.engine.resetAll()
            if (onboard) s.engine.completeOnboarding(TireLogEngine.OnboardingInput(
                RecommendedPressure(280.0, 280.0, RecommendedSource.USER_INPUT), TireSetType.SUMMER, "235/55R19", null,
                rotationIntervalKm = 12_000), s.time.now() - 86_400_000, 10_000.0)
        }
        app.container.popups.dismiss()
        return s
    }
    private fun intent(dest: String) = Intent(app, MainActivity::class.java).putExtra(MainActivity.EXTRA_DEST, dest).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    private fun selectGrandeur() {
        compose.onNodeWithContentDescription("차종·연식, 차종 선택").performScrollTo().performClick()
        compose.onNodeWithText("현대 그랜저 · 2026 · 가솔린/LPI · 국내").performScrollTo().performClick()
        compose.onNodeWithContentDescription("차량 타이어 규격, 규격 선택").performScrollTo().performClick()
        compose.onNodeWithText("245/45R19").performScrollTo().performClick()
    }

    @Test fun registrationOmitsVehiclePresetsAndSourceDialogsAndSavesManualTireInfo() {
        val s = seed(onboard = false)
        val automatic = requireNotNull(com.tirelog.core.catalog.VehicleCatalog.lookup(runBlocking { s.gateway.readVin() }))
        ActivityScenario.launch<MainActivity>(intent("onboarding")).use {
            compose.onNodeWithText("차종 기준 불러오기(선택)").assertDoesNotExist()
            compose.onNodeWithText("차종 기준 불러오기").assertDoesNotExist()
            compose.onNodeWithText("기준 및 출처").assertDoesNotExist()
            compose.onNodeWithText("등록하고 시작").assertIsDisplayed().assertIsNotEnabled()
            compose.selectTireType("올시즌")
            compose.onNodeWithTag("tire-size-width").scrollToIfNeeded().performTextReplacement("235")
            compose.onNodeWithTag("tire-size-aspect").scrollToIfNeeded().performTextReplacement("55")
            compose.onNodeWithTag("tire-size-rim").scrollToIfNeeded().performTextReplacement("19")
            compose.onNodeWithTag("tire-size-rim").performImeAction()
            compose.onNodeWithText("등록하고 시작").assertIsDisplayed().assertIsEnabled()
            captureUi("registration-onboarding-refined")
            compose.onNodeWithText("등록하고 시작").performClick()
            compose.waitUntil(5_000) { runBlocking { s.engine.settings().onboardingDone } }
            val settings = runBlocking { s.engine.settings() }
            assertNull(settings.vehicleGuideId)
            val expectedFront = com.tirelog.core.Units.psiToKpa(com.tirelog.core.Units.psiRounded(automatic.frontKpa).toDouble())
            val expectedRear = com.tirelog.core.Units.psiToKpa(com.tirelog.core.Units.psiRounded(automatic.rearKpa).toDouble())
            assertEquals(expectedFront, requireNotNull(settings.recommended).frontKpa, 0.001)
            assertEquals(expectedRear, requireNotNull(settings.recommended).rearKpa, 0.001)
            assertEquals(RecommendedSource.VEHICLE_DEFAULT, requireNotNull(settings.recommended).source)
            assertEquals(automatic.rotationIntervalKm, settings.rotationIntervalKm)
            assertEquals("235/55 R19", runBlocking { s.engine.mountedSet()!!.size })
        }
    }

    @Test fun viewingACatalogDoesNotOverwriteManualSettingsAndApplyingUsesBothDefaults() {
        val s = seed()
        ActivityScenario.launch<MainActivity>(intent("settings")).use {
            compose.onNodeWithText("차종 기준 불러오기").performScrollTo().performClick()
            selectGrandeur()
            compose.waitForIdle()
            instrumentation.uiAutomation.executeShellCommand("screencap -p /data/local/tmp/tirelog-vehicle-guide.png").use { descriptor ->
                java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
            }
            assertEquals(280.0, runBlocking { s.engine.settings().recommended!!.frontKpa }, 0.001)
            assertEquals(12_000, runBlocking { s.engine.settings().rotationIntervalKm })
            compose.onNodeWithText("라벨과 확인한 기준 적용").performScrollTo().performClick()
            compose.waitUntil(5_000) { runBlocking { s.engine.settings().vehicleGuideId != null } }
            assertEquals(230.0, runBlocking { s.engine.settings().recommended!!.frontKpa }, 0.001)
            assertEquals(10_000, runBlocking { s.engine.settings().rotationIntervalKm })
        }
    }

    @Test fun individualSizeEditingPersistsWithoutChangingTheOtherTires() {
        val s = seed()
        ActivityScenario.launch<MainActivity>(intent("tire_FRONT_LEFT")).use {
            compose.waitUntil(5_000) { compose.onAllNodesWithText("이 타이어 정보 수정").fetchSemanticsNodes().size == 1 }
            compose.onNodeWithText("이 타이어 정보 수정").scrollToIfNeeded().performClick()
            compose.onNodeWithTag("tire-size-width").scrollToIfNeeded().performTextReplacement("245")
            compose.onNodeWithTag("tire-size-aspect").scrollToIfNeeded().performTextReplacement("45")
            compose.onNodeWithTag("tire-size-rim").scrollToIfNeeded().performTextReplacement("19")
            compose.onNodeWithTag("tire-size-rim").performImeAction()
            compose.onNodeWithText("저장").scrollToIfNeeded().performClick()
            compose.waitUntil(5_000) { runBlocking { s.engine.mountedTires()[WheelPosition.FRONT_LEFT]?.profile?.size == "245/45 R19" } }
            assertEquals("235/55R19", runBlocking { s.engine.mountedSet()!!.size })
            assertTrue(runBlocking { s.engine.mountedTires().filterKeys { it != WheelPosition.FRONT_LEFT }.values.all { it.profile == null } })
        }
    }

    @Test fun lowPressureReferenceIsDisplayedSeparatelyFromRecommendedPressure() {
        val s = seed()
        (s.gateway as DemoVehicleGateway).update { it.copy(lowPressureKpa = mapOf(WheelPosition.FRONT_LEFT to 190.0)) }
        ActivityScenario.launch<MainActivity>(intent("tire_FRONT_LEFT")).use {
            compose.onNodeWithText("차량 저압 기준 190kPa · 참고값이며 권장 공기압과 다릅니다.").performScrollTo().assertIsDisplayed()
            assertEquals(280.0, runBlocking { s.engine.settings().recommended!!.frontKpa }, 0.001)
        }
    }
}
