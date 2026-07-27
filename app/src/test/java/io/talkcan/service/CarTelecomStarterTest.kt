package io.talkcan.service

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.telecom.TelecomManager
import io.talkcan.audio.ScoAudioController
import io.talkcan.telecom.TalkcanPhoneAccountRegistrar
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CarTelecomStarterTest {
    @Test
    fun successfulHfpPrimeStopsExactCarAfterConnectAndWaitsForDisconnect() = runTest {
        val car = TestDevice("car")
        val events = mutableListOf<String>()
        val startedDevices = mutableListOf<TestDevice>()
        val observedDevices = mutableListOf<TestDevice>()
        val stoppedDevices = mutableListOf<TestDevice>()
        var audioConnected = false

        val priming = async {
            primeHfpDeviceForTelecom(
                device = car,
                startVoiceRecognition = { device ->
                    startedDevices += device
                    events += "start"
                    true
                },
                isAudioConnected = { device ->
                    observedDevices += device
                    when {
                        audioConnected && "connected" !in events -> events += "connected"
                        !audioConnected && "stop" in events && "disconnected" !in events -> events += "disconnected"
                    }
                    audioConnected
                },
                stopVoiceRecognition = { device ->
                    stoppedDevices += device
                    events += "stop"
                    true
                },
                timeoutMs = TIMEOUT_MS,
                pollMs = POLL_MS,
            )
        }

        runCurrent()
        assertEquals(listOf("start"), events)
        assertFalse(priming.isCompleted)

        audioConnected = true
        advanceTimeBy(POLL_MS)
        runCurrent()
        assertEquals(listOf("start", "connected", "stop"), events)
        assertFalse("must retain the handoff until HFP audio disconnects", priming.isCompleted)

        audioConnected = false
        advanceTimeBy(POLL_MS)
        runCurrent()

        assertTrue(priming.await())
        assertEquals(listOf("start", "connected", "stop", "disconnected"), events)
        assertEquals(1, startedDevices.size)
        assertSame(car, startedDevices.single())
        assertTrue(observedDevices.all { it === car })
        assertEquals(1, stoppedDevices.size)
        assertSame(car, stoppedDevices.single())
    }

    @Test
    fun disconnectTimeoutFailsAfterStoppingExactConnectedCar() = runTest {
        val car = TestDevice("car")
        val events = mutableListOf<String>()
        val stoppedDevices = mutableListOf<TestDevice>()
        var audioConnected = false

        val priming = async {
            primeHfpDeviceForTelecom(
                device = car,
                startVoiceRecognition = {
                    events += "start"
                    true
                },
                isAudioConnected = {
                    if (audioConnected && "connected" !in events) events += "connected"
                    audioConnected
                },
                stopVoiceRecognition = { device ->
                    stoppedDevices += device
                    events += "stop"
                    true
                },
                timeoutMs = TIMEOUT_MS,
                pollMs = POLL_MS,
            )
        }

        runCurrent()
        audioConnected = true
        advanceTimeBy(POLL_MS)
        runCurrent()
        assertEquals(listOf("start", "connected", "stop"), events)
        assertFalse(priming.isCompleted)

        advanceTimeBy(TIMEOUT_MS)
        runCurrent()

        assertFalse(priming.await())
        assertEquals(listOf("start", "connected", "stop"), events)
        assertEquals(1, stoppedDevices.size)
        assertSame(car, stoppedDevices.single())
    }

    @Test
    fun timedOutHfpPrimeStopsTheExactStartedDevice() = runTest {
        val car = TestDevice("car")
        val startedDevices = mutableListOf<TestDevice>()
        val stoppedDevices = mutableListOf<TestDevice>()

        val priming = async {
            primeHfpDeviceForTelecom(
                device = car,
                startVoiceRecognition = { device ->
                    startedDevices += device
                    true
                },
                isAudioConnected = { false },
                stopVoiceRecognition = { device ->
                    stoppedDevices += device
                    true
                },
                timeoutMs = TIMEOUT_MS,
                pollMs = POLL_MS,
            )
        }

        runCurrent()
        assertFalse(priming.isCompleted)
        assertTrue(stoppedDevices.isEmpty())

        advanceTimeBy(TIMEOUT_MS)
        runCurrent()

        assertFalse(priming.await())
        assertEquals(1, startedDevices.size)
        assertSame(car, startedDevices.single())
        assertEquals(1, stoppedDevices.size)
        assertSame(car, stoppedDevices.single())
    }

    @Test
    fun cancelledHfpPrimeStopsTheExactStartedDevice() = runTest {
        val car = TestDevice("car")
        val startedDevices = mutableListOf<TestDevice>()
        val stoppedDevices = mutableListOf<TestDevice>()

        val priming = async {
            primeHfpDeviceForTelecom(
                device = car,
                startVoiceRecognition = { device ->
                    startedDevices += device
                    true
                },
                isAudioConnected = { false },
                stopVoiceRecognition = { device ->
                    stoppedDevices += device
                    true
                },
                timeoutMs = TIMEOUT_MS,
                pollMs = POLL_MS,
            )
        }

        runCurrent()
        priming.cancelAndJoin()

        assertTrue(priming.isCancelled)
        assertEquals(1, startedDevices.size)
        assertSame(car, startedDevices.single())
        assertEquals(1, stoppedDevices.size)
        assertSame(car, stoppedDevices.single())
    }

    @Test
    fun unconfiguredCarStopsBeforeCaptureRouteAndTelecomEffects() = runTest {
        val downstreamEffects = mutableListOf<String>()
        val hardwareInspections = mutableListOf<String>()
        var captureReservations = 0
        val sco = mockk<ScoAudioController>(relaxed = true)
        val telecomRegistrar = mockk<TalkcanPhoneAccountRegistrar>(relaxed = true)
        val context = mockk<Context>(relaxed = true)

        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 1_000L
        try {
            val starter = CarTelecomStarter(
                context = context,
                serviceScope = this,
                sco = sco,
                audioManager = mockk<AudioManager>(relaxed = true),
                headsetProxyProvider = {
                    hardwareInspections += "headset profile"
                    error("unconfigured car must not inspect the headset profile")
                },
                targetRsm = {
                    hardwareInspections += "target RSM"
                    error("unconfigured car must not inspect the target RSM")
                },
                inputModeController = InputModeController(),
                carConfigurationStore = FakeCarHfpConfigurationStore(null),
                telecomRegistrar = telecomRegistrar,
                resolvePttAudioRoute = {
                    downstreamEffects += "error route resolution"
                    error("unconfigured car must not resolve an error route")
                },
                publishInputMode = { downstreamEffects += "input mode publication" },
                isActivePttSession = { false },
                decidePttDispatch = {
                    downstreamEffects += "PTT dispatch"
                    PttDispatchDecision.Dispatch("car-ptt")
                },
                reserveCaptureAdmission = {
                    captureReservations += 1
                    downstreamEffects += "capture reservation"
                    HostCaptureAdmission.Busy
                },
                abandonCaptureAdmission = {
                    downstreamEffects += "capture abandonment"
                    true
                },
                reservePendingCarPtt = { _, _ ->
                    downstreamEffects += "pending car PTT reservation"
                    true
                },
                cancelPendingCarPtt = { downstreamEffects += "pending car PTT cancellation" },
                logAudioRouteSnapshot = {},
                updateCarMediaState = { downstreamEffects += "car media update" },
            )

            starter.startTelecomCarPtt()
            advanceUntilIdle()

            assertEquals("unconfigured car must not reserve capture", 0, captureReservations)
            assertTrue(
                "unconfigured car must not trigger downstream lifecycle callbacks: $downstreamEffects",
                downstreamEffects.isEmpty(),
            )
            assertTrue(
                "unconfigured car must not inspect Bluetooth hardware: $hardwareInspections",
                hardwareInspections.isEmpty(),
            )
            coVerify(exactly = 0) { sco.releaseImmediately(any()) }
            verify(exactly = 0) { telecomRegistrar.register() }
            verify(exactly = 0) { context.getSystemService(TelecomManager::class.java) }
        } finally {
            unmockkStatic(SystemClock::class)
        }
    }

    @Test
    fun configuredResolverSelectsOnlyExactConfiguredEndpointAmongMultipleConnectedDevices() {
        val configuredCar = TestDevice(CONFIGURED_CAR_ADDRESS)
        val targetRsm = TestDevice(TARGET_RSM_ADDRESS)
        val otherEndpoint = TestDevice(OTHER_ENDPOINT_ADDRESS)

        val resolution = resolveConfiguredCarHfpDevice(
            configuredCar = ConfiguredCar(CONFIGURED_CAR_ADDRESS, "Configured car"),
            inspection = CarHfpProfileInspection.Available(
                listOf(otherEndpoint, targetRsm, configuredCar),
            ),
            targetRsmAddress = TARGET_RSM_ADDRESS,
            addressOf = TestDevice::address,
            isConnected = { true },
        )

        assertSame(configuredCar, resolvedDevice(resolution))
    }

    @Test
    fun configuredResolverNeverFallsBackWhenTheConfiguredEndpointCannotBeUsed() {
        val configuredCar = TestDevice(CONFIGURED_CAR_ADDRESS)
        val fallbackEndpoint = TestDevice(OTHER_ENDPOINT_ADDRESS)
        val cases = listOf(
            ResolutionCase(
                name = "no configured car",
                configuredCar = null,
                inspection = CarHfpProfileInspection.Available(listOf(fallbackEndpoint)),
                expected = ConfiguredCarResolution.Unconfigured,
            ),
            ResolutionCase(
                name = "configured car absent from profile",
                configuredCar = ConfiguredCar(CONFIGURED_CAR_ADDRESS, "Configured car"),
                inspection = CarHfpProfileInspection.Available(listOf(fallbackEndpoint)),
                expected = ConfiguredCarResolution.Absent,
            ),
            ResolutionCase(
                name = "configured car disconnected",
                configuredCar = ConfiguredCar(CONFIGURED_CAR_ADDRESS, "Configured car"),
                inspection = CarHfpProfileInspection.Available(listOf(configuredCar, fallbackEndpoint)),
                isConnected = { device -> device === fallbackEndpoint },
                expected = ConfiguredCarResolution.Disconnected,
            ),
            ResolutionCase(
                name = "configured car conflicts with target RSM",
                configuredCar = ConfiguredCar(CONFIGURED_CAR_ADDRESS, "Configured car"),
                inspection = CarHfpProfileInspection.Available(listOf(configuredCar, fallbackEndpoint)),
                targetRsmAddress = CONFIGURED_CAR_ADDRESS,
                expected = ConfiguredCarResolution.TargetRsmConflict,
            ),
            ResolutionCase(
                name = "bluetooth permission inspection unavailable",
                configuredCar = ConfiguredCar(CONFIGURED_CAR_ADDRESS, "Configured car"),
                inspection = CarHfpProfileInspection.Unavailable(
                    CarHfpInspectionFailure.PermissionUnavailable,
                ),
                expected = ConfiguredCarResolution.InspectionFailed(
                    CarHfpInspectionFailure.PermissionUnavailable,
                ),
            ),
            ResolutionCase(
                name = "headset profile inspection unavailable",
                configuredCar = ConfiguredCar(CONFIGURED_CAR_ADDRESS, "Configured car"),
                inspection = CarHfpProfileInspection.Unavailable(
                    CarHfpInspectionFailure.ProfileUnavailable,
                ),
                expected = ConfiguredCarResolution.InspectionFailed(
                    CarHfpInspectionFailure.ProfileUnavailable,
                ),
            ),
            ResolutionCase(
                name = "profile query inspection unavailable",
                configuredCar = ConfiguredCar(CONFIGURED_CAR_ADDRESS, "Configured car"),
                inspection = CarHfpProfileInspection.Unavailable(CarHfpInspectionFailure.QueryFailed),
                expected = ConfiguredCarResolution.InspectionFailed(CarHfpInspectionFailure.QueryFailed),
            ),
            ResolutionCase(
                name = "configured identity invalid",
                configuredCar = ConfiguredCar("not-a-device-identity", "Configured car"),
                inspection = CarHfpProfileInspection.Available(listOf(fallbackEndpoint)),
                expected = ConfiguredCarResolution.InspectionFailed(
                    CarHfpInspectionFailure.InvalidConfiguredIdentity,
                ),
            ),
            ResolutionCase(
                name = "configured device connection inspection fails",
                configuredCar = ConfiguredCar(CONFIGURED_CAR_ADDRESS, "Configured car"),
                inspection = CarHfpProfileInspection.Available(listOf(configuredCar, fallbackEndpoint)),
                isConnected = { throw SecurityException("connection state unavailable") },
                expected = ConfiguredCarResolution.InspectionFailed(CarHfpInspectionFailure.QueryFailed),
            ),
        )

        cases.forEach { case ->
            val resolution = resolveConfiguredCarHfpDevice(
                configuredCar = case.configuredCar,
                inspection = case.inspection,
                targetRsmAddress = case.targetRsmAddress,
                addressOf = TestDevice::address,
                isConnected = case.isConnected,
            )

            assertEquals(case.name, case.expected, resolution)
            assertFalse("${case.name} must not select another endpoint", resolution is ConfiguredCarResolution.Resolved)
        }
    }

    @Test
    fun resolvedEndpointSnapshotSurvivesStoreReplacementUntilTheNextResolution() {
        val firstCar = TestDevice(CONFIGURED_CAR_ADDRESS)
        val replacementCar = TestDevice(OTHER_ENDPOINT_ADDRESS)
        val store = FakeCarHfpConfigurationStore(ConfiguredCar(CONFIGURED_CAR_ADDRESS, "First car"))
        val inspection = CarHfpProfileInspection.Available(listOf(firstCar, replacementCar))

        val firstResolution = resolveConfiguredCarHfpDevice(
            configuredCar = store.configuredCar.value,
            inspection = inspection,
            targetRsmAddress = null,
            addressOf = TestDevice::address,
            isConnected = { true },
        )
        assertTrue(store.replace(OTHER_ENDPOINT_ADDRESS, "Replacement car"))
        val laterResolution = resolveConfiguredCarHfpDevice(
            configuredCar = store.configuredCar.value,
            inspection = inspection,
            targetRsmAddress = null,
            addressOf = TestDevice::address,
            isConnected = { true },
        )

        assertSame(firstCar, resolvedDevice(firstResolution))
        assertSame(replacementCar, resolvedDevice(laterResolution))
    }

    private fun resolvedDevice(
        resolution: ConfiguredCarResolution<TestDevice>,
    ): TestDevice {
        assertTrue("expected configured endpoint to resolve", resolution is ConfiguredCarResolution.Resolved)
        return (resolution as ConfiguredCarResolution.Resolved).device
    }

    private companion object {
        const val TIMEOUT_MS = 100L
        const val POLL_MS = 10L
        const val CONFIGURED_CAR_ADDRESS = "AA:BB:CC:DD:EE:01"
        const val OTHER_ENDPOINT_ADDRESS = "AA:BB:CC:DD:EE:02"
        const val TARGET_RSM_ADDRESS = "AA:BB:CC:DD:EE:03"
    }

    private data class ResolutionCase(
        val name: String,
        val configuredCar: ConfiguredCar?,
        val inspection: CarHfpProfileInspection<TestDevice>,
        val targetRsmAddress: String? = null,
        val isConnected: (TestDevice) -> Boolean = { true },
        val expected: ConfiguredCarResolution<TestDevice>,
    )

    private class FakeCarHfpConfigurationStore(initial: ConfiguredCar?) : CarHfpConfigurationStore {
        private val state = MutableStateFlow(initial)

        override val configuredCar: StateFlow<ConfiguredCar?> = state.asStateFlow()

        override fun replace(address: String, displayLabel: String?): Boolean {
            val canonicalAddress = canonicalBluetoothAddress(address) ?: return false
            state.value = ConfiguredCar(
                canonicalAddress = canonicalAddress,
                displayLabel = displayLabel?.trim()?.takeIf(String::isNotEmpty),
            )
            return true
        }
    }

    private class TestDevice(val address: String) {
        override fun equals(other: Any?): Boolean =
            other is TestDevice && address == other.address

        override fun hashCode(): Int = address.hashCode()
    }
}
