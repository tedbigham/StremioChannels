package com.tedbigham.stremiochannels

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters

class TmdbRefreshWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : Worker(appContext, workerParams) {
    override fun doWork(): Result {
        Log.i(TAG, "TMDB refresh worker start: attempt=$runAttemptCount")
        val summary = runCatching {
            TmdbChannelRefresher.refreshAll(applicationContext)
        }.getOrElse { error ->
            Log.e(TAG, "TMDB refresh worker failed; scheduling retry", error)
            return Result.retry()
        }

        return if (summary.failures > 0 && summary.channelsRefreshed == 0) {
            Log.w(TAG, "TMDB refresh worker end with failures; scheduling retry: failures=${summary.failures}")
            Result.retry()
        } else {
            Log.i(
                TAG,
                "TMDB refresh worker end: channelsRefreshed=${summary.channelsRefreshed} " +
                    "programsUpdated=${summary.programsUpdated} failures=${summary.failures}"
            )
            Result.success()
        }
    }

    private companion object {
        const val TAG = "TvChannelsProof"
    }
}
