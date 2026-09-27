package net.harveywilliams.bluetoothbouncer.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.harveywilliams.bluetoothbouncer.data.BlockedDeviceDao
import net.harveywilliams.bluetoothbouncer.shizuku.ShizukuHelper
import net.harveywilliams.bluetoothbouncer.util.BluetoothAclHelper
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns every change to a blocked device's OS-level connection policy, and keeps that policy
 * in line with the block list in Room.
 *
 * Room is the app's source of truth, but what actually stops a device connecting is the
 * connection policy stored by the Bluetooth stack — and the two can drift apart:
 *  - a blocked device is re-paired while Shizuku isn't running (re-pairing resets the policy),
 *  - re-blocking a temporarily-allowed device fails because Shizuku was down when it left range,
 *  - the phone reboots in the middle of a temporary-allow session.
 *
 * [reconcile] repairs all of these. It runs whenever Shizuku becomes ready, at boot, and when
 * Bluetooth is switched on.
 *
 * Every "change OS policy + update Room" sequence runs under one [Mutex], so a reconcile can
 * never interleave with — and undo — a block, unblock, or temporary allow that is in flight.
 * The lock is not re-entrant: never call one of this class's locking methods from inside
 * [withPolicyLock].
 */
class PolicyEnforcer(
    private val context: Context,
    private val dao: BlockedDeviceDao,
    private val shizukuHelper: ShizukuHelper,
    private val nearbyTracker: NearbyDeviceTracker,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()

    /** MAC → [SystemClock.elapsedRealtime] of the last temporary allow granted in this process. */
    private val recentAllows = ConcurrentHashMap<String, Long>()

    /** Pending re-block jobs for temp-allowed devices that left range while still connected. */
    private val deferredReblocks = ConcurrentHashMap<String, Job>()

    @Volatile private var lastReconcileAt = 0L

    /** Runs [block] while holding the policy lock. See the class docs. */
    suspend fun <T> withPolicyLock(block: suspend () -> T): T = mutex.withLock { block() }

    /**
     * Temporarily allows a blocked device: sets the policy to ALLOWED and marks it
     * `isTemporarilyAllowed`, then asks the device to connect. Policy changes on their own only
     * let the device connect the next time *it* tries, and most headphones only try at power-on,
     * so without the explicit connect "Allow" often looked like it did nothing.
     *
     * Returns the result of the policy change; the connect attempt is best-effort.
     */
    suspend fun allowTemporarily(macAddress: String): Result<IntArray> {
        cancelDeferredReblock(macAddress)
        val result = withPolicyLock {
            shizukuHelper.setConnectionPolicy(macAddress, ShizukuHelper.POLICY_ALLOWED).also {
                if (it.isSuccess) {
                    dao.updateIsTemporarilyAllowed(macAddress, true)
                    recentAllows[macAddress] = SystemClock.elapsedRealtime()
                }
            }
        }
        if (result.isSuccess) {
            shizukuHelper.connectDevice(macAddress).onFailure {
                Log.d(TAG, "Best-effort connect after allowing $macAddress did not succeed: ${it.message}")
            }
        }
        return result
    }

    /**
     * Ends any temporary allow for a blocked device and re-applies CONNECTION_POLICY_FORBIDDEN.
     *
     * - [disconnectFirst]: actively disconnect the device's profiles before re-blocking. The
     *   disconnect is best-effort — re-blocking is what matters, and forbidding the policy
     *   drops the profiles anyway.
     * - [removeFromNearby]: the device has left range. Drop it from the nearby set (cancelling
     *   its notification) and forget its recent allow — even if the re-block fails, so the next
     *   [reconcile] treats the session as over and retries.
     *
     * No-op (success) if the device is no longer in the block list — it was unblocked meanwhile.
     */
    suspend fun reblock(
        macAddress: String,
        disconnectFirst: Boolean = false,
        removeFromNearby: Boolean = false,
    ): Result<IntArray> {
        cancelDeferredReblock(macAddress)
        return withPolicyLock {
            val entity = dao.getDeviceByMac(macAddress)
                ?: return@withPolicyLock Result.success(IntArray(0))
            if (disconnectFirst) {
                shizukuHelper.disconnectDevice(macAddress).onFailure {
                    Log.d(TAG, "Best-effort disconnect of $macAddress did not succeed: ${it.message}")
                }
            }
            shizukuHelper.setConnectionPolicy(macAddress, ShizukuHelper.POLICY_FORBIDDEN).also {
                if (it.isSuccess) {
                    if (entity.isTemporarilyAllowed) dao.updateIsTemporarilyAllowed(macAddress, false)
                    recentAllows.remove(macAddress)
                }
                if (removeFromNearby) {
                    recentAllows.remove(macAddress)
                    nearbyTracker.removeDevice(macAddress)
                }
            }
        }
    }

    /**
     * A temporarily-allowed device left range (CDM) but still has an ACL link — usually a
     * disconnect/reconnect race. Re-block it once the link actually drops, unless it comes
     * back into range first (see [cancelDeferredReblock]). Polls because there is no reliable
     * background callback for "ACL dropped" once the CompanionDeviceService has unbound.
     */
    fun scheduleDeferredReblock(macAddress: String) {
        deferredReblocks.remove(macAddress)?.cancel()
        deferredReblocks[macAddress] = scope.launch {
            val self = currentCoroutineContext()[Job]
            try {
                val deadline = SystemClock.elapsedRealtime() + DEFERRED_REBLOCK_MAX_MS
                while (SystemClock.elapsedRealtime() < deadline) {
                    delay(DEFERRED_REBLOCK_POLL_MS)
                    if (isAclConnected(macAddress) != false) continue
                    Log.d(TAG, "Deferred re-block: $macAddress has disconnected — re-blocking")
                    withPolicyLock {
                        val entity = dao.getDeviceByMac(macAddress)
                        if (entity?.isTemporarilyAllowed == true) {
                            val result = shizukuHelper.setConnectionPolicy(macAddress, ShizukuHelper.POLICY_FORBIDDEN)
                            if (result.isSuccess) {
                                dao.updateIsTemporarilyAllowed(macAddress, false)
                                recentAllows.remove(macAddress)
                                nearbyTracker.removeDevice(macAddress)
                            } else {
                                Log.w(TAG, "Deferred re-block failed for $macAddress — reconcile will retry: ${result.exceptionOrNull()?.message}")
                            }
                        }
                    }
                    return@launch
                }
                Log.w(TAG, "Deferred re-block for $macAddress gave up — reconcile will retry")
            } finally {
                deferredReblocks.remove(macAddress, self)
            }
        }
    }

    /** Cancels a pending [scheduleDeferredReblock] — the device came back into range. */
    fun cancelDeferredReblock(macAddress: String) {
        deferredReblocks.remove(macAddress)?.cancel()
    }

    /**
     * Re-applies the connection policy for every blocked device so the OS matches Room.
     *
     * - Blocked devices get CONNECTION_POLICY_FORBIDDEN again (idempotent).
     * - Temporarily-allowed devices are re-blocked only when their session is clearly over:
     *   not allowed within the last few minutes, not in range, and definitely not connected.
     *   If the connection state can't be read, they're left alone rather than risk cutting off
     *   a device the user is using.
     * - Devices that are no longer paired are skipped; [BondStateReceiver] re-blocks them if
     *   they're paired again.
     *
     * Skipped when Shizuku isn't ready or Bluetooth is off (profile proxies aren't available,
     * so every call would time out). Back-to-back triggers within a few seconds (e.g. boot and
     * "Shizuku ready" together) run once unless [force] is set.
     */
    @SuppressLint("MissingPermission")
    suspend fun reconcile(reason: String, force: Boolean = false) {
        withPolicyLock {
            val now = SystemClock.elapsedRealtime()
            if (!force && lastReconcileAt != 0L && now - lastReconcileAt < MIN_RECONCILE_INTERVAL_MS) {
                Log.d(TAG, "reconcile($reason): ran ${now - lastReconcileAt} ms ago — skipping")
                return@withPolicyLock
            }
            if (shizukuHelper.state.value !is ShizukuHelper.State.Ready) {
                Log.d(TAG, "reconcile($reason): Shizuku not ready — skipping")
                return@withPolicyLock
            }
            val adapter = bluetoothAdapter()
            if (adapter == null || !adapter.isEnabled) {
                Log.d(TAG, "reconcile($reason): Bluetooth off — skipping")
                return@withPolicyLock
            }

            val bonded: Set<String>? = try {
                adapter.bondedDevices?.map { it.address }?.toSet()
            } catch (e: SecurityException) {
                null // BLUETOOTH_CONNECT not granted — can't filter, so re-apply to everything
            }

            val devices = dao.getAllDevices().first()
            Log.d(TAG, "reconcile($reason): checking ${devices.size} blocked device(s)")
            for (entity in devices) {
                val mac = entity.macAddress
                if (bonded != null && mac !in bonded) continue

                if (entity.isTemporarilyAllowed) {
                    if (!isTemporaryAllowStale(mac)) continue
                    Log.d(TAG, "reconcile($reason): temporary allow for $mac has ended — re-blocking")
                }

                val result = shizukuHelper.setConnectionPolicy(mac, ShizukuHelper.POLICY_FORBIDDEN)
                if (result.isSuccess) {
                    if (entity.isTemporarilyAllowed) {
                        dao.updateIsTemporarilyAllowed(mac, false)
                        recentAllows.remove(mac)
                        nearbyTracker.removeDevice(mac)
                    }
                } else {
                    Log.w(TAG, "reconcile($reason): failed to re-apply FORBIDDEN for $mac: ${result.exceptionOrNull()?.message}")
                }
            }
            lastReconcileAt = SystemClock.elapsedRealtime()
        }
    }

    private fun isTemporaryAllowStale(macAddress: String): Boolean {
        val allowedAt = recentAllows[macAddress]
        if (allowedAt != null && SystemClock.elapsedRealtime() - allowedAt < RECENT_ALLOW_WINDOW_MS) return false
        if (macAddress in nearbyTracker.nearbyDevices.value) return false
        if (deferredReblocks.containsKey(macAddress)) return false
        return isAclConnected(macAddress) == false
    }

    /** True/false if the ACL state is known; null if it can't be determined. */
    private fun isAclConnected(macAddress: String): Boolean? {
        return try {
            val adapter = bluetoothAdapter() ?: return null
            BluetoothAclHelper.isConnectedOrNull(adapter.getRemoteDevice(macAddress))
        } catch (e: Exception) {
            Log.w(TAG, "isAclConnected failed for $macAddress", e)
            null
        }
    }

    private fun bluetoothAdapter(): BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    companion object {
        private const val TAG = "PolicyEnforcer"

        /** Triggers closer together than this collapse into one reconcile pass. */
        private const val MIN_RECONCILE_INTERVAL_MS = 5_000L

        /** A temporary allow granted this recently is never treated as stale by [reconcile]. */
        private const val RECENT_ALLOW_WINDOW_MS = 5 * 60_000L

        private const val DEFERRED_REBLOCK_POLL_MS = 15_000L
        private const val DEFERRED_REBLOCK_MAX_MS = 30 * 60_000L
    }
}
