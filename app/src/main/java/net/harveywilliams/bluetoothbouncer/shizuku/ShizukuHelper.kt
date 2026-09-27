package net.harveywilliams.bluetoothbouncer.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import net.harveywilliams.bluetoothbouncer.BuildConfig
import net.harveywilliams.bluetoothbouncer.IBluetoothBouncerUserService
import rikka.shizuku.Shizuku

/**
 * Manages all Shizuku integration: state detection, permission requests,
 * and UserService lifecycle.
 */
class ShizukuHelper(private val context: Context) {

    sealed class State {
        /** Shizuku app is not installed on the device. */
        object NotInstalled : State()

        /** Shizuku is installed but not currently running (needs ADB/Wireless Debug start). */
        object NotRunning : State()

        /** Shizuku is running but permission has not been granted to this app. */
        object PermissionDenied : State()

        /** Shizuku is running and permission is granted; the UserService is still binding. */
        object Connecting : State()

        /** Shizuku is running, permission granted, UserService is bound and ready. */
        object Ready : State()
    }

    private val _state = MutableStateFlow<State>(State.NotRunning)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile
    private var userService: IBluetoothBouncerUserService? = null

    /** Uptime (ms) at which the in-flight UserService bind was started, or 0 if none. */
    @Volatile
    private var bindStartedAt = 0L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var monitorJob: Job? = null

