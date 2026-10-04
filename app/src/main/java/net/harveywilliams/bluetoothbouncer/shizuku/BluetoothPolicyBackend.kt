package net.harveywilliams.bluetoothbouncer.shizuku

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.util.Log
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * The privileged half of Bluetooth Bouncer: changes connection policy and connects/disconnects
 * devices through the hidden Bluetooth profile APIs. Must run as the shell UID (which holds
 * BLUETOOTH_PRIVILEGED), in one of two hosts:
 *  - [BluetoothBouncerUserService] — a Shizuku UserService (the official API), or
 *  - [ShellMain] — our own `app_process` started through a Shizuku shell process, used on
 *    phones where Shizuku can't start a UserService.
 *
 * The constructor must never throw: in the UserService host, Shizuku would then never hand the
 * binder back and the app would wait at "Connecting…" forever. All Bluetooth setup is wrapped
 * to catch [Throwable]; a failed setup is retried on the next call and shows up as a per-call
 * failure (and in logcat) instead.
 *
 * @param explicitContext context to use for profile proxies. Null means "find one" via
 *   ActivityThread reflection (works inside a Shizuku UserService).
 */
class BluetoothPolicyBackend(private val explicitContext: Context? = null) {

    // Mutable so reinitializeIfNeeded() can replace dead futures and retry.
    @Volatile private var bluetoothAdapter: BluetoothAdapter? = null

    @Volatile private var a2dpFuture = CompletableFuture<BluetoothA2dp?>()
    @Volatile private var headsetFuture = CompletableFuture<BluetoothHeadset?>()
    @Volatile private var hidFuture = CompletableFuture<Any?>()

