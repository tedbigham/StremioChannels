package com.tedbigham.stremiochannels

import android.content.Context
import android.util.Log
import androidx.tvprovider.media.tv.TvContractCompat
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

object ChannelConfigStore {
    private const val TAG = "StremioChannels"
    private const val CHANNEL_CONFIG_ASSET = "channels.json"
    private const val PREFS_NAME = "channel_config_store"
    private const val CUSTOM_CHANNELS_KEY = "custom_channels"
    private const val DEFAULT_MAX_ITEMS = 100

    const val MEDIA_TYPE_MOVIE = "movie"
    const val MEDIA_TYPE_TV = "tv"

    val sources = listOf(
        Source("popular", "Popular"),
        Source("top_rated", "Top Rated"),
        Source("trending_day", "Trending Today"),
        Source("trending_week", "Trending This Week"),
        Source("upcoming", "Upcoming"),
        Source("now_playing", "Now Playing")
    )

    val movieGenres = listOf(
        Genre(28, "Action"),
        Genre(12, "Adventure"),
        Genre(16, "Animation"),
        Genre(35, "Comedy"),
        Genre(80, "Crime"),
        Genre(99, "Documentary"),
        Genre(18, "Drama"),
        Genre(10751, "Family"),
        Genre(14, "Fantasy"),
        Genre(36, "History"),
        Genre(27, "Horror"),
        Genre(10402, "Music"),
        Genre(9648, "Mystery"),
        Genre(10749, "Romance"),
        Genre(878, "Sci-Fi"),
        Genre(53, "Thriller"),
        Genre(10752, "War"),
        Genre(37, "Western")
    )

    val tvGenres = listOf(
        Genre(10759, "Action & Adventure"),
        Genre(16, "Animation"),
        Genre(35, "Comedy"),
        Genre(80, "Crime"),
        Genre(99, "Documentary"),
        Genre(18, "Drama"),
        Genre(10751, "Family"),
        Genre(10762, "Kids"),
        Genre(9648, "Mystery"),
        Genre(10763, "News"),
        Genre(10764, "Reality"),
        Genre(10765, "Sci-Fi & Fantasy"),
        Genre(10766, "Soap"),
        Genre(10767, "Talk"),
        Genre(10768, "War & Politics"),
        Genre(37, "Western")
    )

    fun loadChannelConfigs(context: Context): List<ChannelConfig> {
        val builtIns = loadBundledChannelConfigs(context).map { it.copy(builtIn = true) }
        return builtIns + loadCustomChannelConfigs(context)
    }

    fun loadCustomChannels(context: Context): List<ChannelConfig> =
        loadCustomChannelConfigs(context).map { it.copy(builtIn = false) }

    fun saveCustomChannel(context: Context, config: ChannelConfig) {
        val customChannels = loadCustomChannelConfigs(context).toMutableList()
        val customConfig = config.copy(builtIn = false)
        val index = customChannels.indexOfFirst { it.id == customConfig.id }
        if (index >= 0) {
            customChannels[index] = customConfig
        } else {
            customChannels += customConfig
        }
        saveCustomChannelConfigs(context, customChannels)
    }

    fun deleteCustomChannel(context: Context, id: String) {
        saveCustomChannelConfigs(context, loadCustomChannelConfigs(context).filterNot { it.id == id })
    }

    fun createCustomChannel(
        existingId: String?,
        displayName: String,
        sourceId: String,
        mediaType: String,
        genreIds: List<Int>
    ): ChannelConfig {
        val cleanMediaType = mediaType.takeIf { it == MEDIA_TYPE_MOVIE || it == MEDIA_TYPE_TV } ?: MEDIA_TYPE_MOVIE
        val cleanSourceId = sourceId.takeIf { id -> sources.any { it.id == id } } ?: "popular"
        val cleanGenreIds = genreIds.distinct()
        val path = buildTmdbPath(cleanSourceId, cleanMediaType, cleanGenreIds)
        return ChannelConfig(
            id = existingId ?: "custom-${System.currentTimeMillis()}",
            displayName = displayName.ifBlank { suggestedDisplayName(cleanSourceId, cleanMediaType, cleanGenreIds) },
            tmdbPath = path,
            mediaType = cleanMediaType,
            maxItems = DEFAULT_MAX_ITEMS,
            builtIn = false,
            sourceId = cleanSourceId,
            genreIds = cleanGenreIds
        )
    }

    fun suggestedDisplayName(sourceId: String, mediaType: String, genreIds: List<Int>): String {
        val sourceName = sources.firstOrNull { it.id == sourceId }?.displayName ?: "Popular"
        val genreNames = genresForMediaType(mediaType)
            .filter { it.id in genreIds }
            .joinToString(" ") { it.displayName }
        val mediaName = if (mediaType == MEDIA_TYPE_TV) "TV" else "Movies"
        return listOf(sourceName, genreNames, mediaName)
            .filter { it.isNotBlank() }
            .joinToString(" ")
    }

