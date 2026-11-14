package com.tirelog.app.ui

import com.tirelog.app.ui.main.TireFormState
import com.tirelog.core.engine.NewSetInput
import com.tirelog.core.catalog.TireModel
import com.tirelog.core.model.TireSetType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class TireFormStateTest {
    private fun verifiedModel(brand: String, name: String, type: TireSetType, url: String) =
        TireModel(brand, name, url, listOf(com.tirelog.core.catalog.TireSeasonObservation(
            url, url, "US", type, type.name, null, 1)))

    @Test fun unverifiedModelClearsTheAutomaticChoiceAndCannotBeSavedUntilTheUserChooses() {
        val form = TireFormState(NewSetInput(TireSetType.SUMMER, null, null, manufacturer = "한국타이어"))
        form.selectModel(verifiedModel("hankook", "Known", TireSetType.ALL_WEATHER, "https://example.test/known"))
        form.selectModel(TireModel("hankook", "Unverified", "https://example.test/unverified"))
        assertEquals("", form.type)
        assertFalse(form.modelTypeAutomatic)
        assertFalse(form.valid(java.time.LocalDate.of(2026,11,7)))
        form.selectType(TireSetType.WINTER.name)
        assertTrue(form.valid(java.time.LocalDate.of(2026,11,7)))
        assertEquals(TireSetType.WINTER, form.value().type)
    }

    @Test fun newRegistrationAndCustomNameCannotInheritAnImplicitAllSeasonOrAnotherModelsAutomaticType() {
        val form = TireFormState(NewSetInput(TireSetType.ALL_SEASON, null, null), typeInitiallyConfirmed = false)
        assertEquals("", form.type)
        assertFalse(form.valid(java.time.LocalDate.of(2026,11,7)))
        form.selectManufacturer("한국타이어")
        form.selectModel(verifiedModel("hankook", "Known", TireSetType.SUMMER, "https://example.test/known"))
        form.enterCustomModel("새 타이어")
        assertEquals("", form.type)
        form.selectType(TireSetType.ALL_WEATHER.name)
        assertTrue(form.valid(java.time.LocalDate.of(2026,11,7)))
        assertEquals(TireSetType.ALL_WEATHER, form.value().type)
    }

    @Test fun selectingACatalogModelSetsItsSeasonAndAllowsAnExplicitOverride() {
        val form = TireFormState(NewSetInput(TireSetType.WINTER, null, null, manufacturer = "한국타이어"))
        form.selectModel(verifiedModel("hankook", "iON evo", TireSetType.SUMMER, "https://example.test/ion"))
        assertEquals("iON evo", form.value().tireModel)
        assertEquals(TireSetType.SUMMER, form.value().type)
        assertTrue(form.modelTypeAutomatic)
        form.selectType(TireSetType.ALL_SEASON.name)
        assertEquals(TireSetType.ALL_SEASON, form.value().type)
        assertFalse(form.modelTypeAutomatic)
    }

    @Test fun customModelKeepsTheUsersSeasonWithoutGuessingFromItsName() {
        val form = TireFormState(NewSetInput(TireSetType.SUMMER, null, null, manufacturer = "한국타이어"))
        form.enterCustomModel("  Winter AS Special  ")
        assertEquals("Winter AS Special", form.value().tireModel)
        assertEquals(TireSetType.SUMMER, form.value().type)
        assertFalse(form.modelTypeAutomatic)
    }

    @Test fun changingManufacturerClearsThePreviousModelButKeepsAnExplicitSeason() {
        val form = TireFormState(NewSetInput(TireSetType.WINTER, null, null, manufacturer = "한국타이어", tireModel = "이전 모델"))
        form.selectManufacturer("Hankook Tire")
        assertEquals("이전 모델", form.model)
        form.selectManufacturer("금호")
        assertEquals("", form.model)
        assertEquals(TireSetType.WINTER, form.value().type)
        form.enterCustomModel("직접 등록")
        form.selectManufacturer("새 제조사")
        assertEquals("", form.model)
    }

    @Test fun cannotApplyAModelFromAnotherManufacturer() {
        val form = TireFormState(NewSetInput(TireSetType.ALL_SEASON, null, null, manufacturer = "금호타이어"))
        assertThrows(IllegalArgumentException::class.java) {
            form.selectModel(verifiedModel("hankook", "iON evo", TireSetType.SUMMER, "https://example.test/ion"))
        }
        assertEquals("", form.model)
    }

    @Test fun selectingAKnownAliasSavesItsNameAndAppliesTheVerifiedGuide() {
        val form = TireFormState(NewSetInput(TireSetType.ALL_SEASON, null, null))
        form.selectManufacturer("  NEXEN Tire ")
        assertEquals("넥센", form.value().manufacturer)
        assertEquals("nexen", form.value().lifeGuideId)
        assertEquals(40000, form.value().inspectionKm)
        assertEquals(48, form.value().inspectionMonths)
        form.selectLifeGuide("nexen_ev")
        assertEquals(30000, form.value().inspectionKm)
        assertEquals("넥센", form.value().manufacturer)
    }

    @Test fun reselectingTheSameManufacturerKeepsTheVariantAndCustomPolicy() {
        val form = TireFormState(NewSetInput(TireSetType.ALL_SEASON, null, null,
            manufacturer = "넥센", lifeGuideId = "nexen_ev", inspectionKm = 32000, inspectionMonths = 51))
        form.selectManufacturer("Nexen")
        assertEquals("nexen_ev", form.value().lifeGuideId)
        assertEquals(32000, form.value().inspectionKm)
        assertEquals(51, form.value().inspectionMonths)
    }

    @Test fun unknownManufacturerUsesTheEnteredNameAndDoesNotKeepAnotherManufacturersGuide() {
        val form = TireFormState(NewSetInput(TireSetType.ALL_SEASON, null, null,
            manufacturer = "넥센", lifeGuideId = "nexen_ev", inspectionKm = 30000, inspectionMonths = 48))
        form.selectManufacturer("  한빛 타이어  ")
        assertEquals("한빛 타이어", form.value().manufacturer)
        assertEquals("app", form.value().lifeGuideId)
        assertEquals(40000, form.value().inspectionKm)
        assertEquals(60, form.value().inspectionMonths)
        form.selectManufacturer("")
        assertNull(form.value().manufacturer)
    }

    @Test fun existingGuideWithoutAManufacturerNameCanStillBeDisplayedAndEdited() {
        val form = TireFormState(NewSetInput(TireSetType.ALL_SEASON, null, null,
            lifeGuideId = "nexen_ev", inspectionKm = 32000, inspectionMonths = 51))
        assertEquals("넥센", form.manufacturer)
        form.model = "모델만 변경"
        assertEquals("nexen_ev", form.value().lifeGuideId)
        assertEquals(32000, form.value().inspectionKm)
        assertEquals(51, form.value().inspectionMonths)
    }

    @Test fun editingOtherInformationPreservesSetPressureOverridesAndCustomInspectionPolicy() {
        val input = NewSetInput(TireSetType.WINTER, null, null,
            recommendedFrontKpa = 250.0, recommendedRearKpa = 260.0, inspectionKm = 45_000, inspectionMonths = 55)
        val form = TireFormState(input)
        form.model = "변경한 모델"
        assertEquals(250.0, form.value().recommendedFrontKpa!!, 0.000001)
        assertEquals(260.0, form.value().recommendedRearKpa!!, 0.000001)
        assertEquals(45_000, form.value().inspectionKm)
        assertEquals(55, form.value().inspectionMonths)
    }

    @Test fun mileageCanBeEnteredPreciselyAndClearedWithoutLosingTheFirstUseDate() {
        val form = TireFormState(NewSetInput(TireSetType.ALL_SEASON, null, null,
            initialKm = 12_345.67, firstUsedEpochDay = java.time.LocalDate.of(2025, 6, 1).toEpochDay()))
        form.selectMileage("23456.78")
        assertEquals(23456.78, form.value().initialKm, 0.000001)
        assertTrue(form.value().initialKmKnown)
        form.selectMileage("")
        assertFalse(form.value().initialKmKnown)
        assertEquals(0.0, form.value().initialKm, 0.0)
        assertEquals(java.time.LocalDate.of(2025, 6, 1).toEpochDay(), form.value().firstUsedEpochDay)
    }

    @Test fun selectingAnInstallationDateForANewTireActuallySavesTheDate() {
        val form = TireFormState(NewSetInput(TireSetType.ALL_SEASON, null, null, newUntilMounted = true))
        form.selectMileage("0")
        assertTrue(form.value().newUntilMounted)
        form.selectFirstUse("2026-11-01")
        assertFalse(form.value().newUntilMounted)
        assertEquals(java.time.LocalDate.of(2026, 11, 1).toEpochDay(), form.value().firstUsedEpochDay)
        assertTrue(form.valid(java.time.LocalDate.of(2026, 11, 7)))
    }

    @Test fun positiveMileageConvertsANewTireToUsedAndAnUnknownMileageStaysUnknownAfterDateEntry() {
        val form = TireFormState(NewSetInput(TireSetType.ALL_SEASON, null, null, newUntilMounted = true))
        form.selectMileage("1000")
        assertFalse(form.value().newUntilMounted)
        assertEquals(1000.0, form.value().initialKm, 0.0)
        form.selectMileage("")
        form.selectFirstUse("2026-11-01")
        assertFalse(form.value().initialKmKnown)
        assertEquals(java.time.LocalDate.of(2026, 11, 1).toEpochDay(), form.value().firstUsedEpochDay)
    }

    @Test fun directMileageEntryRejectsInvalidOrOutOfRangeValuesButKeepsUnknownAndDecimals() {
        val today = java.time.LocalDate.of(2026, 11, 7)
        val form = TireFormState(NewSetInput(TireSetType.ALL_SEASON, null, null))
        for (value in listOf("-1", "NaN", "Infinity", "abc", "1000000.1")) {
            form.selectMileage(value)
            assertFalse(value, form.valid(today))
        }
        for (value in listOf("0", "12345.67", "1000000", "")) {
            form.selectMileage(value)
            assertTrue(value, form.valid(today))
        }
    }

    @Test fun inlineWarrantyEntriesCannotSaveBlankFractionalOrOutOfRangeValues() {
        val today = java.time.LocalDate.of(2026, 11, 7)
        val form = TireFormState(NewSetInput(TireSetType.ALL_SEASON, null, null))
        for (value in listOf("", "999", "200001", "40000.5", "NaN")) {
            form.limitKm = value
            assertFalse(value, form.valid(today))
        }
        form.limitKm = "45000"
        for (value in listOf("", "0", "121", "48.5", "NaN")) {
            form.months = value
            assertFalse(value, form.valid(today))
        }
        form.months = "54"
        assertTrue(form.valid(today))
        assertEquals(45000, form.value().inspectionKm)
        assertEquals(54, form.value().inspectionMonths)
    }
}
