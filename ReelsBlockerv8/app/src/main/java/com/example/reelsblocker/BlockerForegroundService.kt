package com.example.reelsblocker

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * BlockerForegroundService  v4
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Keeps the app process alive with a high-priority foreground notification so
 * that aggressive OEM battery savers (MIUI, HyperOS, EMUI, OneUI, ColorOS, etc.)
 * do not suspend it when the screen goes off or Instagram is in the foreground.
 *
 * Carried forward unchanged from v2/v3 (this part was never the problem):
 *
 *  1. startForeground() is called in BOTH onCreate() AND onStartCommand().
 *  2. Notification channel uses IMPORTANCE_HIGH, silenced at the notification
 *     level (setSound(null,null), PRIORITY_LOW) rather than the channel level,
 *     because several OEMs deprioritise IMPORTANCE_LOW/MIN channels entirely.
 *  3. Notification.FLAG_NO_CLEAR | FLAG_ONGOING_EVENT set explicitly (some OEMs
 *     ignore setOngoing()).
 *  4. START_STICKY from onStartCommand().
 *  5. A static `isRunning` flag MainActivity reads directly instead of the
 *     deprecated, unreliable ActivityManager.getRunningServices().
 *
 * ── v4 additions — testing surfaced a real "swiped from Recents" kill on
 * HyperOS/MIUI that eventually disabled the Accessibility Service with no
 * recovery. Two independent mechanisms address this: ──
 *
 *  6. WATCHDOG (Handler/Runnable, ticks every ~12s):
 *       a. Re-asserts startForeground() defensively — cheap, idempotent,
 *          and guards against an OEM silently demoting/hiding the
 *          notification without actually killing the service.
 *       b. Reads ReelsBlockerAccessibilityService.isConnected. IMPORTANT,
 *          and stated plainly rather than overpromised: Android does NOT
 *          let any app silently re-enable its own (or any) Accessibility
 *          Service — that toggle lives in Settings > Accessibility and
 *          requires an explicit user tap, by design, as a security
 *          boundary. So this watchdog cannot auto-heal a disabled service.
 *          What it CAN do, and does, is detect the disconnection within
 *          one tick and flip the persistent notification into a "tap to
 *          re-enable" state that deep-links straight to the Accessibility
 *          settings screen — the fastest recovery an app is allowed to
 *          offer.
 *
 *  7. onTaskRemoved() + AlarmManager fallback:
 *       HyperOS/MIUI (and some other OEM skins) kill the whole process when
 *       the task is swiped from Recents, overriding stock Android's
 *       stopWithTask="false" semantics. onTaskRemoved() fires just before
 *       that happens and makes an immediate restart attempt; because the
 *       process can still die a moment later anyway, two AlarmManager
 *       fallbacks are also scheduled (1.5s and 6s out). AlarmManager lives
 *       outside this process, so those fire and relaunch the service even
 *       if the process was fully killed in between.
 */
class BlockerForegroundService : Service() {

