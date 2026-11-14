package com.tirelog.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.tirelog.app.ui.common.LocalActionsEnabled
import com.tirelog.app.ui.main.TireManagementScreen
import com.tirelog.app.ui.theme.TirelogTheme
import com.tirelog.core.engine.SetRow
import com.tirelog.core.engine.SetsOverview
import com.tirelog.core.maintenance.TireLife
import com.tirelog.core.model.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TireManagementUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @After fun finish() { compose.activityRule.scenario.onActivity { it.finish() } }

    @Test fun knownMileageKeepsBothMetricsAndAllMetadataVisibleInDayAndNight() {
        val dark = mutableStateOf(false)
        compose.setContent {
            TirelogTheme(darkTheme = dark.value) {
                TireManagementScreen(SetsOverview(listOf(row()), emptyList(), 10000), {}, {}, { _, _ -> }, { _, _ -> }, {}, {})
            }
        }
        fun check() {
            compose.onAllNodesWithText("120").assertCountEquals(5)
            compose.onNodeWithText("39,880").assertIsDisplayed()
            compose.onNodeWithText("DOT").assertIsDisplayed()
            compose.onNodeWithText("정보 수정").assertIsDisplayed()
        }
        check()
        captureUi("management-reference")
        compose.runOnIdle { dark.value = true }
        check()
        captureUi("management-night")
    }

    @Test fun wheelSelectionUsesPhysicalTireIdentityAndEditsOnlyThatTire() {
        var edited: Pair<Long, Long?>? = null
        val row = row().let { source -> source.copy(tireLife = source.tireLife.map {
            it.copy(tire = it.tire.copy(position = RotationPattern.X_CROSS.destinationOf(it.tire.position)))
        }) }
        compose.setContent {
            TirelogTheme { TireManagementScreen(SetsOverview(listOf(row), emptyList(), 10000), {}, {},
                { set, tire -> edited = set to tire }, { _, _ -> }, {}, {}) }
        }
        compose.onNodeWithTag("management-selected-tire").assertTextEquals("D · 앞왼쪽")
        compose.onNodeWithTag("management-tire-REAR_RIGHT").assertIsNotSelected().performClick().assertIsSelected()
        compose.onNodeWithTag("management-tire-FRONT_LEFT").assertIsNotSelected()
        compose.onNodeWithTag("management-selected-tire").assertTextEquals("A · 뒤오른쪽")
        compose.onNodeWithText("정보 수정").scrollToIfNeeded().performClick()
        assertEquals(1L to 1L, edited)
        val positions = compose.onNodeWithTag("management-position-panel").fetchSemanticsNode().boundsInRoot
        val detail = compose.onNodeWithTag("management-detail-panel").fetchSemanticsNode().boundsInRoot
        assertTrue("넓은 차량 창에서는 위치와 상세를 나란히 보여야 합니다", positions.right < detail.left)
        listOf("타이어 등록", "폐기 기록", "정비 이력").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
    }

    @Test fun missingMileageAndUnknownTotalAreNotPresentedAsZeroOrRemainingLife() {
        val row = row().let { source -> source.copy(tireLife = source.tireLife.map {
            it.copy(recordedKm = null, remainingKm = null, totalKmKnown = false)
        }) }
        compose.setContent {
            TirelogTheme { TireManagementScreen(SetsOverview(listOf(row), emptyList(), 10000), {}, {}, { _, _ -> }, { _, _ -> }, {}, {}) }
        }
        compose.onNodeWithText("앱 기록 주행").assertIsDisplayed()
        compose.onNodeWithText("거리 확인 필요 · 주행거리 누락 구간이 있습니다.").assertIsDisplayed()
        compose.onNodeWithText("등록 전 거리를 몰라 실제 총사용 거리와 다를 수 있습니다.").assertIsDisplayed()
        compose.onNodeWithText("미입력").assertIsDisplayed()
        compose.onAllNodes(hasText("—") and hasAnyAncestor(hasTestTag("management-detail-panel"))).assertCountEquals(2)
    }

    @Test fun storedSetCanBeSelectedWhileMountedSetCannotBeRetiredOrDeleted() {
        var retired: Long? = null
        val stored = row().copy(set = row().set.copy(id = 2), mounted = false, status = "보관", canDelete = true)
        compose.setContent {
            TirelogTheme { TireManagementScreen(SetsOverview(listOf(row(), stored), emptyList(), 10000), {}, {},
                { _, _ -> }, { _, _ -> }, { retired = it }, {}) }
        }
        compose.onNodeWithContentDescription("타이어 관리 더보기").performClick()
        compose.onNodeWithText("폐기").assertIsNotEnabled()
        compose.onNodeWithText("삭제").assertIsNotEnabled()
        compose.onNodeWithText("상세 보기").performClick()
        compose.onNodeWithTag("management-set-selector").performClick()
        compose.onNodeWithText("썸머 타이어 #2 · 보관").performClick()
        compose.onNodeWithContentDescription("타이어 관리 더보기").performClick()
        compose.onNodeWithText("폐기").assertIsEnabled().performClick()
        compose.onNodeWithText("폐기로 표시").performClick()
        assertEquals(2L, retired)
    }

    @Test fun narrowNightLayoutKeepsUnknownValuesAndLockedActionsReachableWithLargeText() {
        val enabled = mutableStateOf(true)
        var edits = 0
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 1.3f), LocalActionsEnabled provides enabled.value) {
                TirelogTheme(darkTheme = true) {
                    Box(Modifier.width(600.dp)) {
                        TireManagementScreen(SetsOverview(listOf(row()), emptyList(), 10000), {}, {}, { _, _ -> edits++ }, { _, _ -> }, {}, {})
                    }
                }
            }
        }
        compose.onNodeWithTag("management-tire-REAR_RIGHT").scrollToIfNeeded().performClick()
        compose.onNodeWithTag("management-selected-tire").scrollToIfNeeded().assertTextEquals("D · 뒤오른쪽")
        compose.onNodeWithText("정보 수정").scrollToIfNeeded().assertIsDisplayed()
        compose.runOnIdle { enabled.value = false }
        compose.onNodeWithText("정보 수정").assertIsNotEnabled().performTouchInput { click() }
        compose.onNodeWithText("타이어 등록").assertIsNotEnabled().assertIsDisplayed()
        assertEquals(0, edits)
    }

    @Test fun emptyOverviewStillOffersRegistrationAndMaintenanceHistory() {
        var registrations = 0
        compose.setContent {
            TirelogTheme { TireManagementScreen(SetsOverview(emptyList(), emptyList(), 10000), {}, { registrations++ },
                { _, _ -> }, { _, _ -> }, {}, {}) }
        }
        compose.onNodeWithText("등록된 타이어가 없습니다").assertIsDisplayed()
        compose.onNodeWithText("타이어 등록").performClick()
        assertEquals(1, registrations)
        compose.onNodeWithText("정비 이력").performClick()
        compose.onNodeWithText("아직 정비 기록이 없습니다.").assertIsDisplayed()
    }

    private fun row() = SetRow(TireSet(1, TireSetType.SUMMER, null, "1224", 0), 120, "장착 중", true,
        "1224", false, null, tireLife = WheelPosition.entries.map {
            TireLife.Status(Tire(it.ordinal + 1L, 1, ('A' + it.ordinal).toString(), it, "1224"),
                120.0, 0, null, null, 39880.0, null, TireLife.Stage.TRACKING, TireLife.Stage.TRACKING, TireLife.Stage.TRACKING)
        })
}
