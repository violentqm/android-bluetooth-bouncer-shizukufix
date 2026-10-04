package net.harveywilliams.bluetoothbouncer.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.DeadObjectException
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.harveywilliams.bluetoothbouncer.BuildConfig
import net.harveywilliams.bluetoothbouncer.IBluetoothBouncerUserService
import rikka.shizuku.Shizuku

/**
 * Manages all Shizuku integration: state detection, permission requests, and the privileged
 * helper that does the actual Bluetooth work as the shell user.
 *
 * The helper is reached through a [PolicyChannel], which is one of:
 *  - the Shizuku **UserService** ([BluetoothBouncerUserService]) — Shizuku's official API, tried
 *    first; or
 *  - the **shell helper** ([ShellChannel] / [ShellMain]) — our own process started through a
 *    Shizuku shell process. Used when the UserService doesn't connect within
 *    [USER_SERVICE_TIMEOUT_MS]: on some phones Shizuku's UserService starter fails before any
 *    of our code runs (see [ShellMain] for the known causes), which used to leave the app stuck
 *    at "Connecting…" forever. Once the shell helper has had to step in, it's used directly on
 *    later launches.
 */
class ShizukuHelper(private val context: Context) {

    sealed class State {
        /** Shizuku app is not installed on the device. */
        object NotInstalled : State()

        /** Shizuku is installed but not currently running (needs ADB/Wireless Debug start). */
        object NotRunning : State()

        /** Shizuku is running but permission has not been granted to this app. */
        object PermissionDenied : State()

        /** Shizuku is running and permission is granted; the privileged helper is starting. */
        object Connecting : State()

        /** Shizuku is running, permission granted, and the privileged helper is ready. */
        object Ready : State()
    }

    private val _state = MutableStateFlow<State>(State.NotRunning)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Connected UserService, if Shizuku managed to start it. */
    @Volatile
    private var aidlChannel: AidlChannel? = null

    /** Running shell helper, if one was started. */
    @Volatile
    private var shellChannel: ShellChannel? = null

    /** Whether we've asked Shizuku for the UserService in the current Shizuku session. */
    @Volatile
    private var bindRequested = false

    /** Uptime (ms) at which the UserService was requested. */
    @Volatile
    private var bindStartedAt = 0L

    @Volatile
    private var shellStarting = false

    /** The shell helper failed to start in the current Shizuku session (reset by Retry). */
    @Volatile
    private var shellFailed = false

    @Volatile
    private var lastShellError: String? = null

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Persisted: the UserService didn't connect on this phone but the shell helper did. */
    private var preferShellHelper: Boolean
        get() = prefs.getBoolean(KEY_PREFER_SHELL_HELPER, false)
        set(value) = prefs.edit().putBoolean(KEY_PREFER_SHELL_HELPER, value).apply()

    private val _bindProblem = MutableStateFlow<String?>(null)

    /**
     * Human-readable description of why the privileged helper can't be started, or null while
     * things are fine. Shown on the setup screen while the state is stuck at [State.Connecting].
     */
    val bindProblem: StateFlow<String?> = _bindProblem.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var monitorJob: Job? = null

