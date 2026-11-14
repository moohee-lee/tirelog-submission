package com.tirelog.app.ui.main

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.tirelog.app.ui.common.*
import com.tirelog.core.Units
import com.tirelog.core.catalog.VehicleCatalog
import com.tirelog.core.catalog.VehicleGuide
import com.tirelog.core.catalog.VehicleTireSpec

/** 사용자가 라벨과 사양을 대조한 뒤 한 번에 적용한다. 조회·후보 제안만으로 저장값을 바꾸지 않는다. */
@Composable
internal fun VehicleGuideFields(initialId: String?, initialSize: String?, detectedId: String? = null,
    pressureSupported: Boolean = true,
    onApply: (VehicleGuide, VehicleTireSpec) -> Unit) {
    var guideId by rememberSaveable(initialId, detectedId) { mutableStateOf(initialId ?: detectedId) }
    var size by rememberSaveable(guideId, initialSize) { mutableStateOf(initialSize) }
    val guide = VehicleCatalog.guide(guideId)
    SectionTitle("차종 기준 불러오기")
    SelectionMenu("차종·연식", guide?.label ?: "차종 선택", VehicleCatalog.guides.map { it.id to it.label }) { guideId = it; size = null }
    if (detectedId != null && guideId == detectedId) Muted("차량에서 받은 모델·연식과 일치하는 후보입니다. 국내 사양과 구동계를 확인하세요.")
    if (guide != null) {
        SelectionMenu("차량 타이어 규격", guide.tire(size)?.size ?: "규격 선택", guide.tires.map { it.size to it.size }) { size = it }
        val tire = guide.tire(size)
        if (tire != null) {
            if (pressureSupported) Muted("앞 ${Units.psiRounded(tire.frontKpa)}psi (${tire.frontKpa.toInt()}kPa) · 뒤 ${Units.psiRounded(tire.rearKpa)}psi (${tire.rearKpa.toInt()}kPa)")
            Muted("위치교환 ${guide.rotationIntervalKm / 1_000},000km · 현대자동차 국내 설명서 · ${VehicleCatalog.VERIFIED_ON} 확인")
        }
        Muted("운전석 문 라벨·설명서의 차종, 연식, 규격과 대조한 뒤 적용하세요.")
        BigButton("라벨과 확인한 기준 적용", { if (tire != null) onApply(guide, tire) }, enabled = tire != null)
    } else Muted("목록에 없거나 사양이 다르면 권장값과 위치교환 주기를 직접 설정하세요.")
}
