package com.tirelog.app.catalog

import com.tirelog.core.model.TireSetType
import org.junit.Assert.*
import org.junit.Test

class TireModelSourceParserTest {
    private val source = TireModelSource("hankook", "Hankook", "Hankook")
    private fun row(name: String, season: String, slug: String = name.replace(" ", "-"), brand: String = "Hankook") =
        """<tr data-season="1"><td class="tf-table__tyre"><a href="/Tyre/Hankook/$slug.htm"><b>$brand</b> $name</a></td><td class="tf-table__season">$season</td></tr>"""
    private fun page(rows: String) = "<h1>Hankook Tyres</h1><section id='all'><table>$rows</table></section>"
    private fun fixture(name: String) = requireNotNull(javaClass.getResource("/catalog/$name.html")).readText()
    private fun official(brand: String, url: String, format: OfficialFormat, market: String = "US") = OfficialTireSource(brand,url,market,format)

    @Test fun thirdPartySeasonsAndModelNamesNeverBecomeAutomaticClassification() {
        val models = TireModelSourceParser.parse(page(row("Winter AS Edition", "Summer") +
            row("Snow Test", "Winter") + row("Weather Test", "All Weather") + row("Unclassified", "Unknown")), source)
        assertEquals(4, models.size)
        assertTrue(models.all { it.type == null && it.observations.isEmpty() })
        assertEquals("Winter AS Edition", models.first().name)
    }

    @Test fun discoveryKeepsPlusVariantsAndRejectsOtherBrandsAndChangedStructure() {
        val models = TireModelSourceParser.parse(page(row("Test", "Summer") + row("Test+", "Winter") +
            row("Wrong", "Summer", brand = "Michelin")), source)
        assertEquals(listOf("Test", "Test+"), models.map { it.name })
        assertThrows(IllegalArgumentException::class.java) { TireModelSourceParser.parse("<h1>Hankook Tyres</h1>",source) }
    }

    @Test fun officialProductTagsCorrectThePreviouslyMisclassifiedModels() {
        val cases = listOf(
            Triple(official("hankook","https://www.hankooktire.com/kr/ko/tire/dynapro/hpx-ra43.html",OfficialFormat.HANKOOK,"KR"),"hankook-hpx","Dynapro HPX"),
            Triple(official("kumho","https://kumhotireusa.com/tires/crugen-hp71",OfficialFormat.KUMHO),"kumho-hp71","Crugen HP71"),
            Triple(official("nexen","https://www.nexentireusa.com/tires/n_priz_ah8/",OfficialFormat.NEXEN),"nexen-ah8","N'Priz AH8"),
        )
        for ((source,name,model) in cases) {
            val parsed = OfficialTireParser.parse("<nav>Winter Summer All Weather</nav>"+fixture(name),source,10).single()
            assertEquals(model,parsed.name)
            assertEquals(TireSetType.ALL_SEASON,parsed.type)
            assertEquals(source.url,parsed.observations.single().catalogUrl)
            assertEquals(10L,parsed.observations.single().checkedAtMillis)
        }
    }

    @Test fun micrositeAndCompleteSeasonBadgesDistinguishAllWeatherFromAllSeasonAndWinter() {
        val kinergy = OfficialTireParser.parse(fixture("hankook-kinergy"),official("hankook",
            "https://www.hankooktire.com/kr/ko/tire/kinergy/4s2-h750.html",OfficialFormat.HANKOOK,"KR"),10).single()
        assertEquals("Kinergy 4S2",kinergy.name)
        assertEquals(TireSetType.ALL_WEATHER,kinergy.type)
        val michelin = OfficialTireParser.parse(fixture("michelin"),official("michelin",
            "https://www.michelinman.com/auto/browse-tires/all-tyres",OfficialFormat.MICHELIN),10).associateBy { it.name }
        assertEquals(TireSetType.ALL_WEATHER,michelin.getValue("CrossClimate2").type)
        assertEquals(TireSetType.WINTER,michelin.getValue("X-Ice Snow").type)
        assertEquals(TireSetType.SUMMER,michelin.getValue("Pilot Sport 5").type)
        val pirelli = OfficialTireParser.parse(fixture("pirelli-sf3"),official("pirelli",
            "https://www.pirelli.com/tyres/en-ww/car/catalogue/product/cinturato-all-season-sf-3",OfficialFormat.PIRELLI,"EU"),10).single()
        assertEquals(TireSetType.ALL_WEATHER,pirelli.type)
    }

    @Test fun goodyearOfficialCardsSeparateMaxLifeAndWeatherReady() {
        val models = OfficialTireParser.parse(fixture("goodyear"),official("goodyear",
            "https://www.goodyear.com/en-us/tires/by-brand/goodyear/assurance/shop-all/shop-all",OfficialFormat.GOODYEAR),10).associateBy { it.name }
        assertEquals(TireSetType.ALL_SEASON,models.getValue("Assurance MaxLife").type)
        assertEquals(TireSetType.ALL_WEATHER,models.values.single { it.name.replace(" ", "") == "AssuranceWeatherReady2" }.type)
    }

    @Test fun unrecognizedProductTagsRequireSelectionAndChangedStructureCannotPolluteTheCatalog() {
        val source = official("nexen","https://www.nexentireusa.com/tires/n_priz_ah8/",OfficialFormat.NEXEN)
        val unknown = fixture("nexen-ah8").replace("GRAND TOURING ALL SEASON","PERFORMANCE")
        assertNull(OfficialTireParser.parse(unknown,source,20).single().type)
        assertThrows(IllegalArgumentException::class.java) { OfficialTireParser.parse("<nav>All Season</nav>",source,20) }
        assertThrows(IllegalArgumentException::class.java) {
            official("nexen","https://www.nexentireusa.com.evil.example/tires/test/",OfficialFormat.NEXEN)
        }
    }
}
