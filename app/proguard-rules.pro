# OpenKEMI - ProGuard & R8 Optimization and Keep Rules

# Retain domain models and immutable state
-keep class com.supplices.kemimotowear.model.** { *; }

# Retain BLE protocol constants, callbacks and packet structures
-keep class com.supplices.kemimotowear.ble.** { *; }

# Retain service binder and lifecycle components
-keep class com.supplices.kemimotowear.service.** { *; }

# Retain Wear OS UI and Compose entry points
-keep class com.supplices.kemimotowear.ui.** { *; }

# Suppress and keep Compose Runtime snapshots to eliminate lock verification warnings
-dontwarn androidx.compose.runtime.snapshots.**
-keepclassmembers class androidx.compose.runtime.snapshots.** {
    *;
}

# Keep Kotlin Coroutines and StateFlow internals
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# Keep Wear Compose components
-keep class androidx.wear.compose.** { *; }
-dontwarn androidx.wear.compose.**
