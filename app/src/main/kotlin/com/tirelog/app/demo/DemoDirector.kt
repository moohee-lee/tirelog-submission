package com.tirelog.app.demo

import com.tirelog.app.vehicle.DemoClock
import com.tirelog.app.vehicle.DemoVehicleGateway
import com.tirelog.core.catalog.VehicleCatalog
import com.tirelog.core.demo.DemoStoryline
import com.tirelog.core.drive.GearState
import com.tirelog.core.engine.TireLogEngine
import com.tirelog.core.model.RecommendedPressure
import com.tirelog.core.model.RecommendedSource
import com.tirelog.core.model.TireSetType
import com.tirelog.core.model.WheelPosition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.max

/**
 * 시연 시나리오(유저 시나리오 5장)를 단계별로 재생한다.
 * 차량 신호(기어·시동·주행 규제·ODO·공기압·외기온도)만 바꾸고, 판단과 알림은 실제 수집기·엔진이 한다.
 */
class DemoDirector(
    private val engine: TireLogEngine,
    private val vehicle: DemoVehicleGateway,
    private val clock: DemoClock,
    private val scope: CoroutineScope,
) {
    data class Step(val id: String, val title: String, val description: String)

    val steps = listOf(
        Step("prepare", "시연 준비", "기록을 지우고 시연 차량(권장 36psi, 여름 세트)으로 설정 · 10월 20℃ 기준 측정"),
        Step("day1", DemoStoryline.days[0].title, "−10℃ · 네 바퀴 31psi → 기온 영향 + 보충 필요"),
        Step("day2", DemoStoryline.days[1].title, "보충 뒤 36psi → \"공기를 보충하셨나요?\""),
        Step("day3", DemoStoryline.days[2].title, "앞오른쪽만 보정 후 감소(1회) · 시즌 재알림★"),
        Step("day4", DemoStoryline.days[3].title, "앞오른쪽만 보정 후 감소(2회) · 알림 없음"),
        Step("day5", DemoStoryline.days[4].title, "3회 연속 → 누설 의심 + 타이어점 길안내"),
        Step("shop", "5일차 저녁 · 정비소 도착", "증상 카드★를 보여 주고 수리·위치교환을 기록하세요"),
        Step("day6", DemoStoryline.days[5].title, "새 기준 측정 → 작업 뒤 50km에서 너트 점검"),
        Step("setVisit", "다음 주 · 세트 교체", "겨울 세트(DOT 4220)를 등록하고 세트 교체를 기록하세요"),
        Step("weekLater", "일주일 뒤", "+280km · 세트별 주행거리와 제조 6년 알림"),
    )

    /** 출발 순간의 공기압(냉간). 며칠 세워 둔 뒤 출발할 때 쓴다. */
    private var lastColdKpa: Map<WheelPosition, Double> = DemoStoryline.baseline.kpa

    private val _running = MutableStateFlow<String?>(null)
    val running: StateFlow<String?> = _running.asStateFlow()

    private val _done = MutableStateFlow<Set<String>>(emptySet())
    val done: StateFlow<Set<String>> = _done.asStateFlow()

    fun run(stepId: String) {
        if (_running.value != null) return
        scope.launch {
            _running.value = stepId
            try {
                when (stepId) {
                    "prepare" -> prepare()
                    "day1" -> day(0)
                    "day2" -> day(1)
                    "day3" -> day(2)
                    "day4" -> day(3)
                    "day5" -> day(4)
                    "shop" -> drive(to = DemoStoryline.SHOP_ODO_KM, startAt = at(DemoStoryline.days[4].date, 18, 10), minutes = 20)
                    "day6" -> day(5, arrivalKm = 45.0)
                    "setVisit" -> drive(
                        to = DemoStoryline.SET_CHANGE_ODO_KM,
                        startAt = at(DemoStoryline.days[5].date.plusDays(2), 9, 30),
                        minutes = 30,
                    )
                    "weekLater" -> drive(
                        to = (vehicle.live.value.odoKm ?: DemoStoryline.SET_CHANGE_ODO_KM) + 280.0,
                        startAt = at(DemoStoryline.days[5].date.plusDays(9), 8, 0),
                        minutes = 40,
                    )
                }
                _done.value = _done.value + stepId
            } finally {
                _running.value = null
            }
        }
    }

    // region 수동 조작

    fun setIgnition(on: Boolean) = vehicle.update { it.copy(ignitionOn = on, restricted = if (on) it.restricted else false) }

    /** 출발 → 측정 → 정차를 한 번에. 주행 중에는 조작할 수 없어서 정차까지 자동으로 한다. */
    fun shortDrive(km: Double) {
        if (_running.value != null) return
        scope.launch {
            _running.value = "short"
            try {
                drive(to = (vehicle.live.value.odoKm ?: 0.0) + km, startAt = clock.now() + MINUTE, minutes = 15)
            } finally {
                _running.value = null
            }
        }
    }

    fun nextMorning() = scope.launch {
        val today = java.time.Instant.ofEpochMilli(clock.now()).atZone(DemoStoryline.ZONE).toLocalDate()
        eveningPark(today.plusDays(1), vehicle.live.value.odoKm ?: 0.0)
        clock.setTo(at(today.plusDays(1), 7, 50))
        vehicle.update { it.copy(ignitionOn = true, gear = GearState.PARK, restricted = false) }
    }

    // endregion

    private suspend fun prepare() {
        engine.resetAll()
        _done.value = emptySet()
        val base = DemoStoryline.baseline
        clock.setTo(at(base.date.minusDays(1), 18, 0))
        vehicle.update {
            it.copy(
                pressuresKpa = emptyMap(), pressuresAt = null, odoKm = DemoStoryline.INSTALL_ODO_KM,
                gear = GearState.PARK, ignitionOn = true, restricted = false, tempC = base.tempC,
            )
        }
        engine.completeOnboarding(
            TireLogEngine.OnboardingInput(
                recommended = RecommendedPressure(DemoStoryline.RECOMMENDED_KPA, DemoStoryline.RECOMMENDED_KPA, RecommendedSource.LABEL_CONFIRMED),
                setType = TireSetType.SUMMER,
                setSize = DemoStoryline.TIRE_SIZE,
                setDot = DemoStoryline.SUMMER_DOT,
                vin = VehicleCatalog.DEMO_VIN,
            ),
            now = clock.now(),
            odoKm = DemoStoryline.INSTALL_ODO_KM,
        )
        coldDay(base, arrivalKm = 20.0, showStops = false)
    }

    private suspend fun day(index: Int, arrivalKm: Double = 20.0) {
        coldDay(DemoStoryline.days[index], arrivalKm, showStops = true)
    }

    /** 전날 저녁 주차 → 아침 시동 → 출발(1~2분 뒤 냉간 측정) → 도착(정차 알림). */
    private suspend fun coldDay(day: DemoStoryline.ColdDay, arrivalKm: Double, showStops: Boolean) {
        eveningPark(day.date, day.departOdoKm)
        lastColdKpa = day.kpa

        clock.setTo(at(day.date, DemoStoryline.DEPART_TIME.hour, DemoStoryline.DEPART_TIME.minute))
        vehicle.update {
            it.copy(
                ignitionOn = true, gear = GearState.PARK, restricted = false, tempC = day.tempC,
                pressuresKpa = day.kpa, pressuresAt = clock.now(),
            )
        }
        pause(if (showStops) 2_500 else 600)

        clock.setTo(at(day.date, 7, 51))
        vehicle.update { it.copy(gear = GearState.DRIVE, restricted = true) }
        pause(400)
        clock.setTo(at(day.date, 7, 52))
        vehicle.update { it.copy(odoKm = day.departOdoKm + 0.9) }
        pause(MEASURE_WAIT)
        vehicle.update { v -> v.copy(pressuresKpa = day.kpa.mapValues { it.value * HOT_FACTOR }, pressuresAt = clock.now()) }
        pause(if (showStops) 2_500 else 300)

        clock.setTo(at(day.date, 8, 30))
        vehicle.update { it.copy(odoKm = day.departOdoKm + arrivalKm, gear = GearState.PARK, restricted = false) }
        pause(600)
    }

    /** 전날 저녁에 주차해 둔 상태를 만든다. 시동을 끈 채라 알림은 띄우지 않는다. */
    private suspend fun eveningPark(date: LocalDate, parkOdoKm: Double) {
        val currentOdo = vehicle.live.value.odoKm ?: parkOdoKm
        val start = maxOf(clock.now() + MINUTE, at(date.minusDays(1), 18, 20))
        clock.setTo(start)
        vehicle.update { it.copy(pressuresKpa = emptyMap(), pressuresAt = null, ignitionOn = true, gear = GearState.DRIVE, restricted = true) }
        pause(300)
        clock.setTo(start + 20 * MINUTE)
        vehicle.update {
            it.copy(odoKm = max(parkOdoKm, currentOdo), gear = GearState.PARK, restricted = false, ignitionOn = false)
        }
        pause(300)
    }

    /** 정차 상태에서 출발해 목적지에 세운다(시동 켠 채 도착 → 정차 알림). */
    private suspend fun drive(to: Double, startAt: Long, minutes: Int) {
        clock.setTo(maxOf(startAt, clock.now() + MINUTE))
        vehicle.update {
            it.copy(ignitionOn = true, gear = GearState.DRIVE, restricted = true, pressuresKpa = lastColdKpa, pressuresAt = clock.now())
        }
        pause(MEASURE_WAIT)
        vehicle.update { v -> v.copy(pressuresKpa = lastColdKpa.mapValues { it.value * HOT_FACTOR }, pressuresAt = clock.now()) }
        pause(1_500)
        clock.setTo(clock.now() + minutes * MINUTE)
        vehicle.update { it.copy(odoKm = max(to, it.odoKm ?: to), gear = GearState.PARK, restricted = false) }
        pause(600)
    }

    private suspend fun pause(millis: Long) = delay(millis)

    private fun at(date: LocalDate, hour: Int, minute: Int = 0): Long =
        date.atTime(LocalTime.of(hour, minute)).atZone(DemoStoryline.ZONE).toInstant().toEpochMilli()

    private companion object {
        const val MINUTE = 60_000L
        const val HOT_FACTOR = 1.06

        /** 수집기의 시연용 측정 지연(1.5초)보다 조금 길게 기다린다. */
        const val MEASURE_WAIT = 2_000L
    }
}
