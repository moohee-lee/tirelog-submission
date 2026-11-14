package com.tirelog.app

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.core.content.edit
import com.tirelog.app.collect.AlertNotifier
import com.tirelog.app.catalog.CachedTireModelCatalog
import com.tirelog.app.catalog.TireModelRefreshWorker
import com.tirelog.app.collect.AlertPresenter
import com.tirelog.app.collect.Collector
import com.tirelog.app.data.RoomTireLogStore
import com.tirelog.app.data.TirelogDatabase
import com.tirelog.app.demo.DemoDirector
import com.tirelog.app.navi.TireShopNavigator
import com.tirelog.app.vehicle.DemoClock
import com.tirelog.app.vehicle.DemoVehicleGateway
import com.tirelog.app.vehicle.PleosVehicleGateway
import com.tirelog.app.vehicle.VehicleGateway
import com.tirelog.app.voice.Speaker
import com.tirelog.core.TimeSource
import com.tirelog.core.alert.ActiveAlert
import com.tirelog.core.demo.DemoStoryline
import com.tirelog.core.engine.TireLogEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.ZoneId

class TirelogApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        registerActivityLifecycleCallbacks(container.visibility)
        container.start()
    }
}

/** 화면에 앱이 보이는지. 안 보이면 정차 알림을 시스템 알림으로 띄운다. */
class AppVisibility : Application.ActivityLifecycleCallbacks {
    @Volatile private var started = 0
    val isForeground: Boolean get() = started > 0

    override fun onActivityStarted(activity: Activity) { started++ }
    override fun onActivityStopped(activity: Activity) { started = (started - 1).coerceAtLeast(0) }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}

/** 정차 알림 하나. 화면이 보이면 팝업으로, 아니면 시스템 알림으로 띄우고 첫 문장을 읽어 준다. */
class PopupHub(
    private val speaker: Speaker,
    private val notifier: AlertNotifier,
    private val visibility: AppVisibility,
) : AlertPresenter {
    private val _current = MutableStateFlow<ActiveAlert?>(null)
    val current: StateFlow<ActiveAlert?> = _current.asStateFlow()
    override val hasActiveAlert: Boolean get() = _current.value != null

    override fun present(alert: ActiveAlert) {
        _current.value = alert
        speaker.speak(alert.speech)
        if (!visibility.isForeground) notifier.show(alert)
    }

    override fun dismiss() {
        if (_current.value != null) {
            _current.value = null
            notifier.cancel()
        }
    }
}

class Session(
    val demo: Boolean,
    val engine: TireLogEngine,
    val gateway: VehicleGateway,
    val time: TimeSource,
    val collector: Collector,
    val director: DemoDirector?,
    val scope: CoroutineScope,
    private val database: TirelogDatabase,
) {
    fun close() {
        collector.stop()
        scope.cancel()
        // 화면이 새 세션으로 넘어가는 동안 진행 중인 조회가 닫힌 DB에 닿지 않도록 잠시 뒤에 닫는다.
        CoroutineScope(Dispatchers.IO).launch {
            delay(3_000)
            database.close()
        }
    }
}

class AppContainer(private val app: Application) {
    internal val tireModels = CachedTireModelCatalog(
        seed = { app.assets.open("catalog/tire-models.json").bufferedReader().use { it.readText() } },
        cacheFile = java.io.File(app.filesDir, "tire-model-catalog.json"),
    )
    private val prefs = app.getSharedPreferences("tirelog", Context.MODE_PRIVATE)
    val visibility = AppVisibility()
    val speaker = Speaker(app)
    val notifier = AlertNotifier(app)
    val navigator = TireShopNavigator(app)
    val popups = PopupHub(speaker, notifier, visibility)

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()

    val isDemoPreferred: Boolean get() = prefs.getBoolean(KEY_DEMO, false)

    @Synchronized
    fun start() {
        if (_session.value != null) return
        tireModels.start()
        TireModelRefreshWorker.schedule(app)
        notifier.ensureChannels()
        speaker.init()
        _session.value = createSession(isDemoPreferred)
    }

    @Synchronized
    fun switchMode(demo: Boolean) {
        prefs.edit { putBoolean(KEY_DEMO, demo) }
        popups.dismiss()
        _session.value?.close()
        _session.value = createSession(demo)
    }

    private fun createSession(demo: Boolean): Session {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val database = TirelogDatabase.open(app, demo)
        val clock = if (demo) DemoClock(prefs) else null
        val time: TimeSource = clock ?: TimeSource.System
        val zone = if (demo) DemoStoryline.ZONE else ZoneId.systemDefault()
        val engine = TireLogEngine(RoomTireLogStore(database), zone)
        val gateway: VehicleGateway = if (demo) DemoVehicleGateway() else PleosVehicleGateway(app, time)
        val collector = Collector(
            engine = engine,
            gateway = gateway,
            time = time,
            scope = scope,
            presenter = popups,
            measureDelayMillis = if (demo) DEMO_MEASURE_DELAY_MILLIS else MEASURE_DELAY_MILLIS,
        )
        val director = if (demo) DemoDirector(engine, gateway as DemoVehicleGateway, clock!!, scope) else null
        collector.start()
        return Session(demo, engine, gateway, time, collector, director, scope, database)
    }

    private companion object {
        const val KEY_DEMO = "demo_mode"

        /** 출발 1~2분 뒤 기록(기획서 5장). */
        const val MEASURE_DELAY_MILLIS = 90_000L
        const val DEMO_MEASURE_DELAY_MILLIS = 1_500L
    }
}
