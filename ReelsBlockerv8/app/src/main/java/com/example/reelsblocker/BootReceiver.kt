package com.example.reelsblocker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * BootReceiver
 *
 * Restarts the foreground service after the device reboots so protection
 * is active even if the user never manually opens the app again.
 *
 * Note: the AccessibilityService itself is re-enabled automatically by the OS
 * if it was enabled before the reboot.  The foreground service, however, must
 * be started explicitly — that's what this receiver does.
 *
 * Required permission in the manifest: RECEIVE_BOOT_COMPLETED
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ReelsBlockerBoot"
    }

    override fun onReceive(context: Context, intent: Intent) {
        try {
            if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
                intent.action == "android.intent.action.QUICKBOOT_POWERON" ||
                intent.action == "com.htc.intent.action.QUICKBOOT_POWERON"
            ) {
                Log.i(TAG, "Boot completed — starting BlockerForegroundService")
                val serviceIntent = Intent(context, BlockerForegroundService::class.java)
                context.startForegroundService(serviceIntent)
            }
        } catch (e: Exception) {
            // v4: consistent with the rest of the app, a broadcast receiver
            // must never let an exception escape — startForegroundService
            // can throw on some OEM/Android version combinations.
            Log.e(TAG, "onReceive failed", e)
        }
    }
}
