package com.tirelog.core

import com.tirelog.core.catalog.*
import kotlin.test.*

class VehicleCatalogTest {
    @Test fun `확인한 제조사 모델 연식만 후보로 제안한다`() {
        assertEquals("hyundai_dn8hev_2026_kr", VehicleCatalog.suggest(VehicleIdentity("Hyundai", "SONATA HYBRID", 2026))?.id)
        assertNull(VehicleCatalog.suggest(VehicleIdentity("Hyundai", "SONATA", 2026)))
        assertNull(VehicleCatalog.suggest(VehicleIdentity("Hyundai", "SONATA HYBRID", 2025)))
        assertNull(VehicleCatalog.suggest(VehicleIdentity("Other", "GN7", 2026)))
        assertNull(VehicleCatalog.lookup("KMH12345678901234"), "확인되지 않은 VIN 접두사로 압력을 추측하지 않는다")
    }
    @Test fun `공식 국내 설명서 기준은 규격별로 확인하고 선택한 규격만 적용한다`() {
        val grande = VehicleCatalog.guide("hyundai_gn7_2026_kr")!!
        assertEquals(230.0, grande.tire("245/45 R19")?.frontKpa)
        assertEquals(10_000, grande.rotationIntervalKm)
        assertNull(grande.tire("255/45R20"))
        assertEquals(240.0, VehicleCatalog.guide("hyundai_dn8hev_2026_kr")!!.tire("215/55R17")?.rearKpa)
        assertTrue(VehicleCatalog.guides.all { it.pressureSourceUrl.startsWith("https://ownersmanual.hyundai.com/") })
    }
}