    init {
        Log.i(TAG, "Backend created (uid=${Process.myUid()}, sdk=${Build.VERSION.SDK_INT}, explicitContext=${explicitContext != null})")
        try {
            val ctx = explicitContext ?: getAppContext()
            bluetoothAdapter = resolveBluetoothAdapter(ctx)

            if (ctx == null || bluetoothAdapter == null) {
                Log.w(TAG, "No context or adapter at init — will retry on first call")
                bluetoothAdapter = null
                // Complete futures with null so any early callers don't block forever.
                completeFuturesWithNull()
            } else {
                initProxies(ctx, bluetoothAdapter!!)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Bluetooth setup failed at init — will retry on first call", t)
            bluetoothAdapter = null
            completeFuturesWithNull()
        }
    }

    private fun completeFuturesWithNull() {
        a2dpFuture.complete(null)
        headsetFuture.complete(null)
        hidFuture.complete(null)
    }

    /**
     * If the adapter was unavailable at construction (common in Shizuku's app_process
     * environment where ActivityThread isn't fully set up yet), try again now.
     * Replaces the dead CompletableFutures so the new proxy callbacks land correctly.
     */
    @Synchronized
    private fun reinitializeIfNeeded() {
        if (bluetoothAdapter != null) return

        Log.d(TAG, "Retrying initialization on first call")
        try {
            val ctx = explicitContext ?: getAppContext() ?: run {
                Log.e(TAG, "Still no context after retry — blocking will not work")
                return
            }
            val adapter = resolveBluetoothAdapter(ctx) ?: run {
                Log.e(TAG, "Still no BluetoothAdapter after retry")
                return
            }

            bluetoothAdapter = adapter
            // Replace futures (the old ones are already completed with null).
            a2dpFuture = CompletableFuture()
            headsetFuture = CompletableFuture()
            hidFuture = CompletableFuture()
            initProxies(ctx, adapter)
        } catch (t: Throwable) {
            Log.e(TAG, "Bluetooth setup retry failed", t)
            bluetoothAdapter = null
            completeFuturesWithNull()
        }
    }

    private fun initProxies(ctx: Context, adapter: BluetoothAdapter) {
        requestProxy(ctx, adapter, BluetoothProfile.A2DP, "A2DP") { a2dpFuture.complete(it as? BluetoothA2dp) }
        requestProxy(ctx, adapter, BluetoothProfile.HEADSET, "Headset") { headsetFuture.complete(it as? BluetoothHeadset) }
        // HID Host — BluetoothProfile.HID_HOST = 4 (constant not in compile-time SDK; use literal)
        requestProxy(ctx, adapter, 4 /* HID_HOST */, "HID") { hidFuture.complete(it) }
    }

    /**
     * Requests one profile proxy. [onProxy] receives the proxy, or null if the request itself
     * failed — so a failure on one profile never takes the others (or the service) down.
     */
    private fun requestProxy(
        ctx: Context,
        adapter: BluetoothAdapter,
        profile: Int,
        name: String,
        onProxy: (BluetoothProfile?) -> Unit,
    ) {
        try {
            val requested = adapter.getProfileProxy(ctx, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    Log.d(TAG, "$name proxy connected")
                    onProxy(proxy)
                }
                override fun onServiceDisconnected(profile: Int) {}
            }, profile)
            if (!requested) {
                Log.w(TAG, "getProfileProxy($name) returned false")
                onProxy(null)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "getProfileProxy($name) failed", t)
            onProxy(null)
        }
    }

    // ── Operations ────────────────────────────────────────────────────────────

    fun setConnectionPolicy(macAddress: String, policy: Int): IntArray {
        val results = forEachProfile(macAddress) { proxy, device ->
            callSetConnectionPolicy(proxy, device, policy)
        }
        Log.d(TAG, "setConnectionPolicy($macAddress, $policy) → ${results.toList()}")
        return results
    }

    fun connectDevice(macAddress: String): IntArray {
        val results = forEachProfile(macAddress) { proxy, device ->
            callProfileMethod(proxy, device, "connect")
        }
        Log.d(TAG, "connectDevice($macAddress) → ${results.toList()}")
        return results
    }

    fun disconnectDevice(macAddress: String): IntArray {
        val results = forEachProfile(macAddress) { proxy, device ->
            callProfileMethod(proxy, device, "disconnect")
        }
        Log.d(TAG, "disconnectDevice($macAddress) → ${results.toList()}")
        return results
    }

    // ── Core dispatch helper ──────────────────────────────────────────────────

    /**
     * Dispatches [action] across all three Bluetooth profile proxies (A2DP, Headset, HID),
     * returning an [IntArray] of size 3 with the result for each:
     *   1 = success, 0 = call returned false, -1 = proxy unavailable or timed out.
     *
     * Handles lazy initialisation, MAC→[BluetoothDevice] resolution, and per-proxy
     * timeout handling in one place so the three operations stay one-liners.
     */
    private fun forEachProfile(
        macAddress: String,
        action: (proxy: Any, device: BluetoothDevice) -> Int,
    ): IntArray {
        reinitializeIfNeeded()

        val device: BluetoothDevice? = try {
            bluetoothAdapter?.getRemoteDevice(macAddress)
        } catch (e: Exception) {
            Log.e(TAG, "Invalid MAC address: $macAddress", e)
            null
        }
        if (device == null) {
            Log.e(TAG, "forEachProfile: device is null (adapter=$bluetoothAdapter)")
            return intArrayOf(-1, -1, -1)
        }

        data class ProfileEntry(val name: String, val future: CompletableFuture<*>)
        val profiles = listOf(
            ProfileEntry("A2DP",    a2dpFuture),
            ProfileEntry("Headset", headsetFuture),
            ProfileEntry("HID",     hidFuture),
        )

        val results = IntArray(3) { -1 }
        profiles.forEachIndexed { i, entry ->
            try {
                val proxy = entry.future.get(PROXY_TIMEOUT_SEC, TimeUnit.SECONDS)
                results[i] = if (proxy != null) action(proxy, device) else -1
            } catch (e: Exception) {
                Log.w(TAG, "${entry.name} proxy timeout/error", e)
            }
        }
        return results
    }

    // ── Reflection helpers ────────────────────────────────────────────────────

    /**
     * Calls proxy.setConnectionPolicy(device, policy) via reflection.
     * The method is hidden API but accessible when running as shell UID.
     */
    private fun callSetConnectionPolicy(proxy: Any, device: BluetoothDevice, policy: Int): Int {
        return try {
            val method = proxy.javaClass.getMethod(
                "setConnectionPolicy",
                BluetoothDevice::class.java,
                Int::class.java
            )
            val result = method.invoke(proxy, device, policy) as? Boolean ?: false
            if (result) 1 else 0
        } catch (e: Exception) {
            Log.e(TAG, "setConnectionPolicy reflection failed on ${proxy.javaClass.simpleName}", e)
            0
        }
    }

    /**
     * Calls proxy.connect(device) or proxy.disconnect(device) via reflection.
     * Both are hidden APIs accessible when running as shell UID.
     */
    private fun callProfileMethod(proxy: Any, device: BluetoothDevice, methodName: String): Int {
        return try {
            val method = proxy.javaClass.getMethod(methodName, BluetoothDevice::class.java)
            val result = method.invoke(proxy, device) as? Boolean ?: false
            if (result) 1 else 0
        } catch (e: Exception) {
            Log.e(TAG, "$methodName reflection failed on ${proxy.javaClass.simpleName}", e)
            0
        }
    }

    companion object {
        private const val TAG = "BBUserService"
        private const val PROXY_TIMEOUT_SEC = 8L

        /**
         * Resolves a BluetoothAdapter using three strategies in order:
         *
         *  1. context.getSystemService(BLUETOOTH_SERVICE) — works in normal app processes.
         *  2. BluetoothAdapter.getDefaultAdapter() — deprecated but still functional on some paths.
         *  3. ServiceManager.getService("bluetooth_manager") + hidden BluetoothAdapter constructor —
         *     works in Shizuku's app_process environment where the system service registry is not
         *     wired into the Context but the binder is still accessible as shell UID.
         */
        @SuppressLint("PrivateApi")
        private fun resolveBluetoothAdapter(ctx: Context?): BluetoothAdapter? {
            // Strategy 1: normal system service lookup
            try {
                ctx?.let {
                    val adapter = (it.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
                    if (adapter != null) {
                        Log.d(TAG, "BluetoothAdapter via getSystemService()")
                        return adapter
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "getSystemService(BLUETOOTH_SERVICE) failed: $t")
            }

            // Strategy 2: deprecated static getter (still works on some API 31+ paths)
            try {
                @Suppress("DEPRECATION")
                BluetoothAdapter.getDefaultAdapter()?.let {
                    Log.d(TAG, "BluetoothAdapter via getDefaultAdapter()")
                    return it
                }
            } catch (t: Throwable) {
                Log.w(TAG, "getDefaultAdapter() failed: $t")
            }

            // Strategy 3: get bluetooth_manager binder via ServiceManager, then construct adapter directly.
            // ServiceManager is accessible as shell UID. BluetoothAdapter has a hidden constructor that
            // takes an IBluetoothManager — same path getDefaultAdapter() uses internally but called explicitly.
            return try {
                val binder = Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String::class.java)
                    .invoke(null, "bluetooth_manager") as? IBinder
                    ?: return null.also { Log.e(TAG, "ServiceManager.getService(bluetooth_manager) returned null") }

                val iBluetoothManagerClass = Class.forName("android.bluetooth.IBluetoothManager\$Stub")
                val managerService = iBluetoothManagerClass
                    .getMethod("asInterface", IBinder::class.java)
                    .invoke(null, binder)

                val iBluetoothManagerInterface = Class.forName("android.bluetooth.IBluetoothManager")

                // Try one-arg constructor first (it builds AttributionSource internally).
                // Fall back to two-arg (API 31+) with a real AttributionSource if one-arg is absent.
                val adapter: BluetoothAdapter? = try {
                    BluetoothAdapter::class.java
                        .getDeclaredConstructor(iBluetoothManagerInterface)
                        .also { it.isAccessible = true }
                        .newInstance(managerService) as? BluetoothAdapter
                } catch (e: NoSuchMethodException) {
                    val attributionSourceClass = Class.forName("android.content.AttributionSource")
                    val myAttributionSource = attributionSourceClass
                        .getMethod("myAttributionSource")
                        .invoke(null)
                    BluetoothAdapter::class.java
                        .getDeclaredConstructor(iBluetoothManagerInterface, attributionSourceClass)
                        .also { it.isAccessible = true }
                        .newInstance(managerService, myAttributionSource) as? BluetoothAdapter
                }

                if (adapter == null) {
                    Log.e(TAG, "BluetoothAdapter constructor returned null")
                    return null
                }

                Log.d(TAG, "BluetoothAdapter via ServiceManager reflection")

                // BluetoothProfileConnector has a field initialized as:
                //   private final BluetoothAdapter mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
                // This runs BEFORE the constructor body, so it uses the static singleton.
                // If getDefaultAdapter() returns null (as it does in this process), every
                // BluetoothProfileConnector.connect() call NPEs. Fix: populate the static
                // sAdapter singleton BEFORE getProfileProxy() creates any BluetoothA2dp/etc.
                try {
                    BluetoothAdapter::class.java
                        .getDeclaredField("sAdapter")
                        .also { it.isAccessible = true }
                        .set(null, adapter)
                    Log.d(TAG, "Set BluetoothAdapter.sAdapter")
                } catch (e: Throwable) {
                    Log.w(TAG, "Could not set sAdapter — getProfileProxy may still fail: ${e.message}")
                }

                adapter
            } catch (e: Throwable) {
                Log.e(TAG, "BluetoothAdapter via ServiceManager failed: ${e.message}", e)
                null
            }
        }

        /**
         * Gets a usable Context for the Shizuku UserService process.
         *
         * Strategy:
         *  1. Try ActivityThread.currentApplication() — works if Shizuku has wired up a real app.
         *  2. Fall back to ActivityThread.currentActivityThread().getSystemContext() — always
         *     available in app_process environments (which is how Shizuku starts UserServices),
         *     even without a real Application object.
         */
        @SuppressLint("PrivateApi")
        private fun getAppContext(): Context? {
            // Attempt 1: real Application context
            try {
                val app = Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication")
                    .invoke(null) as? Application
                if (app != null) {
                    Log.d(TAG, "Got context via currentApplication()")
                    return app
                }
            } catch (e: Throwable) {
                Log.w(TAG, "currentApplication() failed: ${e.message}")
            }

            // Attempt 2: system context from the ActivityThread (always present in app_process)
            try {
                val atClass = Class.forName("android.app.ActivityThread")
                val thread = atClass.getDeclaredMethod("currentActivityThread")
                    .also { it.isAccessible = true }
                    .invoke(null)
                if (thread != null) {
                    val ctx = atClass.getDeclaredMethod("getSystemContext")
                        .also { it.isAccessible = true }
                        .invoke(thread) as? Context
                    if (ctx != null) {
                        Log.d(TAG, "Got context via getSystemContext()")
                        return ctx
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "getSystemContext() failed: ${e.message}")
            }

            Log.e(TAG, "Could not obtain any Context — Bluetooth proxy init will fail")
            return null
        }
    }
}
