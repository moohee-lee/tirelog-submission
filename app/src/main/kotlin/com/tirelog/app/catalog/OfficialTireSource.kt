package com.tirelog.app.catalog

import com.tirelog.core.catalog.TireModel
import com.tirelog.core.catalog.TireModels
import com.tirelog.core.catalog.TireSeasonClassifier
import com.tirelog.core.catalog.TireSeasonObservation
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.net.URI

internal enum class OfficialFormat {
    HANKOOK_INDEX, HANKOOK, KUMHO_INDEX, KUMHO, NEXEN_INDEX, NEXEN, MICHELIN, GOODYEAR, PIRELLI, BRIDGESTONE,
    SCOPED, CONTI_DATA, DUNLOP_DATA, YOKOHAMA_DATA, BRIDGESTONE_DATA, HANKOOK_DATA, APOLLO_DATA, PRODUCT_META, PRODUCT_INDEX, OFFICIAL_DOCUMENT,
}

internal data class OfficialTireSource(val manufacturerId: String, val url: String, val market: String,
    val format: OfficialFormat, val rule: OfficialCatalogRule? = null) {
    init {
        require(isOfficialTireUrl(manufacturerId, url))
        if (format in setOf(OfficialFormat.SCOPED, OfficialFormat.CONTI_DATA, OfficialFormat.DUNLOP_DATA,
                OfficialFormat.YOKOHAMA_DATA, OfficialFormat.BRIDGESTONE_DATA, OfficialFormat.HANKOOK_DATA,
                OfficialFormat.APOLLO_DATA, OfficialFormat.PRODUCT_META, OfficialFormat.PRODUCT_INDEX, OfficialFormat.OFFICIAL_DOCUMENT)) {
            require(market in setOf("US", "UK", "EU", "GLOBAL", "KR", "JP", "CA", "AU"))
            if (format == OfficialFormat.SCOPED || format == OfficialFormat.PRODUCT_INDEX) requireNotNull(rule)
            if (format == OfficialFormat.OFFICIAL_DOCUMENT) {
                require(url.endsWith(".pdf", ignoreCase = true))
                requireNotNull(rule)
                require(Regex("[a-f0-9]{64}").matches(rule.documentHash) && rule.documentFacts.isNotEmpty())
            }
        } else {
            require(format.name.substringBefore('_').equals(manufacturerId, ignoreCase = true))
            require(market == when (manufacturerId) {
            "hankook" -> if (URI(url).path.startsWith("/kr/ko/")) "KR" else "US"
            "pirelli" -> "EU"
            else -> "US"
            })
        }
    }
    val index get() = format in setOf(OfficialFormat.HANKOOK_INDEX, OfficialFormat.KUMHO_INDEX, OfficialFormat.NEXEN_INDEX, OfficialFormat.PRODUCT_INDEX)
}

/** 번들에 승인한 제조사 도메인/제품 경로만 접근한다. 출처 없는 과거 type 값은 공식 근거로 취급하지 않는다. */
internal fun isOfficialTireUrl(manufacturer: String, url: String): Boolean = runCatching {
    val uri = URI(url)
    val queryAllowed = uri.rawQuery == null || Regex("page=([1-9]|1[0-9])").matches(uri.rawQuery) ||
        (manufacturer == "apollo" && uri.host == "www.apollotyres.com" && uri.path == "/content.allProducts.getInfo" &&
            uri.rawQuery == APOLLO_PRODUCT_QUERY) ||
        (manufacturer == "yokohama" && uri.host == "www.yokohama.eu" && uri.path == "/wp-admin/admin-ajax.php" &&
            uri.rawQuery == "action=load_tyres_ajax&per_page=30&page=1")
    uri.scheme == "https" && uri.userInfo == null && uri.port == -1 && queryAllowed && uri.rawFragment == null &&
        (uri.host in additionalOfficialTireHosts[manufacturer].orEmpty() || when (manufacturer) {
            "hankook" -> uri.host == "www.hankooktire.com" && Regex("/(kr/ko|us/en|uk/en|au/en)/(sitemap\\.xml|(tire|tirelist|tires)/.+\\.html)").matches(uri.path)
            "kumho" -> uri.host == "kumhotireusa.com" && (uri.path == "/" || Regex("/tires/[a-z0-9-]+/?").matches(uri.path))
            "nexen" -> uri.host == "www.nexentireusa.com" && Regex("/tires/([a-z0-9_-]+/)?").matches(uri.path)
            "michelin" -> uri.host == "www.michelinman.com" && (uri.path == "/auto/browse-tires/all-tyres" || uri.path.startsWith("/auto/tires/michelin-"))
            "goodyear" -> uri.host == "www.goodyear.com" && uri.path.startsWith("/en-us/tires/")
            "pirelli" -> uri.host == "www.pirelli.com" && uri.path.startsWith("/tyres/en-ww/car/catalogue/product/")
            "bridgestone" -> uri.host == "www.bridgestoneamericas.com" && uri.path == "/en/press-release-details.en.2022.bridgestone-launches-weatherpeak"
            else -> false
        })
}.getOrDefault(false)