    companion object {
        private const val TAG                   = "ReelsBlockerSvc"
        const val CHANNEL_ID                     = "reels_blocker_v4"
        const val NOTIFICATION_ID                = 1001
        const val ACTION_STOP                    = "com.example.reelsblocker.ACTION_STOP"

        private const val WATCHDOG_INTERVAL_MS   = 12_000L
        private const val RESTART_REQUEST_CODE_1 = 9001
        private const val RESTART_REQUEST_CODE_2 = 9002

        /**
         * True while this service is alive and in the foreground. Set/cleared
         * only from this service's own lifecycle methods. MainActivity reads
         * this directly instead of querying ActivityManager.
         */
        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private val watchdogHandler = Handler(Looper.getMainLooper())

    /** True once the watchdog has detected and flagged an Accessibility disconnect. */
    @Volatile private var accessibilityWarningActive = false

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            try {
                runWatchdogCheck()
            } catch (e: Exception) {
                Log.e(TAG, "Watchdog tick failed", e)
            } finally {
                // Always reschedule, even if this tick threw — a single bad
                // tick must never silently end the watchdog loop.
                watchdogHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
            }
        }
    }

    // ─── onCreate ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate — promoting to foreground immediately")
        try {
            createNotificationChannel()
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            Log.e(TAG, "onCreate: failed to start foreground", e)
        }
        isRunning = true

        // Start the watchdog loop. onCreate runs exactly once per Service
        // instance, so this can't stack duplicate loops across restarts.
        watchdogHandler.removeCallbacks(watchdogRunnable)
        watchdogHandler.post(watchdogRunnable)
    }

    // ─── onStartCommand ───────────────────────────────────────────────────────

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "Stop action received — shutting down")
            isRunning = false
            watchdogHandler.removeCallbacks(watchdogRunnable)
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (e: Exception) {
                Log.e(TAG, "stopForeground failed", e)
            }
            stopSelf()
            return START_NOT_STICKY
        }

        Log.i(TAG, "onStartCommand — re-promoting to foreground (startId=$startId)")
        try {
            createNotificationChannel()
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            Log.e(TAG, "onStartCommand: failed to start foreground", e)
        }
        isRunning = true

        return START_STICKY
    }

    // ─── onTaskRemoved — the app's task was swiped away from Recents ──────────
    //
    // Stock Android honours android:stopWithTask="false" (already set on this
    // service in the manifest) and leaves a foreground service running when
    // its task is removed. HyperOS/MIUI's aggressive memory manager has been
    // observed overriding that and killing the process anyway. This override
    // fights back on two fronts: an immediate in-process restart attempt
    // (works whenever the OS gives us any time before the actual kill — the
    // common case), plus two AlarmManager-based fallbacks that live outside
    // this process and will fire — and relaunch the service — even if the
    // process was fully killed right after this method returns.

    override fun onTaskRemoved(rootIntent: Intent?) {
        try {
            Log.w(TAG, "onTaskRemoved — task swiped from Recents; scheduling restart watchdogs")
            try {
                startForegroundService(Intent(this, BlockerForegroundService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "onTaskRemoved: immediate restart attempt failed", e)
            }
            scheduleRestartAlarm(RESTART_REQUEST_CODE_1, 1_500L)
            scheduleRestartAlarm(RESTART_REQUEST_CODE_2, 6_000L)
        } catch (e: Exception) {
            Log.e(TAG, "onTaskRemoved failed", e)
        } finally {
            super.onTaskRemoved(rootIntent)
        }
    }

    private fun scheduleRestartAlarm(requestCode: Int, delayMs: Long) {
        try {
            val restartIntent = Intent(this, BlockerForegroundService::class.java)
            val pendingIntent = PendingIntent.getService(
                this,
                requestCode,
                restartIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmManager = getSystemService(ALARM_SERVICE) as? AlarmManager
            if (alarmManager == null) {
                Log.w(TAG, "scheduleRestartAlarm: AlarmManager unavailable")
                return
            }
            val triggerAt = SystemClock.elapsedRealtime() + delayMs
            // setAndAllowWhileIdle needs no special permission (unlike the
            // "exact" variants) and still reliably fires within a few
            // seconds even under Doze, which is all a short recovery delay
            // like this needs.
            alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent)
        } catch (e: Exception) {
            Log.e(TAG, "scheduleRestartAlarm(delay=$delayMs) failed", e)
        }
    }

    override fun onDestroy() {
        isRunning = false
        watchdogHandler.removeCallbacks(watchdogRunnable)
        try {
            super.onDestroy()
        } catch (e: Exception) {
            Log.e(TAG, "super.onDestroy failed", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─── Watchdog ─────────────────────────────────────────────────────────────

    private fun runWatchdogCheck() {
        // (a) Defensively re-assert foreground status. Cheap and idempotent —
        // guards against an OEM silently demoting/hiding the notification
        // without actually killing the service outright.
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            Log.e(TAG, "Watchdog: re-asserting startForeground failed", e)
        }

        // (b) Check whether the Accessibility Service is still connected.
        // See the class doc comment for exactly what this can and cannot do.
        val accessibilityAlive = try {
            ReelsBlockerAccessibilityService.isConnected
        } catch (e: Exception) {
            Log.e(TAG, "Watchdog: failed to read isConnected", e)
            true // don't trigger a false warning off a transient read failure
        }

        if (!accessibilityAlive) {
            if (!accessibilityWarningActive) {
                Log.w(TAG, "Watchdog: ReelsBlockerAccessibilityService is disconnected — flagging notification")
            }
            accessibilityWarningActive = true
        } else {
            accessibilityWarningActive = false
        }
    }

    // ─── Notification channel ─────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description          = getString(R.string.notification_channel_desc)
            setSound(null, null) // Silent despite IMPORTANCE_HIGH
            enableVibration(false)
            enableLights(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    // ─── Notification ─────────────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        val warningActive = accessibilityWarningActive

        // When the watchdog has flagged a disconnect, tapping the
        // notification takes the user straight to the Accessibility
        // settings screen instead of just opening the app — the fastest
        // recovery path an app is allowed to offer for this.
        val contentIntent = if (warningActive) {
            PendingIntent.getActivity(
                this, 1,
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val title = if (warningActive) getString(R.string.notification_title_warning)
                    else getString(R.string.notification_title)
        val text  = if (warningActive) getString(R.string.notification_text_warning)
                    else getString(R.string.notification_text)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(contentIntent)
            .setOngoing(true)              // user cannot swipe it away
            .setAutoCancel(false)
            // PRIORITY_LOW normally, so there's no audible/heads-up alert;
            // bumped to DEFAULT only while flagging a real problem the user
            // needs to notice and act on.
            .setPriority(if (warningActive) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        // Belt-and-suspenders: some OEMs check raw flags rather than the compat wrappers.
        @Suppress("DEPRECATION")
        notification.flags = notification.flags or
                Notification.FLAG_ONGOING_EVENT or
                Notification.FLAG_NO_CLEAR

        return notification
    }
}
