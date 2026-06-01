package com.pierfrancescocontino.sussurrato

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import java.io.File

class ModelDownloadManager(appContext: Context) {

    private val downloadManager =
        appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val externalDir = appContext.getExternalFilesDir(null)

    fun enqueueDownload(model: DownloadableModel): Long {
        val request = DownloadManager.Request(Uri.parse(model.url))
            .setTitle("Downloading ${model.displayName}")
            .setDescription("Model file (${model.sizeLabel})")
            .setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
            )
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .addRequestHeader("User-Agent", "Sussurrato/1.0")

        if (externalDir != null) {
            request.setDestinationUri(
                Uri.fromFile(File(externalDir, model.filename)),
            )
        }

        return downloadManager.enqueue(request)
    }

    fun queryProgress(downloadId: Long): DownloadProgressInfo? {
        val query = DownloadManager.Query().setFilterById(downloadId)
        return downloadManager.query(query).use { cursor ->
            if (cursor.moveToFirst()) {
                DownloadProgressInfo(
                    bytesDownloaded = cursor.getLong(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
                    ),
                    totalBytes = cursor.getLong(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
                    ),
                    status = cursor.getInt(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS),
                    ),
                    reason = cursor.getInt(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON),
                    ),
                    localUri = cursor.getString(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI),
                    ),
                )
            } else null
        }
    }

    fun cancelDownload(downloadId: Long) {
        downloadManager.remove(downloadId)
    }

    fun getDownloadedFile(model: DownloadableModel): File? {
        val dir = externalDir ?: return null
        val file = File(dir, model.filename)
        return if (file.exists()) file else null
    }
}

data class DownloadProgressInfo(
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val status: Int,
    val reason: Int,
    val localUri: String?,
)
