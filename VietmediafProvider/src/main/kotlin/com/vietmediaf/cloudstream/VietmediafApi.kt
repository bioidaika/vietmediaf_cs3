package com.vietmediaf.cloudstream

import com.lagradost.cloudstream3.app
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Client for the VietmediaF backend API (vietmediaf.store/api).
 * Proxies TMDB data and provides Fshare source links.
 */
object VietmediafApi {
    private const val BASE_URL = "https://vietmediaf.store"
    private const val TMDB_BASE = "https://api.themoviedb.org/3"
    private const val TMDB_API_KEY = "8d6d91941230817f7807d643736e8a49"
    private const val IMG_BASE = "https://image.tmdb.org/t/p"
    private const val LANG = "vi"

    fun posterUrl(path: String?, size: String = "w500"): String? {
        return path?.let { "$IMG_BASE/$size$it" }
    }

    fun backdropUrl(path: String?, size: String = "w1280"): String? {
        return path?.let { "$IMG_BASE/$size$it" }
    }

    // ── TMDB Data Classes ──

    data class TmdbListResponse(
        @JsonProperty("page") val page: Int? = null,
        @JsonProperty("results") val results: List<TmdbItem>? = null,
        @JsonProperty("total_pages") val totalPages: Int? = null,
        @JsonProperty("total_results") val totalResults: Int? = null,
    )

    data class TmdbItem(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("title") val title: String? = null,       // movie
        @JsonProperty("name") val name: String? = null,         // tv
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("backdrop_path") val backdropPath: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("vote_average") val voteAverage: Double? = null,
        @JsonProperty("release_date") val releaseDate: String? = null,     // movie
        @JsonProperty("first_air_date") val firstAirDate: String? = null,  // tv
        @JsonProperty("media_type") val mediaType: String? = null,
    ) {
        /** Returns whichever name field is non-null (movie uses title, tv uses name) */
        fun displayTitle(): String = title ?: name ?: "Không rõ tên"

        /** Extract year from release_date or first_air_date */
        fun year(): Int? {
            val dateStr = releaseDate ?: firstAirDate ?: return null
            return dateStr.split("-").firstOrNull()?.toIntOrNull()
        }
    }

    data class TmdbDetail(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("backdrop_path") val backdropPath: String? = null,
        @JsonProperty("release_date") val releaseDate: String? = null,
        @JsonProperty("first_air_date") val firstAirDate: String? = null,
        @JsonProperty("vote_average") val voteAverage: Double? = null,
        @JsonProperty("runtime") val runtime: Int? = null,
        @JsonProperty("number_of_seasons") val numberOfSeasons: Int? = null,
        @JsonProperty("genres") val genres: List<TmdbGenre>? = null,
        @JsonProperty("tagline") val tagline: String? = null,
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("credits") val credits: TmdbCredits? = null,
    ) {
        fun displayTitle(): String = title ?: name ?: "Không rõ tên"

        fun year(): Int? {
            val dateStr = releaseDate ?: firstAirDate ?: return null
            return dateStr.split("-").firstOrNull()?.toIntOrNull()
        }
    }

    data class TmdbCredits(
        @JsonProperty("cast") val cast: List<TmdbCast>? = null,
    )

    data class TmdbCast(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("original_name") val originalName: String? = null,
        @JsonProperty("profile_path") val profilePath: String? = null,
        @JsonProperty("character") val character: String? = null,
    )

    data class TmdbGenre(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
    )

    // ── Source/Fshare Data Classes ──

    data class SourceResponse(
        @JsonProperty("tmdb_id") val tmdbId: String? = null,
        @JsonProperty("media_type") val mediaType: String? = null,
        @JsonProperty("sources") val sources: List<DownloadSource>? = null,
    )

    data class DownloadSource(
        @JsonProperty("uploader") val uploader: String? = null,
        @JsonProperty("sheet_name") val sheetName: String? = null,
        @JsonProperty("sheet_index") val sheetIndex: Int? = null,
        @JsonProperty("size") val size: String? = null,
        @JsonProperty("vmf_code") val vmfCode: String? = null,
        @JsonProperty("download_url") val downloadUrl: String? = null,
        @JsonProperty("trailer_url") val trailerUrl: String? = null,
    )

    // ── API Methods ──

    suspend fun getTrending(type: String, page: Int = 1): TmdbListResponse? {
        // type = "movie" or "tv" or "all"
        return try {
            app.get("$TMDB_BASE/trending/$type/day?api_key=$TMDB_API_KEY&language=$LANG&page=$page")
                .parsed<TmdbListResponse>()
        } catch (e: Exception) {
            null
        }
    }

    suspend fun getDiscover(type: String, networkId: String, page: Int = 1): TmdbListResponse? {
        return try {
            app.get("$TMDB_BASE/discover/$type?api_key=$TMDB_API_KEY&with_networks=$networkId&language=$LANG&page=$page")
                .parsed<TmdbListResponse>()
        } catch (e: Exception) {
            null
        }
    }

    suspend fun search(type: String, query: String, page: Int = 1): TmdbListResponse? {
        return try {
            app.get("$TMDB_BASE/search/$type?api_key=$TMDB_API_KEY&query=${java.net.URLEncoder.encode(query, "UTF-8")}&page=$page&language=$LANG")
                .parsed<TmdbListResponse>()
        } catch (e: Exception) {
            null
        }
    }

    suspend fun getDetail(type: String, tmdbId: Int): TmdbDetail? {
        return try {
            app.get("$TMDB_BASE/$type/$tmdbId?api_key=$TMDB_API_KEY&language=$LANG&append_to_response=credits")
                .parsed<TmdbDetail>()
        } catch (e: Exception) {
            null
        }
    }

    suspend fun getSources(type: String, tmdbId: Int): SourceResponse? {
        return try {
            app.get("$BASE_URL/api/$type/$tmdbId")
                .parsed<SourceResponse>()
        } catch (e: Exception) {
            null
        }
    }
}
