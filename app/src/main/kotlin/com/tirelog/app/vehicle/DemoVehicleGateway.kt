package com.tirelog.app.vehicle

import android.content.SharedPreferences
import androidx.core.content.edit
import com.tirelog.core.TimeSource
import com.tirelog.core.catalog.VehicleCatalog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import com.tirelog.core.drive.GearState

/** 시연용 시계. 지정한 시각부터 실제 시간과 같은 속도로 흐른다. */
class DemoClock(private val prefs: SharedPreferences) : TimeSource {
    @Volatile private var offset = prefs.getLong(KEY, 0L)

    override fun now(): Long = System.currentTimeMillis() + offset

    fun setTo(millis: Long) {
        offset = millis - System.currentTimeMillis()
        prefs.edit { putLong(KEY, offset) }
    }

    private companion object {
        const val KEY = "demo_clock_offset"
    }
}

/**
 * 시연 모드의 차량. 에뮬레이터는 바퀴별 공기압·외기온도를 넣을 수 없어(기획서 10장) 시연 데이터로 대신한다.
 */
class DemoVehicleGateway : VehicleGateway {
    override val isDemo = true
    private val _live = MutableStateFlow(
        LiveVehicle(gear = GearState.PARK, ignitionOn = true, restricted = false),
    )
    override val live: StateFlow<LiveVehicle> = _live.asStateFlow()

    var pressureSupported: Boolean = true

    override suspend fun start() = checkCapabilities()

    override suspend fun checkCapabilities() = VehicleCapabilities(
        pressure = if (pressureSupported) Support.SUPPORTED else Support.UNSUPPORTED,
        odometer = Support.SUPPORTED,
    )

    override fun stop() = Unit

    override suspend fun readPressuresKpa() = _live.value.pressuresKpa

    override suspend fun readOdometerKm() = _live.value.odoKm

    override suspend fun readOutsideTempC() = _live.value.tempC

    override suspend fun readVin() = VehicleCatalog.DEMO_VIN

    fun update(transform: (LiveVehicle) -> LiveVehicle) = _live.update(transform)
}
