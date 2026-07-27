package io.talkcan.service

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import io.talkcan.model.DevicePresence
import io.talkcan.model.RawButtonEvent
import io.talkcan.model.SppState
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RsmSerialConnectionCoordinatorTest {
    @Test
    fun `manual connection admits one session, suppresses duplicate admission, and resolves its bonded target`() = runTest {
        val target = device()
        val fixture = Fixture(scope = backgroundScope, scannerTarget = target)

        fixture.coordinator.connectSerial()
        assertEquals(
            listOf(SerialCoordinatorEvent.SerialDisconnectPendingChanged(pending = false)),
            fixture.events,
        )
        assertTrue(fixture.coordinator.monitoringRequested)

        fixture.coordinator.onReadinessRefreshed()
        val initialAttempt = fixture.scheduler.singlePending()
        assertEquals(fixture.clock.nowMillis, initialAttempt.dueAtMillis)

        fixture.scheduler.run(initialAttempt)
        runCurrent()

        assertEquals(1, fixture.sppFactory.connections.size)
        assertSame(target, fixture.coordinator.targetDevice())
        assertSame(target, fixture.sppFactory.connections.single().requestedDevice)
        assertEquals(
            listOf(
                SerialCoordinatorEvent.RequestReadinessRefresh,
                SerialCoordinatorEvent.RequestEnsureForeground,
                SerialCoordinatorEvent.SppStateChanged(SppState.Disconnected, error = null),
                SerialCoordinatorEvent.RequestReadinessRefresh,
            ),
            fixture.events.drop(1),
        )

        fixture.events.clear()
        fixture.coordinator.connectSerial()
        fixture.coordinator.onReadinessRefreshed()
        runCurrent()

        assertTrue(fixture.events.isEmpty())
        assertEquals(1, fixture.sppFactory.connections.size)
        fixture.close()
    }

    @Test
    fun `explicit disconnect cancels RSM work and retires the active SPP connection in baseline order`() = runTest {
        val fixture = Fixture(scope = backgroundScope, scannerTarget = device())
        fixture.startSession()
        runCurrent()
        val connection = fixture.sppFactory.connections.single()
        fixture.events.clear()

        fixture.coordinator.disconnectSerial()
        runCurrent()

        assertEquals(1, connection.disconnectCount)
        assertFalse(fixture.coordinator.monitoringRequested)
        assertEquals(
            listOf(
                SerialCoordinatorEvent.CancelPtt(
                    caller = PttCancellationCaller.ExplicitSerialDisconnect,
                    reason = "Explicit RSM serial disconnect",
                ),
                SerialCoordinatorEvent.SerialDisconnectPendingChanged(pending = true),
                SerialCoordinatorEvent.RequestStopReadinessRefreshLoop,
                SerialCoordinatorEvent.SppStateChanged(SppState.Disconnected, error = null),
                SerialCoordinatorEvent.ReleaseTts,
                SerialCoordinatorEvent.RequestReevaluateSerialDisconnectShutdown,
                SerialCoordinatorEvent.RequestReadinessRefresh,
            ),
            fixture.events,
        )
        fixture.close()
    }

    @Test
    fun `automatic connection waits for all reconnect prerequisites before scheduling its first attempt`() = runTest {
        val fixture = Fixture(scope = backgroundScope, scannerTarget = device())
        val blocked = listOf(
            ReconnectPrerequisites(false, true, true),
            ReconnectPrerequisites(true, false, true),
            ReconnectPrerequisites(true, true, false),
        )

        fixture.coordinator.connectSerial()
        for (prerequisites in blocked) {
            fixture.prerequisites = prerequisites
            fixture.coordinator.onReadinessRefreshed()

            assertTrue(fixture.scheduler.pending().isEmpty())
            assertTrue(fixture.sppFactory.connections.isEmpty())
        }

        fixture.prerequisites = ReconnectPrerequisites(true, true, true)
        fixture.coordinator.onReadinessRefreshed()

        assertEquals(fixture.clock.nowMillis, fixture.scheduler.singlePending().dueAtMillis)
        fixture.close()
    }

    @Test
    fun `automatic retry waits for its scheduled deadline and a connected attempt completes retry ownership`() = runTest {
        val fixture = Fixture(scope = backgroundScope, scannerTarget = device())
        val first = fixture.startSession()
        runCurrent()
        fixture.events.clear()

        first.finish()
        runCurrent()
        assertTrue(
            fixture.events.contains(
                SerialCoordinatorEvent.LogTermination(
                    automatic = true,
                    everConnected = false,
                    monitoringRequested = true,
                    disposition = RsmReconnectDisposition.Scheduled,
                ),
            ),
        )

        val retry = fixture.scheduler.singlePending()
        assertTrue("failed automatic attempt must wait before retrying", retry.dueAtMillis > fixture.clock.nowMillis)
        fixture.clock.advanceTo(retry.dueAtMillis - 1)
        fixture.scheduler.runDue()
        runCurrent()
        assertEquals(1, fixture.sppFactory.connections.size)

        fixture.clock.advanceTo(retry.dueAtMillis)
        fixture.scheduler.runDue()
        runCurrent()

        val second = fixture.sppFactory.connections[1]
        second.emitState(SppState.Connected)
        runCurrent()

        assertEquals(
            SerialCoordinatorEvent.SppStateChanged(SppState.Connected, error = null),
            fixture.events.last { it is SerialCoordinatorEvent.SppStateChanged },
        )
        assertTrue("a successful attempt must not create another retry", fixture.scheduler.pending().isEmpty())
        fixture.close()
    }

    @Test
    fun `session termination reports each blocked reconnect prerequisite without scheduling a retry`() = runTest {
        data class Case(
            val name: String,
            val prerequisites: ReconnectPrerequisites,
            val disposition: RsmReconnectDisposition,
            val trailingEvents: List<SerialCoordinatorEvent>,
        )

        val cases = listOf(
            Case(
                name = "missing permissions",
                prerequisites = ReconnectPrerequisites(false, true, true),
                disposition = RsmReconnectDisposition.BlockedMissingPermissions,
                trailingEvents = listOf(SerialCoordinatorEvent.RequestReadinessRefresh),
            ),
            Case(
                name = "bluetooth disabled",
                prerequisites = ReconnectPrerequisites(true, false, true),
                disposition = RsmReconnectDisposition.BlockedBluetoothDisabled,
                trailingEvents = listOf(SerialCoordinatorEvent.RequestReadinessRefresh),
            ),
            Case(
                name = "target unavailable",
                prerequisites = ReconnectPrerequisites(true, true, false),
                disposition = RsmReconnectDisposition.BlockedTargetUnavailable,
                trailingEvents = listOf(
                    SerialCoordinatorEvent.DevicePresenceChanged(DevicePresence.NotFound),
                    SerialCoordinatorEvent.RequestReadinessRefresh,
                ),
            ),
        )

        for (case in cases) {
            val fixture = Fixture(scope = backgroundScope, scannerTarget = device())
            val connection = fixture.startSession()
            runCurrent()
            fixture.prerequisites = case.prerequisites
            fixture.events.clear()

            connection.finish()
            runCurrent()

            assertEquals(
                case.name,
                listOf(
                    SerialCoordinatorEvent.CancelPtt(
                        caller = PttCancellationCaller.RsmSerialSessionEnded,
                        reason = "RSM serial session ended",
                    ),
                    SerialCoordinatorEvent.ReleaseTts,
                    SerialCoordinatorEvent.RequestReadinessRefresh,
                    SerialCoordinatorEvent.LogTermination(
                        automatic = true,
                        everConnected = false,
                        monitoringRequested = true,
                        disposition = case.disposition,
                    ),
                ) + case.trailingEvents,
                fixture.events,
            )
            assertTrue("${case.name} must not leave retry work pending", fixture.scheduler.pending().isEmpty())
            fixture.close()
        }
    }

    @Test
    fun `SPP state and raw button events preserve publication order`() = runTest {
        val fixture = Fixture(scope = backgroundScope, scannerTarget = device())
        val connection = fixture.startSession()
        runCurrent()
        fixture.events.clear()

        connection.emitState(SppState.Connecting)
        runCurrent()
        connection.emitState(SppState.Connected)
        runCurrent()
        connection.emitButton(RawButtonEvent.PttPressed)
        runCurrent()

        assertEquals(
            listOf(
                SerialCoordinatorEvent.SppStateChanged(SppState.Connecting, error = null),
                SerialCoordinatorEvent.RequestReadinessRefresh,
                SerialCoordinatorEvent.SppStateChanged(SppState.Connected, error = null),
                SerialCoordinatorEvent.RequestReadinessRefresh,
                SerialCoordinatorEvent.RawButtonReceived(RawButtonEvent.PttPressed),
            ),
            fixture.events,
        )
        fixture.close()
    }

    @Test
    fun `session termination requests RSM cancellation before and during PTT with the same classified caller`() = runTest {
        data class Case(val name: String, val pttActive: Boolean, val expectedCancellation: String)

        val cases = listOf(
            Case("before PTT", pttActive = false, expectedCancellation = "no-rsm-session"),
            Case("during RSM PTT", pttActive = true, expectedCancellation = "cancelled-rsm-session"),
        )

        for (case in cases) {
            val ptt = RecordingPttEdge(active = case.pttActive)
            val fixture = Fixture(scope = backgroundScope, scannerTarget = device(), onEvent = ptt::record)
            val connection = fixture.startSession()
            runCurrent()
            fixture.events.clear()
            ptt.events.clear()

            connection.finish()
            runCurrent()

            assertEquals(listOf(case.expectedCancellation), ptt.cancellationOutcomes)
            assertEquals(
                SerialCoordinatorEvent.CancelPtt(
                    caller = PttCancellationCaller.RsmSerialSessionEnded,
                    reason = "RSM serial session ended",
                ),
                ptt.events.first(),
            )
            assertEquals(SerialCoordinatorEvent.ReleaseTts, ptt.events[1])
            assertTrue(
                "${case.name} must report reconnect disposition after terminal cancellation",
                ptt.events.any { it is SerialCoordinatorEvent.LogTermination },
            )
            fixture.close()
        }
    }

    @Test
    fun `late SPP events after explicit retirement cannot revive connection state`() = runTest {
        val fixture = Fixture(scope = backgroundScope, scannerTarget = device())
        val connection = fixture.startSession()
        runCurrent()
        fixture.coordinator.disconnectSerial()
        runCurrent()
        fixture.events.clear()

        connection.emitState(SppState.Connected)
        connection.emitButton(RawButtonEvent.PttPressed)
        connection.finish()
        runCurrent()

        assertTrue(fixture.events.isEmpty())
        fixture.close()
    }

    @Test
    fun `a cancelled reconnect callback cannot create a new serial session`() = runTest {
        val fixture = Fixture(scope = backgroundScope, scannerTarget = device())
        val connection = fixture.startSession()
        runCurrent()
        connection.finish()
        runCurrent()
        val retry = fixture.scheduler.singlePending()

        fixture.coordinator.disconnectSerial()
        fixture.events.clear()
        fixture.scheduler.run(retry, evenIfCancelled = true)
        runCurrent()

        assertTrue(retry.cancelled)
        assertEquals(1, fixture.sppFactory.connections.size)
        assertTrue(fixture.events.none { it == SerialCoordinatorEvent.RequestEnsureForeground })
        assertTrue(fixture.events.none { it is SerialCoordinatorEvent.SppStateChanged && it.state == SppState.Connected })
        fixture.close()
    }

    private class Fixture(
        scope: CoroutineScope,
        scannerTarget: BluetoothDevice?,
        onEvent: ((SerialCoordinatorEvent) -> Unit)? = null,
    ) {
        val events = mutableListOf<SerialCoordinatorEvent>()
        val clock = FakeElapsedTime(nowMillis = 10_000)
        val scanner = RecordingScanner(scannerTarget)
        val sppFactory = RecordingSppFactory()
        val scheduler = RecordingReconnectScheduler(clock)
        var prerequisites = ReconnectPrerequisites(
            permissionsGranted = true,
            bluetoothEnabled = true,
            bondedTargetAvailable = true,
        )
        val coordinator = RsmSerialConnectionCoordinator(
            scope = scope,
            adapterProvider = { adapter() },
            scanner = scanner,
            sppFactory = sppFactory,
            elapsedRealtime = { clock.nowMillis },
            reconnectScheduler = scheduler,
            prerequisitesProvider = { prerequisites },
            onEvent = { event ->
                events += event
                onEvent?.invoke(event)
            },
        )

        fun startSession(): RecordingSppConnection {
            coordinator.connectSerial()
            coordinator.onReadinessRefreshed()
            scheduler.run(scheduler.singlePending())
            return sppFactory.connections.single()
        }

        fun close() {
            coordinator.shutdown()
        }
    }


    private class RecordingScanner(
        var target: BluetoothDevice?,
    ) : RsmSerialScanner {
        override fun bondedTarget(): BluetoothDevice? = target
    }

    private class RecordingSppFactory : RsmSppConnectionFactory {
        val connections = mutableListOf<RecordingSppConnection>()

        override fun create(adapter: BluetoothAdapter): RsmSppConnection = RecordingSppConnection().also(connections::add)
    }

    private class RecordingSppConnection : RsmSppConnection {
        private val mutableState = MutableStateFlow(SppState.Disconnected)
        private val buttons = Channel<RawButtonEvent>(Channel.UNLIMITED)

        override val state: StateFlow<SppState> = mutableState
        var requestedDevice: BluetoothDevice? = null
            private set
        var disconnectCount = 0
            private set

        override fun events(device: BluetoothDevice): Flow<RawButtonEvent> {
            requestedDevice = device
            return buttons.receiveAsFlow()
        }

        override fun disconnect() {
            disconnectCount += 1
        }

        fun emitState(state: SppState) {
            mutableState.value = state
        }

        fun emitButton(event: RawButtonEvent) {
            buttons.trySend(event)
        }

        fun finish() {
            buttons.close()
        }
    }

    private class FakeElapsedTime(
        var nowMillis: Long,
    ) {
        fun advanceTo(millis: Long) {
            require(millis >= nowMillis)
            nowMillis = millis
        }
    }

    private class RecordingReconnectScheduler(
        private val clock: FakeElapsedTime,
    ) : RsmReconnectScheduler {
        data class Scheduled(
            val dueAtMillis: Long,
            val action: () -> Unit,
            var cancelled: Boolean = false,
            var invoked: Boolean = false,
        )

        private val scheduled = mutableListOf<Scheduled>()

        override fun schedule(delayMs: Long, action: () -> Unit): RsmReconnectHandle {
            val entry = Scheduled(dueAtMillis = clock.nowMillis + delayMs, action = action)
            scheduled += entry
            return RsmReconnectHandle { entry.cancelled = true }
        }

        fun pending(): List<Scheduled> = scheduled.filter { !it.cancelled && !it.invoked }

        fun singlePending(): Scheduled = pending().single()

        fun runDue() {
            pending().filter { it.dueAtMillis <= clock.nowMillis }.forEach(::run)
        }

        fun run(entry: Scheduled, evenIfCancelled: Boolean = false) {
            require(!entry.invoked)
            require(evenIfCancelled || !entry.cancelled)
            entry.invoked = true
            entry.action()
        }
    }

    private class RecordingPttEdge(
        private var active: Boolean,
    ) {
        val events = mutableListOf<SerialCoordinatorEvent>()
        val cancellationOutcomes = mutableListOf<String>()

        fun record(event: SerialCoordinatorEvent) {
            events += event
            if (event is SerialCoordinatorEvent.CancelPtt && event.caller == PttCancellationCaller.RsmSerialSessionEnded) {
                cancellationOutcomes += if (active) {
                    active = false
                    "cancelled-rsm-session"
                } else {
                    "no-rsm-session"
                }
            }
        }
    }

    private companion object {
        fun adapter(): BluetoothAdapter = mockk(relaxed = true)

        fun device(): BluetoothDevice = mockk(relaxed = true)
    }
}