internal object OfficialTireParser {
    fun discover(html: String, source: OfficialTireSource): List<OfficialTireSource> {
        val doc = Jsoup.parse(html, source.url, if (source.url.endsWith(".xml")) Parser.xmlParser() else Parser.htmlParser())
        val links: List<Element>
        val format: OfficialFormat
        when (source.format) {
            OfficialFormat.PRODUCT_INDEX -> {
                links = doc.select(requireNotNull(source.rule).indexLinks)
                format = OfficialFormat.valueOf(source.rule.childFormat)
                require(format in setOf(OfficialFormat.PRODUCT_META, OfficialFormat.SCOPED))
            }
            OfficialFormat.HANKOOK_INDEX -> { links = doc.select("loc, a[href]"); format = OfficialFormat.HANKOOK }
            OfficialFormat.NEXEN_INDEX -> { links = doc.select("a:has(.tireCardInner)"); format = OfficialFormat.NEXEN }
            OfficialFormat.KUMHO_INDEX -> {
                links = doc.select(".nav-mobile-group").filterNot {
                    it.text().contains(Regex("Line Haul|Regional Haul|Mixed Service|Pickup and Delivery|Super Regional|^Bus ", RegexOption.IGNORE_CASE))
                }.flatMap { it.select("a[href]") }
                format = OfficialFormat.KUMHO
            }
            else -> error("제품 목록 출처가 아닙니다.")
        }
        val pages = links.mapNotNull { link ->
            val href = if (link.tagName() == "loc") link.text() else link.attr("href")
            val url = if (source.format == OfficialFormat.PRODUCT_INDEX) canonicalUrl(source.url, href)
                else URI(source.url).resolve(href).toString()
            if (!isOfficialTireUrl(source.manufacturerId, url)) return@mapNotNull null
            if (source.manufacturerId == "toyo" && Regex("/product/(open-country|proxes|extensa|celsius|observe|h|m)/?").matches(URI(url).path)) return@mapNotNull null
            if (format == OfficialFormat.HANKOOK && !URI(url).path.contains("/tire/")) return@mapNotNull null
            if (format == OfficialFormat.HANKOOK && !Regex("/(ion|ventus|dynapro|kinergy|winter-icept|winter-ipike|weatherflex)/").containsMatchIn(URI(url).path)) return@mapNotNull null
            if (url == source.url) return@mapNotNull null
            source.copy(url = url, format = format)
        }.distinctBy { it.url }
        require(pages.isNotEmpty()) { "공식 제품 링크 형식이 변경되었습니다." }
        return pages
    }

