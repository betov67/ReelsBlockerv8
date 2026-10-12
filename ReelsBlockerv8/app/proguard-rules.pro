# ── ReelsBlocker ProGuard rules ──────────────────────────────────────────────

# Keep our own classes intact (they reference Android framework by class name)
-keep class com.example.reelsblocker.** { *; }

# AccessibilityService — the system binds to it by class name
-keep public class * extends android.accessibilityservice.AccessibilityService

# DeviceAdminReceiver — bound by the system
-keep public class * extends android.app.admin.DeviceAdminReceiver

# BroadcastReceivers — declared in manifest
-keep public class * extends android.content.BroadcastReceiver

# Service subclasses
-keep public class * extends android.app.Service

# Suppress common warnings from transitive dependencies
-dontwarn com.google.errorprone.**
