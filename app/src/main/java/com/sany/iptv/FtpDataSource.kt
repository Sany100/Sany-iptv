package com.sany.iptv

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Read-only passive-mode FTP data source for Media3.
 * Credentials are supplied at runtime and are never logged or persisted here.
 */
class FtpDataSource(
    private val username: String,
    private val password: String
) : BaseDataSource(true) {
    private var client: FTPClient? = null
    private var stream: InputStream? = null
    private var openedUri: Uri? = null
    private var bytesRemaining: Long = C.LENGTH_UNSET.toLong()
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val uri = dataSpec.uri
        val host = uri.host ?: throw IOException("FTP URL has no server host")
        val path = URLDecoder.decode(uri.encodedPath?.takeIf { it.isNotBlank() } ?: "/", StandardCharsets.UTF_8.name())
        val ftp = FTPClient()
        try {
            ftp.connectTimeout = 8000
            ftp.defaultTimeout = 8000
            ftp.dataTimeout = 15000
            ftp.connect(host, if (uri.port > 0) uri.port else 21)
            if (!ftp.login(username, password)) {
                throw IOException("FTP authentication failed (reply ${ftp.replyCode})")
            }
            ftp.enterLocalPassiveMode()
            if (!ftp.setFileType(FTP.BINARY_FILE_TYPE)) {
                throw IOException("FTP server rejected binary transfer mode")
            }
            if (dataSpec.position > 0L) ftp.restartOffset = dataSpec.position
            val input = ftp.retrieveFileStream(path)
                ?: throw IOException("FTP server could not open stream (reply ${ftp.replyCode})")
            client = ftp
            stream = input
            openedUri = uri
            bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else C.LENGTH_UNSET.toLong()
            opened = true
            transferStarted(dataSpec)
            return bytesRemaining
        } catch (error: Exception) {
            try { if (ftp.isConnected) ftp.disconnect() } catch (_: Exception) {}
            if (error is IOException) throw error
            throw IOException("FTP connection failed: ${error.message ?: "unknown error"}", error)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val allowed = if (bytesRemaining == C.LENGTH_UNSET.toLong()) length
            else minOf(length.toLong(), bytesRemaining).toInt()
        val count = stream?.read(buffer, offset, allowed) ?: throw IOException("FTP stream is not open")
        if (count == -1) return C.RESULT_END_OF_INPUT
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= count.toLong()
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = openedUri

    override fun close() {
        val input = stream
        val ftp = client
        stream = null
        client = null
        openedUri = null
        try {
            input?.close()
        } finally {
            try {
                if (ftp?.isConnected == true) {
                    ftp.completePendingCommand()
                    ftp.logout()
                    ftp.disconnect()
                }
            } catch (_: Exception) {
                try { if (ftp?.isConnected == true) ftp.disconnect() } catch (_: Exception) {}
            }
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    class Factory(
        private val username: String,
        private val password: String
    ) : androidx.media3.datasource.DataSource.Factory {
        override fun createDataSource(): androidx.media3.datasource.DataSource =
            FtpDataSource(username, password)
    }
}
