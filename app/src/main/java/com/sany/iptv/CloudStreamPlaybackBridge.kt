package com.sany.iptv

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType

/**
 * Converts CloudStream extractor output into the player-neutral SANY request.
 * Headers and referer are preserved so signed/host-restricted streams can load.
 */
object CloudStreamPlaybackBridge {
    fun toSanyRequest(
        title: String,
        link: ExtractorLink,
        subtitles: List<SubtitleFile> = emptyList(),
        provider: String = link.source
    ): SanyPlaybackRequest {
        val subtitleTracks = subtitles.map { subtitle ->
            val path = subtitle.url.substringBefore('?').lowercase()
            val mime = when {
                path.endsWith(".vtt") -> "text/vtt"
                path.endsWith(".srt") -> "application/x-subrip"
                path.endsWith(".ass") || path.endsWith(".ssa") -> "text/x-ssa"
                else -> null
            }
            SanySubtitleTrack(
                url = subtitle.url,
                language = subtitle.langTag ?: subtitle.lang,
                mimeType = mime,
                label = subtitle.lang,
                headers = subtitle.headers.orEmpty()
            )
        }
        val mime = when (link.type) {
            ExtractorLinkType.M3U8 -> "application/x-mpegURL"
            ExtractorLinkType.DASH -> "application/dash+xml"
            ExtractorLinkType.VIDEO -> "video/*"
            else -> null
        }
        return SanyPlaybackRequest(
            title = title,
            url = link.url,
            provider = provider,
            streamMimeType = mime,
            quality = link.quality.takeIf { it > 0 }?.let { "${it}p" },
            headers = link.getAllHeaders(),
            subtitles = subtitleTracks,
            audioTracks = link.audioTracks.mapIndexedNotNull { index, audio ->
                audio.url.takeIf { it.startsWith("https://", true) || it.startsWith("http://", true) }
                    ?.let { url ->
                        SanyAudioTrack(
                            url = url,
                            label = "External audio ${index + 1}",
                            headers = audio.headers.orEmpty()
                        )
                    }
            }
        )
    }
}
