package com.tirelog.app.catalog

import android.content.Context
import androidx.work.*
import com.tirelog.app.TirelogApp
import java.util.concurrent.TimeUnit

class TireModelRefreshWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result =
        if ((applicationContext as TirelogApp).container.tireModels.refreshDue()) Result.success() else Result.retry()

    companion object {
        internal const val WORK_NAME = "tire-model-catalog-weekly-v1"
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<TireModelRefreshWorker>(7, TimeUnit.DAYS)
                .setInitialDelay(7, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
