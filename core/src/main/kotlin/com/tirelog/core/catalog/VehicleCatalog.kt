package com.tirelog.core.catalog

data class VehicleDefaults(
    val name: String,
    val frontKpa: Double,
    val rearKpa: Double,
    val rotationIntervalKm: Int,
    val tireSizes: List<String>,
)

data class VehicleIdentity(val manufacturer: String, val model: String, val year: Int)
data class VehicleTireSpec(val size: String, val frontKpa: Double, val rearKpa: Double)
data class VehicleGuide(
    val id: String, val label: String, val year: Int, val modelAliases: Set<String>,
    val tires: List<VehicleTireSpec>, val rotationIntervalKm: Int,
    val pressureSourceUrl: String, val rotationSourceUrl: String,
) {
    fun tire(size: String?) = tires.firstOrNull { normalizeSize(it.size) == normalizeSize(size.orEmpty()) }
    private fun normalizeSize(value: String) = value.uppercase().replace(" ", "")
}

/**
 * VIN으로 차종 기본값(권장 공기압·위치교환 주기·순정 사이즈)을 찾는다.
 * 실차 값은 제조사 자료로 채워야 하며, 지금은 시연 차량만 있다. 없으면 사용자가 라벨을 보고 입력한다.
 */
object VehicleCatalog {
    const val DEMO_VIN = "KMHTL00DEMO000001"
    const val VERIFIED_ON = "2026-10-31"

    /** 국내 사양의 공식 설명서로 확인한 조합만 제안한다. 같은 차종의 다른 연식·구동계로 일반화하지 않는다. */
    val guides = listOf(
        VehicleGuide("hyundai_gn7_2026_kr", "현대 그랜저 · 2026 · 가솔린/LPI · 국내", 2026,
            setOf("GN7", "GRANDEUR", "그랜저"),
            listOf("225/55R18", "245/45R19", "245/40R20").map { VehicleTireSpec(it, 230.0, 230.0) }, 10_000,
            "https://ownersmanual.hyundai.com/full_webhelp/GN7/2026/ko_KR/idfbf2c9f9b43.html",
            "https://ownersmanual.hyundai.com/full_webhelp/GN7/2026/ko_KR/id6b219dd157c.html"),
        VehicleGuide("hyundai_dn8hev_2026_kr", "현대 쏘나타 하이브리드 · 2026 · 국내", 2026,
            setOf("DN8HEV", "SONATA HYBRID", "쏘나타 하이브리드"),
            listOf("205/65R16", "215/55R17", "235/45R18").map { VehicleTireSpec(it, 240.0, 240.0) }, 10_000,
            "https://ownersmanual.hyundai.com/full_webhelp/DN8HEV/2026/ko_KR/idfbf2c9f9b43.html",
            "https://ownersmanual.hyundai.com/full_webhelp/DN8HEV/2026/ko_KR/id6b219dd157c.html"),
    )

    fun guide(id: String?) = guides.firstOrNull { it.id == id }

    /** VIN 접두사만으로 사양을 추측하지 않는다. 차량이 제공한 제조사·모델·연식이 일치하면 확인 후보를 제안한다. */
    fun suggest(identity: VehicleIdentity?): VehicleGuide? {
        if (identity == null || identity.manufacturer.trim().uppercase() !in setOf("HYUNDAI", "HYUNDAI MOTOR COMPANY", "현대", "현대자동차")) return null
        fun normalized(value: String) = value.uppercase().filter { it.isLetterOrDigit() }
        return guides.singleOrNull { guide -> guide.year == identity.year &&
            guide.modelAliases.any { normalized(it) == normalized(identity.model) } }
    }

    private val entries = mapOf(
        "KMHTL00DEMO" to VehicleDefaults(
            name = "시연 차량",
            frontKpa = 250.0,
            rearKpa = 250.0,
            rotationIntervalKm = 10_000,
            tireSizes = listOf("235/55R19", "255/45R20"),
        ),
    )

    val commonSizes = listOf(
        "205/55R16", "215/55R17", "225/55R17", "225/45R18", "235/55R18",
        "235/60R18", "235/55R19", "245/45R19", "255/45R20", "255/50R19",
    )

    fun lookup(vin: String?): VehicleDefaults? =
        vin?.let { v -> entries.entries.firstOrNull { v.startsWith(it.key) }?.value }
}
