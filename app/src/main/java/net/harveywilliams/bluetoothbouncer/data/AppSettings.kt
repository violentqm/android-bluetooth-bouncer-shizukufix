package net.harveywilliams.bluetoothbouncer.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Small persisted settings store backed by [android.content.SharedPreferences].
 *
 * Holds the "auto-block new devices" preference and the set of devices the auto-blocker has
 * already seen (its baseline), so a device is only ever auto-blocked once.
 */
class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _autoBlockNewDevices = MutableStateFlow(prefs.getBoolean(KEY_AUTO_BLOCK, false))

    /** Whether newly paired devices should be blocked automatically. */
    val autoBlockNewDevices: StateFlow<Boolean> = _autoBlockNewDevices.asStateFlow()

    fun setAutoBlockNewDevices(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_BLOCK, enabled).apply()
        _autoBlockNewDevices.value = enabled
    }

    /**
     * MACs the auto-blocker has already decided about (blocked, or left alone because they were
     * paired/connected before the feature was turned on). Never auto-blocked again.
     */
    @Synchronized
    fun knownDevices(): Set<String> =
        // Copy: SharedPreferences forbids mutating the returned set.
        HashSet(prefs.getStringSet(KEY_KNOWN, emptySet()).orEmpty())

    /** Adds [macAddresses] to the known set. */
    @Synchronized
    fun markKnown(macAddresses: Collection<String>) {
        if (macAddresses.isEmpty()) return
        val updated = knownDevices() + macAddresses
        prefs.edit().putStringSet(KEY_KNOWN, updated).apply()
    }

    /** Replaces the known set with exactly [macAddresses] (the baseline when enabling). */
    @Synchronized
    fun seedKnown(macAddresses: Collection<String>) {
        prefs.edit().putStringSet(KEY_KNOWN, HashSet(macAddresses)).apply()
    }

    companion object {
        private const val PREFS_NAME = "app_settings"
        private const val KEY_AUTO_BLOCK = "auto_block_new_devices"
        private const val KEY_KNOWN = "auto_block_known_devices"
    }
}
