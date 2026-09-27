package net.harveywilliams.bluetoothbouncer.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import net.harveywilliams.bluetoothbouncer.notification.WatchNotificationHelper
import net.harveywilliams.bluetoothbouncer.service.PolicyEnforcer

/**
 * Handles the "Allow temporarily" notification action posted by [DeviceWatcherService].
 *
 * On receipt:
 * 1. Calls [PolicyEnforcer.allowTemporarily], which sets POLICY_ALLOWED, sets
 *    `isTemporarilyAllowed = true` in Room, and asks the device to connect. The
 *    Application-scoped notification observer reacts to the Room write and posts the
 *    "temporarily allowed" notification automatically.
 * 2. On failure, posts an error notification instead.
 *
 * Uses [launchAsync] to keep the receiver alive long enough for the Shizuku call to complete.
 */
class TemporaryAllowReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != WatchNotificationHelper.ACTION_ALLOW_TEMPORARILY) return

        val macAddress = intent.getStringExtra(WatchNotificationHelper.EXTRA_MAC_ADDRESS) ?: return
        val deviceName = intent.getStringExtra(WatchNotificationHelper.EXTRA_DEVICE_NAME) ?: ""

        launchAsync(context) { app ->
            try {
                val result = app.policyEnforcer.allowTemporarily(macAddress)
                if (result.isSuccess) {
                    Log.d(TAG, "Temporarily allowed $macAddress")
                } else {
                    Log.w(TAG, "allowTemporarily failed for $macAddress: ${result.exceptionOrNull()}")
                    WatchNotificationHelper.postErrorNotification(context, macAddress, deviceName, ERROR_TEXT)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error in TemporaryAllowReceiver for $macAddress", e)
                WatchNotificationHelper.postErrorNotification(context, macAddress, deviceName, ERROR_TEXT)
            }
        }
    }

    companion object {
        private const val TAG = "TemporaryAllowReceiver"
        private const val ERROR_TEXT = "Could not allow — Shizuku unavailable"
    }
}