    // ── Shizuku listeners ────────────────────────────────────────────────────

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.d(TAG, "Shizuku binder received")
        refreshState()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.d(TAG, "Shizuku binder dead")
        userService = null
        bindStartedAt = 0L
        refreshState()
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == PERMISSION_REQUEST_CODE) {
                Log.d(TAG, "Permission result: $grantResult")
                refreshState()
            }
        }

    // ── UserService connection ───────────────────────────────────────────────

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            Log.d(TAG, "UserService connected")
            synchronized(this@ShizukuHelper) {
                userService = IBluetoothBouncerUserService.Stub.asInterface(binder)
                bindStartedAt = 0L
                _state.value = State.Ready
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            Log.d(TAG, "UserService disconnected")
            synchronized(this@ShizukuHelper) {
                userService = null
                bindStartedAt = 0L
                refreshState()
            }
        }
    }

    init {
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
        refreshState()
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Manually re-check and update the current Shizuku state.
     *
     * "Running" is checked before "installed": a live Shizuku binder is proof enough that
     * Shizuku (or Sui, or a fork with a different package name) is available, regardless of
     * whether its package is visible to us.
     */
    @Synchronized
    fun refreshState() {
        when {
            !isShizukuRunning() -> {
                userService = null
                bindStartedAt = 0L
                _state.value = if (isShizukuInstalled()) State.NotRunning else State.NotInstalled
            }
            !hasPermission() -> {
                _state.value = State.PermissionDenied
            }
            userService?.asBinder()?.isBinderAlive != true -> {
                userService = null
                _state.value = State.Connecting
                // Permission granted — bind the UserService (unless a bind is already in flight)
                val now = android.os.SystemClock.uptimeMillis()
                if (bindStartedAt == 0L || now - bindStartedAt > BIND_TIMEOUT_MS) {
                    bindStartedAt = now
                    bindUserService()
                }
            }
            else -> {
                _state.value = State.Ready
            }
        }
    }

    /**
     * Start polling Shizuku state while the UI is visible. The Shizuku library does not
     * notify us of every transition (e.g. permission granted from inside the Shizuku app,
     * or a missed binder broadcast), so a cheap periodic re-check keeps the UI accurate.
     * Polling pauses once [State.Ready] is reached and resumes if the state regresses.
     * Call from Activity.onStart; pair with [stopMonitoring] in onStop.
     */
    fun startMonitoring() {
        if (monitorJob?.isActive == true) return
        monitorJob = scope.launch {
            while (isActive) {
                if (_state.value !is State.Ready || userService?.asBinder()?.isBinderAlive != true) {
                    refreshState()
                }
                delay(MONITOR_INTERVAL_MS)
            }
        }
    }

    /** Stop the polling started by [startMonitoring]. */
    fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
    }

    /** Request Shizuku permission from the user. */
    fun requestPermission() {
        if (!isShizukuRunning()) {
            refreshState()
            return
        }
        try {
            if (hasPermission()) {
                refreshState()
            } else {
                Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
            }
        } catch (e: Exception) {
            Log.e(TAG, "requestPermission failed", e)
            refreshState()
        }
    }

    /**
     * Call setConnectionPolicy on the UserService.
     * Returns a [Result] wrapping the int[] from the service,
     * or a failure if the service is unavailable.
     *
     * If [userService] is null but Shizuku is running and permission is granted,
     * the UserService bind is likely still in progress (common on cold start).
     * In that case this function suspends and waits up to [BIND_TIMEOUT_MS] for
     * [State.Ready] before proceeding, rather than failing immediately.
     */
    suspend fun setConnectionPolicy(macAddress: String, policy: Int): Result<IntArray> =
        callService("setConnectionPolicy") { it.setConnectionPolicy(macAddress, policy) }

    /**
     * Actively connects a Bluetooth device across all supported profiles via the UserService.
     * Returns [Result.success] if at least one profile reported success, [Result.failure] otherwise.
     */
    suspend fun connectDevice(macAddress: String): Result<IntArray> =
        callService("connectDevice") { it.connectDevice(macAddress) }

    /**
     * Actively disconnects a Bluetooth device across all supported profiles via the UserService.
     * Returns [Result.success] if at least one profile reported success, [Result.failure] otherwise.
     */
    suspend fun disconnectDevice(macAddress: String): Result<IntArray> =
        callService("disconnectDevice") { it.disconnectDevice(macAddress) }

    /** Release all Shizuku listeners and unbind the service. Call from Application.onTerminate or similar. */
    fun cleanup() {
        stopMonitoring()
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        doUnbindUserService()
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    /**
     * Routes an AIDL call through the shared service-access scaffold:
     * waits for the UserService to bind (if needed), checks availability,
     * invokes [call], validates that at least one profile reported success,
     * and wraps any exception in a [Result.failure].
     */
    private suspend fun callService(
        operationName: String,
        call: (IBluetoothBouncerUserService) -> IntArray,
    ): Result<IntArray> {
        awaitServiceIfNeeded()
        val service = userService
            ?: return Result.failure(IllegalStateException("Shizuku UserService is not available"))
        return try {
            val results = call(service)
            // Each entry: 1 = success, 0 = call returned false, -1 = proxy unavailable.
            // If no profile reported success the OS state was never changed — treat as failure.
            if (results.none { it == 1 }) {
                Result.failure(
                    IllegalStateException(
                        "$operationName had no effect on any profile (results: ${results.toList()})"
                    )
                )
            } else {
                Result.success(results)
            }
        } catch (e: Exception) {
            Log.e(TAG, "$operationName failed", e)
            Result.failure(e)
        }
    }

    /** Suspends until the UserService is ready, or gives up after [BIND_TIMEOUT_MS]. */
    private suspend fun awaitServiceIfNeeded() {
        if (userService == null && isShizukuRunning() && hasPermission()) {
            try {
                withTimeout(BIND_TIMEOUT_MS) {
                    _state.first { it is State.Ready }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Timed out or interrupted waiting for UserService to bind", e)
            }
        }
    }

    fun isShizukuInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun isShizukuRunning(): Boolean = try {
        Shizuku.pingBinder()
    } catch (e: Exception) {
        false
    }

    private fun hasPermission(): Boolean = try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Exception) {
        false
    }

    private fun buildUserServiceArgs() = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, BluetoothBouncerUserService::class.java.name)
    )
        .daemon(false)
        .processNameSuffix("user_service")
        .version(BuildConfig.VERSION_CODE)

    private fun bindUserService() {
        try {
            Shizuku.bindUserService(buildUserServiceArgs(), serviceConnection)
        } catch (e: Exception) {
            Log.e(TAG, "bindUserService failed", e)
            bindStartedAt = 0L
        }
    }

    private fun doUnbindUserService() {
        if (userService != null) {
            try {
                Shizuku.unbindUserService(buildUserServiceArgs(), serviceConnection, true)
            } catch (e: Exception) {
                Log.w(TAG, "unbindUserService failed", e)
            }
            userService = null
        }
    }

    companion object {
        private const val TAG = "ShizukuHelper"
        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private const val PERMISSION_REQUEST_CODE = 1001

        /** Milliseconds to wait for the UserService to bind on cold start before giving up. */
        private const val BIND_TIMEOUT_MS = 10_000L

        /** How often [startMonitoring] re-checks Shizuku state while not Ready. */
        private const val MONITOR_INTERVAL_MS = 1_000L

        /** BluetoothProfile.CONNECTION_POLICY_FORBIDDEN */
        const val POLICY_FORBIDDEN = 0

        /** BluetoothProfile.CONNECTION_POLICY_ALLOWED */
        const val POLICY_ALLOWED = 100
    }
}