    fun parse(html: String, source: OfficialTireSource, checkedAt: Long): List<TireModel> {
        if (source.format in setOf(OfficialFormat.SCOPED, OfficialFormat.CONTI_DATA, OfficialFormat.DUNLOP_DATA,
                OfficialFormat.YOKOHAMA_DATA, OfficialFormat.BRIDGESTONE_DATA, OfficialFormat.HANKOOK_DATA, OfficialFormat.PRODUCT_META,
                OfficialFormat.APOLLO_DATA, OfficialFormat.OFFICIAL_DOCUMENT)) {
            return ExpandedOfficialTireParser.parse(html, source, checkedAt)
        }
        val doc = Jsoup.parse(html, source.url)
        fun record(name: String, product: String, raw: String, snow: Boolean?, separates: Boolean,
            onRoad: Boolean = true): TireModel {
            val clean = name.replace(Regex("[™®©]"), "").trim()
            require(clean.isNotBlank() && clean.length <= 80 && isOfficialTireUrl(source.manufacturerId, product))
            return TireModel(source.manufacturerId, clean, product, listOf(TireSeasonObservation(source.url, product,
                source.market, TireSeasonClassifier.classify(raw, snow, separates, onRoad), raw, snow, checkedAt)))
        }
        val models = when (source.format) {
            OfficialFormat.HANKOOK -> {
                if (doc.selectFirst("h1.prd-name") == null) {
                    // 제조사 구형 제품 전용 페이지. 활성 제품명과 제품 첫 소개의 명시적 올웨더 문구만 인정한다.
                    val name = requireNotNull(doc.selectFirst(".tabs .tab.active")) { "공식 제품명을 찾지 못했습니다." }.text()
                    val introduction = requireNotNull(doc.selectFirst("h2.__title")).text()
                    require(Regex("올웨더 타이어|all.weather (tire|tyre)", RegexOption.IGNORE_CASE).containsMatchIn(introduction))
                    return listOf(record(name, source.url, "All Weather", null, true))
                }
                val name = requireNotNull(doc.selectFirst("h1.prd-name")).text()
                val features = requireNotNull(doc.selectFirst(".feature__list")) { "공식 제품 태그 형식이 변경되었습니다." }
                val raw = features.select(".feature__item").filter { it.select("img[src*=/season/]").isNotEmpty() }
                    .joinToString(" ") {
                        when (it.selectFirst("img")?.attr("src")?.substringAfterLast('/')?.lowercase()) {
                            "road_all_season.svg" -> "All Season"
                            "road_all_weather.svg" -> "All Weather"
                            "road_summer.svg" -> "Summer"
                            "road_winter.svg" -> "Winter"
                            else -> it.select(".feature__name").text()
                        }
                    }
                val snow = features.text().contains("3PMSF", true) || features.select("img[src]").any { it.attr("src").contains("3pmsf", true) }
                val purpose = doc.select(".info-content .desc").text()
                listOf(record(name, source.url, raw, snow, true, !offRoad(purpose)))
            }
            OfficialFormat.KUMHO -> {
                val hero = requireNotNull(doc.selectFirst(".tire-pdp-hero__info")) { "공식 제품 형식이 변경되었습니다." }
                val name = requireNotNull(hero.selectFirst("h1")).text()
                val tags = requireNotNull(hero.selectFirst(".tire-pdp-hero__specialties"))
                val raw = tags.text()
                listOf(record(name, source.url, raw, null, true, !offRoad(raw)))
            }
            OfficialFormat.NEXEN -> {
                val hero = requireNotNull(doc.selectFirst(".tireDetailTitle")) { "공식 제품 형식이 변경되었습니다." }
                val name = requireNotNull(hero.selectFirst("h1")).text()
                val raw = requireNotNull(hero.selectFirst(".tirePerformanceHeader")).text()
                listOf(record(name, source.url, raw, null, true, !offRoad(raw)))
            }
            OfficialFormat.MICHELIN -> doc.select(".ts__product-card").map { card ->
                require(card.selectFirst(".ds__brand")?.text() == "Michelin")
                val name = requireNotNull(card.selectFirst(".ds__tyre-productName")).text()
                val product = requireNotNull(card.selectFirst("header a[href*=/auto/tires/michelin-]")).absUrl("href")
                val tags = requireNotNull(card.selectFirst(".ts__product-tag-list"))
                    .select(".ds__badge-label").map { it.text() }.toSet()
                val raw = tags.filter { it in setOf("Summer", "Winter", "All-Season") }.joinToString(" ")
                // 이 제품들은 공식 상세 페이지의 on-road 설명까지 확인했다. 새로운 눈송이 제품은 추측하지 않는다.
                val snow = "3PMSF" in tags
                val onRoad = !snow || name in setOf("CrossClimate2", "Agilis CrossClimate", "Agilis CrossClimate 2") || raw == "Winter"
                record(name, product, raw, snow, false, onRoad)
            }
            OfficialFormat.GOODYEAR -> doc.select("[data-product-id][data-qa-id*=ProductCard][data-qa-id*=-card-]").map { card ->
                val name = requireNotNull(card.selectFirst("h2")).text()
                val product = requireNotNull(card.selectFirst("a[href*=/en-us/tires/]")).absUrl("href")
                val tags = card.select("[data-qa-id^=tag-label-]").map { it.text() }.toSet()
                val raw = tags.filter { it in setOf("All-Season", "Summer", "Winter", "All-Weather") }.joinToString(" ")
                record(name, product, raw, "3PMSF" in tags, false, !offRoad(tags.joinToString(" ")))
            }
            OfficialFormat.PIRELLI -> {
                val hero = requireNotNull(doc.selectFirst("div[class*=productHero__content__]"))
                val name = requireNotNull(hero.selectFirst("h1")).text()
                val tags = hero.select("[class*=badgeList] [class*=badge__text]").text()
                require(tags.isNotBlank()) { "공식 계절 태그 형식이 변경되었습니다." }
                listOf(record(name, source.url, tags, if (hero.text().contains("3PMSF")) true else null, false))
            }
            OfficialFormat.BRIDGESTONE -> {
                require(doc.selectFirst("h1")?.text()?.contains("WeatherPeak") == true)
                require(doc.select("li").any { it.text().contains("WeatherPeak") && it.text().contains("first touring tire with all-weather capability") })
                listOf(record("WeatherPeak", source.url, "All Weather", null, true))
            }
            else -> error("제품 분류 출처가 아닙니다.")
        }
        require(models.isNotEmpty()) { "공식 제품 목록 형식이 변경되었습니다." }
        return TireModels.merge(emptyList(), models)
    }

    private fun offRoad(text: String) = Regex("all[ -]?terrain|mud[ -]?terrain|rugged[ -]?terrain|온.?오프로드|오프로드", RegexOption.IGNORE_CASE).containsMatchIn(text)
}
