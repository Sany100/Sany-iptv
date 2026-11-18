package com.sany.iptv

object M3uParser {
    fun parse(text: String): List<Channel> {
        val result = mutableListOf<Channel>()
        var name = ""
        var group = "Other"
        var logo: String? = null
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val attrs = Regex("([\\w-]+)=\"([^\"]*)\"").findAll(line)
                        .associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    name = line.substringAfterLast(',').trim().ifBlank { "Unknown channel" }
                    group = attrs["group-title"]?.ifBlank { "Other" } ?: "Other"
                    logo = attrs["tvg-logo"]?.takeIf { it.isNotBlank() }
                }
                line.isNotBlank() && !line.startsWith("#") -> {
                    val fallback = line.substringAfterLast('/').ifBlank { "Channel ${result.size + 1}" }
                    result += Channel(name.ifBlank { fallback }, line, group, logo)
                    name = ""
                    group = "Other"
                    logo = null
                }
            }
        }
        return result.distinctBy { it.url }
    }
}