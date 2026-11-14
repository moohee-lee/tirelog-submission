package com.tirelog.core.catalog

import java.text.Normalizer
import java.util.Locale

data class TireManufacturer(
    val id: String,
    val name: String,
    val englishName: String,
    val sourceUrl: String,
    val aliases: List<String> = emptyList(),
    val lifeGuideIds: List<String> = listOf(TireLifeGuides.DEFAULT_ID),
) {
    val label: String get() = if (name == englishName) name else "$name · $englishName"
    internal val searchTerms = (listOf(name, englishName) + aliases).map(::normalizeManufacturerName).distinct()
}

/**
 * Wikidata(CC0) 조회 결과에서 판매점·서비스·중복 법인을 제외하고 공식 브랜드 목록으로 보완했다.
 * 전 세계 전체 명부가 아니므로 목록에 없는 이름의 직접 등록을 허용한다. 앱 실행 중에는 네트워크를 쓰지 않는다.
 * 수집 범위·갱신 방법: docs/reviews/타이어_제조사_목록.md
 */
object TireManufacturers {
    const val VERIFIED_ON = "2026-11-07"
    private const val WD = "https://www.wikidata.org/wiki/"
    private const val CONTINENTAL = "https://www.continental-tyres.co.uk/about-us/continental-tyres/"
    private const val MICHELIN = "https://gtc.michelin.com/wp-content/uploads/2021/08/gtc-AT-en.pdf"
    private const val GITI = "https://www.giti.com/regional-brand"
    private const val HANKOOK = "https://www.hankooktire.com/global/en/company/company-overview.html"

