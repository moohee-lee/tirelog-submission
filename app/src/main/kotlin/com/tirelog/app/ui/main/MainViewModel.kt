package com.tirelog.app.ui.main

import android.app.Application
import android.database.sqlite.SQLiteException
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tirelog.app.Session
import com.tirelog.app.TirelogApp
import com.tirelog.app.vehicle.VehicleCapabilities
import com.tirelog.core.TimeLabels
import com.tirelog.core.engine.CheckInRequest
import com.tirelog.core.engine.DashboardSnapshot
import com.tirelog.core.engine.LiveReadings
import com.tirelog.core.engine.MechanicCard
import com.tirelog.core.engine.NewSetInput
import com.tirelog.core.engine.SetsOverview
import com.tirelog.core.engine.TireLogEngine
import com.tirelog.core.engine.TrendData
import com.tirelog.core.engine.RotationHistorySnapshot
import com.tirelog.core.model.AppSettings
import com.tirelog.core.model.SavedShop
import com.tirelog.core.model.Tire
import com.tirelog.core.model.TireSet
import com.tirelog.core.model.Measurement
import com.tirelog.core.model.WheelPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

sealed interface Dest {
    data object Detail : Dest
    data object Trend : Dest
    data object Sets : Dest
    data object AddSet : Dest
    data class EditRegistration(val setId: Long, val tireId: Long? = null) : Dest
    data class Inspect(val setId: Long? = null, val position: WheelPosition? = null) : Dest
    data object Replace : Dest
    data object Rotation : Dest
    data class LifeSettings(val setId: Long) : Dest
    data class CheckIn(val preset: CheckInPreset = CheckInPreset.NONE) : Dest
    data object Mechanic : Dest
    data object Settings : Dest
    data object Onboarding : Dest
    data object Demo : Dest
}

