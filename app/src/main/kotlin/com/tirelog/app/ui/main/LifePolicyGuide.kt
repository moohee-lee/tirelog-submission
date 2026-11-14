package com.tirelog.app.ui.main

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import com.tirelog.app.ui.common.Muted
import com.tirelog.core.alert.AlertPlanner
import com.tirelog.core.catalog.TireLifeGuide
import com.tirelog.core.catalog.TireLifeGuides
import com.tirelog.core.model.TireSet

@Composable
internal fun LifePolicySourceNote(guide: TireLifeGuide, value: Int, distance: Boolean) {
    val recommended = if (distance) guide.recommendedKm else guide.recommendedMonths
    val fallback = if (distance) TireSet.DEFAULT_INSPECTION_KM else TireSet.DEFAULT_INSPECTION_MONTHS
    val source = TireLifeGuides.sourceLabel(value, recommended, fallback)
    Text("기준 출처 · $source", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
    Muted(TireLifeGuides.basis(guide, value, distance))
    if (source == "제조사 일반 안내") {
        Muted("${guide.sourceTitle} · ${TireLifeGuides.VERIFIED_ON} 확인 · 제품별 확정 수명은 아닙니다.")
    }
}

@Composable
internal fun LifePolicySummary(guide: TireLifeGuide, km: Int, months: Int) {
    Text("이 타이어로 ${AlertPlanner.formatKm(km.toDouble())}km 사용하면 점검 알림", style = MaterialTheme.typography.bodyLarge)
    LifePolicySourceNote(guide, km, distance = true)
    Text("처음 사용한 날부터 ${TireLifeGuides.periodLabel(months)} 후 점검 알림", style = MaterialTheme.typography.bodyLarge)
    LifePolicySourceNote(guide, months, distance = false)
}

@Composable
internal fun LifePolicyMeaning(distance: Boolean) {
    Muted(if (distance) "이 타이어의 누적 사용 거리입니다. 차량 계기판의 총주행거리나 매번 반복하는 점검 간격이 아닙니다."
        else "처음 장착해 사용한 날부터 셉니다. 중간에 보관한 기간도 포함하며 DOT 제조일 기준은 별도로 확인합니다.")
}
