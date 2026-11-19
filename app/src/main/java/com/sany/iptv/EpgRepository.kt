package com.sany.iptv

import android.content.Context
import android.util.Xml
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlSerializer
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
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

    private data class TvRoot(val attributes: List<Triple<String, String, String>>)

    /**
     * Streams XMLTV entries to disk. It never builds a complete feed or merged guide
     * in memory, which avoids OutOfMemoryError on large EPG sources.
     */
    private fun streamFeedBody(source: String, destination: File): TvRoot {
        var connection: HttpURLConnection? = null
        try {
            connection = URL(source).openConnection() as HttpURLConnection
            connection.connectTimeout = 20000
            connection.readTimeout = 60000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "SANY-IPTV-Android")
            connection.setRequestProperty("Accept", "application/gzip, application/xml, text/xml, */*")

            val raw = BufferedInputStream(connection.inputStream)
            raw.mark(2)
            val magic = ByteArray(2)
            val count = raw.read(magic)
            raw.reset()
            val decoded: InputStream =
                if (count == 2 && magic[0] == 0x1f.toByte() && magic[1] == 0x8b.toByte()) {
                    GZIPInputStream(raw)
                } else raw

            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
            parser.setInput(decoded, "UTF-8")
            val serializer: XmlSerializer = Xml.newSerializer()
            val rootAttributes = mutableListOf<Triple<String, String, String>>()
            var rootFound = false
            var rootClosed = false

            FileOutputStream(destination, false).use { output ->
                serializer.setOutput(output, "UTF-8")
                while (true) {
                    when (parser.next()) {
                        XmlPullParser.START_TAG -> {
                            if (!rootFound) {
                                if (!parser.name.equals("tv", ignoreCase = true)) {
                                    throw IllegalStateException("XMLTV root <tv> not found")
                                }
                                rootFound = true
                                for (i in 0 until parser.attributeCount) {
                                    rootAttributes.add(
                                        Triple(
                                            parser.getAttributeNamespace(i) ?: "",
                                            parser.getAttributeName(i),
                                            parser.getAttributeValue(i)
                                        )
                                    )
                                }
                            } else {
                                serializer.startTag(parser.namespace ?: "", parser.name)
                                for (i in 0 until parser.attributeCount) {
                                    serializer.attribute(
                                        parser.getAttributeNamespace(i) ?: "",
                                        parser.getAttributeName(i),
                                        parser.getAttributeValue(i)
                                    )
                                }
                            }
                        }
                        XmlPullParser.END_TAG -> {
                            if (rootFound && parser.depth == 1 &&
                                parser.name.equals("tv", ignoreCase = true)
                            ) {
                                rootClosed = true
                                break
                            } else if (rootFound && parser.depth >= 2) {
                                serializer.endTag(parser.namespace ?: "", parser.name)
                            }
                        }
                        XmlPullParser.TEXT -> if (rootFound && parser.depth >= 2) {
                            serializer.text(parser.text)
                        }
                        XmlPullParser.CDSECT -> if (rootFound && parser.depth >= 2) {
                            serializer.cdsect(parser.text)
                        }
                        XmlPullParser.ENTITY_REF -> if (rootFound && parser.depth >= 2) {
                            serializer.text(parser.text ?: "")
                        }
                        XmlPullParser.COMMENT -> if (rootFound && parser.depth >= 2) {
                            serializer.comment(parser.text)
                        }
                        XmlPullParser.PROCESSING_INSTRUCTION ->
                            if (rootFound && parser.depth >= 2) serializer.processingInstruction(parser.text)
                        XmlPullParser.IGNORABLE_WHITESPACE ->
                            if (rootFound && parser.depth >= 2) serializer.ignorableWhitespace(parser.text)
                        XmlPullParser.END_DOCUMENT -> break
                    }
                }
                serializer.flush()
            }
            if (!rootFound || !rootClosed) {
                throw IllegalStateException("Incomplete XMLTV document")
            }
            return TvRoot(rootAttributes)
        } finally {
            connection?.disconnect()
        }
    }

    fun update(context: Context): String {
        val bodyFile = File(context.filesDir, "epg_body.tmp")
        val output = File(context.filesDir, "epg_data.xml")
        val temp = File(context.filesDir, "epg_data.xml.tmp")
        var root: TvRoot? = null
        var success = 0
        val errors = mutableListOf<String>()

        try {
            if (bodyFile.exists()) bodyFile.delete()
            bodyFile.createNewFile()

            defaultSources.forEachIndexed { index, source ->
                val sourceBody = File(context.filesDir, "epg_source_$index.tmp")
                try {
                    val sourceRoot = streamFeedBody(source, sourceBody)
                    if (root == null) root = sourceRoot
                    FileOutputStream(bodyFile, true).use { destination ->
                        sourceBody.inputStream().use { it.copyTo(destination, 16 * 1024) }
                        destination.write('\n'.code)
                    }
                    success++
                } catch (e: Exception) {
                    errors.add("Source ${index + 1}: ${e.message ?: "download failed"}")
                } finally {
                    sourceBody.delete()
                }
            }

            val validRoot = root
            if (success == 0 || validRoot == null) {
                throw IllegalStateException(
                    errors.joinToString("; ").take(220).ifBlank { "All EPG sources failed" }
                )
            }

            val header = ByteArrayOutputStream()
            val headerSerializer = Xml.newSerializer()
            headerSerializer.setOutput(header, "UTF-8")
            headerSerializer.startDocument("UTF-8", true)
            headerSerializer.startTag("", "tv")
            validRoot.attributes.forEach { (namespace, name, value) ->
                headerSerializer.attribute(namespace, name, value)
            }
            headerSerializer.flush()

            FileOutputStream(temp, false).use { destination ->
                destination.write(header.toByteArray())
                bodyFile.inputStream().use { it.copyTo(destination, 16 * 1024) }
                destination.write("</tv>".toByteArray(Charsets.UTF_8))
            }

            if (!temp.renameTo(output)) {
                temp.copyTo(output, overwrite = true)
                temp.delete()
            }
            context.getSharedPreferences("sany_iptv", Context.MODE_PRIVATE).edit()
                .putString("epg_last_updated", System.currentTimeMillis().toString())
                .putInt("epg_sources_updated", success)
                .apply()
            return "Updated $success/${defaultSources.size} EPG sources"
        } finally {
            bodyFile.delete()
            temp.delete()
        }
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
