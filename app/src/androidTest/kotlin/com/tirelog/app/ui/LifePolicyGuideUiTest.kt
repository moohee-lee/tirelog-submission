package com.tirelog.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.tirelog.app.ui.common.ScreenFrame
import com.tirelog.app.ui.main.LifePolicyFields
import com.tirelog.app.ui.main.LifePolicySummary
import com.tirelog.app.ui.theme.TirelogTheme
import com.tirelog.core.catalog.TireLifeGuides
import org.junit.After
import org.junit.Rule
import org.junit.Test

class LifePolicyGuideUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @After fun finishActivity() { compose.activityRule.scenario.onActivity { it.finish() } }

    @Test fun michelinSummaryShowsAnAppDistanceAndAManufacturerPeriodIndependently() {
        compose.setContent { TirelogTheme { ScreenFrame("점검 기준", {}) {
            LifePolicySummary(TireLifeGuides.find("michelin"), 40_000, 60)
        } } }
        compose.onNodeWithText("이 타이어로 40,000km 사용하면 점검 알림").assertIsDisplayed()
        compose.onNodeWithText("기준 출처 · 앱 기본값").assertIsDisplayed()
        compose.onNodeWithText("처음 사용한 날부터 60개월 (5년) 후 점검 알림").scrollToIfNeeded().assertIsDisplayed()
        compose.onNodeWithText("기준 출처 · 제조사 일반 안내").scrollToIfNeeded().assertIsDisplayed()
        compose.onNodeWithText("매년 전문가 점검", substring = true).scrollToIfNeeded().assertIsDisplayed()
    }

    @Test fun editingExplainsTireDistanceStorageTimeAndTheSourceOfAnOverriddenValue() {
        val km = mutableStateOf("30000")
        val months = mutableStateOf("60")
        compose.setContent { TirelogTheme(darkTheme = true) { ScreenFrame("점검 기준 수정", {}) {
            LifePolicyFields("michelin", {}, "", {}, km.value, { km.value = it }, months.value, { months.value = it })
        } } }
        compose.onNodeWithText("기준 출처 · 사용자 설정").scrollToIfNeeded().assertIsDisplayed()
        compose.onNodeWithText("차량 계기판의 총주행거리", substring = true).scrollToIfNeeded().assertIsDisplayed()
        compose.onNodeWithText("보관한 기간도 포함", substring = true).scrollToIfNeeded().assertIsDisplayed()
        compose.onNodeWithText("DOT 제조일 기준은 별도로", substring = true).assertExists()
        compose.onNodeWithText("기준 출처 · 제조사 일반 안내").scrollToIfNeeded().assertIsDisplayed()
    }
}
