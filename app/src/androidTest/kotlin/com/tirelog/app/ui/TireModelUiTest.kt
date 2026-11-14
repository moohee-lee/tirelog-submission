package com.tirelog.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.tirelog.app.catalog.TireModelCatalog
import com.tirelog.app.catalog.TireModelCatalogState
import com.tirelog.app.ui.common.*
import com.tirelog.app.ui.main.*
import com.tirelog.app.ui.theme.TirelogTheme
import com.tirelog.core.catalog.TireModel
import com.tirelog.core.engine.NewSetInput
import com.tirelog.core.model.TireSetType
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

class TireModelUiTest {
    private fun verifiedModel(brand: String, name: String, type: TireSetType, url: String) =
        TireModel(brand, name, url, listOf(com.tirelog.core.catalog.TireSeasonObservation(
            url, url, "US", type, type.name, null, 1)))

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @After fun finishActivity() { compose.activityRule.scenario.onActivity { it.finish() } }
    private val today = LocalDate.of(2026, 11, 7)
    private val sample = listOf(
        verifiedModel("hankook", "iON evo", TireSetType.SUMMER, "https://example.test/ion"),
        verifiedModel("hankook", "Winter i cept RS3", TireSetType.WINTER, "https://example.test/winter"),
        verifiedModel("hankook", "Kinergy 4S2", TireSetType.ALL_WEATHER, "https://example.test/kinergy"),
        verifiedModel("hankook", "Kinergy ST AS", TireSetType.ALL_SEASON, "https://example.test/st"),
        verifiedModel("kumho", "ECSTA PS71", TireSetType.SUMMER, "https://example.test/ecsta"),
    )
    private class Catalog(models: List<TireModel>) : TireModelCatalog {
        override val state = MutableStateFlow(TireModelCatalogState(models, loaded = true))
        override fun watch(manufacturerId: String) = Unit
    }

    @Composable private fun Form(catalog: Catalog, save: (NewSetInput) -> Unit = {}) {
        CompositionLocalProvider(LocalTireModelCatalog provides catalog) {
            val form = rememberTireForm("models", NewSetInput(TireSetType.ALL_SEASON, null, null, manufacturer = "한국타이어"))
            ScreenFrame("내 타이어 등록", {}) {
                TireFormFields(form, today)
                BigButton("등록하고 시작", { save(form.value()) }, enabled = form.valid(today))
            }
        }
    }

    @Test fun searchingAndSelectingModelsAutomaticallySetsAllFourSeasons() {
        var saved: NewSetInput? = null
        val catalog = Catalog(sample)
        compose.setContent { TirelogTheme { Form(catalog) { saved = it } } }
        for ((query, label, type) in listOf(
            Triple("kinergy st", "Kinergy ST AS · 올시즌", "올시즌"),
            Triple("아이온", "iON evo · 썸머", "썸머"),
            Triple("윈터아이셉트", "Winter i cept RS3 · 윈터", "윈터"),
            Triple("kinergy", "Kinergy 4S2 · 올웨더", "올웨더"),
        )) {
            compose.onNodeWithTag("tire-model-selector").scrollToIfNeeded().performClick()
            compose.onNodeWithTag("tire-model-search").assertIsNotFocused().performTextReplacement(query)
            compose.onNodeWithText("ECSTA PS71 · 썸머").assertDoesNotExist()
            compose.onNodeWithText(label).performClick()
            compose.onNodeWithText(type).assertIsSelected()
        }
        compose.onNodeWithText("등록하고 시작").scrollToIfNeeded().performClick()
        assertEquals("Kinergy 4S2", requireNotNull(saved).tireModel)
        assertEquals(TireSetType.ALL_WEATHER, requireNotNull(saved).type)
    }

    @Test fun unknownModelUsesTheEnteredNameAndTheUsersSeason() {
        var saved: NewSetInput? = null
        val catalog = Catalog(sample)
        compose.setContent { TirelogTheme(darkTheme = true) { Form(catalog) { saved = it } } }
        compose.selectTireType("윈터")
        compose.enterTireModel("  새로 나온 타이어  ")
        compose.onNodeWithText("윈터").assertIsSelected()
        compose.onNodeWithText("등록하고 시작").scrollToIfNeeded().performClick()
        assertEquals("새로 나온 타이어", requireNotNull(saved).tireModel)
        assertEquals(TireSetType.WINTER, requireNotNull(saved).type)
    }

