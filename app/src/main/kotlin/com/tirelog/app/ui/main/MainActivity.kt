package com.tirelog.app.ui.main

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tirelog.app.ui.card.CardActivity
import com.tirelog.app.ui.common.LocalActionsEnabled
import com.tirelog.app.ui.theme.TirelogColors
import com.tirelog.app.ui.theme.TirelogTheme

/**
 * 정차 중에만 쓰는 화면들(S2~S6). DO를 선언하지 않는다.
 * 주행 규제가 걸리면 시동 카드(한 줄)로 돌아간다.
 */
class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (vm.stack.isEmpty()) vm.open(destOf(intent.getStringExtra(EXTRA_DEST)))
        setContent {
            TirelogTheme {
                MainHost(
                    vm = vm,
                    finish = ::finish,
                    toast = { Toast.makeText(this, it, Toast.LENGTH_LONG).show() },
                    toCard = {
                        startActivity(Intent(this, CardActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                        finish()
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_DEST)?.let { vm.open(destOf(it)) }
    }

    companion object {
        const val EXTRA_DEST = "dest"

        fun destOf(name: String?): Dest = when (name) {
            "trend" -> Dest.Trend
            "sets" -> Dest.Sets
            "checkin" -> Dest.CheckIn()
            "checkin_refill" -> Dest.CheckIn(CheckInPreset.REFILL)
            "checkin_rotation" -> Dest.Rotation
            "rotation" -> Dest.Rotation
            "replace" -> Dest.Replace
            "mechanic" -> Dest.Mechanic
            "settings" -> Dest.Settings
            "onboarding" -> Dest.Onboarding
            "demo" -> Dest.Demo
            else -> when {
                name?.startsWith("tire_") == true -> Dest.Inspect(position = com.tirelog.core.model.WheelPosition.entries.firstOrNull { it.name == name.removePrefix("tire_") })
                name?.startsWith("set_") == true -> Dest.Inspect(setId = name.removePrefix("set_").toLongOrNull())
                else -> Dest.Detail
            }
        }
    }
}

@Composable
private fun MainHost(vm: MainViewModel, finish: () -> Unit, toast: (String) -> Unit, toCard: () -> Unit) {
    val restricted by vm.restricted.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val states = rememberSaveableStateHolder()
    val dest = vm.stack.lastOrNull()
    val stateKey = "${session?.demo}:${vm.stack.lastIndex}:$dest"
    LaunchedEffect(restricted) { if (restricted) toCard() }
    val back: () -> Unit = {
        states.removeState(stateKey)
        if (!vm.back()) finish()
    }
    Column(
        Modifier.fillMaxSize().background(TirelogColors.Background)
            .windowInsetsPadding(WindowInsets.safeDrawing).imePadding(),
    ) {
        if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { message ->
            Surface(color = TirelogColors.SurfaceHigh) {
                Text(message, color = TirelogColors.Red, modifier = Modifier.fillMaxWidth().padding(16.dp).semantics {
                    liveRegion = LiveRegionMode.Assertive
                })
            }
        }
        if (restricted) return@Column
        CompositionLocalProvider(LocalActionsEnabled provides !saving, LocalTireModelCatalog provides vm.container.tireModels) {
            states.SaveableStateProvider(stateKey) {
                when (dest) {
                    Dest.Detail -> DetailScreen(vm, back)
                    Dest.Trend -> TrendScreen(vm, back)
                    Dest.Sets -> SetsScreen(vm, back)
                    Dest.AddSet -> AddSetScreen(vm, back)
                    is Dest.EditRegistration -> EditRegistrationScreen(vm, dest.setId, dest.tireId, back)
                    is Dest.Inspect -> DetailScreen(vm, back, dest.setId, dest.position)
                    Dest.Replace -> ReplacementScreen(vm, back)
                    Dest.Rotation -> {
                        val history by vm.rotationHistory.collectAsStateWithLifecycle()
                        val tires by vm.mountedTires.collectAsStateWithLifecycle()
                        RotationDialog(back, { pattern ->
                            vm.checkIn(com.tirelog.core.engine.CheckInRequest(rotation = pattern)) { message -> toast(message); back() }
                        }, error = error, history = history, tireLabels = tires.mapValues { it.value.label })
                    }
                    is Dest.LifeSettings -> EditRegistrationScreen(vm, dest.setId, null, back)
                    is Dest.CheckIn -> CheckInScreen(vm, dest.preset, back, toast)
                    Dest.Mechanic -> MechanicScreen(vm, back)
                    Dest.Settings -> SettingsScreen(vm, back, toast)
                    Dest.Onboarding -> OnboardingScreen(vm, back, onDone = finish)
                    Dest.Demo -> DemoScreen(vm, back)
                    null -> LaunchedEffect(Unit) { finish() }
                }
            }
        }
    }
}
