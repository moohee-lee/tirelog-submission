package com.tirelog.app.ui

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.tirelog.app.TirelogApp
import com.tirelog.app.ui.main.MainActivity
import com.tirelog.core.engine.TireLogEngine
import com.tirelog.core.model.TireSetType
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals

class WorkFlowTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun manufacturerDefaultsCanBeOverriddenAtRegistrationAndLater() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TirelogApp
        app.container.switchMode(true)
        val session = requireNotNull(app.container.session.value)
        runBlocking {
            session.engine.resetAll()
            session.engine.completeOnboarding(TireLogEngine.OnboardingInput(null, TireSetType.SUMMER, null, null), session.time.now(), 10_000.0)
        }
        val intent = Intent(app, MainActivity::class.java).putExtra(MainActivity.EXTRA_DEST, "sets")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            compose.onNodeWithText("타이어 등록").scrollToIfNeeded().performClick()
            compose.selectTireType("윈터")
            compose.onNodeWithContentDescription("제조사, 기타·모름").scrollToIfNeeded().performClick()
            compose.onNodeWithText("넥센 · Nexen").performClick()
            compose.onNodeWithContentDescription("기준 선택, 넥센 · 일반").scrollToIfNeeded().performClick()
            compose.onNodeWithText("넥센 · 전기차").scrollToIfNeeded().performClick()
            compose.onNodeWithTag("registration-warranty-km").assertTextContains("30000")
            compose.onNodeWithTag("registration-warranty-months").assertTextContains("48")
            compose.setRegistrationNumber("registration-warranty-km", "32000")
            compose.enterTireModel("내 타이어")
            scenario.recreate()
            compose.onNodeWithTag("registration-warranty-km").assertTextContains("32000")
            compose.onNodeWithText("저장").scrollToIfNeeded().performClick()
            compose.waitUntil(5_000) { runBlocking { session.engine.sets().size == 2 } }
            val set = runBlocking { session.engine.sets().single { it.type == TireSetType.WINTER } }
            assertEquals("nexen_ev", set.lifeGuideId)
            assertEquals(32000, set.inspectionKm)
            assertEquals(48, set.inspectionMonths)
            compose.onNodeWithTag("management-set-selector").performClick()
            compose.onNode(hasText("윈터 타이어 #${set.id}", substring = true) and hasText("보관", substring = true)).performClick()
            compose.onNodeWithContentDescription("타이어 관리 더보기").performClick()
            compose.onNodeWithText("세트 공통 정보 수정").performClick()
            compose.onNodeWithTag("registration-warranty-km").assertTextContains("32000")
            compose.setRegistrationNumber("registration-warranty-km", "35000")
            compose.onNodeWithText("저장").scrollToIfNeeded().performClick()
            compose.waitUntil(5_000) { runBlocking { session.engine.sets().single { it.id == set.id }.inspectionKm == 35000 } }
        }
    }

    @Test
    fun savingNewSetReturnsToTheUnchangedWorkDraft() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TirelogApp
        app.container.switchMode(true)
        val session = requireNotNull(app.container.session.value)
        runBlocking {
            session.engine.resetAll()
            session.engine.completeOnboarding(
                TireLogEngine.OnboardingInput(null, TireSetType.SUMMER, null, null),
                session.time.now(),
                10_000.0,
            )
        }
        val intent = Intent(app, MainActivity::class.java).putExtra(MainActivity.EXTRA_DEST, "checkin")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            compose.onNodeWithText("세트 교체").scrollToIfNeeded().performClick()
            compose.onNodeWithText("세트 추가").scrollToIfNeeded().performClick()
            compose.selectTireType("윈터")
            compose.enterTireModel("겨울 보관용")
            scenario.recreate()
            compose.onNodeWithText("윈터").scrollToIfNeeded().assertIsSelected()
            compose.onNodeWithTag("tire-model-selector").assertTextContains("겨울 보관용")
            compose.onNodeWithText("저장").scrollToIfNeeded().performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("작업 기록").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("새로 낀 세트").scrollToIfNeeded().assertIsDisplayed()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("윈터 세트").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("윈터 세트").scrollToIfNeeded().assertIsDisplayed()
        }
    }
}
