package com.tirelog.app.ui.card

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tirelog.app.TirelogApp
import com.tirelog.app.vehicle.VehicleCapabilities
import com.tirelog.core.alert.ActiveAlert
import com.tirelog.core.alert.AlertAction
import com.tirelog.core.engine.DashboardSnapshot
import com.tirelog.core.engine.LiveReadings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface CardUiState {
    data object Loading : CardUiState
    data class Ready(
        val snapshot: DashboardSnapshot,
        val restricted: Boolean,
        val demo: Boolean,
        val capabilities: VehicleCapabilities?,
        val registeredSets: List<com.tirelog.core.model.TireSet> = emptyList(),
    ) : CardUiState
}

@OptIn(ExperimentalCoroutinesApi::class)
class CardViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as TirelogApp).container
    private val ticker = flow {
        while (true) {
            emit(Unit)
            delay(30_000)
        }
    }

    val state: StateFlow<CardUiState> = container.session.filterNotNull().flatMapLatest { s ->
        combine(s.engine.version, s.gateway.live, s.collector.afterDriving, s.collector.capabilities, ticker) { _, live, after, caps, _ ->
            val snapshot = s.engine.dashboard(s.time.now(), LiveReadings(live.pressuresKpa, live.pressuresAt, after, live.odoKm,
                live.pressurePositions, live.pressureTimes, live.lowPressureKpa))
            CardUiState.Ready(snapshot, live.restricted || live.gear == com.tirelog.core.drive.GearState.DRIVE,
                s.demo, caps, s.engine.sets())
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CardUiState.Loading)

    val popup: StateFlow<ActiveAlert?> = container.popups.current

    val checking: StateFlow<Boolean> = container.session.filterNotNull().flatMapLatest { it.collector.checking }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun recheck() {
        container.session.value?.collector?.recheck()
    }

    /** 앱을 열었을 때 정차 중이면 미뤄 둔 알림을 하나 띄운다(시동 때 자동 실행이 안 되는 경우 대비). */
    fun onOpened() {
        val session = container.session.value ?: return
        session.collector.onAppOpened()
    }

    fun answerRefill(refilled: Boolean) {
        val session = container.session.value ?: return
        viewModelScope.launch(Dispatchers.Default) { session.engine.answerRefillQuestion(refilled, session.time.now()) }
    }

    /** 팝업 버튼. 화면 이동이 필요한 동작은 [open]으로 넘긴다. */
    fun onAlertAction(alert: ActiveAlert, action: AlertAction, open: (String) -> Unit, toast: (String) -> Unit) {
        val session = container.session.value ?: return
        container.popups.dismiss()
        when (action) {
            AlertAction.DETAILS -> open("detail")
            AlertAction.RECORD_ROTATION -> open("checkin_rotation")
            AlertAction.NAVIGATE -> viewModelScope.launch {
                val shop = session.engine.settings().favoriteShop
                if (!container.navigator.navigateToTireShop(shop)) toast("내비게이션을 열 수 없습니다. 가까운 타이어점에서 점검받으세요.")
            }
            else -> Unit
        }
        viewModelScope.launch(Dispatchers.Default) {
            session.engine.respond(alert, action, session.time.now(), session.gateway.live.value.odoKm)
        }
    }

    fun switchToDemo() = container.switchMode(true)
}