    fun genresForMediaType(mediaType: String): List<Genre> =
        if (mediaType == MEDIA_TYPE_TV) tvGenres else movieGenres

    fun resolveDatePlaceholders(path: String): String {
        val today = Calendar.getInstance()
        return path
            .replace("{today}", formatDate(today))
            .replace("{todayMinus45}", formatDate(today.copyWithDaysAdded(-45)))
            .replace("{todayMinus7}", formatDate(today.copyWithDaysAdded(-7)))
    }

    fun parseChannelConfigs(jsonArray: JSONArray, builtIn: Boolean): List<ChannelConfig> =
        buildList {
            for (index in 0 until jsonArray.length()) {
                val json = jsonArray.optJSONObject(index)
                if (json == null) {
                    Log.w(TAG, "Skipping invalid channel config at index=$index")
                    continue
                }

                val id = json.optString("id").trim()
                val displayName = json.optString("displayName").trim()
                val tmdbPath = json.optString("tmdbPath").trim()
                val mediaType = json.optString("mediaType").trim().lowercase()
                val maxItems = json.optInt("maxItems", DEFAULT_MAX_ITEMS).coerceIn(1, DEFAULT_MAX_ITEMS)
                val sourceId = json.optString("sourceId").trim().ifBlank { null }
                val genreIds = parseGenreIds(json.optJSONArray("genreIds"))

                if (id.isBlank() || displayName.isBlank() || tmdbPath.isBlank() || mediaType !in setOf(MEDIA_TYPE_MOVIE, MEDIA_TYPE_TV)) {
                    Log.w(TAG, "Skipping invalid channel config: index=$index id=$id displayName=$displayName")
                    continue
                }

                add(ChannelConfig(id, displayName, tmdbPath, mediaType, maxItems, builtIn, sourceId, genreIds))
            }
        }

    private fun loadBundledChannelConfigs(context: Context): List<ChannelConfig> =
        runCatching {
            val json = context.assets.open(CHANNEL_CONFIG_ASSET).bufferedReader().use { it.readText() }
            parseChannelConfigs(JSONArray(json), builtIn = true).ifEmpty {
                error("No valid channel configs found in $CHANNEL_CONFIG_ASSET")
            }
        }.onFailure { error ->
            Log.w(TAG, "Failed to load $CHANNEL_CONFIG_ASSET; using fallback channel config", error)
        }.getOrElse {
            fallbackChannelConfigs()
        }

    private fun loadCustomChannelConfigs(context: Context): List<ChannelConfig> {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(CUSTOM_CHANNELS_KEY, null)
            ?: return emptyList()
        return runCatching {
            parseChannelConfigs(JSONArray(json), builtIn = false)
        }.onFailure {
            Log.w(TAG, "Failed to load custom channel configs", it)
        }.getOrDefault(emptyList())
    }

    private fun saveCustomChannelConfigs(context: Context, configs: List<ChannelConfig>) {
        val json = JSONArray()
        configs.forEach { json.put(it.toJson()) }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(CUSTOM_CHANNELS_KEY, json.toString())
            .apply()
    }

    private fun buildTmdbPath(sourceId: String, mediaType: String, genreIds: List<Int>): String {
        val encodedGenres = genreIds.joinToString(",")
        val genreQuery = if (encodedGenres.isBlank()) "" else "&with_genres=$encodedGenres"
        return when (sourceId) {
            "top_rated" -> "/discover/$mediaType?language=en-US&sort_by=vote_average.desc&vote_count.gte=200$genreQuery"
            "trending_day" -> "/trending/$mediaType/day?language=en-US"
            "trending_week" -> "/trending/$mediaType/week?language=en-US"
            "upcoming" -> {
                if (mediaType == MEDIA_TYPE_TV) {
                    "/discover/tv?language=en-US&sort_by=first_air_date.asc&first_air_date.gte={today}$genreQuery"
                } else {
                    "/discover/movie?language=en-US&sort_by=primary_release_date.asc&with_release_type=2|3&primary_release_date.gte={today}$genreQuery"
                }
            }
            "now_playing" -> {
                if (mediaType == MEDIA_TYPE_TV) {
                    "/discover/tv?language=en-US&sort_by=popularity.desc&air_date.gte={todayMinus7}&air_date.lte={today}$genreQuery"
                } else {
                    "/discover/movie?language=en-US&sort_by=popularity.desc&with_release_type=2|3&primary_release_date.gte={todayMinus45}&primary_release_date.lte={today}$genreQuery"
                }
            }
            else -> "/discover/$mediaType?language=en-US&sort_by=popularity.desc$genreQuery"
        }
    }

    private fun parseGenreIds(jsonArray: JSONArray?): List<Int> =
        buildList {
            if (jsonArray == null) return@buildList
            for (index in 0 until jsonArray.length()) {
                val id = jsonArray.optInt(index, -1)
                if (id > 0) add(id)
            }
        }.distinct()

