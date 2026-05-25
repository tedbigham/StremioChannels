package com.tedbigham.stremiochannels

import android.app.Activity
import android.os.Bundle
import android.util.Log

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.i(TAG, "Launcher setup entry opened; scheduling TMDB refresh work")
        TmdbRefreshScheduler.schedulePeriodicRefresh(this)
        TmdbRefreshScheduler.scheduleStartupRefresh(this)
        finish()
    }

    private companion object {
        private const val TAG = "MainActivity"
    }
}
