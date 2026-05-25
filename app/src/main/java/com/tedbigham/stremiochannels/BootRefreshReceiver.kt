package com.tedbigham.stremiochannels

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }

        Log.i(TAG, "Boot completed; scheduling TMDB channel refresh")
        TmdbRefreshScheduler.schedulePeriodicRefresh(context)
        TmdbRefreshScheduler.scheduleStartupRefresh(context)
    }

    private companion object {
        const val TAG = "TvChannelsProof"
    }
}
