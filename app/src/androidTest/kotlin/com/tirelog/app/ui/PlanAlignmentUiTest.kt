package com.tirelog.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.tirelog.app.ui.card.CardUiState
import com.tirelog.app.ui.card.FullCard
import com.tirelog.app.ui.common.BigButton
import com.tirelog.app.ui.common.ScreenFrame
import com.tirelog.app.ui.main.TireFormFields
import com.tirelog.app.ui.main.rememberTireForm
import com.tirelog.app.ui.theme.TirelogTheme
import com.tirelog.core.engine.DashboardSnapshot
import com.tirelog.core.engine.NewSetInput
import com.tirelog.core.engine.RefillQuestionView
import com.tirelog.core.model.TireSetType
import com.tirelog.core.model.WheelPosition
import org.junit.Assert.*
import org.junit.Rule
import org.junit.After
import org.junit.Test
import java.time.LocalDate

class PlanAlignmentUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @After fun finishActivity() { compose.activityRule.scenario.onActivity { it.finish() } }
    private val today = LocalDate.of(2026, 10, 31)

    @Test fun unsupportedHomeHidesPressureToolsButKeepsMaintenanceActions() {
        var destination: String? = null
        val snapshot = DashboardSnapshot.empty(0).copy(onboardingDone = true, pressureSupported = false)
        compose.setContent { TirelogTheme {
            FullCard(CardUiState.Ready(snapshot, false, true, null), { destination = it }, false, {}, {})
        } }
        compose.onNodeWithText("추이 보기").assertDoesNotExist()
        compose.onNodeWithText("권장 공기압 설정").assertDoesNotExist()
        compose.onNodeWithText("타이어 교체").assertIsDisplayed().performClick()
        assertEquals("replace", destination)
        compose.onNodeWithText("위치교환").assertIsDisplayed().performClick()
        assertEquals("rotation", destination)
    }

    @Test fun homeExplainsCauseAndCoverageAndLetsUserAnswerRefillDirectly() {
        var answer: Boolean? = null
        val snapshot = DashboardSnapshot.empty(0).copy(onboardingDone = true,
            headline = "공기압 부족", causeText = "오늘 판단 · 원인 판단 보류(측정이 부족합니다)",
            measuredDaysLast7 = 2, needsRecommended = true,
            refillQuestion = RefillQuestionView("공기를 보충하셨나요?", WheelPosition.entries))
        compose.setContent { TirelogTheme {
            FullCard(CardUiState.Ready(snapshot, false, true, null), {}, false, {}, { answer = it })
        } }
        compose.onNodeWithText("오늘 판단 · 원인 판단 보류(측정이 부족합니다)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("최근 7일 중 측정 2일").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("권장 공기압 설정").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("네, 보충했어요").performScrollTo().performClick()
        assertEquals(true, answer)
        listOf("타이어 관리", "추이 보기", "타이어 교체", "위치교환").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
    }

    @Test fun registrationUsesCalendarAndTouchChoicesAndSavesSelectedValues() {
        var saved: NewSetInput? = null
        compose.setContent { TirelogTheme {
            val form = rememberTireForm("test", NewSetInput(TireSetType.WINTER, null, "2423", initialKm = 15_000.0))
            ScreenFrame("정보 수정", {}) {
                TireFormFields(form, today, editing = true, allowSetPressure = true)
                BigButton("저장", { saved = form.value() })
            }
        } }
        compose.onNodeWithTag("tire-dot-input").assertTextContains("2423")
        compose.onNodeWithText("제조 연도").assertDoesNotExist()
        compose.onNodeWithText("제조 주").assertDoesNotExist()
        compose.setRegistrationNumber("registration-mileage", "16000")
        compose.onNodeWithTag("first-use-date").performScrollTo().assertHasClickAction().performClick()
        selectCalendarDate(today.minusYears(1))
        compose.onNodeWithTag("first-use-date").assertTextContains(today.minusYears(1).toString())
        compose.setRegistrationNumber("registration-warranty-km", "41000")
        compose.setRegistrationNumber("registration-warranty-months", "59")
        compose.onNodeWithText("추가 설정 · 권장 공기압").performScrollTo().performClick()
        compose.onNodeWithText("세트별 입력").performScrollTo().performClick()
        compose.onNodeWithContentDescription("세트 뒤 권장 공기압 늘리기").performScrollTo().performClick()
        compose.onNodeWithText("저장").performScrollTo().performClick()
        val result = requireNotNull(saved)
        assertEquals(16_000.0, result.initialKm, 0.001)
        assertEquals(today.minusYears(1).toEpochDay(), result.firstUsedEpochDay)
        assertEquals(41_000, result.inspectionKm)
        assertEquals(59, result.inspectionMonths)
        assertTrue(result.recommendedRearKpa!! > result.recommendedFrontKpa!!)
    }
}
