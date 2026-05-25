package com.tedbigham.stremiochannels

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.tvprovider.media.tv.PreviewChannel
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat

class MainActivity : AppCompatActivity() {
    private val channelHelper by lazy { PreviewChannelHelper(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        publishPreviewChannel()
    }

    private fun publishPreviewChannel() {
        val channelId = upsertPreviewChannel()
        if (channelId == -1L) {
            Log.e(TAG, "Skipping preview programs because channel creation failed")
            return
        }

        TvContractCompat.requestChannelBrowsable(this, channelId)
        Log.i(TAG, "Requested channel browsable: id=$channelId")

        clearExistingPrograms(channelId)
        val insertedProgramIds = mutableListOf<Long>()
        testPrograms().forEachIndexed { index, item ->
            val posterUri = resourceUri(item.posterResId)
            val programKey = "$PROGRAM_INTERNAL_ID_PREFIX${index + 1}"
            val intentUri = Uri.parse(mainActivityIntent(programKey).toUri(Intent.URI_INTENT_SCHEME))
            val program = PreviewProgram.Builder()
                .setChannelId(channelId)
                .setType(TvContractCompat.PreviewPrograms.TYPE_MOVIE)
                .setTitle(item.title)
                .setDescription(item.description)
                .setPosterArtUri(posterUri)
                .setThumbnailUri(posterUri)
                .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
                .setThumbnailAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
                .setIntentUri(intentUri)
                .setBrowsable(true)
                .setContentId(programKey)
                .setWeight(index)
                .setInternalProviderId(programKey)
                .build()

            val programId = channelHelper.publishPreviewProgram(program)
            if (programId != -1L) {
                insertedProgramIds += programId
            }
            Log.i(
                TAG,
                "Inserted preview program: id=$programId channelId=$channelId " +
                    "title=${item.title} type=${TvContractCompat.PreviewPrograms.TYPE_MOVIE} " +
                    "posterArtUri=$posterUri thumbnailUri=$posterUri aspectRatio=16:9 intentUri=$intentUri"
            )
        }
        saveProgramIds(insertedProgramIds)

        logProgramsForChannel(channelId)
    }

    private fun upsertPreviewChannel(): Long {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val savedChannelId = prefs.getLong(KEY_CHANNEL_ID, -1L)
        val existingChannelId = savedChannelId.takeIf { it != -1L && channelHelper.getPreviewChannel(it) != null }
            ?: channelHelper.getAllChannels()
                .firstOrNull { it.internalProviderId == CHANNEL_INTERNAL_ID }
                ?.id
        val channel = PreviewChannel.Builder()
            .setDisplayName(CHANNEL_NAME)
            .setDescription("Hardcoded proof-of-life channel for Android TV launchers")
            .setAppLinkIntent(mainActivityIntent(CHANNEL_INTERNAL_ID))
            .setInternalProviderId(CHANNEL_INTERNAL_ID)
            .setLogo(channelLogo())
            .build()

        if (existingChannelId != null) {
            runCatching {
                channelHelper.updatePreviewChannel(existingChannelId, channel)
                prefs.edit().putLong(KEY_CHANNEL_ID, existingChannelId).apply()
                Log.i(TAG, "Updated preview channel: id=$existingChannelId name=$CHANNEL_NAME")
                return existingChannelId
            }.onFailure {
                Log.w(TAG, "Existing channel id=$existingChannelId could not be updated; creating a new channel", it)
            }
        }

        val channelId = channelHelper.publishChannel(channel)
        if (channelId != -1L) {
            prefs.edit().putLong(KEY_CHANNEL_ID, channelId).apply()
        }
        Log.i(TAG, "Created preview channel: id=$channelId name=$CHANNEL_NAME")
        return channelId
    }

    private fun clearExistingPrograms(channelId: Long) {
        val idsToDelete = (loadSavedProgramIds() + findExistingProgramIds(channelId)).distinct()
        val deleted = idsToDelete.sumOf { programId ->
            contentResolver.delete(TvContractCompat.buildPreviewProgramUri(programId), null, null)
        }
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().remove(KEY_PROGRAM_IDS).apply()
        Log.i(TAG, "Deleted $deleted existing preview programs for channel id=$channelId")
    }

    private fun mainActivityIntent(itemId: String): Intent =
        Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("stremiochannels://preview/$itemId")
            putExtra(EXTRA_PREVIEW_ITEM_ID, itemId)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

    private fun resourceUri(resourceId: Int): Uri =
        Uri.parse(
            "android.resource://$packageName/" +
                "${resources.getResourceTypeName(resourceId)}/" +
                resources.getResourceEntryName(resourceId)
        )

    private fun logProgramsForChannel(channelId: Long) {
        val projection = arrayOf(
            TvContractCompat.PreviewPrograms._ID,
            TvContractCompat.PreviewPrograms.COLUMN_CHANNEL_ID,
            TvContractCompat.PreviewPrograms.COLUMN_TITLE,
            TvContractCompat.PreviewPrograms.COLUMN_SHORT_DESCRIPTION,
            TvContractCompat.PreviewPrograms.COLUMN_TYPE,
            TvContractCompat.PreviewPrograms.COLUMN_POSTER_ART_URI,
            TvContractCompat.PreviewPrograms.COLUMN_THUMBNAIL_URI,
            TvContractCompat.PreviewPrograms.COLUMN_POSTER_ART_ASPECT_RATIO,
            TvContractCompat.PreviewPrograms.COLUMN_THUMBNAIL_ASPECT_RATIO,
            TvContractCompat.PreviewPrograms.COLUMN_INTENT_URI,
            TvContractCompat.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID,
            TvContractCompat.PreviewPrograms.COLUMN_CONTENT_ID
        )

        contentResolver.query(
            TvContractCompat.PreviewPrograms.CONTENT_URI,
            projection,
            null,
            null,
            null
        )?.use { cursor ->
            var matchingRows = 0
            while (cursor.moveToNext()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(TvContractCompat.PreviewPrograms._ID))
                val rowChannelId = cursor.getLong(
                    cursor.getColumnIndexOrThrow(TvContractCompat.PreviewPrograms.COLUMN_CHANNEL_ID)
                )
                if (rowChannelId != channelId) {
                    continue
                }

                matchingRows++
                val title = cursor.getString(cursor.getColumnIndexOrThrow(TvContractCompat.PreviewPrograms.COLUMN_TITLE))
                val type = cursor.getInt(cursor.getColumnIndexOrThrow(TvContractCompat.PreviewPrograms.COLUMN_TYPE))
                val posterArtUri = cursor.getString(
                    cursor.getColumnIndexOrThrow(TvContractCompat.PreviewPrograms.COLUMN_POSTER_ART_URI)
                )
                val thumbnailUri = cursor.getString(
                    cursor.getColumnIndexOrThrow(TvContractCompat.PreviewPrograms.COLUMN_THUMBNAIL_URI)
                )
                val posterAspectRatio = cursor.getInt(
                    cursor.getColumnIndexOrThrow(TvContractCompat.PreviewPrograms.COLUMN_POSTER_ART_ASPECT_RATIO)
                )
                val thumbnailAspectRatio = cursor.getInt(
                    cursor.getColumnIndexOrThrow(TvContractCompat.PreviewPrograms.COLUMN_THUMBNAIL_ASPECT_RATIO)
                )
                val intentUri = cursor.getString(
                    cursor.getColumnIndexOrThrow(TvContractCompat.PreviewPrograms.COLUMN_INTENT_URI)
                )
                val contentId = cursor.getString(
                    cursor.getColumnIndexOrThrow(TvContractCompat.PreviewPrograms.COLUMN_CONTENT_ID)
                )
                val description = cursor.getString(
                    cursor.getColumnIndexOrThrow(TvContractCompat.PreviewPrograms.COLUMN_SHORT_DESCRIPTION)
                )

                Log.i(
                    TAG,
                    "TvProvider row: id=$id channelId=$rowChannelId title=$title " +
                        "description=$description type=$type posterArtUri=$posterArtUri " +
                        "thumbnailUri=$thumbnailUri posterAspectRatio=$posterAspectRatio " +
                        "thumbnailAspectRatio=$thumbnailAspectRatio contentId=$contentId intentUri=$intentUri"
                )
            }
            Log.i(TAG, "TvProvider query confirmed $matchingRows preview programs for channelId=$channelId")
        } ?: Log.e(TAG, "TvProvider query returned null cursor for channelId=$channelId")
    }

