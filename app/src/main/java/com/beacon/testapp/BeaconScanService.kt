package com.beacon.testapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean

class BeaconScanService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var keepAliveRegistered = false
    private val screenOff = AtomicBoolean(false)

    private val keepAliveReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOff.set(true)
                    keepAwakeForStandby()
                    BeaconTracker.get(application).start()
                }
                Intent.ACTION_SCREEN_ON -> {
                    screenOff.set(false)
                    BeaconTracker.get(application).start()
                    refreshWakeLock()
                }
                PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                    BeaconTracker.get(application).start()
                    keepAwakeForStandby()
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    if (state != BluetoothAdapter.STATE_ON) return
                    BeaconTracker.get(application).start()
                }
                else -> BeaconTracker.get(application).start()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        val powerManager = getSystemService(PowerManager::class.java)
        screenOff.set(powerManager?.isInteractive == false)
        val notification = buildNotification(measuring = false)
        BeaconTracker.get(application).enableForegroundScanning(notification, NOTIFICATION_ID)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        registerKeepAlive()
        BeaconTracker.get(application).start()
        keepAwakeForStandby()
        ScanWatchdog.schedule(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val powerManager = getSystemService(PowerManager::class.java)
        screenOff.set(powerManager?.isInteractive == false)
        BeaconTracker.get(application).start()
        keepAwakeForStandby()
        ScanWatchdog.schedule(this)
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        BeaconScanService.start(applicationContext)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        if (keepAliveRegistered) {
            unregisterReceiver(keepAliveReceiver)
            keepAliveRegistered = false
        }
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun applyMeasuringState(measuring: Boolean) {
        refreshWakeLock(measuring)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(measuring))
    }

    private fun keepAwakeForStandby() {
        val powerManager = getSystemService(PowerManager::class.java)
        val idle = powerManager?.isDeviceIdleMode == true
        if (screenOff.get() || idle) {
            acquireWakeLock()
        } else {
            refreshWakeLock()
        }
    }

    private fun refreshWakeLock(measuring: Boolean = BeaconTracker.peek()?.uiState?.value?.isMeasuring == true) {
        val powerManager = getSystemService(PowerManager::class.java)
        val idle = powerManager?.isDeviceIdleMode == true
        if (measuring || screenOff.get() || idle) {
            acquireWakeLock()
        } else {
            releaseWakeLock()
        }
    }

    private fun acquireWakeLock() {
        val current = wakeLock
        if (current?.isHeld == true) return
        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "chaodi:beacon-scan",
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            if (lock.isHeld) lock.release()
        }
        wakeLock = null
    }

    private fun registerKeepAlive() {
        if (keepAliveRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(
            this,
            keepAliveReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        keepAliveRegistered = true
    }

    private fun buildNotification(measuring: Boolean): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.scan_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.scan_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)

        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = if (measuring) {
            getString(R.string.scan_notification_title_measuring)
        } else {
            getString(R.string.scan_notification_title)
        }
        val text = if (measuring) {
            getString(R.string.scan_notification_text_measuring)
        } else {
            getString(R.string.scan_notification_text)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_beacon)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "beacon-distance-scan"
        const val NOTIFICATION_ID = 1001

        @Volatile
        private var instance: BeaconScanService? = null

        fun start(context: Context) {
            val intent = Intent(context, BeaconScanService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun onMeasuringChanged(measuring: Boolean) {
            instance?.applyMeasuringState(measuring)
        }
    }
}