    private fun ChannelConfig.toJson(): JSONObject =
        JSONObject()
            .put("id", id)
            .put("displayName", displayName)
            .put("tmdbPath", tmdbPath)
            .put("mediaType", mediaType)
            .put("maxItems", maxItems)
            .put("sourceId", sourceId)
            .put("genreIds", JSONArray(genreIds))

    private fun fallbackChannelConfigs() = listOf(
        ChannelConfig("tmdb-now-playing-movies", "Now Playing Movies", "/movie/now_playing?language=en-US", MEDIA_TYPE_MOVIE, builtIn = true),
        ChannelConfig("tmdb-popular-movies", "Popular Movies", "/movie/popular?language=en-US", MEDIA_TYPE_MOVIE, builtIn = true),
        ChannelConfig("tmdb-trending-movies-day", "Trending Movies Today", "/trending/movie/day?language=en-US", MEDIA_TYPE_MOVIE, builtIn = true),
        ChannelConfig("tmdb-trending-movies-week", "Trending Movies This Week", "/trending/movie/week?language=en-US", MEDIA_TYPE_MOVIE, builtIn = true),
        ChannelConfig("tmdb-upcoming-movies", "Upcoming Movies", "/movie/upcoming?language=en-US", MEDIA_TYPE_MOVIE, builtIn = true),
        ChannelConfig("tmdb-top-rated-movies", "Top Rated Movies", "/movie/top_rated?language=en-US", MEDIA_TYPE_MOVIE, builtIn = true),
        ChannelConfig("tmdb-popular-tv", "Popular TV", "/tv/popular?language=en-US", MEDIA_TYPE_TV, builtIn = true),
        ChannelConfig("tmdb-top-rated-tv", "Top Rated TV", "/tv/top_rated?language=en-US", MEDIA_TYPE_TV, builtIn = true),
        ChannelConfig("tmdb-trending-tv-day", "Trending TV Today", "/trending/tv/day?language=en-US", MEDIA_TYPE_TV, builtIn = true),
        ChannelConfig("tmdb-trending-tv-week", "Trending TV This Week", "/trending/tv/week?language=en-US", MEDIA_TYPE_TV, builtIn = true),
        ChannelConfig("tmdb-airing-today-tv", "Airing Today TV", "/tv/airing_today?language=en-US", MEDIA_TYPE_TV, builtIn = true),
        ChannelConfig("tmdb-on-the-air-tv", "On The Air TV", "/tv/on_the_air?language=en-US", MEDIA_TYPE_TV, builtIn = true),
        ChannelConfig("tmdb-action-movies", "Action Movies", "/discover/movie?language=en-US&sort_by=popularity.desc&with_genres=28", MEDIA_TYPE_MOVIE, builtIn = true),
        ChannelConfig("tmdb-comedy-movies", "Comedy Movies", "/discover/movie?language=en-US&sort_by=popularity.desc&with_genres=35", MEDIA_TYPE_MOVIE, builtIn = true),
        ChannelConfig("tmdb-horror-movies", "Horror Movies", "/discover/movie?language=en-US&sort_by=popularity.desc&with_genres=27", MEDIA_TYPE_MOVIE, builtIn = true),
        ChannelConfig("tmdb-sci-fi-movies", "Sci-Fi Movies", "/discover/movie?language=en-US&sort_by=popularity.desc&with_genres=878", MEDIA_TYPE_MOVIE, builtIn = true),
        ChannelConfig("tmdb-documentary-movies", "Documentary Movies", "/discover/movie?language=en-US&sort_by=popularity.desc&with_genres=99", MEDIA_TYPE_MOVIE, builtIn = true),
        ChannelConfig("tmdb-family-movies", "Family Movies", "/discover/movie?language=en-US&sort_by=popularity.desc&with_genres=10751", MEDIA_TYPE_MOVIE, builtIn = true)
    )

    private fun Calendar.copyWithDaysAdded(days: Int): Calendar =
        (clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, days) }

    private fun formatDate(calendar: Calendar): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(calendar.time)

    data class Source(val id: String, val displayName: String)

    data class Genre(val id: Int, val displayName: String)

    data class ChannelConfig(
        val id: String,
        val displayName: String,
        val tmdbPath: String,
        val mediaType: String,
        val maxItems: Int = DEFAULT_MAX_ITEMS,
        val builtIn: Boolean = false,
        val sourceId: String? = null,
        val genreIds: List<Int> = emptyList()
    ) {
        val previewProgramType: Int
            get() = when (mediaType) {
                MEDIA_TYPE_MOVIE -> TvContractCompat.PreviewPrograms.TYPE_MOVIE
                MEDIA_TYPE_TV -> TvContractCompat.PreviewPrograms.TYPE_TV_SERIES
                else -> TvContractCompat.PreviewPrograms.TYPE_MOVIE
            }
    }
}