    private fun findExistingProgramIds(channelId: Long): List<Long> {
        val projection = arrayOf(
            TvContractCompat.PreviewPrograms._ID,
            TvContractCompat.PreviewPrograms.COLUMN_CHANNEL_ID,
            TvContractCompat.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID
        )

        return contentResolver.query(
            TvContractCompat.PreviewPrograms.CONTENT_URI,
            projection,
            null,
            null,
            null
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val rowChannelId = cursor.getLong(
                        cursor.getColumnIndexOrThrow(TvContractCompat.PreviewPrograms.COLUMN_CHANNEL_ID)
                    )
                    val internalProviderId = cursor.getString(
                        cursor.getColumnIndexOrThrow(TvContractCompat.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID)
                    )
                    if (rowChannelId == channelId && internalProviderId?.startsWith(PROGRAM_INTERNAL_ID_PREFIX) == true) {
                        add(cursor.getLong(cursor.getColumnIndexOrThrow(TvContractCompat.PreviewPrograms._ID)))
                    }
                }
            }
        } ?: emptyList()
    }

    private fun loadSavedProgramIds(): List<Long> =
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(KEY_PROGRAM_IDS, null)
            ?.split(",")
            ?.mapNotNull { it.toLongOrNull() }
            .orEmpty()

    private fun saveProgramIds(programIds: List<Long>) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(KEY_PROGRAM_IDS, programIds.joinToString(","))
            .apply()
    }

    private fun channelLogo(): Bitmap {
        val bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(30, 42, 56))

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 26f
            isFakeBoldText = true
        }
        canvas.drawText("New Releases", 32f, 92f, paint)
        return bitmap
    }

    private fun testPrograms() = listOf(
        TestProgram(
            title = "Orbit Drift",
            description = "A stranded pilot races a failing signal across the outer colonies.",
            posterResId = R.drawable.release_orbit_drift
        ),
        TestProgram(
            title = "Midnight Signal",
            description = "Three friends discover a pirate broadcast from a city that vanished.",
            posterResId = R.drawable.release_midnight_signal
        ),
        TestProgram(
            title = "The Last Archive",
            description = "A quiet archivist uncovers the map to a lost cinematic universe.",
            posterResId = R.drawable.release_last_archive
        )
    )

    private data class TestProgram(
        val title: String,
        val description: String,
        val posterResId: Int
    )

    private companion object {
        const val TAG = "TvChannelsProof"
        const val PREFS_NAME = "tv_preview_channel"
        const val KEY_CHANNEL_ID = "new_releases_channel_id"
        const val KEY_PROGRAM_IDS = "new_releases_program_ids"
        const val CHANNEL_NAME = "New Releases Test"
        const val CHANNEL_INTERNAL_ID = "new-releases-test-channel"
        const val PROGRAM_INTERNAL_ID_PREFIX = "new-release-test-"
        const val EXTRA_PREVIEW_ITEM_ID = "preview_item_id"
    }
}
