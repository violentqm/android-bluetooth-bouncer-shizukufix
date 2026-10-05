package net.harveywilliams.bluetoothbouncer

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.companion.CompanionDeviceManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import net.harveywilliams.bluetoothbouncer.data.AppDatabase
import net.harveywilliams.bluetoothbouncer.data.AppSettings
import net.harveywilliams.bluetoothbouncer.notification.WatchNotificationHelper
import net.harveywilliams.bluetoothbouncer.service.NearbyDeviceTracker
import net.harveywilliams.bluetoothbouncer.service.PolicyEnforcer
import net.harveywilliams.bluetoothbouncer.shizuku.ShizukuHelper

/**
 * Application class — provides the Room database, ShizukuHelper, the app-level
 * Bluetooth refresh signal, and the [NearbyDeviceTracker] singletons.
 */
class BluetoothBouncerApp : Application() {

    val database: AppDatabase by lazy { AppDatabase.getDatabase(this) }

    val shizukuHelper: ShizukuHelper by lazy { ShizukuHelper(this) }

    /** Persisted user settings (e.g. auto-block new devices). */
    val appSettings: AppSettings by lazy { AppSettings(this) }

    /**
     * App-level event bus for Bluetooth state changes (ACL connect/disconnect, bond state,
     * adapter on/off). Any component (ViewModel receiver, BondStateReceiver) can emit Unit
     * into this flow; [DeviceListViewModel] collects it with a debounce to trigger a refresh.
     */
    val refreshSignal: MutableSharedFlow<Unit> = MutableSharedFlow(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Application-scoped coroutine scope for long-lived observers that must outlive any
     * individual Activity, ViewModel, or Service.
     */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Tracks which CDM-associated devices are currently within Bluetooth range.
     *
     * Encapsulates the nearby-device [StateFlow], grace-period removal jobs, and the
     * transition helpers ([NearbyDeviceTracker.addDevice], [NearbyDeviceTracker.scheduleRemoval],
     * [NearbyDeviceTracker.cancelPendingRemoval]) that were previously inline on this class.
     */
    val nearbyTracker: NearbyDeviceTracker by lazy { NearbyDeviceTracker(applicationScope) }

    /**
     * Single owner of blocked-device policy changes; re-applies blocks when the OS policy
     * drifts from Room. See [PolicyEnforcer].
     */
    val policyEnforcer: PolicyEnforcer by lazy {
        PolicyEnforcer(this, database.blockedDeviceDao(), shizukuHelper, nearbyTracker, appSettings, applicationScope)
    }

    override fun onCreate() {
        super.onCreate()
        WatchNotificationHelper.createNotificationChannel(this)
        // Trigger lazy init immediately so Shizuku binding starts at process launch.
        // This narrows the race window where setConnectionPolicy() is called before
        // the UserService binder is delivered (e.g. notification action on cold start).
        shizukuHelper
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            cleanUpStaleCdmAssociations()
        }
        launchNotificationObserver()
        launchPolicyReconciler()
        registerBluetoothStateReceiver()
    }

    override fun onTerminate() {
        super.onTerminate()
        shizukuHelper.cleanup()
    }

    /**
     * Launches an Application-scoped observer that reactively posts or cancels the correct
     * device-watch notification for each blocked device based on its current presence and
     * temporary-allow state.
     *
     * Logic:
     *  - MAC in nearbyDevices + isTemporarilyAllowed  → postAllowedNotification
     *  - MAC in nearbyDevices + isAlertEnabled         → postNearbyNotification
     *  - MAC in nearbyDevices only (no alert, no temp) → cancel (CDM-only device, no alert)
     *  - MAC not in nearbyDevices                      → cancel
     *
     * [WatchNotificationHelper] uses setOnlyAlertOnce(true) so re-posting an identical
     * notification does not re-alert the user with sound or vibration.
     */
    private fun launchNotificationObserver() {
        applicationScope.launch {
            kotlinx.coroutines.flow.combine(
                nearbyTracker.nearbyDevices,
                database.blockedDeviceDao().getAllDevices()
            ) { nearby, devices ->
                nearby to devices
            }.collect { (nearby, devices) ->
                for (device in devices) {
                    val mac = device.macAddress
                    val notifId = WatchNotificationHelper.notificationId(mac)
                    when {
                        mac in nearby && device.isTemporarilyAllowed ->
                            WatchNotificationHelper.postAllowedNotification(this@BluetoothBouncerApp, mac, device.deviceName)
                        mac in nearby && device.isAlertEnabled ->
                            WatchNotificationHelper.postNearbyNotification(this@BluetoothBouncerApp, mac, device.deviceName)
                        else ->
                            androidx.core.app.NotificationManagerCompat.from(this@BluetoothBouncerApp).cancel(notifId)
                    }
                }
            }
        }
    }

