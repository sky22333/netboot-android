package com.sky22333.netboot.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.sky22333.netboot.MainActivity
import com.sky22333.netboot.R
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@AndroidEntryPoint
class DownloadService : Service() {
    @Inject lateinit var repository: DownloadRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val commands = Mutex()
    private var pendingCommands = 0
    private val jobs = ConcurrentHashMap<String, Job>()
    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val taskId = intent?.getStringExtra(ExtraTaskId) ?: return START_NOT_STICKY
        startAsForeground(taskId, 0, 0)
        pendingCommands++
        scope.launch {
            commands.withLock {
                try {
                    when (intent.action) {
                        ActionStart -> startTask(taskId)
                        ActionPause -> pauseTask(taskId)
                        ActionCancel -> cancelTask(taskId, intent.getBooleanExtra(ExtraDeletePartial, false))
                    }
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    terminalNotification(taskId, false)
                } finally {
                    pendingCommands--
                    stopIfIdle()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        jobs.values.forEach { it.cancel() }
        scope.cancel()
        super.onDestroy()
    }

    private fun startTask(taskId: String) {
        if (jobs[taskId]?.isActive == true) return
        startAsForeground(taskId, 0, 0)
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            runCatching {
                repository.run(taskId) { progress ->
                    updateNotification(progress.taskId, progress.downloadedBytes, progress.totalBytes)
                }
            }.onSuccess {
                terminalNotification(taskId, true)
            }.onFailure { error ->
                if (error !is kotlinx.coroutines.CancellationException) terminalNotification(taskId, false)
            }
        }
        jobs[taskId] = job
        job.invokeOnCompletion {
            scope.launch {
                commands.withLock {
                    jobs.remove(taskId, job)
                    stopIfIdle()
                }
            }
        }
        job.start()
    }

    private suspend fun pauseTask(taskId: String) {
        jobs.remove(taskId)?.cancelAndJoin()
        repository.markPaused(taskId)
    }

    private suspend fun cancelTask(taskId: String, deletePartial: Boolean) {
        jobs.remove(taskId)?.cancelAndJoin()
        repository.cancel(taskId, deletePartial)
        notificationManager.cancel(taskId.notificationId())
    }

    private fun stopIfIdle() {
        if (jobs.isEmpty() && pendingCommands == 0) {
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // run() persists cancellation in NonCancellable; stopping promptly satisfies the platform deadline.
        jobs.values.forEach { it.cancel() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startAsForeground(taskId: String, downloaded: Long, total: Long) {
        ServiceCompat.startForeground(
            this,
            taskId.notificationId(),
            buildNotification(taskId, downloaded, total),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
    }

    private fun updateNotification(taskId: String, downloaded: Long, total: Long) {
        notificationManager.notify(taskId.notificationId(), buildNotification(taskId, downloaded, total))
    }

    private fun buildNotification(taskId: String, downloaded: Long, total: Long): Notification {
        val progress = if (total > 0) ((downloaded * 100) / total).toInt() else 0
        val pauseIntent = servicePendingIntent(ActionPause, taskId)
        val cancelIntent = servicePendingIntent(ActionCancel, taskId)
        return NotificationCompat.Builder(this, ChannelId)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.download_running))
            .setContentText(if (total > 0) getString(R.string.download_progress, formatBytes(downloaded), formatBytes(total)) else null)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, progress, total <= 0)
            .setContentIntent(mainPendingIntent())
            .addAction(0, getString(R.string.pause), pauseIntent)
            .addAction(0, getString(R.string.cancel), cancelIntent)
            .build()
    }

    private fun terminalNotification(taskId: String, success: Boolean) {
        notificationManager.notify(
            taskId.notificationId(),
            NotificationCompat.Builder(this, ChannelId)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(getString(if (success) R.string.download_complete else R.string.download_failed))
                .setAutoCancel(true)
                .setContentIntent(mainPendingIntent())
                .build(),
        )
    }

    private fun mainPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun servicePendingIntent(action: String, taskId: String): PendingIntent = PendingIntent.getService(
        this,
        taskId.hashCode() xor action.hashCode(),
        serviceIntent(this, action, taskId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun createChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(ChannelId, getString(R.string.download_channel), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.download_channel_description)
            },
        )
    }

    private val notificationManager get() = getSystemService(NotificationManager::class.java)

    inner class LocalBinder : Binder() {
        val service: DownloadService get() = this@DownloadService
    }

    companion object {
        private const val ChannelId = "iso_downloads"
        private const val ActionStart = "com.sky22333.netboot.download.START"
        private const val ActionPause = "com.sky22333.netboot.download.PAUSE"
        private const val ActionCancel = "com.sky22333.netboot.download.CANCEL"
        private const val ExtraTaskId = "task_id"
        private const val ExtraDeletePartial = "delete_partial"

        fun start(context: Context, taskId: String) {
            context.startForegroundService(serviceIntent(context, ActionStart, taskId))
        }

        fun pause(context: Context, taskId: String) {
            context.startService(serviceIntent(context, ActionPause, taskId))
        }

        fun cancel(context: Context, taskId: String, deletePartial: Boolean) {
            context.startService(serviceIntent(context, ActionCancel, taskId, deletePartial))
        }

        private fun serviceIntent(
            context: Context,
            action: String,
            taskId: String,
            deletePartial: Boolean = false,
        ): Intent = Intent(context, DownloadService::class.java)
            .setAction(action)
            .putExtra(ExtraTaskId, taskId)
            .putExtra(ExtraDeletePartial, deletePartial)

        private fun String.notificationId(): Int = hashCode() and Int.MAX_VALUE

        private fun formatBytes(bytes: Long): String {
            if (bytes < 1024) return "$bytes B"
            val units = arrayOf("KiB", "MiB", "GiB", "TiB")
            var value = bytes.toDouble()
            var unit = -1
            while (value >= 1024 && unit < units.lastIndex) {
                value /= 1024
                unit++
            }
            return "%.1f %s".format(value, units[unit])
        }
    }
}
