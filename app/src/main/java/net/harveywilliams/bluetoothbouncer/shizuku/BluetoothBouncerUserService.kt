package net.harveywilliams.bluetoothbouncer.shizuku

import android.content.Context
import net.harveywilliams.bluetoothbouncer.IBluetoothBouncerUserService

/**
 * Shizuku UserService — runs as shell UID (which holds BLUETOOTH_PRIVILEGED) in a process
 * Shizuku starts for us. All the work is done by [BluetoothPolicyBackend]; this class only
 * exposes it over AIDL.
 *
 * On some phones Shizuku can't start UserServices at all (see [ShellMain]); the app then falls
 * back to running [BluetoothPolicyBackend] in its own shell process instead.
 */
class BluetoothBouncerUserService() : IBluetoothBouncerUserService.Stub() {

    /** Shizuku prefers this constructor when present. The context isn't needed. */
    @Suppress("UNUSED_PARAMETER")
    constructor(context: Context) : this()

    private val backend = BluetoothPolicyBackend()

    override fun setConnectionPolicy(macAddress: String, policy: Int): IntArray =
        backend.setConnectionPolicy(macAddress, policy)

    override fun connectDevice(macAddress: String): IntArray = backend.connectDevice(macAddress)

    override fun disconnectDevice(macAddress: String): IntArray = backend.disconnectDevice(macAddress)

    override fun isAlive(): Boolean = true
}
