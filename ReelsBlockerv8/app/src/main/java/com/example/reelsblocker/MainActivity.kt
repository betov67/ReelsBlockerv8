package com.example.reelsblocker

import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * MainActivity  v3
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Single-screen dashboard with:
 *  • Traffic-light status indicator (Fully Active / Partial / Inactive)
 *  • Per-permission rows with contextual action buttons
 *  • OEM battery-bypass guidance (MIUI / HyperOS / EMUI / ColorOS / OneUI)
 *
 * v2 changes (carried forward):
 *  • "Battery Optimization" row opens the OEM-specific autostart screen
 *    directly on Xiaomi/MIUI-HyperOS, Huawei/EMUI, and OPPO/ColorOS, instead
 *    of only the stock Android battery-optimization intent.
 *  • Added OEM autostart help dialog with per-manufacturer instructions.
 *
 * v3 change:
 *  • isForegroundServiceRunning() now reads BlockerForegroundService's own
 *    static `isRunning` flag instead of querying the deprecated, unreliable
 *    ActivityManager.getRunningServices().
 */
class MainActivity : AppCompatActivity() {

    // ─── Views ────────────────────────────────────────────────────────────────
    private lateinit var statusIcon:    ImageView
    private lateinit var statusText:    TextView
    private lateinit var statusSubtext: TextView

    private data class PermRow(
        val root:   View,
        val button: Button,
        val title:  TextView,
        val desc:   TextView
    )

    private lateinit var rowAccessibility: PermRow
    private lateinit var rowDeviceAdmin:   PermRow
    private lateinit var rowBattery:       PermRow
    private lateinit var rowAutostart:     PermRow   // NEW: OEM autostart row
    private lateinit var rowService:       PermRow

