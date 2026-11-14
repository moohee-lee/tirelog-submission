package com.tirelog.app.collect

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.tirelog.app.TirelogApp

/** 주행 중 화면을 다른 앱이 쓰고 있어도 기록이 이어지도록 프로세스를 유지한다. */
class CollectorService : Service() {

    override fun onCreate() {
        super.onCreate()
        val app = application as TirelogApp
        val notification = app.container.notifier.collectorNotification()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(AlertNotifier.ID_COLLECTOR, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(AlertNotifier.ID_COLLECTOR, notification)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "포그라운드 서비스 시작 실패. 앱을 열었을 때만 기록한다.", t)
            stopSelf()
            return
        }
        app.container.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "CollectorService"

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, CollectorService::class.java))
            }.onFailure { Log.w(TAG, "서비스 시작 실패", it) }
        }
    }
}

/** 시동(부팅) 때 기록을 시작한다. 허용되지 않으면 앱을 열 때 시작한다(US-13). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) CollectorService.start(context)
    }
}
