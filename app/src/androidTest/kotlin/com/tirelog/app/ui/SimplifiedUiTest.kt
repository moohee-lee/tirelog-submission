package com.tirelog.app.ui

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.tirelog.app.Session
import com.tirelog.app.TirelogApp
import com.tirelog.app.ui.card.CardActivity
import com.tirelog.app.ui.main.MainActivity
import com.tirelog.core.engine.TireLogEngine
import com.tirelog.core.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SimplifiedUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as TirelogApp

    private fun seed(): Session {
        app.container.switchMode(true)
        app.container.popups.dismiss()
        val session = requireNotNull(app.container.session.value)
        runBlocking {
            val now = session.time.now()
            session.engine.resetAll()
            session.engine.completeOnboarding(TireLogEngine.OnboardingInput(
                RecommendedPressure(250.0, 250.0, RecommendedSource.LABEL_CONFIRMED), TireSetType.ALL_SEASON,
                null, "0125", initialKm = 2000.0, tireModel = "시연 타이어",
            ), now - 3 * TireLogEngine.DAY_MILLIS, 15_000.0)
            for (i in 2 downTo 0) session.engine.recordColdReading(WheelPosition.entries.associateWith { 250.0 },
                18.0 + i, 15_100.0 + (2 - i) * 20, now - i * TireLogEngine.DAY_MILLIS)
        }
        return session
    }

    private fun intent(dest: String) = Intent(app, MainActivity::class.java)
        .putExtra(MainActivity.EXTRA_DEST, dest).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun screenshot(name: String) {
        compose.waitForIdle()
        instrumentation.uiAutomation.waitForIdle(300, 5000)
        instrumentation.uiAutomation.executeShellCommand("screencap -p /data/local/tmp/tirelog-$name.png").use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
        }
    }

    @Test fun rotationExplanationIsReadOnlyAndApplyMovesTheSameTires() {
        val session = seed()
        val before = runBlocking { session.engine.mountedTires() }
        ActivityScenario.launch<MainActivity>(intent("rotation")).use {
            compose.onNodeWithTag("rotation-apply").assertIsNotEnabled()
            compose.onNodeWithTag("rotation-choice-REARWARD_CROSS").scrollToIfNeeded().performClick()
            compose.onNodeWithTag("rotation-diagram").scrollToIfNeeded().assertIsDisplayed()
            screenshot("rotation-help")
            assertEquals(before, runBlocking { session.engine.mountedTires() })
            compose.onNodeWithTag("rotation-choice-SIDE_TO_SIDE").scrollToIfNeeded().performClick()
            assertEquals(before, runBlocking { session.engine.mountedTires() })
            compose.onNodeWithTag("rotation-apply").performClick()
            compose.waitUntil(5_000) {
                runBlocking { session.engine.mountedTires()[WheelPosition.FRONT_RIGHT]?.id == before[WheelPosition.FRONT_LEFT]?.id }
            }
            val after = runBlocking { session.engine.mountedTires() }
            assertEquals(before.values.map { it.id }.toSet(), after.values.map { it.id }.toSet())
        }
    }

    @Test fun managementAndRotationKeepPhysicalTireLabelsAfterAnAppliedSwap() {
        val session = seed()
        runBlocking {
            session.engine.resetAll()
            session.engine.completeOnboarding(TireLogEngine.OnboardingInput(
                RecommendedPressure(250.0, 250.0, RecommendedSource.LABEL_CONFIRMED), TireSetType.SUMMER,
                null, "1224", initialKm = 0.0,
            ), session.time.now(), session.gateway.live.value.odoKm?.minus(120.0))
        }
        val before = runBlocking { session.engine.mountedTires() }
        ActivityScenario.launch<MainActivity>(intent("sets")).use {
            compose.onNodeWithTag("management-selected-tire").assertTextEquals("A · 앞왼쪽")
            screenshot("management-mockup")
            compose.onNodeWithTag("management-tire-REAR_RIGHT").performClick().assertIsSelected()
            compose.onNodeWithTag("management-selected-tire").assertTextEquals("D · 뒤오른쪽")
            assertEquals(before, runBlocking { session.engine.mountedTires() })
        }
        ActivityScenario.launch<MainActivity>(intent("rotation")).use {
            compose.onNodeWithTag("rotation-choice-FRONT_REAR").scrollToIfNeeded().performClick()
            screenshot("rotation-mockup")
            compose.onNodeWithTag("rotation-apply").performClick()
            compose.waitUntil(5_000) {
                runBlocking { session.engine.mountedTires()[WheelPosition.REAR_LEFT]?.id == before[WheelPosition.FRONT_LEFT]?.id }
            }
        }
        ActivityScenario.launch<MainActivity>(intent("sets")).use {
            compose.onNodeWithTag("management-selected-tire").assertTextEquals("C · 앞왼쪽")
            compose.onNodeWithTag("management-tire-REAR_LEFT").performClick()
            compose.onNodeWithTag("management-selected-tire").assertTextEquals("A · 뒤왼쪽")
        }
    }

    @Test fun chartLegendTogglesSeriesAndCustomRangeRejectsReverseDates() {
        val session = seed()
        ActivityScenario.launch<MainActivity>(intent("trend")).use {
            compose.onNodeWithText("앞왼쪽 · ON").scrollToIfNeeded()
            screenshot("trend")
            compose.onNodeWithText("앞왼쪽 · ON").scrollToIfNeeded().performClick()
            compose.onNodeWithText("앞왼쪽 · OFF").assertIsNotSelected()
            compose.onNodeWithContentDescription("냉간 공기압 추이.", substring = true)
                .assert(!hasContentDescription("앞왼쪽", substring = true))
            compose.onNodeWithText("외기온도 · ON").scrollToIfNeeded().performClick()
            compose.onNodeWithText("외기온도 · OFF").assertIsNotSelected()
            compose.onNodeWithText("직접 지정").scrollToIfNeeded().performClick()
            val today = session.engine.today(session.time.now())
            compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
            compose.onNodeWithTag("trend-start-date").scrollToIfNeeded().performClick()
            selectCalendarDate(today)
            compose.onNodeWithTag("trend-end-date").scrollToIfNeeded().performClick()
            selectCalendarDate(today.minusDays(2))
            compose.onNodeWithText("기간 적용").scrollToIfNeeded().assertIsNotEnabled()
            compose.onNodeWithTag("trend-start-date").scrollToIfNeeded().performClick()
            selectCalendarDate(today.minusDays(5))
            compose.onNodeWithTag("trend-end-date").scrollToIfNeeded().performClick()
            selectCalendarDate(today)
            compose.onNodeWithText("기간 적용").scrollToIfNeeded().performClick()
            compose.onNodeWithText("직접 지정").scrollToIfNeeded().assertIsSelected()
            compose.onNodeWithText("${today.minusDays(5)} ~ $today").assertExists()
        }
    }

    @Test fun replacementRegistersAndMountsNewTiresWhileKeepingRetiredHistory() {
        val session = seed()
        val old = runBlocking { session.engine.mountedSet()!! }
        ActivityScenario.launch<MainActivity>(intent("replace")).use {
            compose.enterTireModel("신규 등록 타이어")
            compose.onNodeWithText("교체 완료").scrollToIfNeeded().assertIsNotEnabled()
            compose.selectTireType("올시즌")
            compose.onNodeWithText("폐기").scrollToIfNeeded().performClick()
            compose.onNodeWithText("폐기").assertIsSelected()
            compose.onNodeWithText("교체 완료").scrollToIfNeeded().performClick()
            compose.waitUntil(5_000) { runBlocking {
                session.engine.mountedSet()?.tireModel == "신규 등록 타이어" &&
                    session.engine.sets().single { it.id == old.id }.retired
            } }
            assertTrue(runBlocking { session.engine.sets().single { it.id == old.id }.retired })
            assertEquals(12, runBlocking { session.engine.trend(session.time.now(), 7, old.id).series.sumOf { series -> series.points.size } })
        }
    }

    @Test fun homeOffersFourActionsAndWheelDetailsWithoutChangingMounts() {
        val session = seed()
        val id = runBlocking { session.engine.mountedSet()!!.id }
        instrumentation.uiAutomation.executeShellCommand("pm grant --user ${app.applicationInfo.uid / 100000} com.tirelog.app android.permission.POST_NOTIFICATIONS").use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
        }
        ActivityScenario.launch<CardActivity>(Intent(app, CardActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)).use {
            screenshot("home")
            listOf("타이어 관리", "추이 보기", "타이어 교체", "위치교환").forEach { label -> compose.onNodeWithText(label).assertIsDisplayed() }
            screenshot("home")
            compose.onNodeWithTag("pressure-FRONT_LEFT").assertIsDisplayed().performClick()
            compose.onNodeWithText("타이어 상세").assertIsDisplayed()
            assertEquals(id, runBlocking { session.engine.mountedSet()!!.id })
            compose.onNodeWithText("이 타이어 정보 수정").scrollToIfNeeded().assertIsDisplayed()
        }
    }

    @Test fun onboardingAllowsUnknownMileageAndOptionalDotInOneForm() {
        val session = seed()
        runBlocking { session.engine.resetAll() }
        ActivityScenario.launch<MainActivity>(intent("onboarding")).use { scenario ->
            compose.enterTireModel("처음 등록")
            compose.selectTireType("윈터")
            scenario.recreate()
            compose.onNodeWithTag("tire-model-selector").assertTextContains("처음 등록")
            compose.onNodeWithText("등록하고 시작").scrollToIfNeeded().performClick()
            compose.waitUntil(5_000) { runBlocking { session.engine.settings().onboardingDone } }
            val mounted = runBlocking { session.engine.mountedSet()!! }
            assertFalse(mounted.initialKmKnown)
            assertNull(mounted.dot)
            assertEquals(TireSetType.WINTER, mounted.type)
        }
    }

    @Test fun drivingRestrictionClosesRotationBeforeAnyPositionsAreChanged() {
        val session = seed()
        val before = runBlocking { session.engine.mountedTires() }
        ActivityScenario.launch<MainActivity>(intent("rotation")).use {
            compose.onNodeWithTag("rotation-choice-FRONT_REAR").scrollToIfNeeded().performClick()
            (session.gateway as com.tirelog.app.vehicle.DemoVehicleGateway).update { live -> live.copy(restricted = true) }
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag("rotation-apply").fetchSemanticsNodes().isEmpty()
            }
            assertEquals(before, runBlocking { session.engine.mountedTires() })
            (session.gateway as com.tirelog.app.vehicle.DemoVehicleGateway).update { live -> live.copy(restricted = false) }
        }
    }

    @Test fun driveGearRestrictsControlsBeforeUxRestrictionSignalArrives() {
        val session = seed()
        val before = runBlocking { session.engine.mountedTires() }
        val gateway = session.gateway as com.tirelog.app.vehicle.DemoVehicleGateway
        ActivityScenario.launch<MainActivity>(intent("rotation")).use {
            compose.onNodeWithTag("rotation-choice-FRONT_REAR").scrollToIfNeeded().performClick()
            gateway.update { live -> live.copy(gear = com.tirelog.core.drive.GearState.DRIVE, restricted = false) }
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("rotation-apply").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithText("타이어 관리").assertDoesNotExist()
            compose.onNodeWithText("설정").assertDoesNotExist()
            assertEquals(before, runBlocking { session.engine.mountedTires() })
            gateway.update { live -> live.copy(gear = com.tirelog.core.drive.GearState.PARK) }
        }
    }
}
