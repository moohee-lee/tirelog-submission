package com.tirelog.app.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tirelog.app.BuildConfig
import com.tirelog.app.ui.common.AdaptiveColumns
import com.tirelog.app.ui.common.ActionRow
import com.tirelog.app.ui.common.ContentPanel
import com.tirelog.app.ui.common.BigButton
import com.tirelog.app.ui.common.ChoiceChips
import com.tirelog.app.ui.common.Muted
import com.tirelog.app.ui.common.ScreenFrame
import com.tirelog.app.ui.common.SectionTitle
import com.tirelog.app.ui.common.Stepper
import com.tirelog.app.ui.common.NumberSelector
import com.tirelog.app.ui.theme.TirelogColors
import com.tirelog.app.vehicle.Support
import com.tirelog.core.TimeLabels
import com.tirelog.core.Units
import com.tirelog.core.alert.AlertPlanner
import com.tirelog.core.catalog.VehicleCatalog
import com.tirelog.core.catalog.TireLifeGuides
import com.tirelog.core.model.TireSet
import com.tirelog.core.engine.TireLogEngine
import com.tirelog.core.model.AppSettings
import com.tirelog.core.model.RecommendedPressure
import com.tirelog.core.model.RecommendedSource
import com.tirelog.core.model.SavedShop
import com.tirelog.core.model.TireSetType
import com.tirelog.core.season.Region
import kotlinx.coroutines.launch

private val regionOptions: List<Pair<String, String>> =
    Region.entries.map { it.name to it.label } + ("" to "선택 안 함")

