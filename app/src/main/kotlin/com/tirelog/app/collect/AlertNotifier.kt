package com.tirelog.app.collect

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.tirelog.app.R
import com.tirelog.app.ui.card.CardActivity
import com.tirelog.core.alert.ActiveAlert

class AlertNotifier(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(NotificationManager::class.java)

    fun ensureChannels() {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, appContext.getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_HIGH),
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_COLLECTOR, appContext.getString(R.string.channel_collector), NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun openCard(): PendingIntent = PendingIntent.getActivity(
        appContext,
        0,
        Intent(appContext, CardActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** 앱이 화면에 없을 때 정차 알림을 시스템 알림으로 띄운다. */
    fun show(alert: ActiveAlert) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_tire)
            .setContentTitle("tirelog · ${alert.type.title}")
            .setContentText(alert.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(listOfNotNull(alert.message, alert.detail).joinToString("\n")))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(openCard())
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(ID_ALERT, notification) }
    }

    fun cancel() = manager.cancel(ID_ALERT)

    fun collectorNotification(): Notification = NotificationCompat.Builder(appContext, CHANNEL_COLLECTOR)
        .setSmallIcon(R.drawable.ic_stat_tire)
        .setContentTitle(appContext.getString(R.string.app_name))
        .setContentText(appContext.getString(R.string.collector_running))
        .setOngoing(true)
        .setContentIntent(openCard())
        .build()

    companion object {
        const val CHANNEL_ALERTS = "alerts"
        const val CHANNEL_COLLECTOR = "collector"
        const val ID_ALERT = 1001
        const val ID_COLLECTOR = 1002
    }
}