    // ── Shizuku listeners ────────────────────────────────────────────────────

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.d(TAG, "Shizuku binder received")
        synchronized(this) {
            // A (possibly new) Shizuku session — give both helpers a fresh chance.
            bindRequested = false
            bindStartedAt = 0L
            shellFailed = false
        }
        refreshState()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.d(TAG, "Shizuku binder dead")
        synchronized(this) {
            aidlChannel = null
            dropShellChannel()
            bindRequested = false
            bindStartedAt = 0L
        }
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
                aidlChannel = AidlChannel(IBluetoothBouncerUserService.Stub.asInterface(binder))
                _bindProblem.value = null
                _state.value = State.Ready
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            Log.d(TAG, "UserService disconnected")
            synchronized(this@ShizukuHelper) {
                aidlChannel = null
                // Let the next refresh ask again (or fall back to the shell helper).
                bindRequested = false
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
                aidlChannel = null
                dropShellChannel()
                bindRequested = false
                bindStartedAt = 0L
                _state.value = if (isShizukuInstalled()) State.NotRunning else State.NotInstalled
            }
            !hasPermission() -> {
                _state.value = State.PermissionDenied
            }
            activeChannel() != null -> {
                _state.value = State.Ready
            }
            else -> {
                _state.value = State.Connecting
                ensureConnecting()
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
                if (_state.value !is State.Ready || activeChannel() == null) {
                    refreshState()
                }
                delay(MONITOR_INTERVAL_MS)
            }
        }
    }

    /**
     * For the "Retry" button while stuck at [State.Connecting]: restarts the shell helper and
     * clears earlier failures.
     *
     * Deliberately does not remove and re-create the UserService: on some Shizuku builds, once a
     * UserService has been removed it can't be started again until Shizuku itself is restarted
     * (thedjchi/Shizuku#201). An earlier version of this app did exactly that on every retry.
     */
    @Synchronized
    fun restartUserService() {
        Log.i(TAG, "Retrying the privileged helper on request")
        shellFailed = false
        lastShellError = null
        _bindProblem.value = null
        dropShellChannel()
        refreshState()
    }

    /**
     * Builds a plain-text diagnostics report: app/device/Shizuku facts plus the recent system log
     * lines about Bluetooth Bouncer and Shizuku. The log is read through a Shizuku shell process,
     * so it includes the UserService's own process — the only place a startup crash of that
     * service is visible. Works while the state is [State.Connecting]; without Shizuku it
     * returns the facts only.
     */
    suspend fun collectDiagnostics(): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        sb.appendLine("Bluetooth Bouncer diagnostics")
        sb.appendLine("App: ${context.packageName} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        sb.appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
        sb.appendLine("State: ${_state.value.javaClass.simpleName}, channel: ${activeChannel()?.kind ?: "none"}")
        sb.appendLine("UserService: requested=$bindRequested, connected=${aidlChannel?.isAlive() == true}, " +
            "waited ${if (bindStartedAt == 0L) 0 else (android.os.SystemClock.uptimeMillis() - bindStartedAt) / 1000}s")
        sb.appendLine("Shell helper: alive=${shellChannel?.isAlive() == true}, starting=$shellStarting, " +
            "failed=$shellFailed, preferred=$preferShellHelper")
        lastShellError?.let { sb.appendLine("Shell helper error: $it") }
        _bindProblem.value?.let { sb.appendLine("Problem: $it") }
        try {
            sb.appendLine("Shizuku: running=${isShizukuRunning()}, version=${Shizuku.getVersion()}, uid=${Shizuku.getUid()}, permission=${hasPermission()}")
        } catch (e: Exception) {
            sb.appendLine("Shizuku: unavailable ($e)")
        }
        sb.appendLine()
        sb.appendLine("── Log ──")
        try {
            val lines = readSystemLogViaShizuku()
            val relevant = lines.filter { line -> LOG_KEYWORDS.any { line.contains(it, ignoreCase = true) } }
            relevant.takeLast(MAX_DIAGNOSTIC_LOG_LINES).forEach { sb.appendLine(it) }
            if (relevant.isEmpty()) sb.appendLine("(no matching lines in the last ${lines.size} log lines)")
        } catch (e: Exception) {
            sb.appendLine("Couldn't read the system log: $e")
        }
        sb.toString()
    }

    /**
     * Runs `logcat -d` as the Shizuku (shell) user. `Shizuku.newProcess` is private in API 13
     * but still present, so it's called reflectively — this is a diagnostics-only path.
     */
    private fun readSystemLogViaShizuku(): List<String> {
        val process = ShellChannel.newShizukuProcess(arrayOf("logcat", "-d", "-v", "time", "-t", "3000"))
        return try {
            process.inputStream.bufferedReader().readLines()
        } finally {
            process.destroy()
        }
    }

    /** Stop the polling started by [startMonitoring]. */
    fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
    }

    /**
     * Request Shizuku permission from the user.
     *
     * Returns false when Shizuku will not show its permission prompt — the user previously
     * picked "Deny and don't ask again", so permission can only be granted from inside the
     * Shizuku app. Callers should send the user there.
     */
    fun requestPermission(): Boolean {
        if (!isShizukuRunning()) {
            refreshState()
            return true
        }
        return try {
            when {
                hasPermission() -> refreshState()
                Shizuku.shouldShowRequestPermissionRationale() -> return false
                else -> Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "requestPermission failed", e)
            refreshState()
            true
        }
    }

    /**
     * Suspends until the privileged helper is [State.Ready], for callers that start cold (boot,
     * a broadcast) and need Shizuku before doing anything. Returns false straight away if Shizuku
     * isn't running or permission isn't granted, or after [timeoutMs] if no helper starts.
     */
    suspend fun awaitReady(timeoutMs: Long = CONNECT_WAIT_MS): Boolean {
        refreshState()
        if (_state.value is State.Ready) return true
        if (!isShizukuRunning() || !hasPermission()) return false
        return withTimeoutOrNull(timeoutMs) { _state.first { it is State.Ready } } != null
    }

    /**
     * Call setConnectionPolicy through the privileged helper.
     * Returns a [Result] wrapping the int[] from the helper,
     * or a failure if no helper is available.
     *
     * If no helper is connected yet but Shizuku is running and permission is granted, one is
     * likely still starting (common on cold start). In that case this function suspends and
     * waits up to [CONNECT_WAIT_MS] for [State.Ready] before proceeding.
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

    /** Release all Shizuku listeners and helpers. Call from Application.onTerminate or similar. */
    fun cleanup() {
        stopMonitoring()
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        synchronized(this) { dropShellChannel() }
        doUnbindUserService()
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    /**
     * Routes a call through the shared helper-access scaffold:
     * waits for a helper to start (if needed), checks availability,
     * invokes [call] off the main thread, validates that at least one profile reported
     * success, and wraps any exception in a [Result.failure].
     *
     * The call is synchronous and the helper can block for several seconds while its profile
     * proxies connect, so it always runs on [Dispatchers.IO] — callers on the main thread (the
     * ViewModel) would otherwise freeze the UI.
     *
     * If the helper has died since we last used it, the call is retried once on a new one.
     */
    private suspend fun callService(
        operationName: String,
        retryOnDeadService: Boolean = true,
        call: (PolicyChannel) -> IntArray,
    ): Result<IntArray> {
        awaitServiceIfNeeded()
        val channel = activeChannel()
            ?: return Result.failure(IllegalStateException("Bluetooth Bouncer's Shizuku helper is not available"))
        return try {
            val results = withContext(Dispatchers.IO) { call(channel) }
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is DeadObjectException || e is ChannelDeadException) {
                Log.w(TAG, "$operationName: ${channel.kind} died — reconnecting", e)
                synchronized(this) {
                    if (aidlChannel === channel) {
                        aidlChannel = null
                        bindRequested = false
                        bindStartedAt = 0L
                    }
                    if (shellChannel === channel) dropShellChannel()
                }
                refreshState()
                if (retryOnDeadService) {
                    callService(operationName, retryOnDeadService = false, call)
                } else {
                    Result.failure(e)
                }
            } else {
                Log.e(TAG, "$operationName failed via ${channel.kind}", e)
                Result.failure(e)
            }
        }
    }

    /** Suspends until a helper is ready, or gives up after [CONNECT_WAIT_MS]. */
    private suspend fun awaitServiceIfNeeded() {
        if (activeChannel() == null && isShizukuRunning() && hasPermission()) {
            refreshState()
            val ready = withTimeoutOrNull(CONNECT_WAIT_MS) {
                _state.first { it is State.Ready }
            }
            if (ready == null) Log.w(TAG, "Timed out waiting for a privileged helper")
        }
    }

    /** The helper to use: the UserService if connected, else the shell helper, else null. */
    private fun activeChannel(): PolicyChannel? {
        aidlChannel?.let { if (it.isAlive()) return it }
        shellChannel?.let { if (it.isAlive()) return it }
        return null
    }

    /**
     * Gets a helper starting, if one isn't already. Called with the lock held while Connecting.
     *
     * Order: the UserService (once per Shizuku session — never removed and re-requested, see
     * [restartUserService]); if it hasn't connected after [USER_SERVICE_TIMEOUT_MS], or this
     * phone already needed the shell helper before, the shell helper.
     */
    private fun ensureConnecting() {
        if (shellChannel?.isAlive() == false) dropShellChannel()

        if (preferShellHelper && !shellFailed) {
            startShellHelper()
            return
        }
        val now = android.os.SystemClock.uptimeMillis()
        if (!bindRequested) {
            bindRequested = true
            bindStartedAt = now
            bindUserService()
            // Check back when the timeout passes, even if nothing else calls refreshState()
            // (e.g. a broadcast receiver waiting in the background with no UI polling).
            scope.launch {
                delay(USER_SERVICE_TIMEOUT_MS + 250)
                refreshState()
            }
            return
        }
        if (now - bindStartedAt >= USER_SERVICE_TIMEOUT_MS && !shellFailed) {
            startShellHelper()
        }
    }

    /** Starts the shell helper in the background, unless one is already starting. Lock held. */
    private fun startShellHelper() {
        if (shellStarting) return
        shellStarting = true
        Log.i(TAG, if (bindRequested) {
            "UserService hasn't connected — starting the shell helper"
        } else {
            "Starting the shell helper (the UserService didn't work on this phone before)"
        })
        scope.launch(Dispatchers.IO) {
            val result = runCatching { ShellChannel.start(context) }
            synchronized(this@ShizukuHelper) {
                shellStarting = false
                result.onSuccess { channel ->
                    if (!isShizukuRunning()) {
                        channel.destroy()
                    } else {
                        dropShellChannel()
                        shellChannel = channel
                        // The UserService didn't make it — skip straight to this next time.
                        if (aidlChannel == null) preferShellHelper = true
                        shellFailed = false
                        lastShellError = null
                        _bindProblem.value = null
                        _state.value = State.Ready
                    }
                }.onFailure { e ->
                    Log.e(TAG, "Shell helper failed to start", e)
                    shellFailed = true
                    lastShellError = e.message ?: e.toString()
                    // Don't insist on a helper that doesn't work: next time try the UserService.
                    preferShellHelper = false
                    _bindProblem.value = "Shizuku couldn't start Bluetooth Bouncer's helper, " +
                        "either as a background service or as a shell process. " +
                        "Details: ${lastShellError}"
                }
            }
            if (result.isFailure) refreshState() // falls back to the UserService if not tried yet
        }
    }

    /** Lock held. */
    private fun dropShellChannel() {
        shellChannel?.destroy()
        shellChannel = null
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

    /** Lock held. On failure, goes straight to the shell helper. */
    private fun bindUserService() {
        try {
            Shizuku.bindUserService(buildUserServiceArgs(), serviceConnection)
        } catch (e: Exception) {
            Log.e(TAG, "bindUserService failed — using the shell helper", e)
            bindStartedAt = 0L // counts as timed out
            if (!shellFailed) startShellHelper()
        }
    }

    private fun doUnbindUserService() {
        if (aidlChannel != null) {
            try {
                // remove = false: removing a UserService can stop it from ever starting again
                // until Shizuku restarts on some builds (thedjchi/Shizuku#201).
                Shizuku.unbindUserService(buildUserServiceArgs(), serviceConnection, false)
            } catch (e: Exception) {
                Log.w(TAG, "unbindUserService failed", e)
            }
            aidlChannel = null
        }
    }

    /** [PolicyChannel] over the Shizuku UserService's AIDL interface. */
    private class AidlChannel(private val service: IBluetoothBouncerUserService) : PolicyChannel {
        override val kind = "Shizuku UserService"
        override fun isAlive(): Boolean = service.asBinder().isBinderAlive
        override fun setConnectionPolicy(macAddress: String, policy: Int): IntArray =
            service.setConnectionPolicy(macAddress, policy)
        override fun connectDevice(macAddress: String): IntArray = service.connectDevice(macAddress)
        override fun disconnectDevice(macAddress: String): IntArray = service.disconnectDevice(macAddress)
    }

    companion object {
        private const val TAG = "ShizukuHelper"
        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private const val PERMISSION_REQUEST_CODE = 1001

        /** How long to give the UserService before starting the shell helper instead. */
        private const val USER_SERVICE_TIMEOUT_MS = 6_000L

        /**
         * How long callers wait for any helper: the UserService timeout plus the shell helper's
         * own startup (a cold `app_process` can take several seconds).
         */
        private const val CONNECT_WAIT_MS = 30_000L

        private const val PREFS_NAME = "shizuku"
        private const val KEY_PREFER_SHELL_HELPER = "prefer_shell_helper"

        /** Log lines containing any of these make it into [collectDiagnostics]. */
        private val LOG_KEYWORDS = listOf(
            "bluetoothbouncer", "BBUserService", "BBShell", "ShizukuHelper", "PolicyEnforcer",
            "Shizuku", "UserService", "user_service", "AndroidRuntime", "FATAL",
        )
        private const val MAX_DIAGNOSTIC_LOG_LINES = 300

        /** How often [startMonitoring] re-checks Shizuku state while not Ready. */
        private const val MONITOR_INTERVAL_MS = 1_000L

        /** BluetoothProfile.CONNECTION_POLICY_FORBIDDEN */
        const val POLICY_FORBIDDEN = 0

        /** BluetoothProfile.CONNECTION_POLICY_ALLOWED */
        const val POLICY_ALLOWED = 100
    }
}