    @Test fun uncertainCatalogModelClearsThePreviousAutomaticTypeAndRequiresAChoiceAfterRestoration() {
        var saved: NewSetInput? = null
        val unknown = TireModel("hankook", "분류 미확인 모델", "https://example.test/unknown")
        val catalog = Catalog(sample + unknown)
        val restoration = StateRestorationTester(compose)
        restoration.setContent { TirelogTheme { Form(catalog) { saved = it } } }
        compose.onNodeWithTag("tire-model-selector").performClick()
        compose.onNodeWithText("iON evo · 썸머").performClick()
        compose.onNodeWithTag("tire-model-selector").performClick()
        compose.onNodeWithText("분류 미확인 모델 · 종류 직접 선택").scrollToIfNeeded().performClick()
        compose.onNodeWithText("등록하고 시작").scrollToIfNeeded().assertIsNotEnabled()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("tire-type-selector").scrollToIfNeeded().assertTextContains("종류 선택").performClick()
        for (label in listOf("올웨더", "올시즌", "썸머", "윈터")) compose.onNodeWithText(label).assertIsNotSelected()
        compose.onNodeWithText("윈터").performClick()
        compose.onNodeWithText("등록하고 시작").scrollToIfNeeded().performClick()
        assertEquals(TireSetType.WINTER, requireNotNull(saved).type)
    }

    @Test fun classificationCorrectionUpdatesTheListButDoesNotOverwriteTheDraft() {
        val catalog = Catalog(sample)
        compose.setContent { TirelogTheme { Form(catalog) } }
        compose.onNodeWithTag("tire-model-selector").performClick()
        compose.onNodeWithText("iON evo · 썸머").performClick()
        compose.runOnIdle { catalog.state.value = TireModelCatalogState(sample.map {
            if (it.name == "iON evo") verifiedModel("hankook", "iON evo", TireSetType.ALL_SEASON,"https://example.test/corrected") else it
        }, loaded = true) }
        compose.onNodeWithText("썸머").assertIsSelected()
        compose.onNodeWithTag("tire-model-selector").performClick()
        compose.onNodeWithText("iON evo · 올시즌").performClick()
        compose.onNodeWithText("올시즌").assertIsSelected()
    }

    @Test fun switchingManufacturersClearsTheOldModelAndShowsOnlyTheNewManufacturersModels() {
        val catalog = Catalog(sample)
        compose.setContent { TirelogTheme { Form(catalog) } }
        compose.onNodeWithTag("tire-model-selector").performClick()
        compose.onNodeWithText("iON evo · 썸머").performClick()
        compose.onNodeWithTag("tire-manufacturer-selector").performClick()
        compose.onNodeWithText("금호타이어 · Kumho").performClick()
        compose.onNodeWithContentDescription("모델명, 모름").assertIsDisplayed().performClick()
        compose.onNodeWithText("ECSTA PS71 · 썸머").assertIsDisplayed()
        compose.onNodeWithText("iON evo · 썸머").assertDoesNotExist()
    }

    @Test fun automaticSeasonSurvivesRestorationAndCanBeManuallyOverridden() {
        var saved: NewSetInput? = null
        val catalog = Catalog(sample)
        val restoration = StateRestorationTester(compose)
        restoration.setContent { TirelogTheme { Form(catalog) { saved = it } } }
        compose.onNodeWithTag("tire-model-selector").performClick()
        compose.onNodeWithText("iON evo · 썸머").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("tire-model-selector").assertTextContains("iON evo")
        compose.onNodeWithText("썸머").assertIsSelected()
        compose.selectTireType("윈터")
        compose.onNodeWithText("등록하고 시작").scrollToIfNeeded().performClick()
        assertEquals(TireSetType.WINTER, requireNotNull(saved).type)
    }

    @Test fun newlyCollectedModelsAppearWithoutOverwritingTheDraft() {
        val catalog = Catalog(sample)
        var saved: NewSetInput? = null
        compose.setContent { TirelogTheme { Form(catalog) { saved = it } } }
        compose.onNodeWithTag("tire-model-selector").performClick()
        compose.onNodeWithTag("tire-model-search").performTextReplacement("새 모델")
        compose.onNodeWithText("입력한 이름으로 등록").assertIsDisplayed()
        compose.runOnIdle {
            catalog.state.value = TireModelCatalogState(sample + verifiedModel("hankook", "새 모델", TireSetType.WINTER, "https://example.test/new"), loaded = true)
        }
        compose.onNodeWithText("입력한 이름으로 등록").assertDoesNotExist()
        compose.onNodeWithText("새 모델 · 윈터").performClick()
        compose.onNodeWithText("윈터").assertIsSelected()
        compose.onNodeWithText("등록하고 시작").scrollToIfNeeded().performClick()
        assertEquals("새 모델", requireNotNull(saved).tireModel)
    }

    @Test fun drivingRestrictionsDisableOpenModelSearchAndDirectRegistration() {
        val enabled = mutableStateOf(true)
        val catalog = Catalog(sample)
        compose.setContent { TirelogTheme {
            CompositionLocalProvider(LocalActionsEnabled provides enabled.value) { Form(catalog) }
        } }
        compose.onNodeWithTag("tire-model-selector").performClick()
        compose.onNodeWithTag("tire-model-search").performTextReplacement("목록에 없는 모델")
        compose.runOnIdle { enabled.value = false }
        compose.onNodeWithTag("tire-model-search").assertIsNotEnabled()
        compose.onNodeWithText("입력한 이름으로 등록").assertIsNotEnabled()
    }
}
