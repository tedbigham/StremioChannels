package com.tedbigham.stremiochannels

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Window
import android.view.WindowManager
import android.widget.Toast

class LaunchStremioActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_launch_stremio)

        Handler(Looper.getMainLooper()).postDelayed(
            { launchStremioSearch() },
            LAUNCH_DELAY_MS
        )
    }

    private fun launchStremioSearch() {
        val movieTitle = intent.getStringExtra(EXTRA_MOVIE_TITLE)
        val itemId = intent.getStringExtra(EXTRA_PREVIEW_ITEM_ID)

        if (movieTitle.isNullOrBlank()) {
            Log.w(TAG, "Missing movie title in Stremio trampoline intent: itemId=$itemId")
            finish()
            return
        }

        val stremioUri = buildStremioSearchUri(movieTitle)
        val stremioIntent = Intent(Intent.ACTION_VIEW, stremioUri)

        Log.i(TAG, "Movie card clicked: title=$movieTitle itemId=$itemId")
        Log.i(TAG, "Generated Stremio search deep link: title=$movieTitle uri=$stremioUri")

        runCatching {
            startActivity(stremioIntent)
        }.onSuccess {
            Log.i(TAG, "Stremio launch succeeded: title=$movieTitle")
        }.onFailure { error ->
            if (error is ActivityNotFoundException) {
                Log.w(TAG, "Stremio launch failed; app not installed for uri=$stremioUri")
                Toast.makeText(this, "Stremio is not installed", Toast.LENGTH_SHORT).show()
            } else {
                Log.w(TAG, "Stremio launch failed for uri=$stremioUri", error)
                Toast.makeText(this, "Could not open Stremio", Toast.LENGTH_SHORT).show()
            }
        }

        finish()
    }

    private fun buildStremioSearchUri(movieTitle: String): Uri =
        Uri.parse("stremio:///search")
            .buildUpon()
            .appendQueryParameter("search", movieTitle)
            .build()

    private companion object {
        const val TAG = "StremioChannels"
        const val EXTRA_PREVIEW_ITEM_ID = "preview_item_id"
        const val EXTRA_MOVIE_TITLE = "movie_title"
        const val LAUNCH_DELAY_MS = 150L
    }
}
