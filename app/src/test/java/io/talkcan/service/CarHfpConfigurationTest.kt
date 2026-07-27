package io.talkcan.service

import android.content.SharedPreferences
import io.talkcan.model.CarHfpConfigurationState
import io.talkcan.model.CarHfpInspectionStatus
import io.talkcan.model.CarHfpSelectionFailure
import io.talkcan.model.ConfiguredCarStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CarHfpConfigurationTest {
    @Test
    fun emptyPreferencesExposeNoConfiguredCar() {
        val store = SharedPreferencesCarHfpConfigurationStore(InMemorySharedPreferences())

        assertNull(store.configuredCar.value)
    }

    @Test
    fun storeCanonicalizesAndPersistsCompleteRecordAcrossRecreation() {
        val preferences = InMemorySharedPreferences()
        val store = SharedPreferencesCarHfpConfigurationStore(preferences)

        assertTrue(store.replace("  a1:b2:c3:d4:e5:f6  ", "  Family car  "))

        assertEquals(
            ConfiguredCar("A1:B2:C3:D4:E5:F6", "Family car"),
            SharedPreferencesCarHfpConfigurationStore(preferences).configuredCar.value,
        )
    }

    @Test
    fun storeReplacementChangesAddressAndLabelTogether() {
        val preferences = InMemorySharedPreferences()
        val store = SharedPreferencesCarHfpConfigurationStore(preferences)
        assertTrue(store.replace(CAR_A, "Old car"))

        assertTrue(store.replace(CAR_B, "New car"))

        assertEquals(ConfiguredCar(CAR_B, "New car"), store.configuredCar.value)
        assertEquals(
            ConfiguredCar(CAR_B, "New car"),
            SharedPreferencesCarHfpConfigurationStore(preferences).configuredCar.value,
        )
    }

    @Test
    fun failedStoreCommitPreservesPreviouslyConfiguredCarInMemoryAndOnDisk() {
        val preferences = InMemorySharedPreferences()
        val store = SharedPreferencesCarHfpConfigurationStore(preferences)
        assertTrue(store.replace(CAR_A, "Existing car"))
        preferences.commitSucceeds = false

        assertFalse(store.replace(CAR_B, "Replacement car"))

        assertEquals(ConfiguredCar(CAR_A, "Existing car"), store.configuredCar.value)
        assertEquals(
            ConfiguredCar(CAR_A, "Existing car"),
            SharedPreferencesCarHfpConfigurationStore(preferences).configuredCar.value,
        )
    }

    @Test
    fun resolverReturnsNamedOutcomesForUnconfiguredAbsentDisconnectedAndRsmConflict() {
        val configured = ConfiguredCar(CAR_A, "Car")
        val connectedCar = HfpDevice(CAR_A, "Car", connected = true)
        val disconnectedCar = HfpDevice(CAR_A, "Car", connected = false)
        val otherCar = HfpDevice(CAR_B, "Other", connected = true)

        data class Case(
            val name: String,
            val configuredCar: ConfiguredCar?,
            val devices: List<HfpDevice>,
            val targetRsmAddress: String?,
            val expected: ConfiguredCarResolution<HfpDevice>,
        )

        listOf(
            Case(
                name = "unconfigured",
                configuredCar = null,
                devices = listOf(otherCar),
                targetRsmAddress = null,
                expected = ConfiguredCarResolution.Unconfigured,
            ),
            Case(
                name = "configured car absent",
                configuredCar = configured,
                devices = listOf(otherCar),
                targetRsmAddress = null,
                expected = ConfiguredCarResolution.Absent,
            ),
            Case(
                name = "configured car disconnected",
                configuredCar = configured,
                devices = listOf(disconnectedCar),
                targetRsmAddress = null,
                expected = ConfiguredCarResolution.Disconnected,
            ),
            Case(
                name = "configured car is target RSM",
                configuredCar = configured,
                devices = listOf(connectedCar),
                targetRsmAddress = CAR_A.lowercase(),
                expected = ConfiguredCarResolution.TargetRsmConflict,
            ),
        ).forEach { case ->
            val resolution = resolveConfiguredCarHfpDevice(
                configuredCar = case.configuredCar,
                inspection = CarHfpProfileInspection.Available(case.devices),
                targetRsmAddress = case.targetRsmAddress,
                addressOf = HfpDevice::address,
                isConnected = HfpDevice::connected,
            )

            assertEquals(case.name, case.expected, resolution)
        }
    }

    @Test
    fun resolverPreservesInspectionFailuresRatherThanSelectingOrRejectingADevice() {
        val configured = ConfiguredCar(CAR_A, "Car")
        val device = HfpDevice(CAR_A, "Car", connected = true)

        listOf(
            CarHfpInspectionFailure.PermissionUnavailable,
            CarHfpInspectionFailure.ProfileUnavailable,
            CarHfpInspectionFailure.QueryFailed,
        ).forEach { reason ->
            val resolution = resolveConfiguredCarHfpDevice(
                configuredCar = configured,
                inspection = CarHfpProfileInspection.Unavailable(reason),
                targetRsmAddress = null,
                addressOf = HfpDevice::address,
                isConnected = HfpDevice::connected,
            )

            assertEquals(ConfiguredCarResolution.InspectionFailed(reason), resolution)
        }

        val addressFailure = resolveConfiguredCarHfpDevice(
            configuredCar = configured,
            inspection = CarHfpProfileInspection.Available(listOf(device)),
            targetRsmAddress = null,
            addressOf = { throw SecurityException("Bluetooth permission revoked") },
            isConnected = HfpDevice::connected,
        )
        assertEquals(
            ConfiguredCarResolution.InspectionFailed(CarHfpInspectionFailure.QueryFailed),
            addressFailure,
        )

        val invalidIdentity = resolveConfiguredCarHfpDevice(
            configuredCar = ConfiguredCar("not an address", "Car"),
            inspection = CarHfpProfileInspection.Available(listOf(device)),
            targetRsmAddress = null,
            addressOf = HfpDevice::address,
            isConnected = HfpDevice::connected,
        )
        assertEquals(
            ConfiguredCarResolution.InspectionFailed(CarHfpInspectionFailure.InvalidConfiguredIdentity),
            invalidIdentity,
        )
    }

    @Test
    fun resolverSelectsOnlyExactConfiguredIdentityAmongDuplicateNamesAndNeverFallsBack() {
        val configured = HfpDevice(CAR_B, "Shared display name", connected = true)
        val sameNamedOther = HfpDevice(CAR_A, "Shared display name", connected = true)
        val anotherSameNamedOther = HfpDevice(CAR_C, "Shared display name", connected = true)

        val exactResolution = resolveConfiguredCarHfpDevice(
            configuredCar = ConfiguredCar(CAR_B, "Shared display name"),
            inspection = CarHfpProfileInspection.Available(
                listOf(sameNamedOther, configured, anotherSameNamedOther),
            ),
            targetRsmAddress = null,
            addressOf = HfpDevice::address,
            isConnected = HfpDevice::connected,
        )
        assertSame(configured, (exactResolution as ConfiguredCarResolution.Resolved).device)

        val noFallbackResolution = resolveConfiguredCarHfpDevice(
            configuredCar = ConfiguredCar(CAR_B, "Shared display name"),
            inspection = CarHfpProfileInspection.Available(listOf(sameNamedOther, anotherSameNamedOther)),
            targetRsmAddress = null,
            addressOf = HfpDevice::address,
            isConnected = HfpDevice::connected,
        )
        assertEquals(ConfiguredCarResolution.Absent, noFallbackResolution)
    }

    @Test
    fun controllerExcludesTargetRsmAndDisconnectedDevicesAndOnlyProjectsOpaqueIds() {
        val rsm = HfpDevice(RSM, "RSM", connected = true)
        val connectedCar = HfpDevice(CAR_A, "Family car", connected = true)
        val disconnectedCar = HfpDevice(CAR_B, "Disconnected car", connected = false)
        val state = controller(
            profileDevices = { listOf(rsm, connectedCar, disconnectedCar) },
            targetRsm = { rsm },
        ).refresh()

        assertEquals(CarHfpInspectionStatus.Available, state.inspectionStatus)
        assertEquals(listOf("Family car"), state.candidates.map { it.label })
        assertNotEquals(CAR_A, state.candidates.single().selectionId)
        assertUiStateDoesNotExposeBluetoothAddresses(state)
    }

    @Test
    fun controllerRefreshFiltersCandidatesThatBecameDisconnectedAndDisambiguatesDuplicateAndMissingLabels() {
        val firstTwin = HfpDevice(CAR_A, "Twin", connected = true)
        val secondTwin = HfpDevice(CAR_B, "Twin", connected = true)
        val unnamed = HfpDevice(CAR_C, null, connected = true)
        var devices = listOf(firstTwin, secondTwin, unnamed)
        val controller = controller(profileDevices = { devices })

        val initial = controller.refresh()
        assertEquals(
            listOf("Twin · 1", "Twin · 2", "Unnamed HFP device"),
            initial.candidates.map { it.label },
        )
        assertUiStateDoesNotExposeBluetoothAddresses(initial)

        devices = listOf(firstTwin.copy(connected = false), secondTwin.copy(connected = false), unnamed)
        val refreshed = controller.refresh()
        assertEquals(listOf("Unnamed HFP device"), refreshed.candidates.map { it.label })
        assertUiStateDoesNotExposeBluetoothAddresses(refreshed)
    }

    @Test
    fun controllerRevalidatesStaleSelectionBeforePersistingIt() {
        val candidate = HfpDevice(CAR_A, "Car", connected = true)
        var deviceSnapshot = listOf(candidate)
        val store = MutableCarStore()
        val controller = controller(store = store, profileDevices = { deviceSnapshot })
        val staleSelectionId = controller.refresh().candidates.single().selectionId
        deviceSnapshot = listOf(candidate.copy(connected = false))

        val state = controller.select(staleSelectionId)

        assertEquals(CarHfpSelectionFailure.CandidateDisconnected, state.selectionFailure)
        assertNull(store.configuredCar.value)
        assertUiStateDoesNotExposeBluetoothAddresses(state)
    }

    @Test
    fun controllerSuccessfulSelectionProjectsNewConfigurationAfterPersistence() {
        val candidate = HfpDevice(CAR_A, "Car", connected = true)
        val store = MutableCarStore()
        val controller = controller(store = store, profileDevices = { listOf(candidate) })
        val selectionId = controller.refresh().candidates.single().selectionId

        val state = controller.select(selectionId)

        assertEquals(ConfiguredCar(CAR_A, "Car"), store.configuredCar.value)
        assertEquals(ConfiguredCarStatus.Connected, state.configuredCar?.status)
        assertTrue(state.candidates.single().selected)
        assertUiStateDoesNotExposeBluetoothAddresses(state)
    }

    @Test
    fun controllerFailedMutationKeepsExistingConfigurationAndSurfacesFailure() {
        val existing = ConfiguredCar(CAR_A, "Existing car")
        val replacement = HfpDevice(CAR_B, "Replacement car", connected = true)
        val store = MutableCarStore(initial = existing, replacementsSucceed = false)
        val controller = controller(store = store, profileDevices = { listOf(replacement) })
        val selectionId = controller.refresh().candidates.single().selectionId

        val state = controller.select(selectionId)

        assertEquals(CarHfpSelectionFailure.PersistenceFailed, state.selectionFailure)
        assertEquals(existing, store.configuredCar.value)
        assertEquals("Existing car", state.configuredCar?.label)
        assertUiStateDoesNotExposeBluetoothAddresses(state)
    }

    @Test
    fun controllerPreservesConfigurationDuringTransientInspectionLossAndRecovers() {
        val configuredDevice = HfpDevice(CAR_A, "Saved car", connected = true)
        val store = MutableCarStore(ConfiguredCar(CAR_A, "Saved car"))
        var permissionGranted = true
        var devices: List<HfpDevice>? = listOf(configuredDevice)
        val controller = controller(
            store = store,
            hasBluetoothConnect = { permissionGranted },
            profileDevices = { devices },
        )

        assertEquals(ConfiguredCarStatus.Connected, controller.refresh().configuredCar?.status)

        permissionGranted = false
        val permissionLoss = controller.refresh()
        assertEquals(CarHfpInspectionStatus.PermissionUnavailable, permissionLoss.inspectionStatus)
        assertEquals(ConfiguredCarStatus.Unavailable, permissionLoss.configuredCar?.status)
        assertEquals("Saved car", permissionLoss.configuredCar?.label)
        assertTrue(permissionLoss.candidates.isEmpty())
        assertUiStateDoesNotExposeBluetoothAddresses(permissionLoss)

        permissionGranted = true
        devices = null
        val profileLoss = controller.refresh()
        assertEquals(CarHfpInspectionStatus.ProfileUnavailable, profileLoss.inspectionStatus)
        assertEquals("Saved car", profileLoss.configuredCar?.label)
        assertUiStateDoesNotExposeBluetoothAddresses(profileLoss)

        devices = listOf(configuredDevice)
        val recovered = controller.refresh()
        assertEquals(CarHfpInspectionStatus.Available, recovered.inspectionStatus)
        assertEquals(ConfiguredCarStatus.Connected, recovered.configuredCar?.status)
        assertUiStateDoesNotExposeBluetoothAddresses(recovered)
    }

    private fun controller(
        store: CarHfpConfigurationStore = MutableCarStore(),
        hasBluetoothConnect: () -> Boolean = { true },
        profileDevices: () -> List<HfpDevice>? = { emptyList() },
        targetRsm: () -> HfpDevice? = { null },
    ): CarHfpConfigurationController<HfpDevice> = CarHfpConfigurationController(
        store = store,
        hasBluetoothConnect = hasBluetoothConnect,
        profileDevicesProvider = profileDevices,
        targetRsmProvider = targetRsm,
        addressOf = HfpDevice::address,
        displayNameOf = HfpDevice::name,
        isConnected = HfpDevice::connected,
    )

    private fun assertUiStateDoesNotExposeBluetoothAddresses(state: CarHfpConfigurationState) {
        val stringFields = buildList {
            state.configuredCar?.label?.let(::add)
            state.candidates.forEach { candidate ->
                add(candidate.selectionId)
                add(candidate.label)
            }
        }
        assertFalse(
            "UI state leaked a colon-delimited Bluetooth address: $stringFields",
            stringFields.any { BLUETOOTH_ADDRESS.containsMatchIn(it) },
        )
    }

    private data class HfpDevice(
        val address: String,
        val name: String?,
        val connected: Boolean,
    )

    private class MutableCarStore(
        initial: ConfiguredCar? = null,
        private val replacementsSucceed: Boolean = true,
    ) : CarHfpConfigurationStore {
        private val configured = MutableStateFlow(initial)

        override val configuredCar: StateFlow<ConfiguredCar?> = configured

        override fun replace(address: String, displayLabel: String?): Boolean {
            if (!replacementsSucceed) return false
            configured.value = ConfiguredCar(address, displayLabel)
            return true
        }
    }

    private class InMemorySharedPreferences : SharedPreferences {
        private val values = mutableMapOf<String, Any?>()
        var commitSucceeds = true

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private val removals = mutableSetOf<String>()
            private var clearRequested = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply { pending[key!!] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = apply { pending[key!!] = values }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply { pending[key!!] = value }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply { pending[key!!] = value }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply { pending[key!!] = value }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply { pending[key!!] = value }
            override fun remove(key: String?): SharedPreferences.Editor = apply { removals += key!! }
            override fun clear(): SharedPreferences.Editor = apply { clearRequested = true }

            override fun commit(): Boolean {
                if (!commitSucceeds) return false
                apply()
                return true
            }

            override fun apply() {
                if (clearRequested) values.clear()
                removals.forEach(values::remove)
                values.putAll(pending)
            }
        }
    }

    private companion object {
        const val CAR_A = "A1:B2:C3:D4:E5:F6"
        const val CAR_B = "B1:C2:D3:E4:F5:A6"
        const val CAR_C = "C1:D2:E3:F4:A5:B6"
        const val RSM = "D1:E2:F3:A4:B5:C6"
        val BLUETOOTH_ADDRESS = Regex("(?:[0-9A-F]{2}:){5}[0-9A-F]{2}")
    }
}
