// SPDX-FileCopyrightText: 2025 Pierfrancesco Contino
// SPDX-License-Identifier: GPL-3.0-only

package com.pierfrancescocontino.sussurrato

import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class ModelDownloadManager(private val downloadDir: File) {

    @Throws(HttpDownloadException::class)
    fun downloadDirect(
        url: String,
        filename: String,
        onProgress: (Float) -> Unit,
    ): File {
        Log.d("ModelDownload", "downloadDirect: $url -> $filename")

        val outputFile = File(downloadDir, filename)
        val tempFile = File(downloadDir, "$filename.part")

        if (outputFile.exists()) {
            Log.d("ModelDownload", "file already exists, skipping: $outputFile")
            return outputFile
        }

        tempFile.delete()

        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.apply {
                setRequestProperty("User-Agent", "Sussurrato/1.0")
                setRequestProperty("Accept", "*/*")
                instanceFollowRedirects = true
                connectTimeout = 30_000
                readTimeout = 30_000
            }

            val responseCode = connection.responseCode
            Log.d("ModelDownload", "HTTP response: $responseCode")

            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw HttpDownloadException(responseCode, connection.responseMessage ?: "")
            }

            val contentLength = connection.contentLengthLong
            Log.d("ModelDownload", "content-length: $contentLength")

            tempFile.outputStream().use { output ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalBytesRead = 0L
                    var lastReportedProgress = 0

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead

                        if (contentLength > 0) {
                            val progress = ((totalBytesRead * 100) / contentLength).toInt()
                            if (progress != lastReportedProgress) {
                                lastReportedProgress = progress
                                onProgress(progress / 100f)
                            }
                        }
                    }
                }
            }

            if (!tempFile.renameTo(outputFile)) {
                tempFile.copyTo(outputFile, overwrite = true)
                tempFile.delete()
            }

            Log.d("ModelDownload", "download complete: $outputFile (${outputFile.length()} bytes)")
            return outputFile
        } catch (e: Exception) {
            tempFile.delete()
            Log.e("ModelDownload", "download failed: ${e.message}", e)
            throw e
        }
    }

    fun cancelDownload(filename: String) {
        val tempFile = File(downloadDir, "$filename.part")
        if (tempFile.exists()) {
            tempFile.delete()
            Log.d("ModelDownload", "cancelled (deleted part): $tempFile")
        }
    }
}

class HttpDownloadException(val httpCode: Int, val httpMessage: String) :
    Exception("HTTP $httpCode${if (httpMessage.isNotEmpty()) ": $httpMessage" else ""}")
