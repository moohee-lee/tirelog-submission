package com.tirelog.app.ui

import android.view.View
import android.widget.DatePicker
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import com.tirelog.app.ui.main.TrendDateDialog
import com.tirelog.app.ui.theme.TirelogTheme
import org.hamcrest.Matcher
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

/** 시스템 날짜 선택기의 결과를 실제 확인 버튼으로 전달한다. */
internal fun selectCalendarDate(date: LocalDate) {
    onView(isAssignableFrom(DatePicker::class.java)).perform(object : ViewAction {
        override fun getConstraints(): Matcher<View> = isAssignableFrom(DatePicker::class.java)
        override fun getDescription() = "날짜 선택기에서 $date 선택"
        override fun perform(uiController: UiController, view: View) {
            (view as DatePicker).updateDate(date.year, date.monthValue - 1, date.dayOfMonth)
            uiController.loopMainThreadUntilIdle()
        }
    })
    onView(withId(android.R.id.button1)).perform(click())
}

class DateRangePickerTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val today = LocalDate.of(2026, 10, 31)

    @Test fun bothDateFieldsOpenTheCalendarAcrossTheWholeSurfaceWithoutTextEntry() {
        compose.setContent { TirelogTheme { TrendDateDialog(today.minusDays(6) to today, today, {}, { _, _ -> }) } }
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithText("시작일 달력").assertDoesNotExist()
        compose.onNodeWithText("종료일 달력").assertDoesNotExist()
        for (tag in listOf("trend-start-date", "trend-end-date")) {
            for (region in 0..2) {
                compose.onNodeWithTag(tag).performScrollTo().assertHasClickAction().performTouchInput {
                    click(when (region) {
                        0 -> Offset(6f, center.y) // 왼쪽 여백
                        1 -> center // 날짜 본문
                        else -> Offset(width - 28f, center.y) // 달력 아이콘 영역
                    })
                }
                onView(isAssignableFrom(DatePicker::class.java)).check(matches(isDisplayed()))
                onView(isAssignableFrom(EditText::class.java)).check(doesNotExist())
                onView(withId(android.R.id.button2)).perform(click())
            }
        }
        compose.onNodeWithTag("trend-start-date").assertTextContains(today.minusDays(6).toString())
        compose.onNodeWithTag("trend-end-date").assertTextContains(today.toString())
        compose.activityRule.scenario.onActivity { it.finish() }
    }

    @Test fun selectedDatesSurviveRestorationAndReverseRangeCannotBeApplied() {
        var result: Pair<LocalDate, LocalDate>? = null
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            TirelogTheme { TrendDateDialog(today.minusDays(6) to today, today, {}, { from, to -> result = from to to }) }
        }
        compose.onNodeWithTag("trend-start-date").performClick()
        selectCalendarDate(today)
        compose.onNodeWithTag("trend-end-date").performClick()
        selectCalendarDate(today.minusDays(2))
        compose.onNodeWithText("기간 적용").assertIsNotEnabled()
        assertNull(result)
        compose.onNodeWithTag("trend-start-date").performClick()
        selectCalendarDate(today.minusDays(5))
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("trend-start-date").assertTextContains(today.minusDays(5).toString())
        compose.onNodeWithTag("trend-end-date").assertTextContains(today.minusDays(2).toString())
        compose.onNodeWithText("기간 적용").assertIsEnabled().performClick()
        assertEquals(today.minusDays(5) to today.minusDays(2), result)
        compose.activityRule.scenario.onActivity { it.finish() }
    }
}
