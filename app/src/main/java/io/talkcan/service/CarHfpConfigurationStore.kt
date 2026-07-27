package io.talkcan.service

import android.content.Context
import android.content.SharedPreferences
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ConfiguredCar(
    val canonicalAddress: String,
    val displayLabel: String?,
)

interface CarHfpConfigurationStore {
    val configuredCar: StateFlow<ConfiguredCar?>

    /** Atomically replaces the complete record. Invalid identities and failed commits leave it unchanged. */
    fun replace(address: String, displayLabel: String?): Boolean
}

class SharedPreferencesCarHfpConfigurationStore(
    private val preferences: SharedPreferences,
) : CarHfpConfigurationStore {
    constructor(context: Context) : this(
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
    )

    private val mutationLock = Any()
    private val _configuredCar = MutableStateFlow(load())

    override val configuredCar: StateFlow<ConfiguredCar?>
        get() = _configuredCar.asStateFlow()

    override fun replace(address: String, displayLabel: String?): Boolean {
        val canonicalAddress = canonicalBluetoothAddress(address) ?: return false
        val replacement = ConfiguredCar(canonicalAddress, displayLabel?.trim()?.takeIf(String::isNotEmpty))
        synchronized(mutationLock) {
            val committed = preferences.edit()
                .putString(KEY_ADDRESS, replacement.canonicalAddress)
                .putString(KEY_LABEL, replacement.displayLabel)
                .commit()
            if (committed) _configuredCar.value = replacement
            return committed
        }
    }

    private fun load(): ConfiguredCar? {
        val canonicalAddress = preferences.getString(KEY_ADDRESS, null)
            ?.let(::canonicalBluetoothAddress)
            ?: return null
        return ConfiguredCar(
            canonicalAddress = canonicalAddress,
            displayLabel = preferences.getString(KEY_LABEL, null)?.trim()?.takeIf(String::isNotEmpty),
        )
    }

    private companion object {
        private const val PREFERENCES_NAME = "car_hfp_configuration"
        private const val KEY_ADDRESS = "canonical_address"
        private const val KEY_LABEL = "display_label"
    }
}

internal fun canonicalBluetoothAddress(address: String): String? {
    val canonical = address.trim().uppercase(Locale.US)
    return canonical.takeIf { BLUETOOTH_ADDRESS.matches(it) }
}

private val BLUETOOTH_ADDRESS = Regex("(?:[0-9A-F]{2}:){5}[0-9A-F]{2}")
