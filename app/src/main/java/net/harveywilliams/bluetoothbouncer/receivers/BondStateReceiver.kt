package net.harveywilliams.bluetoothbouncer.receivers

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Listens for Bluetooth bond state changes. When a device transitions to BONDED
 * and was previously in the blocked list, re-applies CONNECTION_POLICY_FORBIDDEN.
 *
 * This handles the case where a user unpairs and re-pairs a blocked device —
 * re-pairing resets the OS-level policy to ALLOWED. Re-pairing also ends any temporary
 * allow that was left over for the device.
 *
 * If Shizuku isn't available right now the block is re-applied when it next becomes ready
 * (see [PolicyEnforcer.reconcile]).
 */
class BondStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return

        val device: BluetoothDevice = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) ?: return
        val newState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
        if (newState != BluetoothDevice.BOND_BONDED) return

        Log.d(TAG, "Device bonded: ${device.address} — checking if previously blocked")

        launchAsync(context) { app ->
            val blocked = app.database.blockedDeviceDao().getDeviceByMac(device.address)
            if (blocked == null) {
                Log.d(TAG, "Device ${device.address} not in blocked list")
                // A freshly paired device — auto-block it if the user turned that on.
                app.policyEnforcer.autoBlockNewDevices("bonded ${device.address}")
                // Emit so the ViewModel picks up the newly bonded device in the list.
                app.refreshSignal.tryEmit(Unit)
                return@launchAsync
            }

            Log.d(TAG, "Device ${device.address} was previously blocked — re-applying FORBIDDEN")

            // No "is Shizuku ready?" gate: this broadcast often cold-starts the process, when the
            // UserService is still binding. The call waits for the bind if Shizuku is running.
            val result = app.policyEnforcer.reblock(device.address)
            if (result.isSuccess) {
                Log.d(TAG, "Re-applied FORBIDDEN for re-paired device ${device.address}")
            } else {
                Log.w(TAG, "Failed to re-apply FORBIDDEN for ${device.address} — will retry when " +
                    "Shizuku is ready: ${result.exceptionOrNull()?.message}")
            }
            // Emit so the ViewModel reflects the bond/policy change immediately.
            app.refreshSignal.tryEmit(Unit)
        }
    }

    companion object {
        private const val TAG = "BondStateReceiver"
    }
}
