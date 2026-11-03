package io.github.zcodeandroid.app.local

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import io.github.zcodeandroid.app.MainActivity
import io.github.zcodeandroid.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * OWNER of the local ZCode runtime lifecycle (foreground service, dataSync).
 *
 * Contract (runtime-ownership redesign, 0.4.0):
 *  - The UI NEVER spawns or stops Node directly; it asks this service.
 *  - The service decides by probing the REAL server, not by trusting cached
 *    in-memory state: adopt a healthy runtime, restart a dead one, never
 *    spawn a duplicate node on an already-served port.
 *  - START_REDELIVER_INTENT: if the app process dies, the system restarts
 *    this service with the original start intent and ensureRuntime() runs
 *    the same probe sequence again.
 *  - Explicit Stop terminates owned processes and any same-uid leftovers.
 *  - onDestroy must not kill Node: the system may restart the service and
 *    the runtime is meant to outlive UI destruction.
 */
class LocalServerService : Service() {

    /**
     * Deliberately NOT cancelled in onDestroy: a mid-flight start must be
     * allowed to finish its health loop even if the system tears the service
     * down (cancelling it would abort ZcodeServer.start and kill the freshly
     * spawned node). Coroutines here are short, bounded, and self-terminating.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            scope.launch {
                ZcodeServer.stop()
                ZcodeServer.killLeftoverNode()
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        startDiagReportLoop()
        ensureRuntime()
        return START_REDELIVER_INTENT
    }

    /**
     * P1a diag bridge (self-diagnostics study, 2026-11): keep
     * files/logs/diag-report.json fresh — write on every runtime state
     * transition and every 5 minutes while the service lives. The embedded
     * agent reads this file (same uid) to see app-side state; no new IPC,
     * no new service — one bounded coroutine on the existing service scope.
     */
    private fun startDiagReportLoop() {
        if (diagReportLoopStarted) return
        diagReportLoopStarted = true
        val app = applicationContext
        scope.launch {
            RuntimeBus.state.collect { DiagReport.write(app.filesDir, WebDiagnostics.webViewProviderInfo(app), batteryStats(app)) }
        }
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(300_000L)
                DiagReport.write(app.filesDir, WebDiagnostics.webViewProviderInfo(app), batteryStats(app))
            }
        }
    }

    private var diagReportLoopStarted = false

    /**
     * Battery snapshot for the agent-facing diag report, from public APIs
     * only: the sticky ACTION_BATTERY_CHANGED broadcast needs no receiver
     * lifecycle and no permission. The agent cannot read sysfs power nodes
     * from its shell (owner device session 2026-10-31), so this is the only
     * battery evidence path it gets. Empty (never throws) when the system
     * has not broadcast yet or on the JVM.
     */
    private fun batteryStats(context: Context): List<String> {
        val intent = runCatching {
            context.registerReceiver(null as BroadcastReceiver?, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull() ?: return emptyList()
        val stats = ArrayList<String>(6)
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level >= 0 && scale > 0) {
            stats.add("percent=" + (level * 100) / scale)
        }
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        stats.add(
            "status=" + when (status) {
                BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
                BatteryManager.BATTERY_STATUS_FULL -> "full"
                BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not-charging"
                BatteryManager.BATTERY_STATUS_UNKNOWN -> "unknown"
                else -> "unknown"
            }
        )
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
        stats.add(
            "plugged=" + when (plugged) {
                BatteryManager.BATTERY_PLUGGED_AC -> "ac"
                BatteryManager.BATTERY_PLUGGED_USB -> "usb"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
                else -> "none"
            }
        )
        val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        if (temp != Int.MIN_VALUE) {
            stats.add("tempC=" + temp / 10.0)
        }
        val voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
        if (voltage > 0) {
            stats.add("voltageV=" + voltage / 1000.0)
        }
        val health = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)
        stats.add(
            "health=" + when (health) {
                BatteryManager.BATTERY_HEALTH_GOOD -> "good"
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
                BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over-voltage"
                BatteryManager.BATTERY_HEALTH_COLD -> "cold"
                else -> "unknown"
            }
        )
        return stats
    }

    /**
     * Single decision point for "is the runtime running, and who owns it".
     * Order matters — this is what prevents duplicate node processes:
     *   1. we own a live process AND the port answers  -> nothing to do
     *   2. the port answers without an owned process   -> adopt it
     *   3. port dead, provisioned                      -> (re)start via ZcodeServer
     */
    private fun ensureRuntime() {
        scope.launch {
            val s = RuntimeBus.state.value
            if (s is RuntimeState.Running &&
                ZcodeServer.isRunning &&
                ZcodeServer.serverHealthy()
            ) {
                return@launch
            }
            if (ZcodeServer.adoptIfHealthy()) return@launch
            if (s is RuntimeState.Running || s is RuntimeState.Starting) {
                RuntimeBus.append(
                    "port ${RuntimePaths.SERVER_PORT} dead — previous runtime is gone; restarting"
                )
                RuntimeBus.setState(RuntimeState.Stopped)
            }
            val app = applicationContext
            if (RuntimePaths.provisioned(app)) {
                ZcodeServer.start(app)
            } else {
                RuntimeBus.append(
                    "runtime not provisioned — service has nothing to start; run setup first"
                )
            }
        }
    }

    override fun onDestroy() {
        // Intentionally no Node teardown here (see class contract above).
        super.onDestroy()
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.local_server_channel),
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = getString(R.string.local_server_channel_desc) }
            )
        }
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, LocalServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification: Notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.local_server_notif_title))
                .setContentText(getString(R.string.local_server_notif_text))
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentIntent(contentIntent)
                .addAction(0, getString(R.string.local_server_stop), stopIntent)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle(getString(R.string.local_server_notif_title))
                .setContentText(getString(R.string.local_server_notif_text))
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .build()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "local-zcode-server"
        private const val NOTIFICATION_ID = 2001
        const val ACTION_STOP = "io.github.zcodeandroid.app.local.STOP"

        fun start(context: Context) {
            val intent = Intent(context, LocalServerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, LocalServerService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
