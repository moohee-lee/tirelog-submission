package com.tirelog.app.vehicle

import android.car.Car
import android.car.CarInfoManager
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.tirelog.core.drive.GearState

/**
 * AAOS Car API로 읽는 값: 기어(GEAR_SELECTION), 시동(IGNITION_STATE), 외기온도(ENV_OUTSIDE_TEMPERATURE),
 * 주행 규제(CarUxRestrictions). 자동차가 아닌 기기에서는 만들지 않는다.
 */
class CarSignals private constructor(
    private val context: Context,
    private val onGear: (GearState) -> Unit,
    private val onIgnition: (Boolean?) -> Unit,
    private val onRestricted: (Boolean) -> Unit,
) {
    private var car: Car? = null
    private var properties: CarPropertyManager? = null
    private var ux: CarUxRestrictionsManager? = null

    private val propertyCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            val raw = value.value as? Int ?: return
            when (value.propertyId) {
                VehiclePropertyIds.GEAR_SELECTION -> onGear(gearOf(raw))
                VehiclePropertyIds.IGNITION_STATE -> onIgnition(raw >= IGNITION_ON)
            }
        }

        override fun onErrorEvent(propertyId: Int, areaId: Int) = Unit
    }

    @Suppress("DEPRECATION") // subscribePropertyEvents는 API 35부터라 minSdk 29에서 registerCallback을 쓴다.
    fun start() {
        try {
            val connected = Car.createCar(context) ?: return
            car = connected
            properties = connected.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
            ux = connected.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
            ux?.let { manager ->
                onRestricted(manager.currentCarUxRestrictions?.isRequiresDistractionOptimization == true)
                manager.registerListener { restrictions -> onRestricted(restrictions.isRequiresDistractionOptimization) }
            }
            properties?.let { manager ->
                listOf(VehiclePropertyIds.GEAR_SELECTION, VehiclePropertyIds.IGNITION_STATE).forEach { id ->
                    runCatching { manager.registerCallback(propertyCallback, id, CarPropertyManager.SENSOR_RATE_ONCHANGE) }
                        .onFailure { Log.w(TAG, "속성 구독 실패: $id", it) }
                }
                runCatching { onGear(gearOf(manager.getIntProperty(VehiclePropertyIds.GEAR_SELECTION, 0))) }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Car 서비스 연결 실패", t)
        }
    }

    fun readOutsideTempC(): Double? = try {
        properties?.getFloatProperty(VehiclePropertyIds.ENV_OUTSIDE_TEMPERATURE, 0)?.toDouble()
    } catch (t: Throwable) {
        Log.w(TAG, "ENV_OUTSIDE_TEMPERATURE 읽기 실패", t)
        null
    }

    fun readIdentity(): com.tirelog.core.catalog.VehicleIdentity? = runCatching {
        val info = car?.getCarManager(Car.INFO_SERVICE) as? CarInfoManager ?: return null
        com.tirelog.core.catalog.VehicleIdentity(info.manufacturer, info.model, info.modelYearInInteger)
            .takeIf { it.manufacturer.isNotBlank() && it.model.isNotBlank() && it.year > 0 }
    }.getOrNull()

    @Suppress("DEPRECATION")
    fun stop() {
        runCatching { ux?.unregisterListener() }
        runCatching { properties?.unregisterCallback(propertyCallback) }
        runCatching { car?.disconnect() }
        car = null
    }

    private fun gearOf(raw: Int): GearState = when (raw) {
        VehicleGear.GEAR_PARK -> GearState.PARK
        VehicleGear.GEAR_UNKNOWN -> GearState.UNKNOWN
        else -> GearState.DRIVE
    }

    companion object {
        private const val TAG = "CarSignals"

        /** VehicleIgnitionState: LOCK 1, OFF 2, ACC 3, ON 4, START 5 */
        private const val IGNITION_ON = 4

        fun createOrNull(
            context: Context,
            onGear: (GearState) -> Unit,
            onIgnition: (Boolean?) -> Unit,
            onRestricted: (Boolean) -> Unit,
        ): CarSignals? {
            if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) return null
            return try {
                CarSignals(context.applicationContext, onGear, onIgnition, onRestricted)
            } catch (t: Throwable) {
                null
            }
        }
    }
}
