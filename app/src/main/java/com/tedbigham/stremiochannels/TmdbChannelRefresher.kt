package com.tedbigham.stremiochannels

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.tvprovider.media.tv.PreviewChannel
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object TmdbChannelRefresher {
    private const val TAG = "StremioChannels"
    private const val PREFS_NAME = "tv_preview_channel"
    private const val PROGRAM_INTERNAL_ID_PREFIX = "tmdb-program-"
    private const val EXTRA_PREVIEW_ITEM_ID = "preview_item_id"
    private const val EXTRA_MOVIE_TITLE = "movie_title"
    private const val TMDB_BASE_URL = "https://api.themoviedb.org/3"
    private const val TMDB_POSTER_BASE_URL = "https://image.tmdb.org/t/p/w500"
    private const val TMDB_MAX_PAGES = 5
    private const val TMDB_PAGE_SIZE = 20
    private const val CHANNEL_LOGO_SIZE_PX = 320

    fun refreshAll(context: Context): RefreshSummary {
        val appContext = context.applicationContext
        Log.i(TAG, "TMDB channel refresh start")

        val configs = ChannelConfigStore.loadChannelConfigs(appContext)
        Log.i(TAG, "Loaded channel configs: total=${configs.size}")

        var channelsRefreshed = 0
        var programsUpdated = 0
        var failures = 0

        configs.forEach { config ->
            runCatching {
                val result = refreshChannel(appContext, config)
                if (result.refreshed) {
                    channelsRefreshed++
                    programsUpdated += result.programsPublished
                } else {
                    failures++
                }
            }.onFailure { error ->
                failures++
                Log.e(TAG, "TMDB channel refresh failed: channel=${config.displayName}", error)
            }
        }

        Log.i(
            TAG,
            "TMDB channel refresh end: channelsRefreshed=$channelsRefreshed programsUpdated=$programsUpdated failures=$failures"
        )
        return RefreshSummary(channelsRefreshed, programsUpdated, failures)
    }

    private fun refreshChannel(context: Context, config: ChannelConfigStore.ChannelConfig): ChannelRefreshResult {
        val channelHelper = PreviewChannelHelper(context)
        val channelId = upsertPreviewChannel(context, channelHelper, config)
        if (channelId == -1L) {
            Log.e(TAG, "Skipping preview programs because channel creation failed: channel=${config.displayName}")
            return ChannelRefreshResult(refreshed = false, programsPublished = 0)
        }

        TvContractCompat.requestChannelBrowsable(context, channelId)
        val fetchResult = fetchTmdbItems(config)
        if (fetchResult.items.isEmpty()) {
            Log.w(TAG, "No TMDB items fetched; leaving existing preview programs unchanged: channel=${config.displayName}")
            return ChannelRefreshResult(refreshed = false, programsPublished = 0)
        }

        clearExistingPrograms(context, config, channelId)
        val insertedProgramIds = mutableListOf<Long>()
        fetchResult.items.forEachIndexed { index, item ->
            val posterUri = Uri.parse(item.posterUrl)
            val programKey = programInternalProviderId(config, item.id)
            val intentUri = Uri.parse(
                launchIntent(
                    context = context,
                    itemId = programKey,
                    title = item.title
                ).toUri(Intent.URI_INTENT_SCHEME)
            )
            val program = PreviewProgram.Builder()
                .setChannelId(channelId)
                .setType(config.previewProgramType)
                .setTitle(item.title)
                .setDescription(item.description)
                .setPosterArtUri(posterUri)
                .setThumbnailUri(posterUri)
                .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_MOVIE_POSTER)
                .setThumbnailAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_MOVIE_POSTER)
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
        }
        saveProgramIds(context, config, insertedProgramIds)

        val providerCount = countProgramsForChannel(context, channelId)
        Log.i(
            TAG,
            "Published TMDB channel: channel=${config.displayName} channelId=$channelId " +
                "pagesFetched=${fetchResult.pagesFetched} uniqueItems=${fetchResult.items.size} " +
                "programsPublished=${insertedProgramIds.size} providerCount=$providerCount"
        )
        return ChannelRefreshResult(refreshed = true, programsPublished = insertedProgramIds.size)
    }

    private fun upsertPreviewChannel(
        context: Context,
        channelHelper: PreviewChannelHelper,
        config: ChannelConfigStore.ChannelConfig
    ): Long {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val channelKey = channelIdPreferenceKey(config)
        val existingChannelId = findExistingChannelId(context, channelHelper, config)
        val channel = PreviewChannel.Builder()
            .setDisplayName(config.displayName)
            .setDescription("TMDB-backed Android TV home channel")
            .setAppLinkIntent(launchIntent(context = context, itemId = config.id))
            .setInternalProviderId(config.id)
            .setLogo(channelLogo(context))
            .build()

        if (existingChannelId != null) {
            runCatching {
                channelHelper.updatePreviewChannel(existingChannelId, channel)
                prefs.edit().putLong(channelKey, existingChannelId).apply()
                return existingChannelId
            }.onFailure {
                Log.w(TAG, "Existing channel update failed; creating new channel: channel=${config.displayName}", it)
            }
        }

        val channelId = channelHelper.publishChannel(channel)
        if (channelId != -1L) {
            prefs.edit().putLong(channelKey, channelId).apply()
        }
        return channelId
    }

    private fun findExistingChannelId(
        context: Context,
        channelHelper: PreviewChannelHelper,
        config: ChannelConfigStore.ChannelConfig
    ): Long? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedChannelId = prefs.getLong(channelIdPreferenceKey(config), -1L)
        return savedChannelId.takeIf { it != -1L && channelHelper.getPreviewChannel(it) != null }
            ?: channelHelper.getAllChannels()
                .firstOrNull { it.internalProviderId == config.id || (config.builtIn && it.displayName == config.displayName) }
                ?.id
    }

    private fun clearExistingPrograms(context: Context, config: ChannelConfigStore.ChannelConfig, channelId: Long) {
        val idsToDelete = (loadSavedProgramIds(context, config) + findExistingProgramIds(context, channelId))
            .distinct()
        idsToDelete.forEach { programId ->
            context.contentResolver.delete(TvContractCompat.buildPreviewProgramUri(programId), null, null)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(programIdsPreferenceKey(config))
            .apply()
    }

    private fun removeStoredChannel(context: Context, config: ChannelConfigStore.ChannelConfig): Boolean {
        val channelHelper = PreviewChannelHelper(context)
        val channelId = findExistingChannelId(context, channelHelper, config) ?: return false

        clearExistingPrograms(context, config, channelId)
        context.contentResolver.delete(TvContractCompat.buildChannelUri(channelId), null, null)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(channelIdPreferenceKey(config))
            .remove(programIdsPreferenceKey(config))
            .apply()
        return true
    }

    fun removeChannel(context: Context, config: ChannelConfigStore.ChannelConfig): Boolean =
        removeStoredChannel(context.applicationContext, config)

    private fun launchIntent(context: Context, itemId: String, title: String? = null): Intent =
        Intent(context, LaunchStremioActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("stremiochannels://preview/$itemId")
            putExtra(EXTRA_PREVIEW_ITEM_ID, itemId)
            title?.let { putExtra(EXTRA_MOVIE_TITLE, it) }
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

    private fun fetchTmdbItems(config: ChannelConfigStore.ChannelConfig): FetchResult {
        val token = BuildConfig.TMDB_TOKEN.trim()
        if (token.isEmpty()) {
            Log.e(TAG, "TMDB_TOKEN is missing; add it to local.properties")
            return FetchResult(emptyList(), pagesFetched = 0)
        }

        return runCatching {
            val uniqueItems = linkedMapOf<Long, TmdbItem>()
            var pagesFetched = 0

            for (page in 1..TMDB_MAX_PAGES) {
                if (uniqueItems.size >= config.maxItems) break

                val fetchUrl = "$TMDB_BASE_URL${pagedTmdbPath(config.tmdbPath, page)}"
                val resultCount = fetchTmdbPage(config, token, fetchUrl, uniqueItems)
                pagesFetched++
                if (resultCount < TMDB_PAGE_SIZE) break
            }

            FetchResult(uniqueItems.values.take(config.maxItems), pagesFetched)
        }.getOrElse { error ->
            Log.e(TAG, "TMDB fetch failed: channel=${config.displayName}", error)
            FetchResult(emptyList(), pagesFetched = 0)
        }
    }

    private fun fetchTmdbPage(
        config: ChannelConfigStore.ChannelConfig,
        token: String,
        fetchUrl: String,
        uniqueItems: LinkedHashMap<Long, TmdbItem>
    ): Int {
        val connection = (URL(fetchUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
        }

        try {
            val responseCode = connection.responseCode
            val responseBody = readResponse(connection, responseCode)
            if (responseCode !in 200..299) {
                Log.e(TAG, "TMDB request failed: channel=${config.displayName} httpStatus=$responseCode")
                return 0
            }

            val results = JSONObject(responseBody).getJSONArray("results")
            for (index in 0 until results.length()) {
                if (uniqueItems.size >= config.maxItems) break

                val json = results.getJSONObject(index)
                val id = json.getLong("id")
                if (uniqueItems.containsKey(id)) continue
                if (!matchesGenreFilter(config, json)) continue

                val posterPath = json.optString("poster_path").takeIf { it.isNotBlank() && it != "null" }
                if (posterPath == null) continue

                uniqueItems[id] = TmdbItem(
                    id = id,
                    title = titleFromJson(config, json),
                    description = json.optString("overview").ifBlank { "From TMDB." },
                    posterUrl = "$TMDB_POSTER_BASE_URL$posterPath"
                )
            }
            return results.length()
        } finally {
            connection.disconnect()
        }
    }

    private fun titleFromJson(config: ChannelConfigStore.ChannelConfig, json: JSONObject): String =
        when (config.mediaType) {
            ChannelConfigStore.MEDIA_TYPE_TV -> json.optString("name").ifBlank { json.optString("title") }
            else -> json.optString("title").ifBlank { json.optString("name") }
        }.ifBlank { "Untitled" }

    private fun pagedTmdbPath(tmdbPath: String, page: Int): String {
        val resolvedPath = ChannelConfigStore.resolveDatePlaceholders(tmdbPath)
        val pagePattern = Regex("([?&])page=\\d+")
        if (resolvedPath.contains(pagePattern)) {
            return resolvedPath.replace(pagePattern, "$1page=$page")
        }
        val separator = if (resolvedPath.contains("?")) "&" else "?"
        return "$resolvedPath${separator}page=$page"
    }

    private fun matchesGenreFilter(config: ChannelConfigStore.ChannelConfig, json: JSONObject): Boolean {
        if (config.genreIds.isEmpty() || config.tmdbPath.contains("with_genres=")) return true

        val tmdbGenreIds = json.optJSONArray("genre_ids") ?: return false
        val itemGenreIds = buildSet {
            for (index in 0 until tmdbGenreIds.length()) {
                val id = tmdbGenreIds.optInt(index, -1)
                if (id > 0) add(id)
            }
        }
        return itemGenreIds.containsAll(config.genreIds)
    }

    private fun readResponse(connection: HttpURLConnection, responseCode: Int): String {
        val stream = if (responseCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream ?: connection.inputStream
        }

        return BufferedReader(InputStreamReader(stream)).use { reader ->
            reader.readText()
        }
    }

    private fun countProgramsForChannel(context: Context, channelId: Long): Int {
        val projection = arrayOf(
            TvContractCompat.PreviewPrograms._ID,
            TvContractCompat.PreviewPrograms.COLUMN_CHANNEL_ID
        )

        return context.contentResolver.query(
            TvContractCompat.PreviewPrograms.CONTENT_URI,
            projection,
            null,
            null,
            null
        )?.use { cursor ->
            var matchingRows = 0
            while (cursor.moveToNext()) {
                val rowChannelId = cursor.getLong(
                    cursor.getColumnIndexOrThrow(TvContractCompat.PreviewPrograms.COLUMN_CHANNEL_ID)
                )
                if (rowChannelId == channelId) matchingRows++
            }
            matchingRows
        } ?: 0
    }

    private fun findExistingProgramIds(context: Context, channelId: Long): List<Long> {
        val projection = arrayOf(
            TvContractCompat.PreviewPrograms._ID,
            TvContractCompat.PreviewPrograms.COLUMN_CHANNEL_ID
        )

        return context.contentResolver.query(
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
                    if (rowChannelId == channelId) {
                        add(cursor.getLong(cursor.getColumnIndexOrThrow(TvContractCompat.PreviewPrograms._ID)))
                    }
                }
            }
        } ?: emptyList()
    }

    private fun loadSavedProgramIds(context: Context, config: ChannelConfigStore.ChannelConfig): List<Long> =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(programIdsPreferenceKey(config), null)
            ?.split(",")
            ?.mapNotNull { it.toLongOrNull() }
            .orEmpty()

    private fun saveProgramIds(context: Context, config: ChannelConfigStore.ChannelConfig, programIds: List<Long>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(programIdsPreferenceKey(config), programIds.joinToString(","))
            .apply()
    }

    private fun channelIdPreferenceKey(config: ChannelConfigStore.ChannelConfig) = "channel_id_${config.id}"

    private fun programIdsPreferenceKey(config: ChannelConfigStore.ChannelConfig) = "program_ids_${config.id}"

    private fun programPrefix(config: ChannelConfigStore.ChannelConfig) = "$PROGRAM_INTERNAL_ID_PREFIX${config.id}-"

    private fun programInternalProviderId(config: ChannelConfigStore.ChannelConfig, tmdbId: Long) = "${programPrefix(config)}$tmdbId"

    private fun channelLogo(context: Context): Bitmap {
        val logo = BitmapFactory.decodeResource(context.resources, R.drawable.ic_launcher_user_foreground)
        return Bitmap.createScaledBitmap(logo, CHANNEL_LOGO_SIZE_PX, CHANNEL_LOGO_SIZE_PX, true)
    }

    private data class TmdbItem(
        val id: Long,
        val title: String,
        val description: String,
        val posterUrl: String
    )

    private data class FetchResult(
        val items: List<TmdbItem>,
        val pagesFetched: Int
    )

    data class RefreshSummary(
        val channelsRefreshed: Int,
        val programsUpdated: Int,
        val failures: Int
    )

    private data class ChannelRefreshResult(
        val refreshed: Boolean,
        val programsPublished: Int
    )
}