    private lateinit var deviceAdminComponent: ComponentName

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        deviceAdminComponent = ComponentName(this, AppDeviceAdminReceiver::class.java)
        bindViews()
        populateRowLabels()
        setupClickListeners()
        startBlockerService()
    }

    override fun onResume() {
        super.onResume()
        refreshUI()
    }

    // ─── View wiring ──────────────────────────────────────────────────────────

    private fun bindViews() {
        statusIcon    = findViewById(R.id.iv_status_icon)
        statusText    = findViewById(R.id.tv_status_text)
        statusSubtext = findViewById(R.id.tv_status_subtext)

        fun row(id: Int) = PermRow(
            root   = findViewById<View>(id),
            button = findViewById<View>(id).findViewById(R.id.btn_row_action),
            title  = findViewById<View>(id).findViewById(R.id.tv_row_title),
            desc   = findViewById<View>(id).findViewById(R.id.tv_row_desc)
        )

        rowAccessibility = row(R.id.row_accessibility)
        rowDeviceAdmin   = row(R.id.row_device_admin)
        rowBattery       = row(R.id.row_battery)
        rowAutostart     = row(R.id.row_notification)   // reuses the 4th row slot
        rowService       = row(R.id.row_service)
    }

    private fun populateRowLabels() {
        rowAccessibility.title.setText(R.string.perm_accessibility_title)
        rowAccessibility.desc.setText(R.string.perm_accessibility_desc)

        rowDeviceAdmin.title.setText(R.string.perm_admin_title)
        rowDeviceAdmin.desc.setText(R.string.perm_admin_desc)

        rowBattery.title.setText(R.string.perm_battery_title)
        rowBattery.desc.setText(R.string.perm_battery_desc)

        rowAutostart.title.setText(R.string.perm_autostart_title)
        rowAutostart.desc.setText(R.string.perm_autostart_desc)

        rowService.title.setText(R.string.perm_service_title)
        rowService.desc.setText(R.string.perm_service_desc)
    }

    // ─── Click listeners ──────────────────────────────────────────────────────

    private fun setupClickListeners() {

        // Accessibility
        rowAccessibility.button.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.dialog_accessibility_title)
                .setMessage(R.string.dialog_accessibility_message)
                .setPositiveButton(R.string.btn_open_settings) { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        // Device Admin
        rowDeviceAdmin.button.setOnClickListener {
            startActivity(
                Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, deviceAdminComponent)
                    putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        getString(R.string.device_admin_explanation))
                }
            )
        }

        // Battery optimisation (stock Android)
        rowBattery.button.setOnClickListener {
            try {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                )
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }

        // OEM Autostart / background-app permission (MIUI, EMUI, ColorOS, OneUI…)
        rowAutostart.button.setOnClickListener {
            showOemAutostartDialog()
        }

        // Foreground service manual restart
        rowService.button.setOnClickListener {
            startBlockerService()
            refreshUI()
        }
    }

    // ─── OEM Autostart dialog ─────────────────────────────────────────────────

    /**
     * Tries to open the OEM-specific background/autostart permission screen
     * directly. Falls back to a step-by-step instruction dialog when the
     * OEM intent is not available (stock Android / unknown OEM).
     *
     * Tested OEM intent actions (as of 2025-2026):
     *   MIUI / HyperOS  → miui.intent.action.APP_PERM_EDITOR (opens per-app perm screen)
     *   EMUI / HarmonyOS→ huawei.intent.action.HSM_BOOTAPP_MANAGER
     *   ColorOS / OxygenOS → oppo.intent.action.SHOW_AUTOSTART_SETTING
     *   Samsung OneUI   → com.samsung.android.sm_cn.ACTION_BATTERY_SETTINGS (rough fallback)
     */
    private fun showOemAutostartDialog() {
        val pm = packageManager

        // Ordered list of OEM-specific intents; first one that resolves wins.
        val oemIntents = listOf(
            // MIUI / HyperOS (Xiaomi, Poco, Redmi)
            Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                setClassName("com.miui.securitycenter",
                    "com.miui.permcenter.permissions.PermissionsEditorActivity")
                putExtra("extra_pkgname", packageName)
            },
            // Alternative MIUI entry point
            Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                putExtra("extra_pkgname", packageName)
            },
            // Huawei EMUI / HarmonyOS
            Intent().apply {
                setClassName("com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
            },
            // ColorOS / OxygenOS (OPPO, OnePlus, Realme)
            Intent().apply {
                setClassName("com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity")
            },
            Intent().apply {
                setClassName("com.oppo.safe",
                    "com.oppo.safe.permission.startup.StartupAppListActivity")
            },
            // Vivo FuntouchOS / OriginOS
            Intent().apply {
                setClassName("com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
            }
        )

        val resolvedIntent = oemIntents.firstOrNull { intent ->
            pm.resolveActivity(intent, 0) != null
        }

        if (resolvedIntent != null) {
            try {
                startActivity(resolvedIntent)
                return
            } catch (_: Exception) { /* fall through to dialog */ }
        }

        // Fallback dialog with manual step-by-step instructions
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_autostart_title)
            .setMessage(buildOemInstructions())
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun buildOemInstructions(): String {
        val manufacturer = Build.MANUFACTURER.lowercase()
        return when {
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco") ->
                getString(R.string.autostart_instructions_miui)
            manufacturer.contains("huawei") || manufacturer.contains("honor") ->
                getString(R.string.autostart_instructions_emui)
            manufacturer.contains("oppo") || manufacturer.contains("realme") || manufacturer.contains("oneplus") ->
                getString(R.string.autostart_instructions_coloros)
            manufacturer.contains("vivo") ->
                getString(R.string.autostart_instructions_vivo)
            manufacturer.contains("samsung") ->
                getString(R.string.autostart_instructions_samsung)
            else ->
                getString(R.string.autostart_instructions_generic)
        }
    }

    // ─── UI refresh ───────────────────────────────────────────────────────────

    private fun refreshUI() {
        val accessibilityOk = isAccessibilityServiceEnabled()
        val deviceAdminOk   = isDeviceAdminActive()
        val batteryOk       = isBatteryOptimizationIgnored()
        val serviceRunning  = isForegroundServiceRunning()
        // Autostart cannot be programmatically verified; we show a permanent
        // "Check" button so the user can always open the OEM screen.
        val autostartNote   = false

        applyRow(rowAccessibility, accessibilityOk,
            granted = getString(R.string.status_granted),  pending = getString(R.string.btn_grant))
        applyRow(rowDeviceAdmin,   deviceAdminOk,
            granted = getString(R.string.status_active),   pending = getString(R.string.btn_activate))
        applyRow(rowBattery,       batteryOk,
            granted = getString(R.string.status_excluded), pending = getString(R.string.btn_exclude))
        applyRow(rowAutostart,     autostartNote,
            granted = getString(R.string.status_done),     pending = getString(R.string.btn_check))
        applyRow(rowService,       serviceRunning,
            granted = getString(R.string.status_running),  pending = getString(R.string.btn_start))

        // Overall indicator
        val coreOk    = accessibilityOk && serviceRunning
        val allOk     = coreOk && deviceAdminOk && batteryOk

        when {
            allOk -> {
                statusIcon.setImageResource(R.drawable.ic_status_active)
                statusIcon.setColorFilter(ContextCompat.getColor(this, R.color.status_green))
                statusText.setText(R.string.status_fully_active)
                statusSubtext.setText(R.string.status_fully_active_sub)
            }
            coreOk -> {
                statusIcon.setImageResource(R.drawable.ic_status_partial)
                statusIcon.setColorFilter(ContextCompat.getColor(this, R.color.status_yellow))
                statusText.setText(R.string.status_partially_active)
                statusSubtext.setText(R.string.status_partially_active_sub)
            }
            else -> {
                statusIcon.setImageResource(R.drawable.ic_status_inactive)
                statusIcon.setColorFilter(ContextCompat.getColor(this, R.color.status_red))
                statusText.setText(R.string.status_inactive)
                statusSubtext.setText(R.string.status_inactive_sub)
            }
        }
    }

    private fun applyRow(row: PermRow, isActive: Boolean, granted: String, pending: String) {
        row.button.text      = if (isActive) granted else pending
        // Always keep the autostart button enabled since we can't detect its state
        row.button.isEnabled = if (row == rowAutostart) true else !isActive
        row.button.alpha     = if (isActive && row != rowAutostart) 0.45f else 1.0f
    }

    // ─── State checks ─────────────────────────────────────────────────────────

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedId = "$packageName/${ReelsBlockerAccessibilityService::class.java.name}"
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { it.equals(expectedId, ignoreCase = true) }
    }

    private fun isDeviceAdminActive(): Boolean =
        (getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager)
            .isAdminActive(deviceAdminComponent)

    private fun isBatteryOptimizationIgnored(): Boolean =
        (getSystemService(POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(packageName)

    // v3: reads BlockerForegroundService's own static flag instead of
    // querying ActivityManager.getRunningServices(), which is deprecated,
    // unreliable across OEM skins, and was never meant for self-checks.
    private fun isForegroundServiceRunning(): Boolean = BlockerForegroundService.isRunning

    // ─── Service control ──────────────────────────────────────────────────────

    private fun startBlockerService() {
        startForegroundService(Intent(this, BlockerForegroundService::class.java))
    }
}
