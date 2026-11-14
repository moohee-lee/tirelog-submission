package com.tirelog.app.catalog

import androidx.test.platform.app.InstrumentationRegistry
import com.tirelog.core.catalog.TireModels
import com.tirelog.core.model.TireSetType
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import java.io.ByteArrayInputStream
import java.security.MessageDigest

/** 2026-11-07 제조사 공개 응답에서 제품 영역/필드만 발췌한 실제 구조로 검증한다. */
class ExpandedOfficialCatalogTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().context
    private val target get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun fixture(name: String) = context.assets.open("catalog-expansion/$name.html").bufferedReader().use { it.readText() }
    private fun source(brand: String, format: OfficialFormat, contains: String = ""): OfficialTireSource {
        val seed = JSONObject(target.assets.open("catalog/tire-models.json").bufferedReader().use { it.readText() })
        val sources = seed.getJSONArray("officialSources")
        val row = (0 until sources.length()).map(sources::getJSONObject).first {
            it.getString("manufacturerId") == brand && it.getString("format") == format.name && it.getString("url").contains(contains)
        }
        return OfficialTireSource(brand, row.getString("url"), row.getString("market"), format,
            row.optJSONObject("rule")?.let(OfficialCatalogRule::fromJson))
    }
    private fun parse(name: String, source: OfficialTireSource) = OfficialTireParser.parse(fixture(name), source, 200L).associateBy { it.name }

    @Test fun realCardsSeparateManufacturerAllWeatherFromAllSeasonAndSummer() {
        val nokian = parse("nokian", source("nokian", OfficialFormat.SCOPED, "na.nokian"))
        assertEquals(TireSetType.ALL_WEATHER, nokian.getValue("Remedy WRG5").type)
        assertEquals(TireSetType.ALL_SEASON, nokian.getValue("One").type)
        val pirelli = parse("pirelli", source("pirelli", OfficialFormat.SCOPED, "en-ww"))
        assertEquals(TireSetType.ALL_WEATHER, pirelli.getValue("CINTURATO ALL SEASON SF 3").type)
        assertEquals(TireSetType.SUMMER, pirelli.getValue("P ZERO (PZ5)").type)
        assertEquals(TireSetType.WINTER, pirelli.getValue("CINTURATO WINTER 3").type)
        val kleber = parse("kleber", source("kleber", OfficialFormat.SCOPED, "quadraxer-3"))
        assertEquals(TireSetType.ALL_WEATHER, kleber.getValue("QUADRAXER 3").type)
    }

    @Test fun officialJsonAndEmbeddedProductStatesKeepUncertainClassificationsManual() {
        val continental = parse("continental", source("continental", OfficialFormat.CONTI_DATA))
        assertEquals(TireSetType.SUMMER, continental.getValue("4x4SportContact").type)
        assertEquals(TireSetType.WINTER, continental.getValue("4x4WinterContact").type)
        assertNull(continental.getValue("4x4Contact").type)
        val dunlop = parse("dunlop", source("dunlop", OfficialFormat.DUNLOP_DATA))
        assertEquals(TireSetType.SUMMER, dunlop.getValue("SPORT MAXX RACE 2").type)
        assertEquals(TireSetType.ALL_WEATHER, dunlop.getValue("ALL SEASON 2").type)
        val bridge = parse("bridgestone", source("bridgestone", OfficialFormat.BRIDGESTONE_DATA))
        assertEquals(TireSetType.ALL_SEASON, bridge.getValue("Ecopia EP422 Plus").type)
        val yokohama = parse("yokohama", source("yokohama", OfficialFormat.YOKOHAMA_DATA))
        assertEquals(TireSetType.SUMMER, yokohama.getValue("ADVAN NEOVA AD08R").type)
        assertNull(yokohama.getValue("AVID Touring-S").type)
    }

    @Test fun hankookHandlesBothResponseShapesAndDoesNotTurnAnOffRoadSnowBadgeIntoAllWeather() {
        val models = parse("hankook", source("hankook", OfficialFormat.HANKOOK_DATA))
        assertEquals(3, models.size)
        assertNull(models.getValue("Dynapro AT2 Xtreme").type)
        assertEquals(TireSetType.ALL_SEASON, models.getValue("Kinergy XP").type)
        assertEquals(TireSetType.ALL_WEATHER, models.getValue("Weatherflex GT").type)
    }

    @Test fun parentSeasonHeadingsAreScopedToTheirOwnProducts() {
        val models = parse("falken", source("falken", OfficialFormat.SCOPED))
        assertEquals(TireSetType.SUMMER, models.getValue("Ziex ZE320").type)
        assertEquals(TireSetType.WINTER, models.getValue("Eurowinter HS02").type)
        assertNull(models.getValue("EUROALL SEASON AS220 PRO").type)
        val laufenn = parse("laufenn", source("laufenn", OfficialFormat.SCOPED, "/us/"))
        assertEquals(TireSetType.WINTER, laufenn.getValue("I FIT Ice").type)
        assertNull(laufenn.getValue("S FIT AS").type)
    }

    @Test fun apolloReadsTheProductSeasonAndCertificationInsteadOfTheModelName() {
        val models = parse("apollo", source("apollo", OfficialFormat.APOLLO_DATA))
        assertEquals(TireSetType.SUMMER, models.getValue("ALNAC 4G").type)
        assertEquals(TireSetType.ALL_WEATHER, models.getValue("ALNAC 4G ALL SEASON").type)
        assertEquals(TireSetType.WINTER, models.getValue("ALNAC 4G WINTER").type)
        assertEquals(TireSetType.ALL_SEASON, models.getValue("ASPIRE 4G").type)
    }

    @Test fun productMetadataIgnoresSeasonWordsInTheComparisonDescriptionWhenTheTitleIsExplicit() {
        val models = parse("toyo", source("toyo", OfficialFormat.PRODUCT_META, "/celsius-2/"))
        assertEquals(TireSetType.ALL_WEATHER, models.getValue("Celsius II").type)
    }

    @Test fun pdfFactsAreBoundToTheReviewedBytesAndAChangedDocumentRevokesTheOldType() {
        val src = source("petlas", OfficialFormat.OFFICIAL_DOCUMENT)
        val restored = src.copy(rule = OfficialCatalogRule.fromJson(requireNotNull(src.rule).toJson()))
        assertEquals(src, restored)
        val before = OfficialTireParser.parse(JSONObject().put("sha256", src.rule!!.documentHash).toString(), restored, 200)
        assertEquals(TireSetType.ALL_WEATHER, before.single { it.name == "Multi Action PT 565" }.type)
        val after = OfficialTireParser.parse(JSONObject().put("sha256", "0".repeat(64)).toString(), restored, 300)
        assertTrue(after.all { it.type == null && it.observations.single().rawSeason.contains("재확인 필요") })
        assertTrue(TireModels.merge(before, after).all { it.type == null })
        val bytes = "%PDF-factual-test".toByteArray()
        val expected = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals(expected, hashOfficialDocument(ByteArrayInputStream(bytes)))
        assertThrows(IllegalArgumentException::class.java) { hashOfficialDocument(ByteArrayInputStream("<html>blocked".toByteArray())) }
    }

    @Test fun paginationCollectsEveryProductAndRejectsAnIncompleteOrChangedList() {
        val calls = mutableListOf<Int>()
        val result = collectYokohamaPages { n -> calls += n; JSONObject().put("total", 61).put("html", "page$n;").toString() }
        assertEquals(listOf(1, 2, 3), calls)
        assertEquals("page1;page2;page3;", JSONObject(result).getString("html"))
        assertThrows(IllegalArgumentException::class.java) {
            collectYokohamaPages { n -> JSONObject().put("total", if (n == 1) 41 else 42).put("html", "").toString() }
        }
    }

    @Test fun definitionsCannotCrossManufacturersOrAddPrivateQueriesAndAliasesDoNotGuessGenerations() {
        assertFalse(isOfficialTireUrl("michelin", "https://www.pirelli.com/tires/en-us/car/catalog"))
        assertFalse(isOfficialTireUrl("apollo", "https://www.apollotyres.com/content.allProducts.getInfo?vin=secret"))
        assertFalse(isOfficialTireUrl("nokian", "https://na.nokiantyres.com.evil.example/tires/"))
        val base = source("continental", OfficialFormat.CONTI_DATA)
        val rule = OfficialCatalogRule("", "", "", "", aliases = mapOf("4x4SportContact" to listOf("Conti4x4SportContact")))
        val models = parse("continental", base.copy(rule = rule))
        assertEquals(TireSetType.SUMMER, models.getValue("Conti4x4SportContact").type)
        assertFalse(models.containsKey("4x4SportContact 2"))
    }

    @Test fun allBundledOfficialDefinitionsAreValid() {
        val seed = JSONObject(target.assets.open("catalog/tire-models.json").bufferedReader().use { it.readText() })
        val definitions = seed.getJSONArray("officialSources")
        assertTrue(definitions.length() >= 250)
        for (i in 0 until definitions.length()) {
            val row=definitions.getJSONObject(i)
            OfficialTireSource(row.getString("manufacturerId"),row.getString("url"),row.getString("market"),
                OfficialFormat.valueOf(row.getString("format")),row.optJSONObject("rule")?.let(OfficialCatalogRule::fromJson))
        }
    }

    @Test fun liveExpandedSourcesWorkWithAndroidHttpAndDocumentValidationWhenRequested() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("catalogLive")=="true")
        for (src in listOf(source("apollo",OfficialFormat.APOLLO_DATA),source("nokian",OfficialFormat.SCOPED,"na.nokian"),
            source("yokohama",OfficialFormat.SCOPED,"admin-ajax"),source("nankang",OfficialFormat.OFFICIAL_DOCUMENT))) {
            val models=OfficialTireParser.parse(fetchOfficialTirePage(src),src,System.currentTimeMillis())
            assertTrue("${src.url}: 실제 응답에서 공식 분류를 확인해야 합니다",models.any {it.type!=null})
        }
    }
}
