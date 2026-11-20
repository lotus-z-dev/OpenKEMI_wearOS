package com.supplices.kemimotowear.receiver

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.supplices.kemimotowear.service.KemimotoBleService

/**
 * BroadcastReceiver triggered at system startup (BOOT_COMPLETED) or package update.
 * Safely launches the persistent KemimotoBleService only when required BLE permissions
 * are already granted, avoiding Android 12+/14+ connectedDevice FGS security exceptions.
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        try {
            val serviceIntent = Intent(context, KemimotoBleService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (_: SecurityException) {
            // Defer service launch until user opens the app if FGS start is restricted by OS policy.
        } catch (_: IllegalStateException) {
            // Defer service launch until user opens the app if background start is disallowed.
        }
    }
}
