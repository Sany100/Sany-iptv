package com.anilight

import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink

/**
 * Narrow DTO boundary between the real CloudStream AniLight provider ABI and SANY.
 *
 * This bridge is compiled only in the pinned upstream CloudStream host for now.
 * It deliberately does not load plugin DEX or introduce CloudStream classes into
 * the SANY app classloader.
 */
data class SanyAniLightCard(
    val title: String,
    val url: String,
    val provider: String,
    val contentType: String?,
    val posterUrl: String?,
    val year: Int?,
    val score: String?
)

data class SanyAniLightEpisode(
    val group: String,
    val data: String,
    val title: String?,
    val episode: Int?,
    val season: Int?,
    val posterUrl: String?,
    val description: String?,
    val dateEpochMillis: Long?
)

data class SanyAniLightDetail(
    val title: String,
    val url: String,
    val contentType: String,
    val posterUrl: String?,
    val backgroundPosterUrl: String?,
    val year: Int?,
    val plot: String?,
    val episodes: List<SanyAniLightEpisode>
)

data class SanyAniLightSubtitle(
    val language: String,
    val url: String,
    val headers: Map<String, String>
)

data class SanyAniLightStream(
    val source: String,
    val label: String,
    val url: String,
    val streamType: String,
    val quality: Int,
    val headers: Map<String, String>,
    val subtitles: List<SanyAniLightSubtitle>
)

data class SanyAniLightLinkResult(
    val success: Boolean,
    val streams: List<SanyAniLightStream>,
    val subtitles: List<SanyAniLightSubtitle>
)

/**
 * Uses CloudStream's real provider, HTTP client, JSON utilities and coroutine
 * runtime. The caller receives only SANY-neutral Kotlin data classes.
 */
class SanyAniLightHostBridge(
    private val provider: AniLightProvider = AniLightProvider()
) {
    suspend fun search(query: String): List<SanyAniLightCard> =
        provider.search(query).map(::toCard)

    suspend fun homepage(sectionData: String, page: Int = 1): List<SanyAniLightCard> {
        require(page > 0) { "Page must be greater than zero" }
        val section = provider.mainPage.firstOrNull { it.data == sectionData }
            ?: throw IllegalArgumentException("Unsupported AniLight section: $sectionData")
        val request = MainPageRequest(
            name = section.name,
            data = section.data,
            horizontalImages = section.horizontalImages
        )
        val response: HomePageResponse = provider.getMainPage(page, request)
            ?: return emptyList()
        return response.items.flatMap { row -> row.list.map(::toCard) }
    }

    suspend fun details(url: String): SanyAniLightDetail? {
        val response: LoadResponse = provider.load(url) ?: return null
        val anime = response as? AnimeLoadResponse
            ?: throw IllegalStateException("AniLight returned a non-anime load response")
        val episodes = anime.episodes.flatMap { (dubStatus, values) ->
            values.map { episode ->
                SanyAniLightEpisode(
                    group = dubStatus.name,
                    data = episode.data,
                    title = episode.name,
                    episode = episode.episode,
                    season = episode.season,
                    posterUrl = episode.posterUrl,
                    description = episode.description,
                    dateEpochMillis = episode.date
                )
            }
        }.sortedWith(compareBy<SanyAniLightEpisode> { it.season ?: 0 }.thenBy { it.episode ?: 0 })

        return SanyAniLightDetail(
            title = anime.name,
            url = anime.url,
            contentType = anime.type.name,
            posterUrl = anime.posterUrl,
            backgroundPosterUrl = anime.backgroundPosterUrl,
            year = anime.year,
            plot = anime.plot,
            episodes = episodes
        )
    }

    suspend fun links(episodeData: String, isCasting: Boolean = false): SanyAniLightLinkResult {
        val streams = mutableListOf<SanyAniLightStream>()
        val subtitles = linkedMapOf<String, SanyAniLightSubtitle>()
        val success = provider.loadLinks(
            data = episodeData,
            isCasting = isCasting,
            subtitleCallback = { subtitle -> subtitles.putIfAbsent(subtitle.url, subtitle.toSanySubtitle()) },
            callback = { link ->
                streams += link.toSanyStream(emptyList())
            }
        )
        val finalSubtitles = subtitles.values.toList()
        return SanyAniLightLinkResult(
            success = success && streams.isNotEmpty(),
            streams = streams.map { it.copy(subtitles = finalSubtitles) },
            subtitles = finalSubtitles
        )
    }

    private fun toCard(item: SearchResponse): SanyAniLightCard {
        val anime = item as? com.lagradost.cloudstream3.AnimeSearchResponse
        return SanyAniLightCard(
            title = item.name,
            url = item.url,
            provider = item.apiName,
            contentType = item.type?.name,
            posterUrl = item.posterUrl,
            year = anime?.year,
            score = item.score?.toString()
        )
    }

    private fun SubtitleFile.toSanySubtitle(): SanyAniLightSubtitle =
        SanyAniLightSubtitle(
            language = lang,
            url = url,
            headers = headers.orEmpty()
        )

    private fun ExtractorLink.toSanyStream(
        subtitles: List<SanyAniLightSubtitle>
    ): SanyAniLightStream {
        val mergedHeaders = if (referer.isBlank() || headers.keys.any { it.equals("referer", true) }) {
            headers
        } else {
            headers + ("Referer" to referer)
        }
        return SanyAniLightStream(
            source = source,
            label = name,
            url = url,
            streamType = type.name,
            quality = quality,
            headers = mergedHeaders,
            subtitles = subtitles
        )
    }
}
