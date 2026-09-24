package com.sky22333.netboot.runtime

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.sky22333.netboot.MainActivity
import com.sky22333.netboot.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class RuntimeService : Service() {
    @Inject lateinit var runtime: RuntimeRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pendingCommands = 0
    private var stateObserver: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            scope.launch { runtime.refreshUsbConnection() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        ContextCompat.registerReceiver(this, usbReceiver, IntentFilter("android.hardware.usb.action.USB_STATE"), ContextCompat.RECEIVER_EXPORTED)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(ChannelId, getString(R.string.runtime_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY
        startForegroundNow()
        pendingCommands++
        acquireWakeLock()
        if (stateObserver == null) stateObserver = scope.launch {
            runtime.state.collect { stopIfIdle() }
        }
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    when (action) {
                        StartNetwork -> runtime.startNetwork(requireNotNull(intent.getStringExtra(ExtraId)))
                        StopNetwork -> runtime.stopNetwork()
                        AttachIso -> runtime.attachIso(requireNotNull(intent.getStringExtra(ExtraId)))
                        DetachIso -> runtime.detachIso()
                    }
                }
            } finally {
                pendingCommands--
                stopIfIdle()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // Do not block the main thread; broker disconnect triggers USB recovery.
        unregisterReceiver(usbReceiver)
        runtime.shutdownAfterServiceDestroyed()
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    private fun stopIfIdle() {
        val state = runtime.state.value
        if (pendingCommands != 0 || state.busy || state.usbPreparing || state.networkRunning || state.usbAttached) return
        releaseWakeLock()
        if (!state.usbRecoveryRequired) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(PowerManager::class.java) ?: return
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WakeLockTag).apply {
            setReferenceCounted(false)
        }
        runCatching { wakeLock?.acquire(WakeLockTimeoutMillis) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
        wakeLock = null
    }

    private fun startForegroundNow() {
        val notification = NotificationCompat.Builder(this, ChannelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.runtime_active))
            .setContentText(getString(R.string.runtime_notification_detail))
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
        ServiceCompat.startForeground(
            this,
            NotificationId,
            notification,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
    }

    companion object {
        private const val ChannelId = "runtime"
        private const val NotificationId = 1201
        private const val ExtraId = "id"
        private const val WakeLockTag = "netboot:runtime"
        // Upper bound only; the lock is released as soon as the session stops.
        private const val WakeLockTimeoutMillis = 6L * 60 * 60 * 1000
        private const val StartNetwork = "com.sky22333.netboot.runtime.START_NETWORK"
        private const val StopNetwork = "com.sky22333.netboot.runtime.STOP_NETWORK"
        private const val AttachIso = "com.sky22333.netboot.runtime.ATTACH_ISO"
        private const val DetachIso = "com.sky22333.netboot.runtime.DETACH_ISO"

        fun startNetwork(context: Context, profileId: String) = start(context, StartNetwork, profileId)
        fun stopNetwork(context: Context) = start(context, StopNetwork)
        fun attachIso(context: Context, isoId: String) = start(context, AttachIso, isoId)
        fun detachIso(context: Context) = start(context, DetachIso)

        private fun start(context: Context, action: String, id: String? = null) {
            context.startForegroundService(Intent(context, RuntimeService::class.java).setAction(action).putExtra(ExtraId, id))
        }
    }
}
