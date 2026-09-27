package net.harveywilliams.bluetoothbouncer.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import net.harveywilliams.bluetoothbouncer.notification.WatchNotificationHelper
import net.harveywilliams.bluetoothbouncer.service.PolicyEnforcer

/**
 * Handles the "Disconnect" notification action on the "temporarily allowed" notification.
 *
 * On receipt, calls [PolicyEnforcer.reblock], which:
 * 1. Best-effort disconnects all profiles.
 * 2. Re-applies POLICY_FORBIDDEN — this is what matters; it also drops the profiles, so a
 *    failed disconnect (e.g. the device already dropped) no longer aborts the re-block.
 * 3. Clears `isTemporarilyAllowed` in Room — the Application-scoped notification observer
 *    reacts to this write and posts the "Nearby" notification automatically.
 *
 * On failure, posts an error notification instead.
 *
 * Uses [launchAsync] to keep the receiver alive long enough for the Shizuku calls to complete.
 */
class DisconnectReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != WatchNotificationHelper.ACTION_DISCONNECT) return

        val macAddress = intent.getStringExtra(WatchNotificationHelper.EXTRA_MAC_ADDRESS) ?: return
        val deviceName = intent.getStringExtra(WatchNotificationHelper.EXTRA_DEVICE_NAME) ?: ""

        launchAsync(context) { app ->
            try {
                val result = app.policyEnforcer.reblock(macAddress, disconnectFirst = true)
                if (result.isSuccess) {
                    Log.d(TAG, "Disconnected and re-blocked $macAddress")
                } else {
                    Log.w(TAG, "reblock failed for $macAddress: ${result.exceptionOrNull()}")
                    WatchNotificationHelper.postErrorNotification(context, macAddress, deviceName, ERROR_TEXT)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error in DisconnectReceiver for $macAddress", e)
                WatchNotificationHelper.postErrorNotification(context, macAddress, deviceName, ERROR_TEXT)
            }
        }
    }

    companion object {
        private const val TAG = "DisconnectReceiver"
        private const val ERROR_TEXT = "Could not disconnect — Shizuku unavailable"
    }
}
