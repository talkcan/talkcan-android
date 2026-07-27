package io.talkcan.channel

import io.talkcan.bluetooth.SleepwalkerBleConnection
import io.talkcan.bluetooth.SleepwalkerConnectionResult
import io.talkcan.channel.capability.TextDeliveryOutcome
import io.talkcan.channel.capability.TextOutputFailureReason
import io.talkcan.channel.capability.TextOutputProfile
import io.talkcan.channel.capability.TextOutputRejectionReason
import io.talkcan.channel.capability.TextOutputRequest
import io.talkcan.model.TextOutputTransportState
import io.sleepwalker.core.hid.LowLevelHidImpl
import io.sleepwalker.core.hid.LowLevelOp
import io.sleepwalker.core.keymap.HostProfile
import io.sleepwalker.core.keymap.KeymapDatabase
import io.sleepwalker.core.keymap.SeedKeymapDatabase
import io.sleepwalker.core.protocol.Opcodes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SleepwalkerTextOutputServiceTest {
    @Test
    fun availabilityAndPreparationAreSemanticAndConnectedPreparationDoesNotReconnect() = runTest {
        val connection = RecordingConnection().apply { setState(TextOutputTransportState.Connected) }
        var connectCalls = 0
        val service = service(connection) {
            connectCalls += 1
            SleepwalkerConnectionResult.Connected
        }
        runCurrent()

        assertEquals(TextOutputAvailability.Available, service.availability.value)
        assertEquals(TextOutputPreparation.Available, service.prepare())
        assertEquals(0, connectCalls)

        connection.setState(TextOutputTransportState.Disconnected)
        runCurrent()
        assertEquals(TextOutputAvailability.Unavailable, service.availability.value)
    }

    @Test
    fun concurrentPreparationJoinsOneHostConnectionAttempt() = runTest {
        val connection = RecordingConnection()
        val connectionResult = CompletableDeferred<SleepwalkerConnectionResult>()
        var connectCalls = 0
        val service = service(connection) {
            connectCalls += 1
            connectionResult.await()
        }

        val first = async { service.prepare() }
        val second = async { service.prepare() }
        runCurrent()

        assertEquals(TextOutputAvailability.Preparing, service.availability.value)
        assertEquals(1, connectCalls)

        connection.setState(TextOutputTransportState.Connected)
        connectionResult.complete(SleepwalkerConnectionResult.Connected)
        assertEquals(TextOutputPreparation.Available, first.await())
        assertEquals(TextOutputPreparation.Available, second.await())
        assertEquals(1, connectCalls)
    }

    @Test
    fun successfulTextDeliveryArmsWritesAcknowledgedTextAndDisarmsOnce() = runTest {
        val connection = RecordingConnection().apply { setState(TextOutputTransportState.Connected) }
        val service = service(connection) { error("connected service must not reconnect") }

        val outcome = service.capabilityFor("office").sendText(
            TextOutputRequest("hello", TextOutputProfile("linux:us")),
        )

        assertEquals(TextDeliveryOutcome.Delivered("office:1"), outcome)
        assertEquals(Opcodes.ARM, connection.sent.first().opcode)
        assertEquals(Opcodes.DISARM, connection.sent.last().opcode)
        assertEquals(1, connection.count(Opcodes.ARM))
        assertEquals(1, connection.count(Opcodes.DISARM))
        assertEquals(0, connection.count(Opcodes.KILL))
        assertTrue(connection.sent.size > 2)
    }

    @Test
    fun normalizedProfileIdResolvesCanonicalMixedCaseDatabaseProfile() = runTest {
        val canonical = HostProfile(hostOs = "macos", layout = "ABC")
        val entries = checkNotNull(SeedKeymapDatabase.lookup(HostProfile.LINUX_US))
        val database = object : KeymapDatabase {
            override val profiles: Collection<HostProfile> = listOf(canonical)

            override fun lookup(profile: HostProfile) = if (profile == canonical) entries else null
        }
        val connection = RecordingConnection().apply { setState(TextOutputTransportState.Connected) }
        val service = service(
            connection = connection,
            keymapDatabase = database,
        ) {
            error("connected service must not reconnect")
        }

        assertEquals(
            TextDeliveryOutcome.Delivered("office:1"),
            service.capabilityFor("office").sendText(
                TextOutputRequest("hello", TextOutputProfile("macos:abc")),
            ),
        )
    }

    @Test
    fun invalidProfileAndUnrepresentableTextAreRejectedBeforeAnyOutputIsArmed() = runTest {
        val connection = RecordingConnection().apply { setState(TextOutputTransportState.Connected) }
        val service = service(connection) { error("connected service must not reconnect") }
        val capability = service.capabilityFor("desk")

        assertEquals(
            TextDeliveryOutcome.Rejected("desk:1", TextOutputRejectionReason.INVALID_PROFILE),
            capability.sendText(TextOutputRequest("hello", TextOutputProfile("not-a-profile"))),
        )
        assertEquals(
            TextDeliveryOutcome.Rejected("desk:2", TextOutputRejectionReason.INVALID_PROFILE),
            capability.sendText(TextOutputRequest("hello", TextOutputProfile("linux:no-keymap"))),
        )
        assertEquals(
            TextDeliveryOutcome.Rejected("desk:3", TextOutputRejectionReason.UNSUPPORTED_CHARACTER),
            capability.sendText(TextOutputRequest("£", TextOutputProfile("linux:us"))),
        )
        assertTrue(connection.sent.isEmpty())
    }

    @Test
    fun eachSubmissionHasOneTypedTerminalOutcomeAndMonotonicOperationIdentity() = runTest {
        val connection = RecordingConnection()
        val nextConnectionResult: SleepwalkerConnectionResult = SleepwalkerConnectionResult.Failed("offline")
        val service = service(connection) { nextConnectionResult }
        val capability = service.capabilityFor("instance-a")

        val rejected = capability.sendText(TextOutputRequest("", TextOutputProfile("linux:us")))
        val failed = capability.sendText(TextOutputRequest("hello", TextOutputProfile("linux:us")))

        connection.setState(TextOutputTransportState.Connected)
        connection.acknowledges = false
        val indeterminate = capability.sendText(TextOutputRequest("hello", TextOutputProfile("linux:us")))

        connection.acknowledges = true
        val delivered = capability.sendText(TextOutputRequest("hello", TextOutputProfile("linux:us")))

        assertEquals(TextDeliveryOutcome.Rejected("instance-a:1", TextOutputRejectionReason.EMPTY_TEXT), rejected)
        assertTrue(failed is TextDeliveryOutcome.Failed)
        assertEquals("instance-a:2", (failed as TextDeliveryOutcome.Failed).operationId)
        assertTrue(indeterminate is TextDeliveryOutcome.Indeterminate)
        assertEquals("instance-a:3", (indeterminate as TextDeliveryOutcome.Indeterminate).operationId)
        assertEquals(TextDeliveryOutcome.Delivered("instance-a:4"), delivered)
        assertEquals(1, connection.count(Opcodes.KILL))
        assertEquals(2, connection.count(Opcodes.DISARM))
    }

    @Test
    fun acknowledgementLossIsIndeterminateCleansUpExactlyOnceAndNeverReplays() = runTest {
        val connection = RecordingConnection().apply {
            setState(TextOutputTransportState.Connected)
            acknowledges = false
        }
        val service = service(connection) { error("connected service must not reconnect") }

        val outcome = service.capabilityFor("desk").sendText(
            TextOutputRequest("hello", TextOutputProfile("linux:us")),
        )
        val sequenceAtTerminal = connection.sent.map { it.opcode }
        advanceUntilIdle()

        assertTrue(outcome is TextDeliveryOutcome.Indeterminate)
        assertEquals("desk:1", (outcome as TextDeliveryOutcome.Indeterminate).operationId)
        assertEquals(Opcodes.ARM, sequenceAtTerminal.first())
        assertEquals(Opcodes.KILL, sequenceAtTerminal[sequenceAtTerminal.size - 2])
        assertEquals(Opcodes.DISARM, sequenceAtTerminal.last())
        assertEquals(1, connection.count(Opcodes.KILL))
        assertEquals(1, connection.count(Opcodes.DISARM))
        assertEquals(sequenceAtTerminal, connection.sent.map { it.opcode })
    }

    @Test
    fun disconnectAfterTransmissionStartsIsIndeterminateAndForceReleasesOnce() = runTest {
        val connection = RecordingConnection().apply {
            setState(TextOutputTransportState.Connected)
            throwOnFirstTextOperation = true
        }
        val service = service(connection) { error("connected service must not reconnect") }

        val outcome = service.capabilityFor("desk").sendText(
            TextOutputRequest("hello", TextOutputProfile("linux:us")),
        )

        assertTrue(outcome is TextDeliveryOutcome.Indeterminate)
        assertEquals("desk:1", (outcome as TextDeliveryOutcome.Indeterminate).operationId)
        assertEquals(Opcodes.ARM, connection.sent.first().opcode)
        assertEquals(1, connection.count(Opcodes.KILL))
        assertEquals(1, connection.count(Opcodes.DISARM))
        assertEquals(Opcodes.DISARM, connection.sent.last().opcode)
    }

    @Test
    fun cancellationAndDeliveryDeadlineAfterTransmissionStartsAreIndeterminateAndCleanUpOnce() = runTest {
        val cancellationConnection = RecordingConnection().apply {
            setState(TextOutputTransportState.Connected)
            holdFirstTextOperation = CompletableDeferred()
        }
        val cancellationService = service(cancellationConnection) { error("connected service must not reconnect") }
        val cancelled = async {
            cancellationService.capabilityFor("cancel").sendText(
                TextOutputRequest("hello", TextOutputProfile("linux:us")),
            )
        }
        cancellationConnection.firstTextOperation.await()
        cancelled.cancelAndJoin()

        assertEquals(1, cancellationConnection.count(Opcodes.KILL))
        assertEquals(1, cancellationConnection.count(Opcodes.DISARM))

        val timeoutConnection = RecordingConnection().apply {
            setState(TextOutputTransportState.Connected)
            delayFirstTextOperationMillis = 20
        }
        val timeoutService = service(
            connection = timeoutConnection,
            deliveryTimeoutMs = 10,
        ) { error("connected service must not reconnect") }
        val timedOut = async {
            timeoutService.capabilityFor("timeout").sendText(
                TextOutputRequest("hello", TextOutputProfile("linux:us")),
            )
        }
        runCurrent()
        advanceTimeBy(10)

        val timeoutOutcome = timedOut.await()
        assertTrue(timeoutOutcome is TextDeliveryOutcome.Indeterminate)
        assertEquals("timeout:1", (timeoutOutcome as TextDeliveryOutcome.Indeterminate).operationId)
        assertEquals(1, timeoutConnection.count(Opcodes.KILL))
        assertEquals(1, timeoutConnection.count(Opcodes.DISARM))
    }

    @Test
    fun hostShutdownTerminatesActiveDeliveryAndClosesSharedTransportOnce() = runTest {
        val connection = RecordingConnection().apply {
            setState(TextOutputTransportState.Connected)
            holdFirstTextOperation = CompletableDeferred()
        }
        val service = service(connection) { error("connected service must not reconnect") }
        val delivery = async {
            service.capabilityFor("desk").sendText(
                TextOutputRequest("hello", TextOutputProfile("linux:us")),
            )
        }
        connection.firstTextOperation.await()

        service.close()
        val outcome = delivery.await()

        assertTrue(outcome is TextDeliveryOutcome.Indeterminate)
        assertEquals(TextOutputAvailability.Closed, service.availability.value)
        assertEquals(1, connection.disconnectCount)
        assertEquals(1, connection.count(Opcodes.KILL))
        assertEquals(1, connection.count(Opcodes.DISARM))
    }

    @Test
    fun shutdownDuringPreparationRejectsDeliveryBeforeAnyHardwareEffect() = runTest {
        val connection = RecordingConnection()
        val connectGate = CompletableDeferred<SleepwalkerConnectionResult>()
        val service = service(connection) { connectGate.await() }
        val delivery = async {
            service.capabilityFor("desk").sendText(
                TextOutputRequest("hello", TextOutputProfile("linux:us")),
            )
        }
        runCurrent()

        service.close()

        assertEquals(
            TextDeliveryOutcome.Failed("desk:1", TextOutputFailureReason.CANCELLED),
            delivery.await(),
        )
        assertTrue(connection.sent.isEmpty())
        assertEquals(1, connection.disconnectCount)
    }

    private fun kotlinx.coroutines.test.TestScope.service(
        connection: RecordingConnection,
        deliveryTimeoutMs: Long = 1_000,
        keymapDatabase: KeymapDatabase = SeedKeymapDatabase,
        connectOperation: suspend () -> SleepwalkerConnectionResult,
    ): SleepwalkerTextOutputService = SleepwalkerTextOutputService(
        scope = backgroundScope,
        connection = connection,
        hid = LowLevelHidImpl(),
        keymapDatabase = keymapDatabase,
        connect = { _: Long -> connectOperation() },
        preparationTimeoutMs = 1_000,
        deliveryTimeoutMs = deliveryTimeoutMs,
    )

    private class RecordingConnection : SleepwalkerBleConnection() {
        val sent = mutableListOf<LowLevelOp>()
        val firstTextOperation = CompletableDeferred<Unit>()
        var acknowledges = true
        var throwOnFirstTextOperation = false
        var holdFirstTextOperation: CompletableDeferred<Unit>? = null
        var delayFirstTextOperationMillis = 0L
        var disconnectCount = 0
        private var sawTextOperation = false

        fun setState(state: TextOutputTransportState) {
            _connectionState.value = state
        }

        fun count(opcode: Any): Int = sent.count { it.opcode == opcode }

        override fun disconnect() {
            disconnectCount += 1
            _connectionState.value = TextOutputTransportState.Disconnected
        }

        override suspend fun sendOp(op: LowLevelOp) {
            val safetyOperation = op.opcode == Opcodes.ARM || op.opcode == Opcodes.KILL || op.opcode == Opcodes.DISARM
            if (!safetyOperation && !sawTextOperation) {
                sawTextOperation = true
                firstTextOperation.complete(Unit)
                if (throwOnFirstTextOperation) throw IllegalStateException("link lost")
                sent += op
                holdFirstTextOperation?.await()
                if (delayFirstTextOperationMillis > 0) delay(delayFirstTextOperationMillis)
                return
            }
            sent += op
        }

        override suspend fun awaitAck(seqId: Int, timeoutMs: Long): Boolean = acknowledges
    }
}