    /**
     * Re-applies blocks every time Shizuku becomes ready. Anything that failed while Shizuku
     * was unavailable (re-pair protection, re-blocking a departed temp-allowed device, boot)
     * is repaired here — this is the "retry" for all of them.
     */
    private fun launchPolicyReconciler() {
        applicationScope.launch {
            shizukuHelper.state
                .map { it is ShizukuHelper.State.Ready }
                .distinctUntilChanged()
                .filter { it }
                .collect {
                    policyEnforcer.reconcile("Shizuku ready")
                    // Catch devices paired while Shizuku was unavailable.
                    policyEnforcer.autoBlockNewDevices("Shizuku ready")
                }
        }
    }

    /**
     * Reconciles when Bluetooth is switched on: [PolicyEnforcer.reconcile] skips while the
     * adapter is off, so blocks that couldn't be re-applied then are caught up here.
     */
    private fun registerBluetoothStateReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                if (state != BluetoothAdapter.STATE_ON) return
                applicationScope.launch {
                    // Give the Bluetooth profile services a moment to come up so the
                    // UserService's proxies are usable.
                    delay(BLUETOOTH_ON_SETTLE_MS)
                    policyEnforcer.reconcile("Bluetooth on", force = true)
                    policyEnforcer.autoBlockNewDevices("Bluetooth on")
                }
            }
        }
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        // RECEIVER_EXPORTED so the Bluetooth system service (another package) can deliver it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }

    /**
     * Removes any CDM associations that exist at the OS level but are no longer tracked in Room.
     *
     * This can happen when the app crashes mid-association (before [cdmAssociationId] is
     * persisted), or when the database is cleared. Orphaned associations cause the CDM to keep
     * delivering [onDeviceAppeared]/[onDeviceDisappeared] callbacks with stale IDs that no
     * longer match any Room row.
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun cleanUpStaleCdmAssociations() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val cdm = getSystemService(CompanionDeviceManager::class.java)
                val osAssociations = cdm.myAssociations
                if (osAssociations.isEmpty()) return@launch

                val dao = database.blockedDeviceDao()
                val knownIds = dao.getWatchedDevices().mapNotNull { it.cdmAssociationId }.toSet()

                for (assoc in osAssociations) {
                    if (assoc.id !in knownIds) {
                        Log.d(TAG, "Removing stale CDM association id=${assoc.id} mac=${assoc.deviceMacAddress}")
                        try {
                            cdm.disassociate(assoc.id)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to disassociate stale id=${assoc.id}", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "cleanUpStaleCdmAssociations failed", e)
            }
        }
    }

    companion object {
        private const val TAG = "BluetoothBouncerApp"

        /**
         * How long a device remains in the "nearby" set (and its notification stays visible)
         * after CDM fires [onDeviceDisappeared] for a non-temporarily-allowed device.
         *
         * Matches the UI's detection-decay window so the notification and the
         * "Detected recently" label disappear at the same time.
         */
        const val DETECTION_GRACE_PERIOD_MS = 30_000L

        /** Delay after Bluetooth turns on before reconciling, so profile services are up. */
        private const val BLUETOOTH_ON_SETTLE_MS = 3_000L
    }
}