/** S6 설정. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit, toast: (String) -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var shops by remember { mutableStateOf<List<SavedShop>?>(null) }
    var loadingShops by remember { mutableStateOf(false) }
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    var showVehicleGuide by rememberSaveable { mutableStateOf(false) }
    var detectedId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(session) { detectedId = VehicleCatalog.suggest(vm.readIdentity())?.id }

    ScreenFrame("설정", onBack) {
        val s = settings ?: return@ScreenFrame Loading()
        AdaptiveColumns(first = {
        ContentPanel("공기압과 위치교환") {
        BigButton(if (showVehicleGuide) "차종 기준 접기" else "차종 기준 불러오기", { showVehicleGuide = !showVehicleGuide })
        if (showVehicleGuide) VehicleGuideFields(s.vehicleGuideId, s.vehicleTireSize, detectedId, s.pressureSupported) { guide, tire ->
            vm.updateSettings(onDone = { toast("확인한 차종 기준을 저장했습니다") }) {
                it.copy(vehicleGuideId = guide.id, vehicleTireSize = tire.size,
                    recommended = if (it.pressureSupported) RecommendedPressure(tire.frontKpa, tire.rearKpa, RecommendedSource.VEHICLE_DEFAULT) else it.recommended,
                    rotationIntervalKm = guide.rotationIntervalKm)
            }
        }
        if (s.pressureSupported) {
            SectionTitle("권장 공기압")
            var front by rememberSaveable(s.recommended) { mutableIntStateOf(s.recommended?.frontKpa?.let(Units::psiRounded) ?: 36) }
            var rear by rememberSaveable(s.recommended) { mutableIntStateOf(s.recommended?.rearKpa?.let(Units::psiRounded) ?: 36) }
            Stepper("앞 권장 공기압", "${front}psi", { front -= 1 }, { front += 1 }, minusEnabled = front > 1)
            Stepper("뒤 권장 공기압", "${rear}psi", { rear -= 1 }, { rear += 1 }, minusEnabled = rear > 1)
            Muted("운전석 문 라벨 값입니다. 타이어 옆면의 최대 압력과 다릅니다. 지금 출처: ${s.recommended?.source?.label ?: "미확정"}")
            BigButton(
                "라벨 값으로 저장",
                {
                    vm.updateSettings(onDone = { toast("권장 공기압을 저장했습니다") }) {
                        it.copy(
                            recommended = RecommendedPressure(
                                Units.psiToKpa(front.toDouble()),
                                Units.psiToKpa(rear.toDouble()),
                                RecommendedSource.LABEL_CONFIRMED,
                            ),
                        )
                    }
                },
                primary = true,
            )
        }

        SectionTitle("위치교환 주기")
        Stepper(
            "주기",
            "${AlertPlanner.formatKm(s.rotationIntervalKm.toDouble())}km",
            { vm.updateSettings { it.copy(rotationIntervalKm = (it.rotationIntervalKm - 1_000).coerceAtLeast(5_000)) } },
            { vm.updateSettings { it.copy(rotationIntervalKm = (it.rotationIntervalKm + 1_000).coerceAtMost(20_000)) } },
            minusEnabled = s.rotationIntervalKm > 5_000,
            plusEnabled = s.rotationIntervalKm < 20_000,
        )

        }
        }, second = {
        ContentPanel("지역과 타이어점") {
        SectionTitle("지역(시즌 교체 알림)")
        Muted("위치 정보를 쓰지 않습니다. 평년 기온(기상청 1991~2020)을 볼 지역을 고르세요.")
        ChoiceChips(regionOptions, setOf(s.regionCode ?: ""), onToggle = { code ->
            vm.updateSettings { it.copy(regionCode = code.ifEmpty { null }) }
        })

        SectionTitle("내 타이어점")
        Text(
            s.favoriteShop?.let { "${it.name}${it.address?.let { a -> " · $a" } ?: ""}" }
                ?: "없음 · 길안내 때 내비의 \"타이어\" 검색 화면을 엽니다",
            style = MaterialTheme.typography.bodyLarge,
        )
        ActionRow {
            BigButton("내비 최근 목적지에서 고르기", {
                loadingShops = true
                scope.launch {
                    try {
                        shops = vm.shopCandidates()
                        if (shops == null) toast("최근 목적지를 불러오지 못했습니다. 내비게이션 연결을 확인해 주세요.")
                    } finally {
                        loadingShops = false
                    }
                }
            }, enabled = !loadingShops)
            if (s.favoriteShop != null) BigButton("지우기", { vm.saveShop(null) })
        }
        shops?.let { list ->
            if (list.isEmpty()) Muted("최근 목적지·즐겨찾기가 없습니다.")
            list.take(10).forEach { shop ->
                BigButton(
                    "${shop.name}${shop.address?.let { " · $it" } ?: ""}",
                    {
                        vm.saveShop(shop)
                        shops = null
                    },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
            }
        }

        }
        })
        SectionTitle("데이터")
        val demo = session?.demo == true
        Text(if (demo) "지금: 시연 모드(시연 데이터, 실차 기록과 분리)" else "지금: 실차 데이터", style = MaterialTheme.typography.bodyLarge)
        ActionRow {
            if (demo) {
                BigButton("시연 패널", { vm.open(Dest.Demo) }, primary = true)
                BigButton("실차 데이터로 전환", { vm.switchMode(false) })
            } else {
                BigButton("시연 모드로 전환", {
                    vm.switchMode(true)
                    vm.open(Dest.Demo)
                })
            }
            if (!confirmReset) BigButton("모든 기록 삭제", { confirmReset = true })
        }
        if (confirmReset) {
            Text("${if (demo) "시연" else "실차"} 기록(세트·측정·작업 이력·설정)을 지웁니다. 다른 모드의 기록은 남습니다. 되돌릴 수 없습니다.", color = TirelogColors.Red, style = MaterialTheme.typography.bodyLarge)
            ActionRow {
                BigButton("지우기", { vm.resetAll { confirmReset = false; toast("기록을 지웠습니다"); vm.stack.clear() } }, primary = true)
                BigButton("취소", { confirmReset = false })
            }
        }

        SectionTitle("정보")
        Muted("tirelog ${BuildConfig.VERSION_NAME} · 모든 기록은 이 차 안(로컬 DB)에만 저장하고 서버로 보내지 않습니다.")
        Muted("이 앱은 순정 TPMS 경고와 정비사 점검을 대신하지 않습니다.")
    }
}

/** 첫 실행은 타이어 공통 정보와 권장 공기압 확인만 받는다. */
@Composable
fun OnboardingScreen(vm: MainViewModel, onBack: () -> Unit, onDone: () -> Unit) {
    val capabilities by vm.capabilities.collectAsStateWithLifecycle()
    val checking by vm.checkingCapabilities.collectAsStateWithLifecycle()
    val form = rememberTireForm("onboarding")
    var vin by rememberSaveable { mutableStateOf<String?>(null) }
    var front by rememberSaveable { mutableStateOf("") }
    var rear by rememberSaveable { mutableStateOf("") }
    var pressureMode by rememberSaveable { mutableStateOf("later") }
    var pressureTouched by rememberSaveable { mutableStateOf(false) }
    var source by rememberSaveable { mutableStateOf(RecommendedSource.USER_INPUT) }
    var rotationInterval by rememberSaveable { mutableIntStateOf(AppSettings.DEFAULT_ROTATION_INTERVAL_KM) }
    LaunchedEffect(capabilities?.connected) {
        vin = vm.readVin()
        VehicleCatalog.lookup(vin)?.let { defaults ->
            if (!pressureTouched) {
                front = Units.psiRounded(defaults.frontKpa).toString()
                rear = Units.psiRounded(defaults.rearKpa).toString()
                pressureMode = "input"
                source = RecommendedSource.VEHICLE_DEFAULT
                rotationInterval = defaults.rotationIntervalKm
            }
        }
    }
    val pressureSupported = capabilities?.pressure != Support.UNSUPPORTED
    val frontValue = front.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 && it <= 100 }
    val rearValue = rear.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 && it <= 100 }
    val pressureReady = !pressureSupported || pressureMode == "later" || (frontValue != null && rearValue != null)
    TireRegistrationFrame("내 타이어 등록", onBack, footer = {
        BigButton("등록하고 시작", {
            val input = form.value()
            vm.completeOnboarding(TireLogEngine.OnboardingInput(
                recommended = if (pressureSupported && pressureMode == "input") RecommendedPressure(
                    Units.psiToKpa(requireNotNull(frontValue)),
                    Units.psiToKpa(requireNotNull(rearValue)), source) else null,
                setType = input.type, setSize = input.size, setDot = input.dot,
                initialKm = input.initialKm, initialKmKnown = input.initialKmKnown,
                firstUsedEpochDay = input.firstUsedEpochDay, newUntilMounted = input.newUntilMounted,
                lifeGuideId = input.lifeGuideId, manufacturer = input.manufacturer, tireModel = input.tireModel,
                inspectionKm = input.inspectionKm, inspectionMonths = input.inspectionMonths,
                vin = vin, pressureSupported = pressureSupported,
                odometerSupported = capabilities?.odometer != Support.UNSUPPORTED,
                pressurePositions = capabilities?.pressurePositions,
                rotationIntervalKm = rotationInterval,
            ), onDone)
        }, primary = true, enabled = form.valid(vm.today()) && pressureReady, modifier = Modifier.fillMaxWidth())
    }) {
        Muted("네 바퀴의 공통 정보를 한 번만 입력하세요. 다른 타이어는 등록 후 개별 수정할 수 있습니다.")
        TireFormFields(form, vm.today())
        if (pressureSupported) {
            ContentPanel("차량 권장 공기압") {
            ChoiceChips(listOf("later" to "나중에 확인", "input" to "권장값 입력"), setOf(pressureMode), onToggle = {
                pressureMode = it; pressureTouched = true
            })
            if (pressureMode == "input") {
                AdaptiveColumns(first = {
                NumberSelector("앞 권장 공기압", front, { front = it; pressureTouched = true; source = RecommendedSource.USER_INPUT },
                    1..100, 1, listOf(32, 34, 36, 38, 40), "psi", 36)
                }, second = {
                NumberSelector("뒤 권장 공기압", rear, { rear = it; pressureTouched = true; source = RecommendedSource.USER_INPUT },
                    1..100, 1, listOf(32, 34, 36, 38, 40), "psi", 36)
                })
                Muted("출처: ${source.label} · 운전석 문 라벨 또는 차량 설명서의 냉간 권장값을 확인하세요.")
            } else Muted("권장값을 확인하기 전에는 보충 여부 판단을 보류합니다.")
            }
        }
        capabilities?.message?.let { Muted(it) }
        if (capabilities?.pending != false) {
            Muted("차량 데이터가 연결되면 측정을 시작합니다. 타이어 정보는 먼저 등록할 수 있습니다.")
            BigButton(if (checking) "확인 중…" else "다시 확인", vm::recheckCapabilities, enabled = !checking)
        }

    }
}

