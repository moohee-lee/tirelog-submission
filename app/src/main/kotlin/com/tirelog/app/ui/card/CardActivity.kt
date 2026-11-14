package com.tirelog.app.ui.card

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.tirelog.app.ui.common.SelectionMenu
import com.tirelog.app.ui.main.displayName
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tirelog.app.collect.CollectorService
import com.tirelog.app.ui.common.AdaptiveColumns
import com.tirelog.app.ui.common.ContentPanel
import com.tirelog.app.ui.common.AlertPopup
import com.tirelog.app.ui.common.BigButton
import com.tirelog.app.ui.common.Muted
import com.tirelog.app.ui.common.PendingList
import com.tirelog.app.ui.common.VehiclePressureDiagram
import com.tirelog.app.ui.main.MainActivity
import com.tirelog.app.ui.theme.TirelogColors
import com.tirelog.app.ui.theme.TirelogTheme
import com.tirelog.core.engine.DashboardSnapshot
import com.tirelog.core.engine.RefillQuestionView

/**
 * 시동 카드(S1). DO를 선언한 유일한 화면이다.
 * 주행 규제 중에는 큰 공기압·상태만 보여 주고 버튼·팝업을 숨긴다. 정차(P단, 조작 제한 해제) 때만 알림을 띄운다.
 */
class CardActivity : ComponentActivity() {
    private val vm: CardViewModel by viewModels()

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CollectorService.start(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            TirelogTheme {
                CardScreen(
                    vm = vm,
                    open = ::open,
                    toast = { Toast.makeText(this, it, Toast.LENGTH_LONG).show() },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        vm.onOpened()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        vm.onOpened()
    }

    private fun open(dest: String) {
        startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_DEST, dest))
    }
}

@Composable
private fun CardScreen(vm: CardViewModel, open: (String) -> Unit, toast: (String) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val popup by vm.popup.collectAsStateWithLifecycle()
    val checking by vm.checking.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize().background(TirelogColors.Background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        when (val s = state) {
            CardUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            is CardUiState.Ready -> {
                if (s.restricted) {
                    if (s.snapshot.onboardingDone) DrivingOverview(s.snapshot) else OneLine("정차 후 초기 설정을 해 주세요")
                } else if (!s.snapshot.onboardingDone) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { OnboardingPrompt(s, open, onDemo = {
                        vm.switchToDemo()
                        open("demo")
                    }) }
                } else {
                    FullCard(s, open, checking = checking, onRecheck = vm::recheck, onAnswerRefill = vm::answerRefill)
                    popup?.let { alert ->
                        AlertPopup(alert, s.snapshot.wheels) { action -> vm.onAlertAction(alert, action, open, toast) }
                    }
                }
            }
        }
    }
}

