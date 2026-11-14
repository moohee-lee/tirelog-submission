package com.tirelog.app.catalog

import com.tirelog.core.catalog.TireModel
import org.jsoup.Jsoup
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest

internal data class TireModelSource(val manufacturerId: String, val brand: String, val slug: String) {
    init { require(slug.matches(Regex("[A-Za-z0-9-]+"))) }
    val url: String get() = "https://www.tyrereviews.com/Tyre/$slug/"
}

internal fun InputStream.readCatalogBytes(limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        require(output.size() + count <= limit) { "목록 크기 제한을 초과했습니다." }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

internal object TireModelSourceParser {
    fun parse(html: String, source: TireModelSource): List<TireModel> {
        val doc = Jsoup.parse(html, source.url)
        require(doc.selectFirst("h1")?.text() == "${source.brand} Tyres") { "제조사 목록을 확인할 수 없습니다." }
        val rows = doc.select("section#all tr[data-season]")
        require(rows.isNotEmpty()) { "모델 목록 형식이 변경되었습니다." }
        val models = rows.mapNotNull { row ->
            val link = row.selectFirst(".tf-table__tyre > a") ?: return@mapNotNull null
            if (link.selectFirst("b")?.text() != source.brand) return@mapNotNull null
            val url = URI(source.url).resolve(link.attr("href"))
            if (url.scheme != "https" || url.host != "www.tyrereviews.com" ||
                !url.path.startsWith("/Tyre/${source.slug}/") || !url.path.endsWith(".htm") ||
                url.rawQuery != null || url.rawFragment != null) return@mapNotNull null
            val name = link.clone().apply { select("b").remove() }.text().trim()
            if (name.isBlank() || name.length > 80) return@mapNotNull null
            TireModel(source.manufacturerId, name, url.toString())
        }
        require(models.isNotEmpty()) { "모델을 찾지 못했습니다." }
        return models.distinctBy { it.key }
    }
}

/** 입력한 검색어나 차량 정보는 보내지 않고, 정해진 제조사 목록 URL만 읽는다. */
internal fun fetchTireModelPage(source: TireModelSource): String {
    return fetchCatalogPage(source.url)
}

internal fun fetchOfficialTirePage(source: OfficialTireSource): String {
    if (source.format == OfficialFormat.OFFICIAL_DOCUMENT) return fetchOfficialDocumentHash(source.url)
    if (source.manufacturerId == "yokohama" && URI(source.url).host == "www.yokohama.eu" &&
        URI(source.url).rawQuery == "action=load_tyres_ajax&per_page=30&page=1") {
        return collectYokohamaPages { page ->
            val url = source.url.substringBeforeLast('=') + "=" + page
            fetchCatalogPage(url, official = true, form = URI(url).rawQuery)
        }
    }
    return fetchCatalogPage(source.url, official = true)
}

/** 문서를 추측해서 다시 분류하지 않는다. 검토한 판본과 같은지 스트리밍으로 확인한다. */
internal fun hashOfficialDocument(input: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val signature = ByteArray(5)
    var read = 0
    while (read < signature.size) {
        val count = input.read(signature, read, signature.size - read)
        require(count > 0) { "공식 PDF 문서가 아닙니다." }; read += count
    }
    require(signature.toString(Charsets.US_ASCII) == "%PDF-") { "공식 PDF 문서가 아닙니다." }
    digest.update(signature)
    val buffer = ByteArray(8192)
    var total = signature.size
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        require(total <= 25_000_000) { "공식 문서 크기 제한을 초과했습니다." }
        digest.update(buffer, 0, count)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun fetchOfficialDocumentHash(url: String): String {
    val connection = URL(url).openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 15_000; connection.readTimeout = 15_000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; tirelog/0.1)")
        connection.setRequestProperty("Accept", "application/pdf")
        require(connection.responseCode == HttpURLConnection.HTTP_OK)
        require(connection.contentType.orEmpty().substringBefore(';').equals("application/pdf", true))
        return connection.inputStream.use { JSONObject().put("sha256", hashOfficialDocument(it)).toString() }
    } finally { connection.disconnect() }
}

/** 공개 목록 조회가 POST를 사용하는 제조사다. 페이지 수를 확인해 첫 화면 이후 제품도 함께 읽는다. */
internal fun collectYokohamaPages(fetch: (Int) -> String): String {
    val first = JSONObject(fetch(1))
    val total = first.getInt("total")
    require(total in 1..300)
    val html = StringBuilder(first.getString("html"))
    for (page in 2..((total + 29) / 30)) {
        val next = JSONObject(fetch(page))
        require(next.getInt("total") == total)
        html.append(next.getString("html"))
    }
    return first.put("html", html.toString()).toString()
}

private fun fetchCatalogPage(url: String, official: Boolean = false, form: String? = null): String {
    val connection = URL(url).openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("User-Agent", if (official) "Mozilla/5.0 (compatible; tirelog/0.1)" else "tirelog/0.1 (+https://github.com/moohee-lee/tirelog)")
        connection.setRequestProperty("Accept", "text/html")
        if (form != null) {
            require(official && URI(url).host == "www.yokohama.eu" &&
                Regex("action=load_tyres_ajax&per_page=30&page=([1-9]|10)").matches(form))
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
        }
        if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("모델 목록 HTTP ${connection.responseCode}")
        val contentType = connection.contentType.orEmpty().lowercase(java.util.Locale.ROOT)
        val xml = official && url.endsWith(".xml") && (contentType.startsWith("application/xml") || contentType.startsWith("text/xml"))
        val json = official && (contentType.startsWith("application/json") || contentType.startsWith("text/json"))
        if (!contentType.startsWith("text/html") && !xml && !json) throw IOException("모델 목록 형식을 확인할 수 없습니다.")
        return connection.inputStream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > 6_000_000) throw IOException("모델 목록이 너무 큽니다.")
                output.write(buffer, 0, count)
            }
            output.toString(Charsets.UTF_8.name())
        }
    } finally { connection.disconnect() }
}