enum class CheckInPreset { NONE, REFILL, ROTATION }

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {
    val container = (app as TirelogApp).container
    private val sessions: Flow<Session> = container.session.filterNotNull()
    val session: StateFlow<Session?> = container.session

    val stack = mutableStateListOf<Dest>()
    private val _saving = MutableStateFlow(false)
    val saving = _saving.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    fun clearError() { _error.value = null }

    private val ticker = flow {
        while (true) {
            emit(Unit)
            delay(30_000)
        }
    }

    private fun <T> fromEngine(initial: T, block: suspend (Session) -> T): StateFlow<T> =
        sessions.flatMapLatest { s -> combine(s.engine.version, ticker) { _, _ -> block(s) } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    val dashboard: StateFlow<DashboardSnapshot?> = sessions.flatMapLatest { s ->
        combine(s.engine.version, s.gateway.live, s.collector.afterDriving, ticker) { _, live, after, _ ->
            s.engine.dashboard(s.time.now(), LiveReadings(live.pressuresKpa, live.pressuresAt, after, live.odoKm,
                live.pressurePositions, live.pressureTimes, live.lowPressureKpa))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val restricted: StateFlow<Boolean> = sessions.flatMapLatest { it.gateway.live }
        .map { it.restricted || it.gear == com.tirelog.core.drive.GearState.DRIVE }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val capabilities: StateFlow<VehicleCapabilities?> = sessions.flatMapLatest { it.collector.capabilities }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val checkingCapabilities: StateFlow<Boolean> = sessions.flatMapLatest { it.collector.checking }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val settings: StateFlow<AppSettings?> = fromEngine(null) { it.engine.settings() }
    val sets: StateFlow<SetsOverview?> = fromEngine(null) { it.engine.setsOverview(it.time.now(), it.gateway.live.value.odoKm) }
    val mechanic: StateFlow<MechanicCard?> = fromEngine(null) { it.engine.mechanicCard(it.time.now(), it.gateway.live.value.odoKm) }
    val mountedTires: StateFlow<Map<WheelPosition, Tire>> = fromEngine(emptyMap()) { it.engine.mountedTires() }
    val allSets: StateFlow<List<TireSet>> = fromEngine(emptyList()) { it.engine.sets() }
    val allTires: StateFlow<List<Tire>> = fromEngine(emptyList()) { it.engine.tires() }
    val latestReadings: StateFlow<List<Measurement>> = fromEngine(emptyList()) { it.engine.latestReadings() }
    val mountedSet: StateFlow<TireSet?> = fromEngine(null) { it.engine.mountedSet() }
    val rotationHistory: StateFlow<RotationHistorySnapshot?> = fromEngine(null) { it.engine.rotationHistory() }

    private val _trendDays = MutableStateFlow(30)
    val trendDays: StateFlow<Int> = _trendDays.asStateFlow()
    private val _trendSetId = MutableStateFlow<Long?>(null)
    val trendSetId: StateFlow<Long?> = _trendSetId.asStateFlow()
    private val _trendRange = MutableStateFlow<Pair<LocalDate, LocalDate>?>(null)
    val trendRange = _trendRange.asStateFlow()
    val trend: StateFlow<TrendData?> = combine(sessions, _trendDays, _trendSetId, _trendRange) { s, days, setId, range ->
        TrendRequest(s, days, setId, range)
    }.flatMapLatest { (s, days, setId, range) -> combine(s.engine.version, ticker) { _, _ ->
        if (range == null) s.engine.trend(s.time.now(), days, setId) else s.engine.trendRange(s.time.now(),
            range.first.atStartOfDay(s.engine.zone).toInstant().toEpochMilli(),
            minOf(s.time.now(), range.second.plusDays(1).atStartOfDay(s.engine.zone).toInstant().toEpochMilli() - 1), setId)
    } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private data class TrendRequest(val session: Session, val days: Int, val setId: Long?, val range: Pair<LocalDate, LocalDate>?)

    fun setTrendDays(days: Int) {
        _trendRange.value = null
        _trendDays.value = days
    }

    fun setTrendRange(from: LocalDate, to: LocalDate) {
        require(!from.isAfter(to) && !to.isAfter(today()))
        _trendRange.value = from to to
    }

    fun setTrendSet(setId: Long?) {
        _trendSetId.value = setId
    }

    fun today(): LocalDate {
        val s = session.value ?: return LocalDate.now()
        return TimeLabels.date(s.time.now(), s.engine.zone)
    }

    // region 이동

    fun open(dest: Dest) {
        clearError()
        if (stack.lastOrNull() != dest) stack.add(dest)
    }

    fun replaceTop(dest: Dest) {
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
        stack.add(dest)
    }

    /** 이전 화면이 없으면 false(액티비티를 닫는다). */
    fun back(): Boolean {
        clearError()
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    // endregion

    // region 동작

    private fun withSession(block: suspend (Session) -> Unit) {
        val s = session.value
        if (s == null) {
            _error.value = "데이터를 준비 중입니다. 잠시 후 다시 시도해 주세요."
            return
        }
        if (!_saving.compareAndSet(false, true)) return
        clearError()
        viewModelScope.launch {
            try {
                withContext(Dispatchers.Default) { block(s) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                Log.w("MainViewModel", "입력값 확인 실패", e)
                _error.value = e.message ?: "입력값을 확인해 주세요."
            } catch (e: SQLiteException) {
                Log.e("MainViewModel", "기록 저장 실패", e)
                _error.value = "저장하지 못했습니다. 입력은 그대로 남아 있습니다. 다시 시도해 주세요."
            } finally {
                _saving.value = false
            }
        }
    }

    fun checkIn(request: CheckInRequest, onDone: (String) -> Unit) = withSession { s ->
        val events = s.engine.checkIn(request, s.time.now(), s.gateway.readOdometerKm())
        val wheelOff = events.any { it.type.wheelOff }
        val message = when {
            wheelOff && events.any { it.type.wheelOff && it.odoKm == null } -> "작업을 기록했습니다 · 주행거리를 확인하지 못해 거리 기준 점검 안내는 보류합니다"
            wheelOff -> "작업을 기록했습니다 · 50km 뒤 너트 점검을 알려 드립니다"
            else -> "작업을 기록했습니다"
        }
        withContext(Dispatchers.Main) { onDone(message) }
    }

    fun addSet(input: NewSetInput, onDone: () -> Unit) = withSession { s ->
        s.engine.addSet(input, s.time.now())
        withContext(Dispatchers.Main) { onDone() }
    }

    fun retireSet(setId: Long) = withSession { it.engine.retireSet(setId) }

    fun deleteSet(setId: Long) = withSession {
        it.engine.deleteSet(setId)
        if (_trendSetId.value == setId) _trendSetId.value = null
    }

    fun saveRegistration(setId: Long, tireId: Long?, input: NewSetInput, onDone: () -> Unit) = withSession { s ->
        if (tireId == null) s.engine.updateRegistration(setId, input, s.time.now())
        else s.engine.updateTire(tireId, input, s.time.now())
        withContext(Dispatchers.Main) { onDone() }
    }

    fun replaceTires(positions: Set<WheelPosition>, input: NewSetInput?, existingSetId: Long?, discard: Boolean, onDone: () -> Unit) = withSession { s ->
        s.engine.replaceTires(positions, input, existingSetId, discard, s.time.now(), s.gateway.readOdometerKm())
        withContext(Dispatchers.Main) { onDone() }
    }

    fun updateLifeSettings(setId: Long, initialKm: Double, firstUsed: Long?, km: Int, months: Int, guideId: String, model: String, onDone: () -> Unit) = withSession { s ->
        s.engine.updateLifeSettings(setId, initialKm, firstUsed, km, months, s.time.now(), guideId, model)
        withContext(Dispatchers.Main) { onDone() }
    }

    fun updateSettings(onDone: () -> Unit = {}, transform: (AppSettings) -> AppSettings) = withSession {
        it.engine.updateSettings(transform)
        withContext(Dispatchers.Main) { onDone() }
    }

    fun completeOnboarding(input: TireLogEngine.OnboardingInput, onDone: () -> Unit) = withSession { s ->
        s.engine.completeOnboarding(input, s.time.now(), s.gateway.readOdometerKm())
        withContext(Dispatchers.Main) { onDone() }
    }

    fun answerRefill(refilled: Boolean) = withSession { it.engine.answerRefillQuestion(refilled, it.time.now()) }

    suspend fun readVin(): String? = session.value?.gateway?.readVin()
    suspend fun readIdentity() = session.value?.gateway?.readIdentity()

    fun recheckCapabilities() {
        session.value?.collector?.recheck()
    }

    suspend fun shopCandidates(): List<SavedShop>? = container.navigator.shopCandidates()

    fun saveShop(shop: SavedShop?) = updateSettings { it.copy(favoriteShop = shop) }

    fun switchMode(demo: Boolean) {
        withSession {
            container.switchMode(demo)
            _trendSetId.value = null
            _trendRange.value = null
        }
    }

    fun resetAll(onDone: () -> Unit) = withSession { s ->
        container.popups.dismiss()
        s.engine.resetAll()
        withContext(Dispatchers.Main) { onDone() }
    }

    // endregion
}
