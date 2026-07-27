package io.talkcan.audio

import android.bluetooth.BluetoothDevice
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import io.talkcan.model.InputMode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test


/**
 * Behavior tests for the [PlaybackRouteStrategy] / [AcquiredPlaybackRoute] abstraction,
 * covering acquisition outcomes (acquired, busy, unavailable, failed), route release
 * stability (exactly-once), and start/release lifecycle without any Android audio object.
 *
 * [ModePlaybackRouteResolver] itself requires [android.media.AudioManager] and
 * [ScoAudioController] and is therefore not instantiated here. These tests verify
 * the generic contracts that the resolver and [HostAudioCoordinator] depend on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackRouteStrategyTest {
    @After
    fun tearDownMocks() {
        unmockkAll()
    }

    @Test
    fun workRequestsOwnedRsmScoWithVoiceCommunicationUsage() = runTest {
        val audioManager = mockk<AudioManager>()
        val sco = mockk<ScoAudioController>()
        val owner = mockk<BluetoothDevice>()
        val selected = mockk<AudioDeviceInfo>()
        val route = FakeAcquiredRoute()
        val requests = mutableListOf<PlaybackRouteRequest>()
        every { owner.name } returns "RSM-Target"
        every { selected.type } returns AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        every { selected.productName } returns "RSM-Target Handsfree"
        coEvery { sco.acquire() } returns true
        every { sco.selectedCommunicationDevice() } returns selected
        every { sco.release() } just runs

        val resolver = ModePlaybackRouteResolver(
            audioManager = audioManager,
            workSco = sco,
            targetRsmDevice = { owner },
            routeFactory = { request -> requests += request; route },
        )

        val acquisition = resolver.strategyFor(InputMode.Work).acquire()

        assertTrue(acquisition is PlaybackRouteAcquisition.Acquired)
        assertEquals(1, requests.size)
        val request = requests.single()
        assertEquals(InputMode.Work, request.mode)
        assertEquals(AudioRouteEndpoint.Rsm, request.endpoint)
        assertSame(selected, request.preferredDevice)
        assertSame(owner, request.owner)
        assertEquals(AudioAttributes.USAGE_VOICE_COMMUNICATION, request.usage)
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, request.audioMode)

        (acquisition as PlaybackRouteAcquisition.Acquired).route.release()
        verify(exactly = 1) { sco.release() }
    }

    @Test
    fun anonymousScoIsAcceptedWithExplicitBluetoothOwnershipProof() = runTest {
        val audioManager = mockk<AudioManager>()
        val sco = mockk<ScoAudioController>()
        val owner = mockk<BluetoothDevice>()
        val selected = mockk<AudioDeviceInfo>()
        val route = FakeAcquiredRoute()
        val requests = mutableListOf<PlaybackRouteRequest>()
        every { owner.name } returns ""
        every { owner.address } returns ""
        every { selected.type } returns AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        every { selected.productName } returns null
        every { selected.address } returns ""
        coEvery { sco.acquire() } returns true
        every { sco.selectedCommunicationDevice() } returns selected
        every { sco.release() } just runs

        val resolver = ModePlaybackRouteResolver(
            audioManager = audioManager,
            workSco = sco,
            targetRsmDevice = { owner },
            targetRsmOwnershipProof = { it === owner },
            routeFactory = { request -> requests += request; route },
        )
        val acquisition = resolver.strategyFor(InputMode.Work).acquire()

        assertTrue(acquisition is PlaybackRouteAcquisition.Acquired)
        assertSame(owner, requests.single().owner)
        assertSame(owner, requests.single().preferredBluetoothDevice)
        (acquisition as PlaybackRouteAcquisition.Acquired).route.release()
        verify(exactly = 1) { sco.release() }
    }

    @Test
    fun missingBluetoothOwnershipProofRejectsAndReleasesSco() = runTest {
        val audioManager = mockk<AudioManager>()
        val sco = mockk<ScoAudioController>()
        val owner = mockk<BluetoothDevice>()
        val resolver = ModePlaybackRouteResolver(
            audioManager = audioManager,
            workSco = sco,
            targetRsmDevice = { owner },
            targetRsmOwnershipProof = { false },
        )
        coEvery { sco.acquire() } returns true
        every { sco.release() } just runs
        val acquisition = resolver.strategyFor(InputMode.Work).acquire()

        assertEquals(
            PlaybackRouteAcquisition.Unavailable("Target RSM Bluetooth ownership unavailable"),
            acquisition,
        )
        coVerify(exactly = 1) { sco.acquire() }
        verify(exactly = 1) { sco.release() }
    }

    @Test
    fun missingBluetoothDeviceRejectsBeforeScoAcquisition() = runTest {
        val audioManager = mockk<AudioManager>()
        val sco = mockk<ScoAudioController>()
        val resolver = ModePlaybackRouteResolver(audioManager, sco, targetRsmDevice = { null })

        val acquisition = resolver.strategyFor(InputMode.Work).acquire()

        assertEquals(
            PlaybackRouteAcquisition.Unavailable("Target RSM Bluetooth device unavailable"),
            acquisition,
        )
        coVerify(exactly = 0) { sco.acquire() }
    }

    @Test
    fun missingScoTransportRejectsAndReleasesOwnership() = runTest {
        val audioManager = mockk<AudioManager>()
        val sco = mockk<ScoAudioController>()
        val owner = mockk<BluetoothDevice>()
        coEvery { sco.acquire() } returns true
        every { sco.selectedCommunicationDevice() } returns null
        every { sco.release() } just runs

        val acquisition = ModePlaybackRouteResolver(audioManager, sco, targetRsmDevice = { owner })
            .strategyFor(InputMode.Work).acquire()

        assertEquals(
            PlaybackRouteAcquisition.Unavailable("Target RSM SCO transport unavailable"),
            acquisition,
        )
        coVerify(exactly = 1) { sco.acquire() }
        verify(exactly = 1) { sco.release() }
    }

    @Test
    fun subsystemProvenScoTransportIsAcceptedDespiteForeignEndpointMetadata() = runTest {
        val audioManager = mockk<AudioManager>()
        val sco = mockk<ScoAudioController>()
        val owner = mockk<BluetoothDevice>()
        val selected = mockk<AudioDeviceInfo>()
        val route = FakeAcquiredRoute()
        // Some OEMs label the SCO communication endpoint with the phone's own
        // product name and adapter address. The target-RSM SCO subsystem's proof
        // (targeted voice recognition plus target HFP audio connection) is the
        // authoritative ownership signal; endpoint metadata is never a veto.
        every { selected.type } returns AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        every { selected.productName } returns "CPH2653"
        every { selected.address } returns "AA:BB:CC:DD:EE:99"
        coEvery { sco.acquire() } returns true
        every { sco.selectedCommunicationDevice() } returns selected
        every { sco.release() } just runs

        val acquisition = ModePlaybackRouteResolver(
            audioManager = audioManager,
            workSco = sco,
            targetRsmDevice = { owner },
            routeFactory = { route },
        ).strategyFor(InputMode.Work).acquire()

        assertTrue(acquisition is PlaybackRouteAcquisition.Acquired)
        (acquisition as PlaybackRouteAcquisition.Acquired).route.release()
        verify(exactly = 1) { sco.release() }
    }

    @Test
    fun routeConstructionFailureReleasesOwnedSco() = runTest {
        val audioManager = mockk<AudioManager>()
        val sco = mockk<ScoAudioController>()
        val owner = mockk<BluetoothDevice>()
        val selected = mockk<AudioDeviceInfo>()
        every { owner.name } returns "RSM-Target"
        every { selected.type } returns AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        every { selected.productName } returns "RSM-Target"
        coEvery { sco.acquire() } returns true
        every { sco.selectedCommunicationDevice() } returns selected
        every { sco.release() } just runs

        val acquisition = ModePlaybackRouteResolver(
            audioManager = audioManager,
            workSco = sco,
            targetRsmDevice = { owner },
            routeFactory = { error("route construction failure") },
        ).strategyFor(InputMode.Work).acquire()

        assertTrue(acquisition is PlaybackRouteAcquisition.Failed)
        verify(exactly = 1) { sco.release() }
    }

    @Test
    fun onAPinchRequestsBuiltInSpeakerMediaInNormalMode() = runTest {
        val audioManager = mockk<AudioManager>()
        val sco = mockk<ScoAudioController>()
        val speaker = mockk<AudioDeviceInfo>()
        val route = FakeAcquiredRoute()
        val requests = mutableListOf<PlaybackRouteRequest>()
        every { audioManager.mode } returns AudioManager.MODE_NORMAL
        every { audioManager.communicationDevice } returns null
        every { audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) } returns arrayOf(speaker)
        every { speaker.type } returns AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        every { sco.isActive() } returns false

        val resolver = ModePlaybackRouteResolver(
            audioManager = audioManager,
            workSco = sco,
            routeFactory = { request -> requests += request; route },
        )
        val acquisition = resolver.strategyFor(InputMode.OnAPinch).acquire()

        assertTrue(acquisition is PlaybackRouteAcquisition.Acquired)
        val request = requests.single()
        assertEquals(InputMode.OnAPinch, request.mode)
        assertEquals(AudioRouteEndpoint.Local, request.endpoint)
        assertSame(speaker, request.preferredDevice)
        assertEquals(AudioAttributes.USAGE_MEDIA, request.usage)
        assertEquals(AudioManager.MODE_NORMAL, request.audioMode)
        coVerify(exactly = 0) { sco.acquire() }
    }

    @Test
    fun onAPinchWithoutSpeakerFailsWithoutAmbientOutput() = runTest {
        val audioManager = mockk<AudioManager>()
        val sco = mockk<ScoAudioController>()
        every { audioManager.mode } returns AudioManager.MODE_NORMAL
        every { audioManager.communicationDevice } returns null
        every { sco.isActive() } returns false
        every { audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) } returns emptyArray()

        val acquisition = ModePlaybackRouteResolver(audioManager, sco)
            .strategyFor(InputMode.OnAPinch).acquire()

        assertEquals(
            PlaybackRouteAcquisition.Unavailable("Phone speaker output unavailable"),
            acquisition,
        )
    }

    @Test
    fun onAPinchSpeakerAcquisitionIsNotBlockedByOwnedWorkScoState() = runTest {
        val audioManager = mockk<AudioManager>()
        val sco = mockk<ScoAudioController>()
        val speaker = mockk<AudioDeviceInfo>()
        val scoEndpoint = mockk<AudioDeviceInfo>()
        val route = FakeAcquiredRoute()
        val requests = mutableListOf<PlaybackRouteRequest>()
        // The app's own Work SCO lease or keep-warm residue holds communication
        // routing; phone speaker acquisition must not treat it as a foreign busy
        // route, or delayed entries recorded in Work can never drain after a
        // mode switch to On-a-pinch.
        every { sco.isActive() } returns true
        every { audioManager.mode } returns AudioManager.MODE_IN_COMMUNICATION
        every { audioManager.communicationDevice } returns scoEndpoint
        every { scoEndpoint.type } returns AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        every { audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) } returns arrayOf(speaker)
        every { speaker.type } returns AudioDeviceInfo.TYPE_BUILTIN_SPEAKER

        val resolver = ModePlaybackRouteResolver(
            audioManager = audioManager,
            workSco = sco,
            routeFactory = { request -> requests += request; route },
        )
        val acquisition = resolver.strategyFor(InputMode.OnAPinch).acquire()

        assertTrue(acquisition is PlaybackRouteAcquisition.Acquired)
        val request = requests.single()
        assertEquals(AudioRouteEndpoint.Local, request.endpoint)
        assertSame(speaker, request.preferredDevice)
        assertEquals(AudioAttributes.USAGE_MEDIA, request.usage)
    }

    @Test
    fun onAPinchBusyUnderForeignCommunicationRoute() = runTest {
        val audioManager = mockk<AudioManager>()
        val sco = mockk<ScoAudioController>()
        val scoEndpoint = mockk<AudioDeviceInfo>()
        every { sco.isActive() } returns false
        every { audioManager.mode } returns AudioManager.MODE_IN_COMMUNICATION
        every { audioManager.communicationDevice } returns scoEndpoint
        every { scoEndpoint.type } returns AudioDeviceInfo.TYPE_BLUETOOTH_SCO

        val acquisition = ModePlaybackRouteResolver(audioManager, sco)
            .strategyFor(InputMode.OnAPinch).acquire()

        assertEquals(PlaybackRouteAcquisition.Busy, acquisition)
    }

    @Test
    fun onTheRoadWaitsForTelecomCaptureReleaseBeforeInspectingAudioRoute() = runTest {
        val audioManager = mockk<AudioManager>()
        val sco = mockk<ScoAudioController>()
        val gate = CompletableDeferred<Boolean>()
        val events = mutableListOf<String>()
        every { audioManager.mode } answers { events += "mode"; AudioManager.MODE_NORMAL }
        every { audioManager.communicationDevice } answers { events += "communication"; null }
        every { sco.isActive() } returns false
        val resolver = ModePlaybackRouteResolver(
            audioManager = audioManager,
            workSco = sco,
            awaitTelecomCaptureRelease = { gate.await() },
            carMediaDevice = { events += "car"; null },
        )

        val pending = async { resolver.strategyFor(InputMode.OnTheRoad).acquire() }
        runCurrent()
        assertTrue(events.isEmpty())
        gate.complete(false)
        assertEquals(
            PlaybackRouteAcquisition.Unavailable("Telecom capture route unavailable"),
            pending.await(),
        )
        assertTrue(events.isEmpty())
    }

    @Test
    fun onTheRoadRequestsValidatedCarMediaOnlyAfterCaptureRelease() = runTest {
        val audioManager = mockk<AudioManager>()
        val sco = mockk<ScoAudioController>()
        val car = mockk<AudioDeviceInfo>()
        val route = FakeAcquiredRoute()
        val requests = mutableListOf<PlaybackRouteRequest>()
        every { audioManager.mode } returns AudioManager.MODE_NORMAL
        every { audioManager.communicationDevice } returns null
        every { car.type } returns AudioDeviceInfo.TYPE_USB_DEVICE
        every { sco.isActive() } returns false

        val resolver = ModePlaybackRouteResolver(
            audioManager = audioManager,
            workSco = sco,
            awaitTelecomCaptureRelease = { true },
            carMediaDevice = { car },
            routeFactory = { request -> requests += request; route },
        )
        val acquisition = resolver.strategyFor(InputMode.OnTheRoad).acquire()

        assertTrue(acquisition is PlaybackRouteAcquisition.Acquired)
        val request = requests.single()
        assertEquals(InputMode.OnTheRoad, request.mode)
        assertEquals(AudioRouteEndpoint.Car, request.endpoint)
        assertSame(car, request.preferredDevice)
        assertEquals(AudioAttributes.USAGE_MEDIA, request.usage)
        assertEquals(AudioManager.MODE_NORMAL, request.audioMode)
        coVerify(exactly = 0) { sco.acquire() }
    }

    @Test
    fun onTheRoadWithActiveScoIsBusyWithoutReusingPttRouteOrAmbientFallback() = runTest {
        val audioManager = mockk<AudioManager>()
        val sco = mockk<ScoAudioController>()
        val activeSco = mockk<AudioDeviceInfo>()
        val events = mutableListOf<String>()
        every { audioManager.mode } returns AudioManager.MODE_NORMAL
        every { audioManager.communicationDevice } returns activeSco
        every { activeSco.type } returns AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        every { sco.isActive() } returns false

        val acquisition = ModePlaybackRouteResolver(
            audioManager = audioManager,
            workSco = sco,
            awaitTelecomCaptureRelease = { true },
            carMediaDevice = { events += "car"; null },
        ).strategyFor(InputMode.OnTheRoad).acquire()

        assertEquals(PlaybackRouteAcquisition.Busy, acquisition)
        assertTrue(events.isEmpty())
        coVerify(exactly = 0) { sco.acquire() }
    }

    @Test
    fun onTheRoadWithoutEnumerableCarMediaFallsBackToUnpinnedMediaRouting() = runTest {
        val audioManager = mockk<AudioManager>()
        val sco = mockk<ScoAudioController>()
        val route = FakeAcquiredRoute()
        val requests = mutableListOf<PlaybackRouteRequest>()
        every { sco.isActive() } returns false
        every { audioManager.mode } returns AudioManager.MODE_NORMAL
        every { audioManager.communicationDevice } returns null

        val acquisition = ModePlaybackRouteResolver(
            audioManager = audioManager,
            workSco = sco,
            awaitTelecomCaptureRelease = { true },
            carMediaDevice = { null },
            carMediaFocus = mockk(relaxed = true),
            routeFactory = { request -> requests += request; route },
        ).strategyFor(InputMode.OnTheRoad).acquire()

        assertTrue(acquisition is PlaybackRouteAcquisition.Acquired)
        val request = requests.single()
        assertEquals(AudioRouteEndpoint.Car, request.endpoint)
        assertEquals(null, request.preferredDevice)
        assertEquals(AudioAttributes.USAGE_MEDIA, request.usage)
    }

    @Test
    fun onTheRoadAcceptsBluetoothA2DPCarMediaOutput() = runTest {
        val audioManager = mockk<AudioManager>()
        val sco = mockk<ScoAudioController>()
        val car = mockk<AudioDeviceInfo>()
        val route = FakeAcquiredRoute()
        val requests = mutableListOf<PlaybackRouteRequest>()
        every { audioManager.mode } returns AudioManager.MODE_NORMAL
        every { audioManager.communicationDevice } returns null
        every { car.type } returns AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        every { sco.isActive() } returns false

        val resolver = ModePlaybackRouteResolver(
            audioManager = audioManager,
            workSco = sco,
            awaitTelecomCaptureRelease = { true },
            carMediaDevice = { car },
            carMediaFocus = mockk(relaxed = true),
            routeFactory = { request -> requests += request; route },
        )
        val acquisition = resolver.strategyFor(InputMode.OnTheRoad).acquire()

        assertTrue(acquisition is PlaybackRouteAcquisition.Acquired)
        val request = requests.single()
        assertEquals(InputMode.OnTheRoad, request.mode)
        assertEquals(AudioRouteEndpoint.Car, request.endpoint)
        assertSame(car, request.preferredDevice)
        assertEquals(AudioAttributes.USAGE_MEDIA, request.usage)
        assertEquals(AudioManager.MODE_NORMAL, request.audioMode)
    }

    @Test
    fun onTheRoadReleasesStaleWorkRouteBeforeAcquiringCarMedia() = runTest {
        val audioManager = mockk<AudioManager>()
        val sco = mockk<ScoAudioController>()
        val route = FakeAcquiredRoute()
        every { sco.isActive() } returns true
        coEvery { sco.releaseImmediately(any()) } returns RouteGateResult.Success("car-playback-acquire")
        every { audioManager.mode } returns AudioManager.MODE_NORMAL
        every { audioManager.communicationDevice } returns null

        val acquisition = ModePlaybackRouteResolver(
            audioManager = audioManager,
            workSco = sco,
            awaitTelecomCaptureRelease = { true },
            carMediaDevice = { null },
            carMediaFocus = mockk(relaxed = true),
            routeFactory = { route },
        ).strategyFor(InputMode.OnTheRoad).acquire()

        assertTrue(acquisition is PlaybackRouteAcquisition.Acquired)
        coVerify(exactly = 1) { sco.releaseImmediately("car-playback-acquire") }
    }


    @Test
    fun strategyAcquiringAnAcquiredRouteReturnsThatRoute() = runTest {
        val route = FakeAcquiredRoute()
        val strategy = PlaybackRouteStrategy { PlaybackRouteAcquisition.Acquired(route) }

        val acquisition = strategy.acquire()
        assertTrue(acquisition is PlaybackRouteAcquisition.Acquired)
        assertSame(route, (acquisition as PlaybackRouteAcquisition.Acquired).route)
    }

    @Test
    fun strategyReturningBusyDoesNotCarryARoute() = runTest {
        val strategy = PlaybackRouteStrategy { PlaybackRouteAcquisition.Busy }
        val acquisition = strategy.acquire()
        assertEquals(PlaybackRouteAcquisition.Busy, acquisition)
    }

    @Test
    fun strategyReturningUnavailableCarriesAReason() = runTest {
        val strategy = PlaybackRouteStrategy { PlaybackRouteAcquisition.Unavailable("SCO transport unavailable") }
        val acquisition = strategy.acquire()
        assertTrue(acquisition is PlaybackRouteAcquisition.Unavailable)
        assertEquals("SCO transport unavailable", (acquisition as PlaybackRouteAcquisition.Unavailable).reason)
    }

    @Test
    fun strategyReturningFailedCarriesAReason() = runTest {
        val strategy = PlaybackRouteStrategy { PlaybackRouteAcquisition.Failed("audio track construction failed") }
        val acquisition = strategy.acquire()
        assertTrue(acquisition is PlaybackRouteAcquisition.Failed)
        assertEquals("audio track construction failed", (acquisition as PlaybackRouteAcquisition.Failed).reason)
    }

    @Test
    fun acquiredRouteStartReturnsAnActivePlayback() = runTest {
        val route = FakeAcquiredRoute()
        val recording = RecordedPcm(ShortArray(10) { it.toShort() }, 16_000)

        val playback = route.start(recording)
        assertEquals(AudioRouteEndpoint.Unspecified, route.endpoint)
        assertTrue(playback is FakeActivePlayback)
    }

    @Test
    fun acquiredRouteReleaseIsExactlyOnce() = runTest {
        val route = FakeAcquiredRoute()
        assertFalse(route.released)
        assertEquals(0, route.releaseCount)

        route.release()
        assertTrue(route.released)
        assertEquals(1, route.releaseCount)

        // Second release is a no-op.
        route.release()
        assertEquals(1, route.releaseCount)
    }

    @Test
    fun activePlaybackAwaitCompletionResolvesExactlyOnceWithTerminalClassification() = runTest {
        val playback = FakeActivePlayback()
        playback.complete(PlaybackCompletion.Completed)
        assertEquals(PlaybackCompletion.Completed, playback.awaitCompletion())

        // A second await returns the same result; the deferred is not re-completed.
        assertEquals(PlaybackCompletion.Completed, playback.awaitCompletion())
    }

    @Test
    fun activePlaybackRejectPttWithToneReturnsTrueForFirstCallAndFalseAfterCompletion() = runTest {
        val playback = FakeActivePlayback()
        assertTrue(playback.rejectPttWithTone())
        // The single-slot debounce prevents a second tone from being latched.
        assertFalse(playback.rejectPttWithTone())

        playback.complete(PlaybackCompletion.Completed)
        assertFalse(playback.rejectPttWithTone())
    }

    @Test
    fun activePlaybackSkipReturnsTrueBeforeCompletionAndFalseAfter() = runTest {
        val playback = FakeActivePlayback()
        assertTrue(playback.skip())
        // A second skip is a no-op.
        assertFalse(playback.skip())

        playback.complete(PlaybackCompletion.ExplicitlySkipped)
        assertFalse(playback.skip())
    }

    @Test
    fun activePlaybackSkipCancelsALatchedTone() = runTest {
        val playback = FakeActivePlayback()
        // Latch a tone.
        assertTrue(playback.rejectPttWithTone())
        // Skip wins and cancels the latched tone.
        assertTrue(playback.skip())
        // After skip, no tone can be latched (completion is pending).
        assertFalse(playback.rejectPttWithTone())
    }

    @Test
    fun strategyCompositionCanChainReleaseAfterPlaybackTermination() = runTest {
        val innerRoute = FakeAcquiredRoute()
        val releasedFlag = CompletableDeferred<Unit>()
        val releasingRoute = ReleasingPlaybackRoute(innerRoute) { releasedFlag.complete(Unit) }
        val strategy = PlaybackRouteStrategy { PlaybackRouteAcquisition.Acquired(releasingRoute) }

        val acquired = strategy.acquire() as PlaybackRouteAcquisition.Acquired
        val playback = acquired.route.start(RecordedPcm(ShortArray(1), 16_000)) as FakeActivePlayback
        playback.complete(PlaybackCompletion.Completed)
        assertEquals(PlaybackCompletion.Completed, playback.awaitCompletion())

        acquired.route.release()
        assertTrue(innerRoute.released)
        assertTrue(releasedFlag.isCompleted)
    }

    @Test
    fun mediaFocusRouteRequestsFocusBeforePlaybackAndAbandonsOnRelease() = runTest {
        val events = mutableListOf<String>()
        val inner = FakeAcquiredRoute()
        val route = MediaFocusPlaybackRoute(
            requestFocus = { events += "focus"; true },
            abandonFocus = { events += "abandon" },
            delegate = inner,
        )

        val playback = route.start(RecordedPcm(ShortArray(4), 16_000))
        assertEquals(listOf("focus"), events)
        assertTrue(playback is FakeActivePlayback)

        route.release()
        assertEquals(listOf("focus", "abandon"), events)
        assertTrue(inner.released)
    }

    @Test
    fun mediaFocusRoutePlaysThroughWhenFocusDeniedBestEffort() = runTest {
        val events = mutableListOf<String>()
        val inner = FakeAcquiredRoute()
        val route = MediaFocusPlaybackRoute(
            requestFocus = { events += "focus"; false },
            abandonFocus = { events += "abandon" },
            delegate = inner,
        )

        val playback = route.start(RecordedPcm(ShortArray(4), 16_000))
        assertEquals(listOf("focus"), events)
        assertTrue(playback is FakeActivePlayback)

        route.release()
        // Focus was denied (never held), so nothing is abandoned; the delegate still
        // played and is released — focus is best-effort, not a playback precondition.
        assertEquals(listOf("focus"), events)
        assertTrue(inner.released)
    }

    // ------------------------------------------------------------------
    // Fakes
    // ------------------------------------------------------------------


    internal class FakeAcquiredRoute : AcquiredPlaybackRoute {
        override val endpoint: AudioRouteEndpoint = AudioRouteEndpoint.Unspecified

        var released = false
            private set
        var releaseCount = 0
            private set

        override suspend fun start(recording: RecordedPcm): ActivePcmPlayback = FakeActivePlayback()

        override suspend fun release() {
            if (released) return
            released = true
            releaseCount += 1
        }
    }

    internal class FakeActivePlayback : ActivePcmPlayback {
        private val completion = CompletableDeferred<PlaybackCompletion>()
        private val toneSlot = java.util.concurrent.atomic.AtomicBoolean(false)

        fun complete(result: PlaybackCompletion) {
            completion.complete(result)
        }

        override suspend fun awaitCompletion(): PlaybackCompletion = completion.await()

        override fun rejectPttWithTone(): Boolean {
            if (!completion.isActive) return false
            return toneSlot.compareAndSet(false, true)
        }

        override fun skip(): Boolean {
            if (!completion.isActive) return false
            toneSlot.set(false) // skip wins; cancel any latched tone
            completion.complete(PlaybackCompletion.ExplicitlySkipped)
            return true
        }
    }

    /**
     * Mirrors [ModePlaybackRouteResolver.ReleasingPlaybackRoute]: delegates start to an inner
     * route and chains a release callback after the inner route is released.
     */
    internal class ReleasingPlaybackRoute(
        private val delegate: AcquiredPlaybackRoute,
        private val onRelease: () -> Unit,
    ) : AcquiredPlaybackRoute {
        override val endpoint: AudioRouteEndpoint get() = delegate.endpoint

        override suspend fun start(recording: RecordedPcm): ActivePcmPlayback = delegate.start(recording)

        override suspend fun release() {
            try {
                delegate.release()
            } finally {
                onRelease()
            }
        }
    }
}