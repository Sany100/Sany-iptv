package com.sany.iptv

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

object EpgRepository {
    val defaultSources = listOf(
        "http://epg.it999.ru/epg.xml.gz",
        "https://worker-9dd4.onrender.com/guide.xml.gz",
        "https://raw.githubusercontent.com/StrangeDrVN/epg/public/guide.xml.gz"
    )

    fun update(context: Context): String {
        val mergedBodies = StringBuilder()
        var rootTag: String? = null
        var success = 0
        val errors = mutableListOf<String>()

        defaultSources.forEachIndexed { index, source ->
            var connection: HttpURLConnection? = null
            try {
                connection = URL(source).openConnection() as HttpURLConnection
                connection.connectTimeout = 20000
                connection.readTimeout = 60000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", "SANY-IPTV-Android")
                connection.setRequestProperty("Accept", "application/gzip, application/xml, text/xml, */*")
                val input = BufferedInputStream(connection.inputStream)
                input.mark(2)
                val magic = ByteArray(2)
                val read = input.read(magic)
                input.reset()
                val decoded = if (read == 2 && magic[0] == 0x1f.toByte() && magic[1] == 0x8b.toByte()) {
                    GZIPInputStream(input)
                } else input
                val bytes = decoded.use { stream ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(16 * 1024)
                    var total = 0
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= 60 * 1024 * 1024) { "EPG feed too large" }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                val xml = bytes.toString(Charsets.UTF_8)
                val open = Regex("(?is)<tv\\b[^>]*>").find(xml)
                    ?: throw IllegalStateException("Missing XMLTV root")
                val close = Regex("(?is)</tv\\s*>").findAll(xml).lastOrNull()
                    ?: throw IllegalStateException("Incomplete XMLTV document")
                var body = xml.substring(open.range.last + 1, close.range.first)
                body = body.replace(Regex("(?is)<!DOCTYPE\\s+tv(?:\\s+[^>]*)?>"), "")
                body = body.replace(Regex("(?is)<\\?xml[^?]*\\?>"), "")
                if (rootTag == null) rootTag = open.value
                mergedBodies.append(body).append('\n')
                success++
            } catch (e: Exception) {
                errors.add("Source ${index + 1}: ${e.message ?: "download failed"}")
            } finally {
                connection?.disconnect()
            }
        }

        if (success == 0 || rootTag == null) {
            throw IllegalStateException(errors.joinToString("; ").take(220).ifBlank { "All EPG sources failed" })
        }
        val output = java.io.File(context.filesDir, "epg_data.xml")
        val temp = java.io.File(context.filesDir, "epg_data.xml.tmp")
        temp.writeText("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + rootTag + "\n" +
            mergedBodies.toString() + "\\n</tv>", Charsets.UTF_8)
        if (output.exists() && !output.delete()) throw IllegalStateException("Cannot replace old EPG file")
        if (!temp.renameTo(output)) {
            temp.copyTo(output, overwrite = true)
            temp.delete()
        }
        context.getSharedPreferences("sany_iptv", Context.MODE_PRIVATE).edit()
            .putString("epg_last_updated", System.currentTimeMillis().toString())
            .putInt("epg_sources_updated", success)
            .apply()
        return "Updated $success/${defaultSources.size} EPG sources"
    }

    fun scheduleDaily(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<EpgDailyWorker>(24, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "sany_daily_epg_update",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
}

class EpgDailyWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        return try {
            EpgRepository.update(applicationContext)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
