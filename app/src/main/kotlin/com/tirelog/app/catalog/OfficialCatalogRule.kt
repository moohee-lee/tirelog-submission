package com.tirelog.app.catalog

import org.json.JSONObject
import org.json.JSONArray

/** 사람이 원문과 해당 페이지를 확인한 문서 근거. 문서 내용이 바뀌면 다시 확인한다. */
internal data class OfficialDocumentFact(val name: String, val season: String, val page: Int,
    val snow: Boolean? = null, val onRoad: Boolean = true) {
    fun toJson() = JSONObject().put("name", name).put("season", season).put("page", page)
        .put("snow", snow ?: JSONObject.NULL).put("onRoad", onRoad)
}

/** 제조사별 실제 제품 영역에 한정한 추출 규칙. 원문 계절값과 검증된 이름 별칭만 사용한다. */
internal data class OfficialCatalogRule(
    val cards: String,
    val name: String,
    val link: String,
    val season: String,
    val indexLinks: String = "",
    val responseHtmlKey: String = "",
    val seasonAttribute: String = "",
    val seasonPrefix: String = "",
    val purpose: String = "",
    val snow: String = "",
    val completeSnow: Boolean = false,
    val separatesAllWeather: Boolean = false,
    val removeNamePrefix: String = "",
    val seasonMap: Map<String, String> = emptyMap(),
    val aliases: Map<String, List<String>> = emptyMap(),
    val childFormat: String = "PRODUCT_META",
    val categoryHeading: String = "",
    val categoryExpected: String = "",
    val categorySeason: String = "",
    val documentHash: String = "",
    val documentFacts: List<OfficialDocumentFact> = emptyList(),
    val seasonAncestor: String = "",
    val removeNameSelector: String = "",
) {
    fun toJson(): JSONObject = JSONObject().put("cards", cards).put("name", name).put("link", link)
        .put("season", season).put("indexLinks", indexLinks).put("responseHtmlKey", responseHtmlKey)
        .put("seasonAttribute", seasonAttribute).put("seasonPrefix", seasonPrefix)
        .put("purpose", purpose).put("snow", snow).put("completeSnow", completeSnow)
        .put("separatesAllWeather", separatesAllWeather).put("removeNamePrefix", removeNamePrefix)
        .put("seasonMap", JSONObject(seasonMap)).put("aliases", JSONObject(aliases))
        .put("childFormat", childFormat).put("categoryHeading", categoryHeading)
        .put("categoryExpected", categoryExpected).put("categorySeason", categorySeason)
        .put("documentHash", documentHash).put("documentFacts", JSONArray(documentFacts.map { it.toJson() }))
        .put("seasonAncestor", seasonAncestor).put("removeNameSelector", removeNameSelector)

    companion object {
        fun fromJson(json: JSONObject): OfficialCatalogRule {
            fun text(key: String) = json.optString(key).also { require(it.length <= 500) }
            val map = json.optJSONObject("seasonMap")
            val aliases = json.optJSONObject("aliases")
            return OfficialCatalogRule(cards = text("cards"), name = text("name"), link = text("link"), season = text("season"),
                indexLinks = text("indexLinks"), responseHtmlKey = text("responseHtmlKey"),
                seasonAttribute = text("seasonAttribute"), seasonPrefix = text("seasonPrefix"), purpose = text("purpose"), snow = text("snow"),
                completeSnow = json.optBoolean("completeSnow"), separatesAllWeather = json.optBoolean("separatesAllWeather"),
                removeNamePrefix = text("removeNamePrefix"),
                seasonMap = map?.keys()?.asSequence()?.associateWith { map.getString(it) }.orEmpty(),
                aliases = aliases?.keys()?.asSequence()?.associateWith { key -> aliases.getJSONArray(key).let { a ->
                    (0 until a.length()).map { a.getString(it) }
                } }.orEmpty(), childFormat = json.optString("childFormat", "PRODUCT_META"),
                categoryHeading = text("categoryHeading"), categoryExpected = text("categoryExpected"), categorySeason = text("categorySeason"),
                documentHash = text("documentHash"), documentFacts = json.optJSONArray("documentFacts")?.let { facts ->
                    require(facts.length() <= 500)
                    (0 until facts.length()).map { i -> facts.getJSONObject(i).let { fact ->
                        OfficialDocumentFact(fact.getString("name"), fact.getString("season"), fact.getInt("page"),
                            if (fact.isNull("snow")) null else fact.getBoolean("snow"), fact.optBoolean("onRoad", true)).also {
                            require(it.name.length in 1..80 && it.season.length in 1..100 && it.page in 1..1000)
                        }
                    } }
                }.orEmpty(), seasonAncestor = text("seasonAncestor"), removeNameSelector = text("removeNameSelector"))
        }
    }
}
