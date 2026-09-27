package net.harveywilliams.bluetoothbouncer.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import net.harveywilliams.bluetoothbouncer.BluetoothBouncerApp
import net.harveywilliams.bluetoothbouncer.notification.WatchNotificationHelper
import net.harveywilliams.bluetoothbouncer.util.BluetoothAclHelper

/**
 * Background presence-detection service for watched blocked devices.
 *
 * The OS wakes this service whenever a registered CDM-associated device appears or disappears,
 * even when the app process is not running.
 *
 * Requires API 33 (TIRAMISU) for [onDeviceAppeared] and [onDeviceDisappeared] with
 * [AssociationInfo] parameter. [CompanionDeviceService] itself was added in API 31, but
 * presence observation ([startObservingDevicePresence]) and these callbacks require API 33.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class DeviceWatcherService : CompanionDeviceService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Called when a CDM-associated device comes into Bluetooth range.
     *
     * Posts the "nearby" notification if the device is still blocked and not yet
     * temporarily allowed.
     */
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        val associationId = associationInfo.id
        val mac = associationInfo.deviceMacAddress?.toString()?.uppercase()
        Log.d(TAG, "onDeviceAppeared: associationId=$associationId mac=$mac")

        serviceScope.launch {
            val app = applicationContext as BluetoothBouncerApp
            val dao = app.database.blockedDeviceDao()
            val entity = resolveEntity(dao, associationId, mac) ?: run {
                Log.w(TAG, "onDeviceAppeared: no entity found for associationId=$associationId mac=$mac")
                return@launch
            }
            // Heal any stale association ID persisted in Room
            if (entity.cdmAssociationId != associationId) {
                Log.d(TAG, "Updating stale cdmAssociationId ${entity.cdmAssociationId} -> $associationId for ${entity.macAddress}")
                dao.updateCdmAssociationId(entity.macAddress, associationId)
            }
            // Cancel any pending grace-period removal for this device — it's back in range.
            app.nearbyTracker.addDevice(entity.macAddress)
            // Back in range: a temporary-allow session that was waiting to be re-blocked continues.
            app.policyEnforcer.cancelDeferredReblock(entity.macAddress)
            Log.d(TAG, "Device appeared: ${entity.deviceName} (${entity.macAddress})")
        }
    }

    /**
     * Called when a CDM-associated device leaves Bluetooth range.
     *
     * If the device was temporarily allowed and is no longer connected, ends the session:
     * re-applies POLICY_FORBIDDEN and clears the temporary-allow flag. If it's still connected
     * (or its state can't be read), the re-block is deferred until the link drops.
     */
    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        val associationId = associationInfo.id
        val mac = associationInfo.deviceMacAddress?.toString()?.uppercase()
        Log.d(TAG, "onDeviceDisappeared: associationId=$associationId mac=$mac")

        serviceScope.launch {
            val app = applicationContext as BluetoothBouncerApp
            val dao = app.database.blockedDeviceDao()
            val entity = resolveEntity(dao, associationId, mac) ?: run {
                Log.w(TAG, "onDeviceDisappeared: no entity found for associationId=$associationId mac=$mac")
                // Unknown device — best-effort remove from nearby set using raw MAC if available
                if (mac != null) app.nearbyTracker.removeDevice(mac)
                return@launch
            }

            if (!entity.isTemporarilyAllowed) {
                // Not temp-allowed: schedule a grace-period removal via the NearbyDeviceTracker
                // so the job outlives this service instance (CompanionDeviceService is short-lived
                // and serviceScope is cancelled in onDestroy before any delay could fire).
                app.nearbyTracker.scheduleRemoval(entity.macAddress, entity.deviceName)
                return@launch
            }

            if (isDeviceAclConnected(entity.macAddress) != false) {
                // Still ACL-connected (or unknown) — defer the re-block until the link drops.
                // Keep in nearby set so the "Disconnect" notification stays visible meanwhile.
                Log.d(TAG, "Device ${entity.deviceName} still connected — deferring re-block")
                app.policyEnforcer.scheduleDeferredReblock(entity.macAddress)
                return@launch
            }

            // Removes from the nearby set — the notification observer then cancels the notification.
            val result = app.policyEnforcer.reblock(entity.macAddress, removeFromNearby = true)
            if (result.isSuccess) {
                Log.d(TAG, "Re-blocked ${entity.deviceName} after departure")
            } else {
                // The reconcile that runs when Shizuku is next ready will re-block it. Tell the
                // user their device is not blocked right now.
                Log.e(TAG, "Failed to re-block ${entity.deviceName}: ${result.exceptionOrNull()}")
                WatchNotificationHelper.postErrorNotification(
                    this@DeviceWatcherService,
                    entity.macAddress,
                    entity.deviceName,
                    "Left range but couldn't be re-blocked — Shizuku unavailable. " +
                        "It will be blocked again as soon as Shizuku is running.",
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    /**
     * Returns the [BlockedDeviceEntity] for the given [associationId], falling back to a
     * MAC-address lookup if no row matches the ID directly.
     *
     * The fallback handles stale association IDs that were created before the app crashed or
     * was reinstalled — the CDM may keep delivering callbacks for an old ID while Room only
     * knows about a newer one.
     */
    private suspend fun resolveEntity(
        dao: net.harveywilliams.bluetoothbouncer.data.BlockedDeviceDao,
        associationId: Int,
        mac: String?,
    ): net.harveywilliams.bluetoothbouncer.data.BlockedDeviceEntity? {
        dao.getDeviceByAssociationId(associationId)?.let { return it }
        if (mac != null) {
            val byMac = dao.getDeviceByMac(mac)
            // Only use the MAC fallback if this device is actually watched (non-null cdmAssociationId)
            if (byMac?.cdmAssociationId != null) return byMac
        }
        return null
    }

    /**
     * Returns whether a Bluetooth ACL link to the given [macAddress] is still active, or null
     * if that can't be determined. Delegates to [BluetoothAclHelper] which uses the hidden
     * [BluetoothDevice.isConnected] API via reflection.
     */
    @SuppressLint("MissingPermission")
    private fun isDeviceAclConnected(macAddress: String): Boolean? {
        return try {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return null
            val device = adapter.getRemoteDevice(macAddress)
            BluetoothAclHelper.isConnectedOrNull(device)
        } catch (e: Exception) {
            Log.w(TAG, "isDeviceAclConnected failed for $macAddress", e)
            null
        }
    }

    companion object {
        private const val TAG = "DeviceWatcherService"
    }
}
