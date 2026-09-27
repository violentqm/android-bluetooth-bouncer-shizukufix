package net.harveywilliams.bluetoothbouncer.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Re-applies FORBIDDEN policies for all blocked devices on boot, if Shizuku is running.
 * Handles the edge cases where a device was re-paired while Shizuku wasn't available, or the
 * phone was restarted in the middle of a temporary-allow session (a reboot ends the session).
 *
 * If Shizuku isn't running yet, nothing is lost: [PolicyEnforcer.reconcile] also runs as soon
 * as Shizuku becomes ready (see [BluetoothBouncerApp]).
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        Log.d(TAG, "Boot completed — re-applying blocked device policies")

        launchAsync(context) { app ->
            // The process has only just started, so the UserService is still binding —
            // wait for it rather than checking the state once and giving up.
            if (!app.shizukuHelper.awaitReady()) {
                Log.i(TAG, "Shizuku not ready at boot — will reconcile when it starts")
                return@launchAsync
            }
            app.policyEnforcer.reconcile("boot")
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
