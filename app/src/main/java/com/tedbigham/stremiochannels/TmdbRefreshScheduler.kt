package com.tedbigham.stremiochannels

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object TmdbRefreshScheduler {
    private const val TAG = "StremioChannels"
    private const val IMMEDIATE_WORK_NAME = "tmdb-channel-refresh-now"
    private const val PERIODIC_WORK_NAME = "tmdb-channel-refresh-periodic"

    fun scheduleStartupRefresh(context: Context) {
        val request = OneTimeWorkRequestBuilder<TmdbRefreshWorker>()
            .setConstraints(networkConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(IMMEDIATE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        Log.i(TAG, "Scheduled startup TMDB refresh work")
    }

    fun schedulePeriodicRefresh(context: Context) {
        val request = PeriodicWorkRequestBuilder<TmdbRefreshWorker>(6, TimeUnit.HOURS, 1, TimeUnit.HOURS)
            .setConstraints(networkConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        Log.i(TAG, "Scheduled periodic TMDB refresh work")
    }

    private fun networkConstraints(): Constraints =
        Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
}
