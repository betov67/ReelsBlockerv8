package com.example.reelsblocker

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast

/**
 * AppDeviceAdminReceiver
 *
 * Handles Device Administrator lifecycle callbacks.
 *
 * Being a Device Admin serves one purpose: when the user tries to uninstall
 * the app, Android first requires them to navigate to
 *   Settings → Security → Device Admin Apps → ReelsBlocker → Deactivate
 * before the normal uninstall flow is available.  This friction is intentional
 * for a focus/anti-distraction tool — it gives the user a moment to reconsider.
 *
 * We do NOT use any sensitive policy (remote wipe, password enforcement, etc.).
 */
class AppDeviceAdminReceiver : DeviceAdminReceiver() {

    companion object {
        private const val TAG = "ReelsBlockerAdmin"
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "Device Admin enabled")
        Toast.makeText(context, context.getString(R.string.device_admin_enabled_msg), Toast.LENGTH_SHORT).show()
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.i(TAG, "Device Admin disabled")
        Toast.makeText(context, context.getString(R.string.device_admin_disabled_msg), Toast.LENGTH_LONG).show()
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        // This message is shown in the system dialog when the user tries to
        // deactivate Device Admin (required step before uninstalling).
        return context.getString(R.string.device_admin_disable_warning)
    }
}
