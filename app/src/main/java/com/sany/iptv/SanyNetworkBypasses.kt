package com.sany.iptv

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** ISP bypass transport for SANY extension repository/catalogue requests. */
object SanyNetworkBypasses {
    private const val PREFS = "sany_network_bypasses"
    private const val CLONES = "clone_sites"
    private val rawGithub = Regex("^https://raw\\.githubusercontent\\.com/([A-Za-z0-9-]+)/([A-Za-z0-9_.-]+)/(.+)$")

    fun githubProxyEnabled(context: Context) = context.getSharedPreferences(PREFS, 0).getBoolean("github_proxy", false)
    fun dohEnabled(context: Context) = context.getSharedPreferences(PREFS, 0).getBoolean("doh_enabled", false)
    fun dohProvider(context: Context) = context.getSharedPreferences(PREFS, 0).getString("doh_provider", "Cloudflare") ?: "Cloudflare"

    fun cloneMappings(context: Context): Map<String, String> {
        val raw = context.getSharedPreferences(PREFS, 0).getString(CLONES, "{}") ?: "{}"
        return runCatching {
            val json = JSONObject(raw)
            buildMap {
                val keys = json.keys()
                while (keys.hasNext()) {
                    val host = keys.next().lowercase().trim()
                    val base = json.optString(host).trim().trimEnd('/')
                    if (host.isNotBlank() && base.startsWith("https://")) put(host, base)
                }
            }
        }.getOrDefault(emptyMap())
    }

    fun saveClone(context: Context, hostInput: String, mirrorInput: String): Boolean {
        val host = hostInput.trim().lowercase()
        val mirror = mirrorInput.trim().trimEnd('/')
        if (!host.matches(Regex("^[a-z0-9.-]+$")) || !mirror.startsWith("https://") || mirror.contains("@") || mirror.contains(" ")) return false
        val prefs = context.getSharedPreferences(PREFS, 0)
        val json = runCatching { JSONObject(prefs.getString(CLONES, "{}") ?: "{}") }.getOrElse { JSONObject() }
        json.put(host, mirror)
        prefs.edit().putString(CLONES, json.toString()).apply()
        return true
    }

    fun rewriteUrl(context: Context, input: String): String {
        var url = input
        if (githubProxyEnabled(context)) {
            val match = rawGithub.find(url)
            if (match != null) {
                val (owner, repo, rawPath) = match.destructured
                // Raw GitHub URLs commonly use refs/heads/<branch>/...; jsDelivr expects @<branch>/...
                val path = when {
                    rawPath.startsWith("refs/heads/") -> rawPath.removePrefix("refs/heads/")
                    rawPath.startsWith("refs/tags/") -> rawPath.removePrefix("refs/tags/")
                    else -> rawPath
                }
                url = "https://cdn.jsdelivr.net/gh/$owner/$repo@$path"
            }
        }
        val parsed = runCatching { url.toHttpUrl() }.getOrNull() ?: return url
        val mirror = cloneMappings(context)[parsed.host.lowercase()] ?: return url
        val path = parsed.encodedPath + (parsed.encodedQuery?.let { "?$it" } ?: "")
        return mirror + if (path == "/") "" else path
    }

    fun client(context: Context): OkHttpClient {
        val builder = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS).followRedirects(true).followSslRedirects(false)
        if (dohEnabled(context)) {
            val (endpoint, ips) = when (dohProvider(context)) {
                "Google" -> "https://dns.google/dns-query" to listOf("8.8.8.8", "8.8.4.4")
                "Quad9" -> "https://dns.quad9.net/dns-query" to listOf("9.9.9.9", "149.112.112.112")
                else -> "https://cloudflare-dns.com/dns-query" to listOf("1.1.1.1", "1.0.0.1")
            }
            builder.dns(DnsOverHttps.Builder().client(OkHttpClient()).url(endpoint.toHttpUrl())
                .bootstrapDnsHosts(ips.map { InetAddress.getByName(it) }).build())
        }
        return builder.build()
    }

    fun execute(context: Context, url: String): Response {
        val request = Request.Builder().url(rewriteUrl(context, url))
            .header("User-Agent", "SANY-IPTV-Extensions/1.0")
            .header("Accept", "application/json, application/zip, application/octet-stream").build()
        val response = client(context).newCall(request).execute()
        if (!response.isSuccessful || !response.request.url.isHttps) {
            val code = response.code
            response.close()
            error("Repository request failed: HTTP $code")
        }
        return response
    }
}