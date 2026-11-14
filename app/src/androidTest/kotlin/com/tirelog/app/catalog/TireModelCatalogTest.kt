package com.tirelog.app.catalog

import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.tirelog.core.catalog.TireModels
import com.tirelog.core.model.TireSetType
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

class TireModelCatalogTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun cache() = File(context.cacheDir,"model-catalog-${UUID.randomUUID()}.json")
    private val product = "https://www.hankooktire.com/us/en/tire/ventus/test.html"
    private val discovery = "https://www.tyrereviews.com/Tyre/Hankook/"
    private val seed get() = """{"schemaVersion":2,"classificationPolicyVersion":1,
        "sources":[{"manufacturerId":"hankook","brand":"Hankook","slug":"Hankook"}],
        "officialSources":[{"manufacturerId":"hankook","market":"US","format":"HANKOOK","url":"$product"}],
        "checkedAt":{"$discovery":100,"$product":100},
        "models":[{"manufacturerId":"hankook","name":"Existing","sourceUrl":"https://www.tyrereviews.com/Tyre/Hankook/Existing.htm",
        "observations":[{"catalogUrl":"$product","productUrl":"$product","market":"US","type":"SUMMER","rawSeason":"Summer","snowCertified":false,"checkedAtMillis":100}]}]}"""
    private fun page(name: String) = """<h1>Hankook Tyres</h1><section id="all"><table><tr data-season="1"><td class="tf-table__tyre"><a href="/Tyre/Hankook/$name.htm"><b>Hankook</b> $name</a></td><td class="tf-table__season">Summer</td></tr></table></section>"""
    private fun officialPage(season: String = "All Season") = """<h1 class="prd-name">Existing</h1><ul class="feature__list"><li class="feature__item"><img src="/tires_icons/season/example.svg"><span class="feature__name">$season</span></li></ul>"""

    @Test fun weeklyRefreshCorrectsExistingClassificationAddsUnknownModelsAndRestoresCorrections() = runBlocking {
        val file=cache();var now=200L;var discoveryCalls=0;var officialCalls=0
        val repository=CachedTireModelCatalog({seed},file,clock={now},fetch={discoveryCalls++;page("New")},fetchOfficial={officialCalls++;officialPage()})
        try {
            assertTrue(repository.loadManufacturer("hankook"))
            assertEquals(0,discoveryCalls);assertEquals(0,officialCalls)
            assertEquals(TireSetType.SUMMER,repository.state.value.models.first().type)
            now+=CachedTireModelCatalog.REFRESH_INTERVAL_MS
            assertTrue(repository.refreshDue())
            assertEquals(1,discoveryCalls);assertEquals(1,officialCalls)
            assertEquals(TireSetType.ALL_SEASON,repository.state.value.models.first().type)
            assertNull(repository.state.value.models.single { it.name=="New" }.type)
            assertTrue(repository.refreshDue());assertEquals(1,officialCalls)
            val restored=CachedTireModelCatalog({seed},file,clock={now},fetch={error("캐시 유효")},fetchOfficial={error("공식 캐시 유효")})
            assertTrue(restored.loadManufacturer("hankook"))
            assertEquals(repository.state.value.models,restored.state.value.models)
        } finally {file.delete()}
    }

    @Test fun sourceFailuresKeepEvidenceAndOnlyFailedSourcesAreRetried() = runBlocking {
        val file=cache();var offline=true;var discoveryCalls=0;var officialCalls=0
        val repository=CachedTireModelCatalog({seed},file,clock={CachedTireModelCatalog.REFRESH_INTERVAL_MS+200},
            fetch={discoveryCalls++;page("New")},fetchOfficial={officialCalls++;if(offline)throw IOException("offline") else officialPage()})
        try {
            assertFalse(repository.loadManufacturer("hankook"))
            assertEquals(TireSetType.SUMMER,repository.state.value.models.first().type)
            assertEquals(2,repository.state.value.models.size)
            offline=false
            assertTrue(repository.refreshDue())
            assertEquals(1,discoveryCalls);assertEquals(2,officialCalls)
            assertEquals(TireSetType.ALL_SEASON,repository.state.value.models.first().type)
        } finally {file.delete()}
    }

    @Test fun newlyAmbiguousOfficialTagRevokesAutomaticSelectionButLayoutFailureRetainsLastEvidence() = runBlocking {
        val file=cache();var now=CachedTireModelCatalog.REFRESH_INTERVAL_MS+200;var html="<nav>All Season</nav>"
        val repository=CachedTireModelCatalog({seed},file,clock={now},fetch={page("New")},fetchOfficial={html})
        try {
            assertFalse(repository.loadManufacturer("hankook"))
            assertEquals(TireSetType.SUMMER,repository.state.value.models.first().type)
            html=officialPage("Performance")
            assertTrue(repository.refreshDue())
            assertNull(repository.state.value.models.first().type)
            html=officialPage("All Weather");now+=CachedTireModelCatalog.REFRESH_INTERVAL_MS
            assertTrue(repository.refreshDue())
            assertEquals(TireSetType.ALL_WEATHER,repository.state.value.models.first().type)
        } finally {file.delete()}
    }

    @Test fun versionOneCacheKeepsNamesAndTrackedBrandsButDiscardsEveryUnverifiedType() = runBlocking {
        val file=cache()
        var now=200L
        val old="""{"schemaVersion":1,"models":[{"manufacturerId":"hankook","name":"Old","type":"WINTER","sourceUrl":"https://www.tyrereviews.com/Tyre/Hankook/Old.htm"}],"checkedAt":{"hankook":100},"tracked":["hankook"]}"""
        file.writeText(old)
        val repository=CachedTireModelCatalog({seed},file,clock={now},fetch={page("New")},fetchOfficial={officialPage()})
        try {
            assertTrue(repository.loadManufacturer("unknown"))
            val oldModel=repository.state.value.models.single { it.name=="Old" }
            assertNull(oldModel.type);assertTrue(oldModel.observations.isEmpty())
            now+=CachedTireModelCatalog.REFRESH_INTERVAL_MS
            assertTrue(repository.refreshDue())
            assertTrue(repository.state.value.models.any { it.name=="New" })
        } finally {file.delete()}
    }

    @Test fun damagedCacheFallsBackAndBundledCatalogContainsOfficialCorrectionsAndNoImplicitTypes() = runBlocking {
        val file=cache();file.writeText("{broken cache")
        val repository=CachedTireModelCatalog({context.assets.open("catalog/tire-models.json").bufferedReader().use { it.readText() }},
            file,clock={0L},fetch={error("요청하면 안 됩니다.")},fetchOfficial={error("요청하면 안 됩니다.")})
        try {
            assertTrue(repository.loadManufacturer("unknown"))
            val models=repository.state.value.models
            assertTrue(models.size>=2044)
            assertTrue(models.count { it.type != null } >= 700)
            assertEquals(models.size,models.map {it.key}.toSet().size)
            assertTrue(TireModels.search(models,"michelin","CrossClimate+").any {it.name=="CrossClimate+"})
            fun kind(brand:String,name:String)=models.single {it.manufacturerId==brand && it.name==name}.type
            // 국내/미국은 All Season, 영국은 Summer로 표시되어 이제 직접 선택한다.
            assertNull(kind("hankook","Dynapro HPX"))
            val hpx=models.single {it.manufacturerId=="hankook" && it.name=="Dynapro HPX"}
            assertTrue(hpx.observations.any {it.market=="KR" && it.type==TireSetType.ALL_SEASON})
            assertTrue(hpx.observations.any {it.market=="UK" && it.type==TireSetType.SUMMER})
            assertEquals(TireSetType.ALL_WEATHER,kind("hankook","Kinergy 4S2"))
            assertEquals(TireSetType.ALL_SEASON,kind("nexen","N Priz AH8"))
            assertEquals(TireSetType.ALL_SEASON,kind("goodyear","Assurance MaxLife"))
            assertTrue(models.filter {it.type!=null}.all {it.observations.isNotEmpty()})
        } finally {file.delete()}
    }

    @Test fun changedPdfRevokesTheClassificationAndSurvivesCacheRestoration() = runBlocking {
        val url="https://www.nankang-tyre.com/files/download_catalog/test.pdf"
        val hash="a".repeat(64)
        val rule=OfficialCatalogRule("","","","",documentHash=hash,
            documentFacts=listOf(OfficialDocumentFact("SV3","Winter",6)))
        val definition=JSONObject().put("manufacturerId","nankang").put("url",url).put("market","GLOBAL")
            .put("format","OFFICIAL_DOCUMENT").put("rule",rule.toJson())
        val bundled=JSONObject().put("schemaVersion",2).put("classificationPolicyVersion",1)
            .put("sources",org.json.JSONArray()).put("officialSources",org.json.JSONArray().put(definition))
            .put("models",org.json.JSONArray()).put("checkedAt",JSONObject()).toString()
        val file=cache();var now=200L;var current=hash;var calls=0
        val repository=CachedTireModelCatalog({bundled},file,clock={now},fetch={error("명칭 수집 없음")},
            fetchOfficial={calls++;JSONObject().put("sha256",current).toString()})
        try {
            assertTrue(repository.loadManufacturer("nankang"))
            assertEquals(TireSetType.WINTER,repository.state.value.models.single().type)
            now+=CachedTireModelCatalog.REFRESH_INTERVAL_MS;current="b".repeat(64)
            assertTrue(repository.refreshDue());assertEquals(2,calls)
            assertNull(repository.state.value.models.single().type)
            val restored=CachedTireModelCatalog({bundled},file,clock={now},fetch={error("요청하면 안 됩니다")},
                fetchOfficial={error("주간 확인 결과를 캐시에서 복원해야 합니다")})
            assertTrue(restored.loadManufacturer("nankang"))
            assertNull(restored.state.value.models.single().type)
        } finally {file.delete()}
    }

    @Test fun weeklyWorkIsActuallyScheduledOnlyOnce() {
        TireModelRefreshWorker.schedule(context);TireModelRefreshWorker.schedule(context)
        val jobs=WorkManager.getInstance(context).getWorkInfosForUniqueWork(TireModelRefreshWorker.WORK_NAME).get(5,TimeUnit.SECONDS)
        assertEquals(1,jobs.size)
        assertEquals(TimeUnit.DAYS.toMillis(7),jobs.single().periodicityInfo!!.repeatIntervalMillis)
    }

    @Test fun liveOfficialSourceCanBeFetchedFromAndroidWhenRequested() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("catalogLive")=="true")
        val source=OfficialTireSource("nexen","https://www.nexentireusa.com/tires/n_priz_ah8/","US",OfficialFormat.NEXEN)
        val parsed=OfficialTireParser.parse(fetchOfficialTirePage(source),source,System.currentTimeMillis()).single()
        assertEquals(TireSetType.ALL_SEASON,parsed.type)
        assertEquals("N'Priz AH8",parsed.name)
    }
}