    // 국내 제조사 우선, 나머지는 영문명 순서. 모회사·계열 브랜드는 별개 항목이며 점검 기준을 상속하지 않는다.
    val entries: List<TireManufacturer> = listOf(
        TireManufacturer("hankook", "한국타이어", "Hankook", WD + "Q493094",
            listOf("한국", "한국타이어앤테크놀로지", "Hankook Tire", "Hankook Tyre"), listOf("hankook")),
        TireManufacturer("kumho", "금호타이어", "Kumho", WD + "Q493108", listOf("금호", "Kumho Tire")),
        TireManufacturer("nexen", "넥센", "Nexen", WD + "Q625573",
            listOf("넥센타이어", "Nexen Tire"), listOf("nexen", "nexen_ev")),
    ) + listOf(
        TireManufacturer("apollo", "아폴로", "Apollo", WD + "Q4780362", listOf("Apollo Tyres")),
        TireManufacturer("barum", "바룸", "Barum", WD + "Q81510", listOf("Continental Barum")),
        TireManufacturer("belshina", "Belshina", "Belshina", WD + "Q443129"),
        TireManufacturer("bfgoodrich", "비에프굿리치", "BFGoodrich", MICHELIN, listOf("BF Goodrich", "비에프구드리치")),
        TireManufacturer("bkt", "BKT", "BKT", WD + "Q19321885", listOf("Balkrishna Industries")),
        TireManufacturer("bridgestone", "브리지스톤", "Bridgestone", WD + "Q179433", listOf("브릿지스톤")),
        TireManufacturer("casumina", "Casumina", "Casumina", WD + "Q420569"),
        TireManufacturer("ceat", "CEAT", "CEAT", "https://www.ceat.com/"),
        TireManufacturer("continental", "콘티넨탈", "Continental", WD + "Q163241", listOf("컨티넨탈", "Continental AG", "Conti")),
        TireManufacturer("cooper", "쿠퍼", "Cooper", WD + "Q1129847", listOf("Cooper Tire")),
        TireManufacturer("dextero", "Dextero", "Dextero", GITI),
        TireManufacturer("doublecoin", "더블코인", "Double Coin", "https://www.doublecointires.com/"),
        TireManufacturer("dunlop", "던롭", "Dunlop", WD + "Q5315444"),
        TireManufacturer("falken", "팔켄", "Falken", WD + "Q61055670", listOf("팔콘", "Falken Tire")),
        TireManufacturer("federal", "페더럴", "Federal", "https://www.federaltire.com/en/", listOf("페더럴타이어")),
        TireManufacturer("firestone", "파이어스톤", "Firestone", WD + "Q420837"),
        TireManufacturer("fulda", "Fulda", "Fulda", WD + "Q317982"),
        TireManufacturer("general", "제너럴 타이어", "General Tire", WD + "Q369128", listOf("General", "제너럴")),
        TireManufacturer("gislaved", "Gislaved", "Gislaved", CONTINENTAL),
        TireManufacturer("giti", "지티", "Giti", WD + "Q14915570", listOf("Giti Tire")),
        TireManufacturer("goldstone", "Goldstone", "Goldstone", WD + "Q5580358"),
        TireManufacturer("goodyear", "굿이어", "Goodyear", WD + "Q620875", listOf("굳이어", "Good Year")),
        TireManufacturer("gri", "GRI", "GRI", WD + "Q28455985", listOf("Global Rubber Industries", "GRI Tires")),
        TireManufacturer("gtradial", "GT 래디얼", "GT Radial", GITI, listOf("지티래디얼", "GT레디얼")),
        TireManufacturer("hoosier", "후지어", "Hoosier", WD + "Q2739895", listOf("Hoosier Racing Tire")),
        TireManufacturer("jktyre", "JK Tyre", "JK Tyre", WD + "Q126811070", listOf("JK Tire")),
        TireManufacturer("kenda", "켄다", "Kenda", "https://www.kendatire.com/", listOf("Kenda Tire")),
        TireManufacturer("kleber", "클레베", "Kleber", MICHELIN, listOf("클레버", "Kléber")),
        TireManufacturer("kormoran", "Kormoran", "Kormoran", MICHELIN),
        TireManufacturer("laufenn", "라우펜", "Laufenn", HANKOOK),
        TireManufacturer("linglong", "링롱", "Linglong", WD + "Q48802850", listOf("Shandong Linglong", "Linglong Tire")),
        TireManufacturer("mabor", "Mabor", "Mabor", CONTINENTAL),
        TireManufacturer("matador", "마타도르", "Matador", WD + "Q1908026"),
        TireManufacturer("maxxis", "맥시스", "Maxxis", WD + "Q4215912"),
        TireManufacturer("michelin", "미쉐린", "Michelin", WD + "Q151107", listOf("미슐랭"), listOf("michelin")),
        TireManufacturer("mrf", "MRF", "MRF", WD + "Q1789944", listOf("Madras Rubber Factory")),
        TireManufacturer("multistrada", "Multistrada", "Multistrada", WD + "Q30632955", listOf("Multistrada Arah Sarana")),
        TireManufacturer("nankang", "난강", "Nankang", WD + "Q711934", listOf("낭강", "Nankang Rubber Tire")),
        TireManufacturer("nokian", "노키안", "Nokian Tyres", WD + "Q1481634", listOf("Nokian", "노키안 타이어")),
        TireManufacturer("omni", "Omni United", "Omni United", WD + "Q17006322"),
        TireManufacturer("optimo", "옵티모", "Optimo", HANKOOK),
        TireManufacturer("orium", "Orium", "Orium", MICHELIN),
        TireManufacturer("petlas", "페트라스", "Petlas", "https://www.petlas.com/"),
        TireManufacturer("pirelli", "피렐리", "Pirelli", WD + "Q207770"),
        TireManufacturer("primewell", "Primewell", "Primewell", GITI),
        TireManufacturer("riken", "리켄", "Riken", MICHELIN),
        TireManufacturer("runway", "Runway", "Runway", GITI),
        TireManufacturer("sailun", "세일룬", "Sailun", "https://en.sailungroup.com/", listOf("사일룬", "Sailun Tire")),
        TireManufacturer("semperit", "Semperit", "Semperit", CONTINENTAL),
        TireManufacturer("sportiva", "Sportiva", "Sportiva", CONTINENTAL),
        TireManufacturer("sumitomo", "스미토모", "Sumitomo", WD + "Q1648148", listOf("Sumitomo Rubber Industries", "스미토모 고무")),
        TireManufacturer("taurus", "Taurus", "Taurus", MICHELIN),
        TireManufacturer("tigar", "Tigar", "Tigar", WD + "Q12747125", listOf("Tigar Tyres")),
        TireManufacturer("toyo", "토요", "Toyo", WD + "Q568406", listOf("토요타이어", "도요 고무", "Toyo Tire")),
        TireManufacturer("trayal", "Trayal", "Trayal", WD + "Q2450698", listOf("Trayal Corporation")),
        TireManufacturer("triangle", "트라이앵글", "Triangle", WD + "Q7840066", listOf("Triangle Group", "Triangle Tyre")),
        TireManufacturer("uniroyal", "유니로얄", "Uniroyal", CONTINENTAL, listOf("United States Rubber Company")),
        TireManufacturer("viking", "Viking", "Viking", CONTINENTAL),
        TireManufacturer("yazd", "Yazd Tire", "Yazd Tire", WD + "Q7345330"),
        TireManufacturer("yokohama", "요코하마", "Yokohama", WD + "Q1471756", listOf("요코하마 고무", "Yokohama Rubber Company")),
        TireManufacturer("zc", "ZC Rubber", "ZC Rubber", WD + "Q5648136", listOf("Zhongce Rubber", "Hangzhou Zhongce Rubber Company")),
    ).sortedBy { it.englishName.lowercase(Locale.ROOT) }

    fun search(query: String): List<TireManufacturer> {
        if (query.isBlank()) return entries
        val key = normalizeManufacturerName(query)
        if (key.isEmpty()) return emptyList()
        return entries.filter { entry -> entry.searchTerms.any { key in it } }.sortedBy { entry ->
            when {
                key in entry.searchTerms -> 0
                entry.searchTerms.any { it.startsWith(key) } -> 1
                else -> 2
            }
        }
    }

    /** 일부 글자만 일치한 결과는 선택 전까지 제조사로 확정하지 않는다. */
    fun find(name: String): TireManufacturer? {
        val key = normalizeManufacturerName(name).takeIf { it.isNotEmpty() } ?: return null
        return entries.firstOrNull { key in it.searchTerms }
    }
}

private fun normalizeManufacturerName(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
