package com.actioncut.feature.export.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.actioncut.core.domain.port.MediaSaver
import com.actioncut.core.domain.usecase.ExportProjectUseCase
import com.actioncut.core.domain.usecase.GetProjectUseCase
import com.actioncut.core.model.ExportSettings
import com.actioncut.core.model.ExportState
import com.actioncut.core.model.VideoFormat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Runs a project export protected by a foreground service + partial wake lock so
 * exporting continues cleanly even when the screen turns off or the user presses Home.
 * Export resolution/bitrate remain 100% untouched.
 */
@Singleton
class ExportManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val getProject: GetProjectUseCase,
    private val exportProject: ExportProjectUseCase,
    private val mediaSaver: MediaSaver,
) {
    data class ExportHandle(val outputPath: String, val state: Flow<ExportState>)

    fun export(projectId: String, settings: ExportSettings): ExportHandle {
        val fileName = "ActionCut_${System.currentTimeMillis()}.${settings.format.extension}"
        val outputFile = File(context.cacheDir, fileName)
        val outputPath = outputFile.absolutePath

        val state = channelFlow {
            val wakeLock = acquireWakeLock()
            startKeepAliveService(0)
            try {
                val project = getProject(projectId)
                if (project == null) {
                    trySend(ExportState.Failed("Project not found"))
                    close()
                    return@channelFlow
                }
                var lastPct = -1
                exportProject(project, settings, outputPath).collect { s ->
                    when (s) {
                        is ExportState.InProgress -> {
                            val pct = (s.progress * 100).roundToInt().coerceIn(0, 99)
                            if (pct != lastPct && pct % 5 == 0) {
                                lastPct = pct
                                startKeepAliveService(pct)
                            }
                            trySend(s)
                        }
                        is ExportState.Completed -> {
                            val mime = if (settings.format == VideoFormat.WEBM_VP9) "video/webm" else "video/mp4"
                            val galleryUri = runCatching {
                                mediaSaver.saveVideoToGallery(outputPath, fileName, mime)
                            }.getOrNull()
                            runCatching { outputFile.delete() }
                            trySend(ExportState.Completed(galleryUri ?: s.outputUri))
                        }
                        else -> trySend(s)
                    }
                }
                close()
            } finally {
                stopKeepAliveService()
                releaseWakeLock(wakeLock)
            }
        }
        return ExportHandle(outputPath, state)
    }

    fun cancel(projectId: String) {
        stopKeepAliveService()
    }

    private fun startKeepAliveService(progressPct: Int) {
        runCatching {
            val intent = Intent(context, ExportForegroundService::class.java)
                .putExtra(ExportForegroundService.EXTRA_PROGRESS, progressPct)
            ContextCompat.startForegroundService(context, intent)
        }
    }

    private fun stopKeepAliveService() {
        runCatching {
            context.stopService(Intent(context, ExportForegroundService::class.java))
        }
    }

    private fun acquireWakeLock(): PowerManager.WakeLock? = runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return null
        pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ActionCut:ExportWakeLock").apply {
            setReferenceCounted(false)
            acquire(60 * 60 * 1000L) // 60-minute safety timeout
        }
    }.getOrNull()

    private fun releaseWakeLock(wakeLock: PowerManager.WakeLock?) {
        runCatching {
            if (wakeLock != null && wakeLock.isHeld) wakeLock.release()
        }
    }
}

/** Lightweight foreground service keeping the Media3 export process alive during screen-off / background. */
class ExportForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        val pct = intent?.getIntExtra(EXTRA_PROGRESS, 0) ?: 0
        val notification = buildNotification(pct)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Video Export",
                NotificationManager.IMPORTANCE_LOW,
            )
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(progressPct: Int): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("ActionCut Exporting Video")
            .setContentText(if (progressPct > 0) "Exporting… $progressPct%" else "Preparing export…")
            .setProgress(100, progressPct, progressPct == 0)
            .setOngoing(true)
            .setSilent(true)
            .build()

    companion object {
        private const val CHANNEL_ID = "actioncut_export_channel"
        private const val NOTIFICATION_ID = 4201
        const val EXTRA_PROGRESS = "extra_progress"
    }
}
