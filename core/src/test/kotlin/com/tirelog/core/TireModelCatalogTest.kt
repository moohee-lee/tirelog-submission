package com.tirelog.core

import com.tirelog.core.catalog.*
import com.tirelog.core.model.TireSetType
import kotlin.test.*
import org.junit.Test

class TireModelCatalogTest {
    private fun model(brand: String, name: String, type: TireSetType? = TireSetType.SUMMER,
        stamp: Long = 1, market: String = "US") = TireModel(brand, name, "https://example.test/$brand/$name",
        listOf(TireSeasonObservation("https://example.test/$brand/$market", "https://example.test/$brand/$name",
            market, type, type?.name.orEmpty(), null, stamp)))

    @Test fun searchIsScopedToTheManufacturerAndAcceptsKoreanEnglishAndCodes() {
        val models = listOf(model("hankook", "iON evo AS"), model("hankook", "Ventus S1 evo3 K127"),
            model("kumho", "iON evo AS"), model("nexen", "N Fera Supreme"))
        assertEquals(listOf(models[0]), TireModels.search(models, "hankook", "아이온 에보"))
        assertEquals(listOf(models[1]), TireModels.search(models, "hankook", "벤투스 S1"))
        assertEquals(listOf(models[1]), TireModels.search(models, "hankook", "k127"))
        assertEquals(listOf(models[3]), TireModels.search(models, "nexen", "엔페라"))
        assertTrue(TireModels.search(models, null, "").isEmpty())
        assertTrue(TireModels.search(models, "hankook", "등록되지 않은 모델").isEmpty())
    }

    @Test fun plusModelsRemainSeparateAndTrademarksDoNotCreateAnotherModel() {
        val original = model("michelin", "CrossClimate", TireSetType.ALL_WEATHER)
        val plus = model("michelin", "CrossClimate+", TireSetType.ALL_WEATHER)
        assertNotEquals(original.key, plus.key)
        assertEquals(2, TireModels.merge(listOf(original), listOf(plus)).size)
        assertEquals(listOf(plus), TireModels.search(listOf(original, plus), "michelin", "CrossClimate+"))
        assertEquals(model("hankook", "Kinergy 4S²").key, model("hankook", "Kinergy 4S2™").key)
    }

    @Test fun newerOfficialEvidenceCorrectsTheCatalogAndOlderSnapshotsCannotRevertIt() {
        val wrong = model("hankook", "Test", TireSetType.SUMMER)
        val corrected = model("hankook", "Test", TireSetType.ALL_SEASON, 2)
        assertEquals(TireSetType.ALL_SEASON, TireModels.merge(listOf(wrong), listOf(corrected)).single().type)
        assertEquals(TireSetType.ALL_SEASON, TireModels.merge(listOf(corrected), listOf(wrong)).single().type)
        val discoveryOnly = TireModel("hankook", "Test", "https://example.test/new")
        assertEquals(corrected, TireModels.merge(listOf(corrected), listOf(discoveryOnly)).single())
    }

    @Test fun conflictingMarketsAndNewlyUnclearOfficialTagsRequireManualSelection() {
        val us = model("hankook", "Test", TireSetType.ALL_SEASON)
        val kr = model("hankook", "Test", TireSetType.SUMMER, market = "KR")
        assertNull(TireModels.merge(listOf(us), listOf(kr)).single().type)
        val unclear = model("hankook", "Test", null, 2)
        assertNull(TireModels.merge(listOf(us), listOf(unclear)).single().type)
        val tie = us.copy(observations = us.observations.map { it.copy(type = TireSetType.WINTER) })
        assertNull(TireModels.merge(listOf(us), listOf(tie)).single().type)
        assertNull(TireModel("hankook", "Unverified", "https://example.test/unverified").type)
    }

    @Test fun seasonPolicySeparatesAllFourAndDoesNotGuessFromMissingSnowInformationOrTerrain() {
        fun kind(raw: String, snow: Boolean? = null, separate: Boolean = false, road: Boolean = true) =
            TireSeasonClassifier.classify(raw, snow, separate, road)
        assertEquals(TireSetType.ALL_WEATHER, kind("All Weather"))
        assertEquals(TireSetType.ALL_WEATHER, kind("All Weather", road = false)) // 제조사가 명시한 계절 태그는 인정한다.
        assertEquals(TireSetType.SUMMER, kind("Summer", road = false))
        assertEquals(TireSetType.ALL_WEATHER, kind("All seasons", true))
        assertEquals(TireSetType.ALL_WEATHER, kind("All-Season", true))
        assertEquals(TireSetType.ALL_SEASON, kind("All Season", false))
        assertEquals(TireSetType.ALL_SEASON, kind("GRAND TOURING ALL SEASON", separate = true))
        assertEquals(TireSetType.SUMMER, kind("ULTRA HIGH PERFORMANCE SUMMER"))
        assertEquals(TireSetType.WINTER, kind("Studdable Winter", true))
        assertNull(kind("All Season"))
        assertNull(kind("All Season", true, road = false))
        assertNull(kind("Winter All Season")) // 제조사 계절 태그로도 모호한 문자열은 확정하지 않는다.
        assertNull(kind("Summer Winter"))
        assertNull(kind("Performance"))
    }
}
