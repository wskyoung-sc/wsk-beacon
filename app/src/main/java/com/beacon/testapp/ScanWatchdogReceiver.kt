package com.beacon.testapp

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock

class ScanWatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (!ScanPermissions.hasRequiredToScan(context)) return
        BeaconScanService.start(context.applicationContext)
        ScanWatchdog.schedule(context.applicationContext)
    }
}

object ScanWatchdog {
    private const val REQUEST_CODE = 2101
    private const val INTERVAL_MS = 9 * 60 * 1000L

    fun schedule(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = pendingIntent(context)
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + INTERVAL_MS,
            pending,
        )
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, ScanWatchdogReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
