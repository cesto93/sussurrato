// SPDX-FileCopyrightText: 2026 Pierfrancesco Contino
// SPDX-License-Identifier: GPL-3.0-only

package com.pierfrancescocontino.sussurrato

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

object DownloadEventBus {
    private val _events = MutableSharedFlow<DownloadEvent>(replay = 1, extraBufferCapacity = 4)
    val events: SharedFlow<DownloadEvent> = _events.asSharedFlow()

    fun emit(event: DownloadEvent) {
        _events.tryEmit(event)
    }
}

sealed class DownloadEvent {
    data class Progress(val modelId: String, val progress: Float) : DownloadEvent()
    data class Completed(val modelId: String) : DownloadEvent()
    data class Failed(val modelId: String, val error: String) : DownloadEvent()
}

class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DOWNLOAD -> {
                @Suppress("DEPRECATION")
                val model = intent.getSerializableExtra(EXTRA_MODEL) as? DownloadableModel
                if (model != null) {
                    startDownload(model)
                }
            }
            ACTION_CANCEL -> {
                cancelDownload()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Model Downloads",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun startDownload(model: DownloadableModel) {
        Log.d("DownloadService", "startDownload: ${model.id}")

        val notification = buildNotification(model, 0f)
        startForeground(NOTIFICATION_ID, notification)

        val downloadDir = getExternalFilesDir(null) ?: filesDir
        val dm = ModelDownloadManager(downloadDir)

        downloadJob = scope.launch {
            try {
                // Notify that download has started
                DownloadEventBus.emit(DownloadEvent.Progress(model.id, 0f))

                dm.downloadDirect(
                    url = model.url,
                    filename = model.filename,
                    onProgress = { progress ->
                        updateNotification(model, progress)
                        DownloadEventBus.emit(DownloadEvent.Progress(model.id, progress))
                    },
                )

                if (model.isGguf && model.mmprojUrl != null && model.mmprojFilename != null) {
                    dm.downloadDirect(
                        url = model.mmprojUrl,
                        filename = model.mmprojFilename,
                        onProgress = { progress ->
                            val overall = 0.5f + progress * 0.5f
                            updateNotification(model, overall)
                            DownloadEventBus.emit(DownloadEvent.Progress(model.id, overall))
                        },
                    )
                }

                Log.d("DownloadService", "download complete: ${model.id}")
                showFinalNotification(model, "Download complete")
                DownloadEventBus.emit(DownloadEvent.Completed(model.id))
            } catch (e: HttpDownloadException) {
                Log.e("DownloadService", "HTTP ${e.httpCode}: ${e.httpMessage}")
                val msg = when (e.httpCode) {
                    403 -> "Access denied (HTTP 403)"
                    404 -> "Model file not found (HTTP 404)"
                    503 -> "Service unavailable (HTTP 503)"
                    else -> "HTTP ${e.httpCode}: ${e.httpMessage}"
                }
                showFinalNotification(model, "Download failed: $msg")
                DownloadEventBus.emit(DownloadEvent.Failed(model.id, msg))
            } catch (e: Exception) {
                Log.e("DownloadService", "download failed: ${e.message}", e)
                val msg = e.message ?: "Unknown error"
                showFinalNotification(model, "Download failed")
                DownloadEventBus.emit(DownloadEvent.Failed(model.id, msg))
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(model: DownloadableModel, progress: Float): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Downloading ${model.displayName}")
            .setContentText("${(progress * 100).toInt()}%")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setProgress(100, (progress * 100).toInt(), false)
            .build()
    }

    private fun updateNotification(model: DownloadableModel, progress: Float) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(model, progress))
    }

    private fun showFinalNotification(model: DownloadableModel, text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(model.displayName)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFICATION_ID + 1, notification)
    }

    companion object {
        private const val CHANNEL_ID = "model_download"
        private const val NOTIFICATION_ID = 1001

        private const val ACTION_DOWNLOAD = "download"
        private const val ACTION_CANCEL = "cancel"
        private const val EXTRA_MODEL = "model"

        fun startDownload(context: Context, model: DownloadableModel) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_DOWNLOAD
                putExtra(EXTRA_MODEL, model)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancel(context: Context) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_CANCEL
            }
            context.startService(intent)
        }
    }
}
