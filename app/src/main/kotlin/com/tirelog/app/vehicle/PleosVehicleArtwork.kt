package com.tirelog.app.vehicle

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.util.Log
import com.tirelog.core.model.WheelPosition

/**
 * 설치된 Pleos DrivingView의 TPMS 탑뷰를 그대로 읽는다. 차종별 3D 모델 API는 아니다.
 * 리소스를 앱에 복제하거나 숫자 ID를 고정하지 않는다. Pleos 업데이트로 이름이 바뀌거나
 * 패키지가 없는 기기에서는 null을 반환해 기본 도형으로 표시한다.
 */
class PleosVehicleArtwork private constructor(val quadrants: Map<WheelPosition, Drawable>) {
    companion object {
        const val PACKAGE_NAME = "ai.umos.drivingview"
        private val names = mapOf(
            WheelPosition.FRONT_LEFT to "ivi_dv_utility_tpms_detail_img_part_front_left",
            WheelPosition.FRONT_RIGHT to "ivi_dv_utility_tpms_detail_img_part_front_right",
            WheelPosition.REAR_LEFT to "ivi_dv_utility_tpms_detail_img_part_rear_left",
            WheelPosition.REAR_RIGHT to "ivi_dv_utility_tpms_detail_img_part_rear_right",
        )

        fun load(context: Context, darkTheme: Boolean = context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES): PleosVehicleArtwork? {
            return try {
                val packageContext = context.createPackageContext(PACKAGE_NAME, 0)
                // 밤 전용 도형은 밝은 표면에서 사라질 수 있어 앱 테마와 같은 리소스를 읽는다.
                val config = Configuration(context.resources.configuration).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        if (darkTheme) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                }
                val resources = packageContext.createConfigurationContext(config).resources
                val parts = names.mapValues { (_, name) ->
                    val id = resources.getIdentifier(name, "drawable", PACKAGE_NAME)
                    if (id == 0) return null
                    resources.getDrawable(id, null).mutate()
                }
                // 네 개의 정사각형 사분면을 합쳐야 한 대가 된다. 부분 로딩은 사용하지 않는다.
                val first = parts.values.first()
                if (first.intrinsicWidth <= 0 || first.intrinsicWidth != first.intrinsicHeight ||
                    parts.values.any { it.intrinsicWidth != first.intrinsicWidth || it.intrinsicHeight != first.intrinsicHeight }
                ) null else PleosVehicleArtwork(parts)
            } catch (_: PackageManager.NameNotFoundException) {
                null
            } catch (e: Resources.NotFoundException) {
                Log.w("PleosVehicleArtwork", "Pleos 탑뷰 리소스를 읽지 못했습니다", e)
                null
            } catch (e: SecurityException) {
                Log.w("PleosVehicleArtwork", "Pleos 탑뷰 리소스에 접근할 수 없습니다", e)
                null
            }
        }
    }
}
