package com.tirelog.core.catalog

import com.tirelog.core.model.TireSetType
import java.text.Normalizer
import java.util.Locale

/** 공식 제품/목록에서 실제로 확인한 값. null은 미확인 또는 이 앱의 네 분류로 확정할 수 없는 경우다. */
data class TireSeasonObservation(
    val catalogUrl: String,
    val productUrl: String,
    val market: String,
    val type: TireSetType?,
    val rawSeason: String,
    val snowCertified: Boolean?,
    val checkedAtMillis: Long,
)

data class TireModel(
    val manufacturerId: String,
    val name: String,
    val sourceUrl: String,
    val observations: List<TireSeasonObservation> = emptyList(),
) {
    val key: String get() = "$manufacturerId:${TireModels.normalize(name)}"
    /** 공식 근거가 없거나 시장/제품별 분류가 충돌하면 자동 선택하지 않는다. */
    val type: TireSetType? get() = observations.map { it.type }.distinct().singleOrNull()
}

/** 모델 이름에 winter/AS 등이 들어간다는 이유로 종류를 추측하지 않는다. 원본의 명시된 분류만 쓴다. */
object TireModels {
    private val searchAliases = mapOf(
        "아이온" to "ion", "에보" to "evo", "벤투스" to "ventus", "키너지" to "kinergy", "다이나프로" to "dynapro",
        "윈터아이셉트" to "wintericept", "윈터아이파이크" to "winteripike", "웨더플렉스" to "weatherflex",
        "엔페라" to "nfera", "엔프리즈" to "npriz", "로디안" to "roadian", "윈가드" to "winguard",
        "마제스티" to "majesty", "솔루스" to "solus", "크루젠" to "crugen", "윈터크래프트" to "wintercraft",
        "파일럿스포츠" to "pilotsport", "프라이머시" to "primacy", "크로스클라이밋" to "crossclimate",
        "블리작" to "blizzak", "포텐자" to "potenza", "에코피아" to "ecopia",
    )

    internal fun normalize(value: String): String = Normalizer.normalize(value.replace(Regex("[™®©]"), ""), Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).filter { it.isLetterOrDigit() || it == '+' }

    private fun searchKey(value: String): String = searchAliases.entries.fold(normalize(value)) { text, (ko, en) ->
        text.replace(ko, en)
    }

    fun search(entries: List<TireModel>, manufacturerId: String?, query: String): List<TireModel> {
        if (manufacturerId == null) return emptyList()
        val key = searchKey(query)
        if (query.isNotBlank() && key.isEmpty()) return emptyList()
        return entries.filter { it.manufacturerId == manufacturerId && key in searchKey(it.name) }
            .sortedWith(compareBy<TireModel> { if (searchKey(it.name) == key) 0 else 1 }.thenBy { it.name.lowercase(Locale.ROOT) })
    }

    /** 목록만 정정한다. 실제 등록 타이어와 작성 중인 폼은 이 목록 갱신을 구독해 덮어쓰지 않는다. */
    fun merge(existing: List<TireModel>, incoming: List<TireModel>): List<TireModel> {
        val result = existing.associateByTo(linkedMapOf()) { it.key }
        incoming.groupBy { it.key }.forEach { (key, group) ->
            val previous = result[key]
            val observations = (previous?.observations.orEmpty() + group.flatMap { it.observations })
                .groupBy { it.catalogUrl }.values.map { records ->
                    val newest = records.filter { it.checkedAtMillis == records.maxOf { row -> row.checkedAtMillis } }
                    val first = newest.first()
                    if (newest.map { it.type }.distinct().size == 1) first
                    else first.copy(type = null, rawSeason = "공식 자료 내 분류 충돌")
                }.sortedBy { it.catalogUrl }
            result[key] = (previous ?: group.first()).copy(observations = observations)
        }
        return result.values.toList()
    }
}