/** 주행 중 한 줄 화면. 조작 요소가 없다. */
@Composable
private fun OneLine(text: String) {
    Box(Modifier.fillMaxSize().padding(horizontal = 32.dp), contentAlignment = Alignment.CenterStart) {
        Text(text, fontSize = 34.sp, color = TirelogColors.OnSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OnboardingPrompt(s: CardUiState.Ready, open: (String) -> Unit, onDemo: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("tirelog", style = MaterialTheme.typography.headlineMedium)
        Text("차가 재는 공기압·외기온도·주행거리를 기록해, 공기 보충과 타이어 점검 시기를 정차 중에 알려 드립니다.", style = MaterialTheme.typography.bodyLarge)
        val caps = s.capabilities
        if (caps != null && !caps.connected && !s.demo) {
            Muted("${caps.message ?: "차량 데이터를 받지 못했습니다"} · 에뮬레이터에서는 시연 모드로 볼 수 있습니다.")
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BigButton("내 타이어 등록", { open("onboarding") }, primary = true)
            if (!s.demo) BigButton("시연 모드로 보기", onDemo) else BigButton("시연 패널", { open("demo") })
        }
    }
}

/** 주행 중에는 같은 화면 영역에 읽기 전용 공기압을 크게 표시한다. */
@Composable
internal fun DrivingOverview(snap: DashboardSnapshot) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("tirelog", style = MaterialTheme.typography.headlineMedium)
        if (snap.pressureSupported) {
            VehiclePressureDiagram(snap.wheels, Modifier.fillMaxWidth().weight(1f),
                summary = true, large = true, fillHeight = true)
        } else Spacer(Modifier.weight(1f))
        Text(snap.headline, style = MaterialTheme.typography.titleLarge)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FullCard(s: CardUiState.Ready, open: (String) -> Unit, checking: Boolean, onRecheck: () -> Unit,
    onAnswerRefill: (Boolean) -> Unit) {
    val snap = s.snapshot
    BoxWithConstraints(Modifier.fillMaxSize().padding(24.dp)) {
        val wide = maxWidth / LocalDensity.current.fontScale >= 1200.dp && maxHeight >= 600.dp
        val status: @Composable () -> Unit = {
            HomeTireSelection(s, open)
            HomeMeasurementSummary(s)
            if (snap.pressureSupported) HomePressureHelp(snap, open)
            if (!s.demo && (s.capabilities?.pending == true || s.capabilities?.connected == false)) {
                Muted(s.capabilities.message ?: "차량 데이터를 기다리고 있습니다")
                if (s.capabilities.pending) BigButton(if (checking) "확인 중…" else "다시 확인", onRecheck, enabled = !checking)
            }
            if (snap.pending.isNotEmpty()) PendingList(snap.pending, limit = 1)
            snap.refillQuestion?.let { RefillQuestionCard(it, onAnswerRefill) }
            if (snap.pending.size > 1) BigButton("상태 및 남은 점검 보기", { open("detail") }, Modifier.fillMaxWidth())
        }
        val unsupported: @Composable () -> Unit = {
            ContentPanel("타이어 관리") {
                Text("이 차량은 공기압 수치를 제공하지 않습니다", style = MaterialTheme.typography.bodyLarge)
                Muted("타이어 기록과 위치교환·너트 점검은 계속 사용할 수 있습니다.")
                BigButton("타이어 상세 보기", { open("detail") }, Modifier.fillMaxWidth())
            }
        }
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("tirelog", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                if (s.demo) Badge("시연 데이터")
                BigButton("설정", { open("settings") })
            }
            if (wide) {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    if (snap.pressureSupported) VehiclePressureDiagram(snap.wheels,
                        Modifier.weight(1.8f).fillMaxHeight().testTag("home-pressure-panel"),
                        summary = true, large = true, fillHeight = true, onWheelClick = { open("tire_${it.name}") })
                    else Column(Modifier.weight(1.8f)) { unsupported() }
                    Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(24.dp)) { status() }
                }
            } else {
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    if (snap.pressureSupported) VehiclePressureDiagram(snap.wheels, Modifier.fillMaxWidth(),
                        summary = true, large = true, onWheelClick = { open("tire_${it.name}") })
                    else unsupported()
                    status()
                }
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val count = if (maxWidth / LocalDensity.current.fontScale >= 800.dp) 4 else if (maxWidth / LocalDensity.current.fontScale >= 300.dp) 2 else 1
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp), maxItemsInEachRow = count) {
                    BigButton("타이어 관리", { open("sets") }, Modifier.weight(1f).heightIn(min = 96.dp))
                    if (snap.pressureSupported) BigButton("추이 보기", { open("trend") }, Modifier.weight(1f).heightIn(min = 96.dp))
                    BigButton("타이어 교체", { open("replace") }, Modifier.weight(1f).heightIn(min = 96.dp))
                    BigButton("위치교환", { open("rotation") }, Modifier.weight(1f).heightIn(min = 96.dp))
                }
            }
        }
    }
}

@Composable
private fun HomeTireSelection(s: CardUiState.Ready, open: (String) -> Unit) {
    if (s.registeredSets.isNotEmpty()) {
        SelectionMenu("조회할 타이어", "현재 장착 · ${s.snapshot.mountedSetLabel ?: "타이어"}",
            s.registeredSets.map { it.id to it.displayName() + if (it.retired) " · 폐기" else "" }) { open("set_$it") }
    }
}

@Composable
private fun HomeMeasurementSummary(s: CardUiState.Ready) {
    val snap = s.snapshot
    Text(if (snap.wheels.size == 4 && snap.wheels.all { it.state == com.tirelog.core.engine.WheelState.NORMAL })
        "최근 냉간 측정 · 보충 알림 없음" else snap.headline, style = MaterialTheme.typography.titleMedium)
    if (snap.pressureSupported) {
        val measured = snap.wheels.map { "${it.measuredLabel} · ${it.conditionLabel}" }.distinct()
        Muted(if (measured.size == 1) measured.single() else "측정 시각·냉간 여부는 바퀴별 상세에서 확인하세요.")
        snap.causeText?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
        Muted("최근 7일 중 측정 ${snap.measuredDaysLast7}일")
    }
}

@Composable
private fun HomePressureHelp(snap: DashboardSnapshot, open: (String) -> Unit) {
    Muted("권장값 출처 · ${snap.recommendedSourceLabel ?: "미설정"}")
    if (snap.needsRecommended) BigButton("권장 공기압 설정", { open("settings") })
    Muted("바퀴를 누르면 상세 정보와 누적 거리를 볼 수 있습니다.")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RefillQuestionCard(question: RefillQuestionView, onAnswer: (Boolean) -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = TirelogColors.SurfaceHigh) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(question.text, style = MaterialTheme.typography.titleMedium)
            Muted("냉간 값이 기온으로 설명되는 값보다 높아졌습니다.")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                BigButton("네, 보충했어요", { onAnswer(true) }, primary = true)
                BigButton("아니요", { onAnswer(false) })
            }
        }
    }
}

@Composable
private fun Badge(text: String) {
    Surface(shape = RoundedCornerShape(12.dp), color = TirelogColors.SurfaceHigh) {
        Text(text, color = TirelogColors.Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
    }
    Spacer(Modifier.size(4.dp))
}
