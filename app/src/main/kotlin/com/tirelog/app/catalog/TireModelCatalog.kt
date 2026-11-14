package com.tirelog.app.catalog

import android.util.AtomicFile
import android.util.Log
import com.tirelog.core.catalog.TireManufacturers
import com.tirelog.core.catalog.TireModel
import com.tirelog.core.catalog.TireModels
import com.tirelog.core.catalog.TireSeasonObservation
import com.tirelog.core.model.TireSetType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI

internal data class TireModelCatalogState(val models: List<TireModel> = emptyList(), val loaded: Boolean = false)
internal interface TireModelCatalog {
    val state: StateFlow<TireModelCatalogState>
    fun watch(manufacturerId: String)
}

/** 공개 목록 전용 캐시. 등록 DB·작성 중인 폼을 변경하지 않는다. 출처별 성공분은 실패와 별개로 보존한다. */
internal class CachedTireModelCatalog(
    private val seed: () -> String,
    cacheFile: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val fetch: (TireModelSource) -> String = ::fetchTireModelPage,
    private val fetchOfficial: (OfficialTireSource) -> String = ::fetchOfficialTirePage,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : TireModelCatalog {
    private val file = AtomicFile(cacheFile)
    private val mutex = Mutex()
    private val _state = MutableStateFlow(TireModelCatalogState())
    override val state: StateFlow<TireModelCatalogState> = _state.asStateFlow()
    private var sources = emptyMap<String, TireModelSource>()
    private var official = emptyMap<String, OfficialTireSource>()
    private var checkedAt = emptyMap<String, Long>()
    private var tracked = emptySet<String>()

    fun start() { scope.launch { mutex.withLock { loadLocked() } } }
    override fun watch(manufacturerId: String) {
        scope.launch {
            try { loadManufacturer(manufacturerId) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { Log.w(TAG, "모델 목록을 갱신하지 못했습니다.", e) }
        }
    }

    suspend fun loadManufacturer(manufacturerId: String): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            loadLocked()
            if (manufacturerId !in sources && official.values.none { it.manufacturerId == manufacturerId }) return@withLock true
            if (manufacturerId !in tracked) { tracked = tracked + manufacturerId; persist() }
            refreshLocked(manufacturerId)
        }
    }

    suspend fun refreshDue(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            loadLocked()
            var success = true
            for (id in tracked) if (!refreshLocked(id)) success = false
            success
        }
    }

    private fun due(url: String): Boolean {
        val previous = checkedAt[url] ?: return true
        val now = clock()
        return now < previous || now - previous >= REFRESH_INTERVAL_MS
    }

    private suspend fun refreshLocked(manufacturerId: String): Boolean {
        var success = true
        sources[manufacturerId]?.takeIf { due(it.url) }?.let { source ->
            try {
                val discovered = TireModelSourceParser.parse(fetch(source), source)
                currentCoroutineContext().ensureActive()
                applyModels(discovered)
                checkedAt = checkedAt + (source.url to clock())
                persist()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { success = false; Log.w(TAG, "모델명 목록 갱신 실패. 기존 목록을 유지합니다.", e) }
        }
        val indexes = official.values.filter { it.manufacturerId == manufacturerId && it.index && due(it.url) }
        for (source in indexes) {
            try {
                val pages = OfficialTireParser.discover(fetchOfficial(source), source)
                currentCoroutineContext().ensureActive()
                official = official + pages.take(MAX_OFFICIAL_PAGES_PER_INDEX).associateBy { it.url }
                checkedAt = checkedAt + (source.url to clock())
                persist()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { success = false; Log.w(TAG, "공식 모델 목록 갱신 실패. 기존 근거를 유지합니다.", e) }
        }
        // 소스별 체크 시각으로 실패한 제품만 재시도한다. 작업이 취소돼도 앞서 완료한 묶음은 남는다.
        val pages = official.values.filter { it.manufacturerId == manufacturerId && !it.index && due(it.url) }
        for (batch in pages.chunked(3)) {
            val results = coroutineScope {
                batch.map { source -> async {
                    try {
                        val stamp = maxOf(clock(), _state.value.models.flatMap { it.observations }
                            .filter { it.catalogUrl == source.url }.maxOfOrNull { it.checkedAtMillis }?.plus(1) ?: 0)
                        source to Result.success(OfficialTireParser.parse(fetchOfficial(source), source, stamp))
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { source to Result.failure<List<TireModel>>(e) }
                } }.awaitAll()
            }
            currentCoroutineContext().ensureActive()
            for ((source, result) in results) {
                result.fold(onSuccess = { models ->
                    val known = _state.value.models.mapTo(hashSetOf()) { it.key }
                    applyModels(models.filter { model ->
                        model.key in known || (source.format != OfficialFormat.CONTI_DATA && model.type != null)
                    })
                    checkedAt = checkedAt + (source.url to clock())
                }, onFailure = { error ->
                    success = false
                    Log.w(TAG, "공식 제품 분류 갱신 실패. 마지막 확인 결과를 유지합니다.", error)
                })
            }
            persist()
        }
        return success
    }

    private fun applyModels(incoming: List<TireModel>) {
        _state.value = TireModelCatalogState(TireModels.merge(_state.value.models, incoming), loaded = true)
    }

    private fun loadLocked() {
        if (_state.value.loaded) return
        val bundled = JSONObject(seed())
        require(bundled.getInt("schemaVersion") in 1..SCHEMA_VERSION)
        val known = TireManufacturers.entries.map { it.id }.toSet()
        sources = bundled.getJSONArray("sources").objects().map { row ->
            TireModelSource(row.getString("manufacturerId"), row.getString("brand"), row.getString("slug"))
                .also { require(it.manufacturerId in known) }
        }.associateBy { it.manufacturerId }
        official = readOfficial(bundled.optJSONArray("officialSources"))
        val models = readModels(bundled)
        checkedAt = readChecked(bundled.optJSONObject("checkedAt"))
        val cached = try {
            file.openRead().use { input ->
                val bytes = input.readCatalogBytes(MAX_CACHE_BYTES)
                require(bytes.size <= MAX_CACHE_BYTES)
                JSONObject(bytes.toString(Charsets.UTF_8)).also { require(it.getInt("schemaVersion") in 1..SCHEMA_VERSION) }
            }
        } catch (_: Exception) { null }
        val restored = try {
            if (cached == null) null else {
                val extra = readOfficial(cached.optJSONArray("officialSources"))
                official = extra + official // 번들에 갱신한 출처 정의를 우선한다.
                val entries = readModels(cached)
                val checks = readChecked(cached.optJSONObject("checkedAt"))
                val interests = cached.getJSONArray("tracked").let { array ->
                    (0 until array.length()).map { array.getString(it) }.filter { it in known }.toSet()
                }
                Triple(entries, checks, interests)
            }
        } catch (_: Exception) { null }
        if (restored != null) {
            checkedAt = (checkedAt.keys + restored.second.keys).associateWith { id -> maxOf(checkedAt[id] ?: 0, restored.second[id] ?: 0) }
            tracked = restored.third
        }
        _state.value = TireModelCatalogState(TireModels.merge(models, restored?.first.orEmpty()), loaded = true)
    }

    private fun readOfficial(array: JSONArray?): Map<String, OfficialTireSource> = array?.objects().orEmpty().mapNotNull { row ->
        runCatching { OfficialTireSource(row.getString("manufacturerId"), row.getString("url"),
            row.getString("market"), OfficialFormat.valueOf(row.getString("format")),
            row.optJSONObject("rule")?.let(OfficialCatalogRule::fromJson)) }.getOrNull()
    }.associateBy { it.url }

    private fun readModels(json: JSONObject): List<TireModel> = json.getJSONArray("models").objects().map { row ->
        val brand = row.getString("manufacturerId")
        val name = row.getString("name").trim()
        val url = row.getString("sourceUrl")
        require(name.isNotEmpty() && name.length <= 80 && validModelUrl(brand, url))
        // v1의 type은 모두 비검증이다. 2,044개 기존 모델명은 보존하고 과거 분류값은 버린다.
        val observations = if (json.optInt("schemaVersion") == SCHEMA_VERSION &&
            json.optInt("classificationPolicyVersion") == CLASSIFICATION_POLICY_VERSION) {
            row.optJSONArray("observations")?.objects().orEmpty().mapNotNull { value -> runCatching {
                val catalogUrl = value.getString("catalogUrl")
                val productUrl = value.getString("productUrl")
                val source = requireNotNull(official[catalogUrl])
                require(source.manufacturerId == brand && isOfficialTireUrl(brand, productUrl))
                require(value.getString("market") == source.market)
                val stamp = value.getLong("checkedAtMillis"); require(stamp >= 0)
                TireSeasonObservation(catalogUrl, productUrl, source.market,
                    value.optString("type").let { type -> TireSetType.entries.firstOrNull { it.name == type } },
                    value.optString("rawSeason"), if (value.isNull("snowCertified")) null else value.getBoolean("snowCertified"), stamp)
            }.getOrNull() }
        } else emptyList()
        TireModel(brand, name, url, observations)
    }

    private fun validModelUrl(brand: String, value: String): Boolean {
        if (isOfficialTireUrl(brand, value)) return true
        val source = sources[brand] ?: return false
        val url = URI(value)
        return url.scheme == "https" && url.host == "www.tyrereviews.com" && url.userInfo == null &&
            url.path.startsWith("/Tyre/${source.slug}/") && url.path.endsWith(".htm") &&
            url.rawQuery == null && url.rawFragment == null
    }

    private fun readChecked(json: JSONObject?): Map<String, Long> = json?.keys()?.asSequence()
        ?.filter { url -> url in official || sources.values.any { it.url == url } }
        ?.associateWith { json.getLong(it) } ?: emptyMap()

    private fun persist() {
        val models = JSONArray()
        _state.value.models.forEach { model ->
            val observations = JSONArray()
            model.observations.forEach { o -> observations.put(JSONObject().put("catalogUrl", o.catalogUrl)
                .put("productUrl", o.productUrl).put("market", o.market).put("type", o.type?.name ?: JSONObject.NULL)
                .put("rawSeason", o.rawSeason).put("snowCertified", o.snowCertified ?: JSONObject.NULL).put("checkedAtMillis", o.checkedAtMillis)) }
            models.put(JSONObject().put("manufacturerId", model.manufacturerId).put("name", model.name)
                .put("sourceUrl", model.sourceUrl).put("observations", observations))
        }
        val definitions = JSONArray()
        official.values.forEach { s -> definitions.put(JSONObject().put("manufacturerId", s.manufacturerId)
            .put("url", s.url).put("market", s.market).put("format", s.format.name).apply {
                s.rule?.let { put("rule", it.toJson()) }
            }) }
        val json = JSONObject().put("schemaVersion", SCHEMA_VERSION).put("classificationPolicyVersion", CLASSIFICATION_POLICY_VERSION)
            .put("models", models).put("officialSources", definitions).put("checkedAt", JSONObject(checkedAt))
            .put("tracked", JSONArray(tracked.toList()))
        val output = file.startWrite()
        try { output.write(json.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(output) }
        catch (e: Exception) { file.failWrite(output); throw e }
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map(::getJSONObject)
    companion object {
        const val REFRESH_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000
        private const val SCHEMA_VERSION = 2
        private const val CLASSIFICATION_POLICY_VERSION = 1
        private const val MAX_CACHE_BYTES = 8_000_000
        private const val MAX_OFFICIAL_PAGES_PER_INDEX = 240
        private const val TAG = "TireModelCatalog"
    }
}