private fun Support.mark() = when (this) {
    Support.SUPPORTED -> "✓"
    Support.UNSUPPORTED -> "✕"
    Support.UNKNOWN -> "?"
}

/** 시연 패널. 시연 데이터 구간임을 화면에 밝힌다. */
@Composable
fun DemoScreen(vm: MainViewModel, onBack: () -> Unit) {
    val session by vm.session.collectAsStateWithLifecycle()
    val director = session?.director
    ScreenFrame("시연 패널", onBack) {
        if (director == null) {
            Muted("시연 모드가 아닙니다.")
            BigButton("시연 모드로 전환", { vm.switchMode(true) }, primary = true)
            return@ScreenFrame
        }
        val running by director.running.collectAsStateWithLifecycle()
        val done by director.done.collectAsStateWithLifecycle()
        val live by session!!.gateway.live.collectAsStateWithLifecycle()
        Surface(shape = RoundedCornerShape(14.dp), color = TirelogColors.Amber.copy(alpha = 0.15f)) {
            Text(
                "시연 데이터 구간 · 에뮬레이터는 바퀴별 공기압·외기온도를 넣을 수 없어(VHAL 미지원) 시연 시나리오 값을 재생합니다. " +
                    "판단·알림은 실제 앱 로직이 합니다.",
                color = TirelogColors.Amber,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(16.dp),
            )
        }
        val now = session!!.time.now()
        val zone = session!!.engine.zone
        Spacer(Modifier.height(8.dp))
        Muted(
            "시연 시각 ${TimeLabels.monthDay(now, zone)} ${java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalTime().withNano(0).toString().take(5)} · " +
                "ODO ${live.odoKm?.let { AlertPlanner.formatKm(it) } ?: "-"}km · " +
                "${if (live.ignitionOn == false) "시동 꺼짐" else if (live.restricted) "주행 중" else "정차(P)"}",
        )
        SectionTitle("시나리오")
        director.steps.forEach { step ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text((if (step.id in done) "✓ " else "") + step.title, style = MaterialTheme.typography.titleMedium)
                    Muted(step.description)
                }
                BigButton(
                    if (running == step.id) "진행 중…" else "실행",
                    { director.run(step.id) },
                    primary = step.id !in done,
                    enabled = running == null,
                )
            }
        }
        SectionTitle("직접 조작")
        ActionRow {
            BigButton("시동 켜기", { director.setIgnition(true) })
            BigButton("시동 끄기", { director.setIgnition(false) })
            BigButton("짧게 주행 +10km", { director.shortDrive(10.0) }, enabled = running == null)
            BigButton("다음 날 아침", { director.nextMorning() }, enabled = running == null)
        }
        Muted("주행 중에는 주행 규제가 걸려 시동 카드 한 줄 화면으로 바뀝니다. 정차하면 시동 카드에서 시연 패널을 다시 열 수 있습니다.")
    }
}
