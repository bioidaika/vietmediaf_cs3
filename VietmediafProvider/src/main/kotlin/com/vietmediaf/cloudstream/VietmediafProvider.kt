package com.vietmediaf.cloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities

class VietmediafProvider : MainAPI() {
    override var mainUrl = "https://vietmediaf.store"
    override var name = "VietmediaF"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "vi"
    override val hasMainPage = true

    companion object {
        const val IMG_BASE = "https://image.tmdb.org/t/p"
    }

    // ── Settings: Fshare credentials ──
    // Users configure these via Cloudstream's plugin settings

    private fun getEmail(): String? {
        return try {
            com.lagradost.cloudstream3.AcraApplication.getKey("fshare_email")
        } catch (_: Exception) { null }
    }

    private fun getPassword(): String? {
        return try {
            com.lagradost.cloudstream3.AcraApplication.getKey("fshare_password")
        } catch (_: Exception) { null }
    }

    private suspend fun ensureFshareLogin(): Boolean {
        if (FshareApi.isLoggedIn) return true
        val email = getEmail() ?: return false
        val password = getPassword() ?: return false
        if (email.isBlank() || password.isBlank()) return false
        return FshareApi.login(email, password) == null
    }

    // ── Main Page ──

    override val mainPage = mainPageOf(
        "movie" to "🎬 Phim Lẻ Thịnh Hành",
        "tv" to "📺 Phim Bộ Thịnh Hành",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val type = request.data  // "movie" or "tv"
        val response = VietmediafApi.getTrending(type, page) ?: return null

        val items = response.results?.mapNotNull { item ->
            item.toSearchResponse(type)
        } ?: emptyList()

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = items,
                isHorizontalImages = false,
            ),
            hasNext = (response.page ?: 1) < (response.totalPages ?: 1)
        )
    }

    // ── Search ──

    override suspend fun search(query: String): List<SearchResponse>? {
        val movieResults = VietmediafApi.search("movie", query)
        val tvResults = VietmediafApi.search("tv", query)

        val results = mutableListOf<SearchResponse>()

        movieResults?.results?.forEach { item ->
            item.toSearchResponse("movie")?.let { results.add(it) }
        }

        tvResults?.results?.forEach { item ->
            item.toSearchResponse("tv")?.let { results.add(it) }
        }

        return results
    }

    // ── Load (Detail Page) ──

    override suspend fun load(url: String): LoadResponse? {
        // url format: "$mainUrl/{type}/{tmdb_id}"
        val parts = url.removePrefix("$mainUrl/").split("/")
        if (parts.size < 2) return null

        val type = parts[0]   // "movie" or "tv"
        val tmdbId = parts[1].toIntOrNull() ?: return null

        val detail = VietmediafApi.getDetail(type, tmdbId) ?: return null
        val sourcesResponse = VietmediafApi.getSources(type, tmdbId)
        val sources = sourcesResponse?.sources?.filter {
            !it.downloadUrl.isNullOrBlank() && it.downloadUrl != "None"
        } ?: emptyList()

        return buildLoadResponse(detail, sources, type)
    }

    @Suppress("DEPRECATION")
    private suspend fun buildLoadResponse(
        detail: VietmediafApi.TmdbDetail,
        sources: List<VietmediafApi.DownloadSource>,
        typeString: String,
    ): TvSeriesLoadResponse {
        val episodes = mutableListOf<Episode>()

        // Ensure Fshare is logged in for folder listing
        val fshareReady = ensureFshareLogin()

        for ((sourceIndex, source) in sources.withIndex()) {
            val downloadUrl = source.downloadUrl ?: continue
            val uploaderLabel = source.uploader ?: "Nguồn ${sourceIndex + 1}"
            val sizeLabel = source.size ?: ""

            if (downloadUrl.contains("/folder/")) {
                // It's a folder → list contents to get episodes
                if (!fshareReady) continue

                val linkcode = extractFolderLinkcode(downloadUrl)
                val folderResult = FshareApi.listFolder(linkcode)

                if (folderResult != null) {
                    val (subfolders, files) = folderResult

                    if (subfolders.isNotEmpty()) {
                        // Subfolders = Seasons
                        for ((seasonIdx, folder) in subfolders.sortedBy { it.name }.withIndex()) {
                            val subResult = FshareApi.listFolder(folder.effectiveLinkcode())
                            val subFiles = subResult?.second ?: continue

                            for ((epIdx, file) in subFiles.sortedBy { it.name }.withIndex()) {
                                val epData = """{"linkcode":"${file.effectiveLinkcode()}","name":"${file.name ?: ""}","uploader":"$uploaderLabel"}"""
                                episodes.add(
                                    newEpisode(epData) {
                                        this.name = file.name
                                        this.season = seasonIdx + 1
                                        this.episode = epIdx + 1
                                    }
                                )
                            }
                        }
                    } else if (files.isNotEmpty()) {
                        // No subfolders → all files = Season 1
                        for ((epIdx, file) in files.sortedBy { it.name }.withIndex()) {
                            val epData = """{"linkcode":"${file.effectiveLinkcode()}","name":"${file.name ?: ""}","uploader":"$uploaderLabel"}"""
                            episodes.add(
                                newEpisode(epData) {
                                    this.name = file.name
                                    this.season = sourceIndex + 1
                                    this.episode = epIdx + 1
                                }
                            )
                        }
                    }
                }
            } else if (downloadUrl.contains("/file/")) {
                // Single file source
                val linkcode = extractLinkcode(downloadUrl)
                val epData = """{"linkcode":"$linkcode","name":"$uploaderLabel $sizeLabel","uploader":"$uploaderLabel"}"""
                episodes.add(
                    newEpisode(epData) {
                        this.name = "$uploaderLabel ($sizeLabel)"
                        this.season = 1
                        this.episode = sourceIndex + 1
                    }
                )
            }
        }

        return newTvSeriesLoadResponse(
            name = detail.displayTitle(),
            url = "$mainUrl/$typeString/${detail.id}",
            type = if (typeString == "movie") TvType.Movie else TvType.TvSeries,
            episodes = episodes,
        ) {
            this.posterUrl = VietmediafApi.posterUrl(detail.posterPath)
            this.backgroundPosterUrl = VietmediafApi.backdropUrl(detail.backdropPath)
            this.year = detail.year()
            this.plot = detail.overview
            this.tags = detail.genres?.mapNotNull { it.name }
            this.actors = detail.credits?.cast?.mapNotNull { cast ->
                val actorName = cast.name ?: cast.originalName ?: return@mapNotNull null
                ActorData(
                    actor = Actor(actorName, VietmediafApi.posterUrl(cast.profilePath)),
                    roleString = cast.character
                )
            }
            this.recommendations = emptyList()
        }
    }

    // ── Load Links (Play) ──

    @Suppress("DEPRECATION")
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (!ensureFshareLogin()) return false

        // Parse the episode data JSON
        val linkcode: String
        val sourceName: String
        try {
            val parsed = parseJson<EpisodeData>(data)
            linkcode = parsed.linkcode ?: return false
            sourceName = parsed.uploader ?: parsed.name ?: "Fshare"
        } catch (_: Exception) {
            return false
        }

        val directUrl = FshareApi.resolve(linkcode) ?: return false

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = sourceName,
                url = directUrl,
            ) {
                this.quality = Qualities.Unknown.value
            }
        )

        return true
    }

    // ── Helper Data Class for episode data ──

    data class EpisodeData(
        val linkcode: String? = null,
        val name: String? = null,
        val uploader: String? = null,
        val size: String? = null,
    )

    // ── Helpers ──

    private fun VietmediafApi.TmdbItem.toSearchResponse(type: String): SearchResponse? {
        val tmdbId = this.id ?: return null
        return newMovieSearchResponse(
            name = this.displayTitle(),
            url = "$mainUrl/$type/$tmdbId",
            type = if (type == "movie") TvType.Movie else TvType.TvSeries,
        ) {
            this.posterUrl = VietmediafApi.posterUrl(this@toSearchResponse.posterPath)
            this.year = this@toSearchResponse.year()
        }
    }

    /**
     * Extract linkcode from a Fshare file URL.
     * e.g. "https://www.fshare.vn/file/ABC123" → "ABC123"
     */
    private fun extractLinkcode(url: String): String {
        val regex = Regex("""/file/([a-zA-Z0-9]+)""")
        return regex.find(url)?.groupValues?.get(1) ?: url
    }

    /**
     * Extract linkcode from a Fshare folder URL.
     * e.g. "https://www.fshare.vn/folder/ABC123" → "ABC123"
     */
    private fun extractFolderLinkcode(url: String): String {
        val regex = Regex("""/folder/([a-zA-Z0-9]+)""")
        return regex.find(url)?.groupValues?.get(1) ?: url
    }
}
