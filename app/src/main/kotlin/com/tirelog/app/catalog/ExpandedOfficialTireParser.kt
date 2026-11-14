package com.tirelog.app.catalog

import com.tirelog.core.catalog.TireModel
import com.tirelog.core.catalog.TireModels
import com.tirelog.core.catalog.TireSeasonClassifier
import com.tirelog.core.catalog.TireSeasonObservation
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI

internal object ExpandedOfficialTireParser {
    fun parse(body: String, source: OfficialTireSource, stamp: Long): List<TireModel> {
        val rule = source.rule
        val html = rule?.responseHtmlKey?.takeIf { it.isNotBlank() }?.let { JSONObject(body).getString(it) } ?: body
        val doc = Jsoup.parse(html, source.url)
        val models = mutableListOf<TireModel>()
        fun record(name: String, url: String, season: String, snow: Boolean?, separates: Boolean, purpose: String = "") {
            val clean = name.replace(Regex("[™®©]"), "").replace(Regex("\\s+"), " ").trim().let { value ->
                val prefix = rule?.removeNamePrefix.orEmpty()
                if (prefix.isNotBlank() && value.startsWith(prefix, ignoreCase = true)) value.substring(prefix.length).trim() else value
            }
            require(clean.isNotBlank() && clean.length <= 80 && !clean.contains("{{"))
            val productUrl = canonicalUrl(source.url, url)
            require(isOfficialTireUrl(source.manufacturerId, productUrl))
            val observation = TireSeasonObservation(source.url, productUrl, source.market,
                TireSeasonClassifier.classify(season, snow, separates, !offRoad(purpose)), season, snow, stamp)
            val model = TireModel(source.manufacturerId, clean, productUrl, listOf(observation))
            models += model
            rule?.aliases?.entries?.firstOrNull { (name, _) -> TireModel(source.manufacturerId, name, productUrl).key == model.key }
                ?.value?.forEach { alias -> models += model.copy(name = alias) }
        }
        when (source.format) {
            OfficialFormat.APOLLO_DATA -> {
                val data = JSONObject(body)
                for (key in data.keys()) {
                    val row = data.optJSONObject(key) ?: continue
                    val product = row.optJSONObject("productInfo") ?: continue
                    val snow = row.getJSONArray("progressbarFirstVariant").objects()
                        .firstOrNull { it.optString("label") == "3PMSF" }?.optString("value")
                        .let { when (it) { "Yes" -> true; "No" -> false; else -> null } }
                    record(product.getString("title"), product.getString("productUrl"), product.getString("season"), snow, false,
                        product.optString("description"))
                }
            }
            OfficialFormat.OFFICIAL_DOCUMENT -> {
                requireNotNull(rule)
                val unchanged = JSONObject(body).getString("sha256") == rule.documentHash
                return rule.documentFacts.map { fact ->
                    val raw = if (unchanged) fact.season else "공식 문서 변경 · 재확인 필요"
                    TireModel(source.manufacturerId, fact.name, source.url, listOf(TireSeasonObservation(
                        source.url, source.url, source.market,
                        if (unchanged) TireSeasonClassifier.classify(fact.season, fact.snow, false, fact.onRoad) else null,
                        "$raw (PDF ${fact.page}쪽)", if (unchanged) fact.snow else null, stamp)))
                }
            }
            OfficialFormat.SCOPED -> {
                requireNotNull(rule)
                val cards = doc.select(rule.cards)
                require(cards.isNotEmpty()) { "공식 제품 영역 형식이 변경되었습니다." }
                if (rule.categorySeason.isNotBlank()) {
                    require(rule.categoryHeading.isNotBlank() && rule.categoryExpected.isNotBlank())
                    require(doc.selectFirst(rule.categoryHeading)?.text()?.equals(rule.categoryExpected, ignoreCase = true) == true) {
                        "공식 계절별 목록 제목이 변경되었습니다."
                    }
                }
                for (card in cards) {
                    val nameNode = requireNotNull(card.selectFirst(rule.name)) { "공식 제품명이 없습니다." }.clone()
                    if (rule.removeNameSelector.isNotBlank()) nameNode.select(rule.removeNameSelector).remove()
                    val name = nameNode.text()
                    if (name.isBlank()) continue // 일부 공식 목록의 빈 자리/미출시 카드
                    val link = if (rule.link.isBlank()) source.url else card.selectFirst(rule.link)?.attr("href") ?: continue
                    val seasonScope = if (rule.seasonAncestor.isBlank()) card else requireNotNull(card.parents().firstOrNull { it.`is`(rule.seasonAncestor) })
                    val rawValues = if (rule.season.isBlank()) emptyList() else seasonScope.select(rule.season).map {
                        if (rule.seasonAttribute.isBlank()) it.text() else it.attr(rule.seasonAttribute)
                    }.filter { rule.seasonPrefix.isBlank() || it.startsWith(rule.seasonPrefix, ignoreCase = true) }
                        .map { if (rule.seasonPrefix.isBlank()) it else it.substring(rule.seasonPrefix.length).trim() }
                    val raw = rule.categorySeason.ifBlank { if (rule.seasonMap.isEmpty()) rawValues.joinToString(" ") else rule.seasonMap.entries
                        .filter { (token, _) -> rawValues.any { it.contains(token, ignoreCase = true) } }.map { it.value }.distinct().joinToString(" ")
                    }
                    val snowText = if (rule.snow.isBlank()) "" else card.select(rule.snow).joinToString(" ", transform = ::evidenceText)
                    val snow = if (Regex("3[ -]?PMSF|snowflake|eu-snow-grip", RegexOption.IGNORE_CASE).containsMatchIn(snowText)) true
                        else if (rule.completeSnow) false else null
                    val purpose = if (rule.purpose.isBlank()) "" else card.select(rule.purpose).joinToString(" ", transform = ::evidenceText)
                    record(name, link, raw, snow, rule.separatesAllWeather, purpose)
                }
            }
            OfficialFormat.CONTI_DATA -> {
                val fragments = JSONObject(body).getJSONArray("contentFragments")
                for (row in fragments.objects()) {
                    val fields = row.getJSONObject("elements")
                    val segment = fields.optString("pogProductGroupSegment")
                    if (segment.isBlank()) continue
                    val description = listOf("introText_approved", "headline_approved", "sub_headline_approved").joinToString(" ") { fields.optString(it) }
                    record(row.getString("title"), source.url, segment, null, false, description)
                }
            }
            OfficialFormat.DUNLOP_DATA -> {
                val state = JSONObject(requireNotNull(doc.selectFirst("script#ng-state")).data())
                val payload = state.keys().asSequence().mapNotNull { state.optJSONObject(it)?.optString("b") }
                    .mapNotNull { runCatching { JSONObject(it) }.getOrNull() }.firstOrNull { it.has("tyres") }
                    ?: error("공식 제품 데이터 형식이 변경되었습니다.")
                for (row in payload.getJSONArray("tyres").objects()) {
                    val sizes = row.getJSONObject("size").getJSONArray("items").objects()
                    val certifications = sizes.map { if (it.isNull("snowflake")) null else it.getBoolean("snowflake") }.distinct()
                    val snow = certifications.singleOrNull()
                    record(row.getString("tyreModelname"), "/en/tyres/${row.getString("slug")}", row.getString("season"), snow, false)
                }
            }
            OfficialFormat.YOKOHAMA_DATA -> {
                val state = JSONObject(requireNotNull(doc.selectFirst("script#__NEXT_DATA__")).data())
                    .getJSONObject("props").getJSONObject("pageProps").getJSONObject("initReduxState")
                for (row in state.getJSONArray("allTirePreviews").objects()) {
                    if ((row.optJSONArray("commercialTireApplication")?.length() ?: 0) != 0 ||
                        (row.optJSONArray("commercialTirePosition")?.length() ?: 0) != 0) continue
                    val description = Jsoup.parse(row.optString("snippet")).text()
                    record(row.getString("title"), row.getString("uri"), explicitSeason(description), null, false, description)
                }
            }
            OfficialFormat.HANKOOK_DATA -> {
                val lists = doc.select("[data-result-list]")
                require(lists.isNotEmpty()) { "공식 제품 목록 데이터 형식이 변경되었습니다." }
                for (list in lists) {
                    val payload = list.attr("data-result-list").trim()
                    val products = if (payload.startsWith("[")) JSONArray(payload) else JSONObject(payload).getJSONArray("PCR")
                    for (row in products.objects()) {
                        if (!row.has("SESN_CD_NM")) continue
                        val product = row.getString("URL").removePrefix("/content/hankooktire").removeSuffix(".html") + ".html"
                        val snow = when (row.optString("SNOW_YN")) { "Y" -> true; "N" -> false; else -> null }
                        record(row.getString("PRD_NM"), product, row.getString("SESN_CD_NM"), snow, true,
                            row.optString("PCR_PFM_TYPES_NM"))
                    }
                }
            }
            OfficialFormat.BRIDGESTONE_DATA -> {
                val blocks = doc.select("connected-product-carousel script[slot=products][type=application/json]")
                require(blocks.isNotEmpty()) { "공식 제품 목록 데이터 형식이 변경되었습니다." }
                for (block in blocks) for (row in JSONArray(block.data()).objects()) {
                    val base = row.getJSONObject("base")
                    if (!base.getString("brand").equals(source.manufacturerId, ignoreCase = true)) continue
                    val family = base.getString("subBrand")
                    val name = base.getString("name").let { if (it.startsWith(family, ignoreCase = true)) it else "$family $it" }
                    val details = row.getJSONObject("marketing").toString() + " " + base.optString("description")
                    val snow = Regex("3[ -]?PMSF|3[ -]?peak mountain snowflake", RegexOption.IGNORE_CASE).containsMatchIn(details)
                    record(name, row.getString("url"), base.getString("season"), snow, true,
                        row.getJSONObject("marketing").getJSONArray("categories").join(" "))
                }
            }
            OfficialFormat.PRODUCT_META -> {
                val name = requireNotNull(doc.selectFirst(rule?.name?.takeIf { it.isNotBlank() } ?: "h1")).text()
                require(name.lowercase() !in setOf("compare tires", "our tires", "tires", "tyres"))
                val title = doc.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()
                val description = doc.selectFirst("meta[name=description]")?.attr("content").orEmpty()
                val titleSeason = explicitSeason(title)
                val raw = titleSeason.ifBlank { explicitSeason(description) }
                record(name, source.url, raw, null, rule?.separatesAllWeather ?: false, "$title $description")
            }
            else -> error("지원하지 않는 공식 목록 형식입니다.")
        }
        require(models.isNotEmpty()) { "공식 제품을 찾지 못했습니다." }
        return TireModels.merge(emptyList(), models)
    }

    /** 해당 제품을 설명하는 tire/tyre 명사구만 인정한다. 일반적인 날씨 표현은 넣지 않는다. */
    private fun explicitSeason(text: String): String = Regex(
        "\\b(all[ -]?weather|all[ -]?season|summer|winter)(?:[ -]+[a-z]+){0,3}[ -]+(?:tires?|tyres?)\\b",
        RegexOption.IGNORE_CASE).findAll(text).map { it.groupValues[1] }.distinct().joinToString(" ")

    private fun evidenceText(element: Element) = element.text() + " " + element.getAllElements().joinToString(" ") {
        listOf("alt", "src", "class", "data-tire-uses").joinToString(" ", transform = it::attr)
    }
    private fun offRoad(text: String) = Regex("all[ -]?terrain|mud[ -]?terrain|rugged[ -]?terrain|off[ -]?road", RegexOption.IGNORE_CASE).containsMatchIn(text)
    private fun JSONArray.objects() = (0 until length()).map(::getJSONObject)
}

internal fun canonicalUrl(base: String, link: String): String {
    val uri = URI(base).resolve(link)
    return URI(uri.scheme, uri.authority, uri.path, null, null).toString()
}
