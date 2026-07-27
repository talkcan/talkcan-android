package io.talkcan.channel

import io.talkcan.bluetooth.SleepwalkerBleConnection
import io.talkcan.bluetooth.SleepwalkerConnectionResult
import io.talkcan.channel.capability.CapabilityScopeIdentity
import io.talkcan.channel.capability.KeyboardOutputSubmission
import io.talkcan.channel.capability.OutputAdmissionDiagnostic
import io.talkcan.channel.capability.OutputAdmissionScheduler
import io.talkcan.channel.capability.OutputAdmissionBounds
import io.talkcan.channel.capability.OutputAdmissionDiagnosticSink
import io.talkcan.channel.capability.OutputExecutionOwner
import io.talkcan.channel.capability.OutputExecutionOwnerKind
import io.talkcan.channel.capability.RuntimeGeneration
import io.talkcan.channel.capability.TextDeliveryOutcome
import io.talkcan.channel.capability.TextKeyRequest
import io.talkcan.channel.capability.TextOutputKey
import io.talkcan.channel.capability.TextOutputProfile
import io.talkcan.channel.capability.TextOutputRejectionReason
import io.talkcan.channel.capability.TextOutputRequest
import io.talkcan.model.TextOutputTransportState
import io.sleepwalker.core.hid.LowLevelHidImpl
import io.sleepwalker.core.hid.LowLevelOp
import io.sleepwalker.core.keymap.SeedKeymapDatabase
import io.sleepwalker.core.protocol.Opcodes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused tests proving the built-in Keyboard port and the generation-scoped Lua adapter share ONE
 * host-wide bounded FIFO admission scheduler (tasks 6.3, 6.4, 6.8, 6.9, 6.12, 6.13) while the
 * Sleepwalker service keeps sole ownership of profile validation, keymap compilation, transport,
 * acknowledgement, and cleanup, and the built-in public behavior is unchanged.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KeyboardOutputAdmissionRoutingTest {

    // --- 6.13 / 6.3 shared single scheduler + FIFO across built-in and Lua -----------------

    @Test
    fun builtInAndAdapterSerializeThroughOneSharedSchedulerInFifoOrder() = runTest {
        val connection = HoldingConnection().apply {
            setState(TextOutputTransportState.Connected)
            holdFirstTextOperation = CompletableDeferred()
        }
        val scheduler = OutputAdmissionScheduler()
        val service = service(connection, scheduler)
        val builtIn = service.capabilityFor("built-in")
        val adapter = service.keyboardOutputAdapter(
            CapabilityScopeIdentity("lua-instance", RuntimeGeneration(5)),
            OutputExecutionOwner(OutputExecutionOwnerKind.INPUT, "owner-1"),
        )

        val builtInResult = async {
            builtIn.sendText(TextOutputRequest("aaa", TextOutputProfile("linux:us")))
        }
        connection.firstTextOperation.await()
        runCurrent()
        assertEquals(1, scheduler.activeCount())

        // The adapter op must queue behind the active built-in op on the SAME scheduler.
        val adapterResult = async {
            adapter.sendText(TextOutputRequest("bbb", TextOutputProfile("linux:us")))
        }
        runCurrent()
        assertEquals(1, scheduler.queuedCount())

        checkNotNull(connection.holdFirstTextOperation).complete(Unit)
        advanceUntilIdle()

        assertTrue(builtInResult.await() is TextDeliveryOutcome.Delivered)
        val adapterOutcome = adapterResult.await()
        assertTrue(adapterOutcome is KeyboardOutputSubmission.Completed)
        assertTrue((adapterOutcome as KeyboardOutputSubmission.Completed).outcome is TextDeliveryOutcome.Delivered)
        assertEquals(0, scheduler.activeCount())
        assertEquals(0, scheduler.queuedCount())
    }

    @Test
    fun adapterReturnsBusyWhenSharedCapacityIsSaturatedBeforeAnyEffect() = runTest {
        val connection = HoldingConnection().apply {
            setState(TextOutputTransportState.Connected)
            holdFirstTextOperation = CompletableDeferred()
        }
        val scheduler = OutputAdmissionScheduler(OutputAdmissionBounds(maxQueuedPerProcess = 1))
        val service = service(connection, scheduler)
        val builtIn = service.capabilityFor("built-in")
        val adapter = service.keyboardOutputAdapter(
            CapabilityScopeIdentity("lua-instance", RuntimeGeneration(5)),
            OutputExecutionOwner(OutputExecutionOwnerKind.MANAGED_TASK, "owner-1"),
        )

        val holder = async { builtIn.sendText(TextOutputRequest("aaa", TextOutputProfile("linux:us"))) }
        connection.firstTextOperation.await()
        runCurrent()
        val queued = async { adapter.sendText(TextOutputRequest("bbb", TextOutputProfile("linux:us"))) }
        runCurrent()

        // Queue (1) is full; the next operation is Busy and never reaches compilation/transport.
        val sentBeforeBusy = connection.sent.size
        assertEquals(KeyboardOutputSubmission.Busy, adapter.sendText(TextOutputRequest("ccc", TextOutputProfile("linux:us"))))
        assertEquals(sentBeforeBusy, connection.sent.size)

        checkNotNull(connection.holdFirstTextOperation).complete(Unit)
        advanceUntilIdle()
        assertTrue(holder.await() is TextDeliveryOutcome.Delivered)
        val queuedOutcome = queued.await()
        assertTrue(queuedOutcome is KeyboardOutputSubmission.Completed)
    }

    // --- 6.4 / 6.8 bounded requests + exact typed outcomes --------------------------------

    @Test
    fun adapterValidatesProfileEmptyOverBoundAndEscapeBeforeAdmission() = runTest {
        val connection = HoldingConnection().apply { setState(TextOutputTransportState.Connected) }
        val scheduler = OutputAdmissionScheduler()
        val service = service(connection, scheduler)
        val adapter = service.keyboardOutputAdapter(
            CapabilityScopeIdentity("lua-instance", RuntimeGeneration(5)),
            OutputExecutionOwner(OutputExecutionOwnerKind.INPUT, "owner-1"),
        )
        val profile = TextOutputProfile("linux:us")

        val invalidProfile = adapter.sendText(TextOutputRequest("hello", TextOutputProfile("not-a-profile")))
        assertEquals(
            KeyboardOutputSubmission.Completed(
                TextDeliveryOutcome.Rejected("lua-instance:1", TextOutputRejectionReason.INVALID_PROFILE),
            ),
            invalidProfile,
        )

        val empty = adapter.sendText(TextOutputRequest("", profile))
        assertEquals(
            KeyboardOutputSubmission.Completed(
                TextDeliveryOutcome.Rejected("lua-instance:2", TextOutputRejectionReason.EMPTY_TEXT),
            ),
            empty,
        )

        val overBound = adapter.sendText(TextOutputRequest("a".repeat(16_385), profile))
        assertEquals(
            KeyboardOutputSubmission.Completed(
                TextDeliveryOutcome.Rejected("lua-instance:3", TextOutputRejectionReason.POLICY_REFUSED),
            ),
            overBound,
        )

        val escape = adapter.sendKey(TextKeyRequest(TextOutputKey.ESCAPE, profile))
        assertEquals(
            KeyboardOutputSubmission.Completed(
                TextDeliveryOutcome.Rejected("lua-instance:4", TextOutputRejectionReason.POLICY_REFUSED),
            ),
            escape,
        )

        // All of the above were rejected before any compilation/transport effect.
        assertTrue(connection.sent.isEmpty())
        assertEquals(0, scheduler.queuedCount())
        assertEquals(0, scheduler.activeCount())
    }

    @Test
    fun adapterDeliversSemanticEnterAndTextAsCompletedDelivered() = runTest {
        val connection = HoldingConnection().apply { setState(TextOutputTransportState.Connected) }
        val scheduler = OutputAdmissionScheduler()
        val service = service(connection, scheduler)
        val adapter = service.keyboardOutputAdapter(
            CapabilityScopeIdentity("lua-instance", RuntimeGeneration(5)),
            OutputExecutionOwner(OutputExecutionOwnerKind.SOS, "owner-1"),
        )
        val profile = TextOutputProfile("linux:us")

        val enter = adapter.sendKey(TextKeyRequest(TextOutputKey.ENTER, profile))
        assertTrue(enter is KeyboardOutputSubmission.Completed)
        assertTrue((enter as KeyboardOutputSubmission.Completed).outcome is TextDeliveryOutcome.Delivered)

        val text = adapter.sendText(TextOutputRequest("hello", profile))
        assertTrue(text is KeyboardOutputSubmission.Completed)
        assertTrue((text as KeyboardOutputSubmission.Completed).outcome is TextDeliveryOutcome.Delivered)
    }

    @Test
    fun builtInOverBoundTextIsRejectedBeforeAnyPhysicalOutput() = runTest {
        val connection = HoldingConnection().apply { setState(TextOutputTransportState.Connected) }
        val service = service(connection, OutputAdmissionScheduler())
        val builtIn = service.capabilityFor("desk")

        val outcome = builtIn.sendText(TextOutputRequest("a".repeat(16_385), TextOutputProfile("linux:us")))
        assertEquals(
            TextDeliveryOutcome.Rejected("desk:1", TextOutputRejectionReason.POLICY_REFUSED),
            outcome,
        )
        assertTrue(connection.sent.isEmpty())
    }

    @Test
    fun builtInPreservesMonotonicInstanceOperationIdentityThroughAdmission() = runTest {
        val connection = HoldingConnection().apply { setState(TextOutputTransportState.Connected) }
        val service = service(connection, OutputAdmissionScheduler())
        val builtIn = service.capabilityFor("desk")
        val profile = TextOutputProfile("linux:us")

        val first = builtIn.sendText(TextOutputRequest("hello", profile))
        val second = builtIn.sendText(TextOutputRequest("world", profile))
        assertEquals(TextDeliveryOutcome.Delivered("desk:1"), first)
        assertEquals(TextDeliveryOutcome.Delivered("desk:2"), second)
    }

    // --- 6.9 queued revocation is effect-not-begun and siblings are untouched --------------

    @Test
    fun adapterGenerationRevocationWhileQueuedIsEffectNotBegunAndPreservesSiblings() = runTest {
        val connection = HoldingConnection().apply {
            setState(TextOutputTransportState.Connected)
            holdFirstTextOperation = CompletableDeferred()
        }
        val scheduler = OutputAdmissionScheduler(OutputAdmissionBounds(maxQueuedPerProcess = 8))
        val service = service(connection, scheduler)
        val builtIn = service.capabilityFor("built-in")
        val revokedIdentity = CapabilityScopeIdentity("lua-victim", RuntimeGeneration(11))
        val siblingIdentity = CapabilityScopeIdentity("lua-sibling", RuntimeGeneration(12))
        val revoked = service.keyboardOutputAdapter(
            revokedIdentity,
            OutputExecutionOwner(OutputExecutionOwnerKind.MANAGED_TASK, "victim-owner"),
        )
        val sibling = service.keyboardOutputAdapter(
            siblingIdentity,
            OutputExecutionOwner(OutputExecutionOwnerKind.MANAGED_TASK, "sibling-owner"),
        )

        val holder = async { builtIn.sendText(TextOutputRequest("aaa", TextOutputProfile("linux:us"))) }
        connection.firstTextOperation.await()
        runCurrent()
        val revokedResult = async { revoked.sendText(TextOutputRequest("bbb", TextOutputProfile("linux:us"))) }
        val siblingResult = async { sibling.sendText(TextOutputRequest("ccc", TextOutputProfile("linux:us"))) }
        runCurrent()
        assertEquals(2, scheduler.queuedCount())

        scheduler.revokeScope(revokedIdentity)
        runCurrent()
        assertEquals(KeyboardOutputSubmission.Revoked, revokedResult.await())
        assertEquals(1, scheduler.queuedCount())

        checkNotNull(connection.holdFirstTextOperation).complete(Unit)
        advanceUntilIdle()
        val siblingOutcome = siblingResult.await()
        assertTrue(siblingOutcome is KeyboardOutputSubmission.Completed)
        assertTrue((siblingOutcome as KeyboardOutputSubmission.Completed).outcome is TextDeliveryOutcome.Delivered)
        assertTrue(holder.await() is TextDeliveryOutcome.Delivered)
    }

    // --- 6.12 content never enters diagnostics or outcome metadata -------------------------

    @Test
    fun admissionDiagnosticsAndOutcomeMetadataNeverCarrySubmittedContent() = runTest {
        val sink = RecordingSink()
        val connection = HoldingConnection().apply { setState(TextOutputTransportState.Connected) }
        val scheduler = OutputAdmissionScheduler(diagnostics = sink)
        val service = service(connection, scheduler)
        val secretText = "TOP-SECRET-TRANSCRIPT-xyzzy"
        val profile = TextOutputProfile("linux:us")

        val builtIn = service.capabilityFor("built-in")
        val adapter = service.keyboardOutputAdapter(
            CapabilityScopeIdentity("lua-instance", RuntimeGeneration(5)),
            OutputExecutionOwner(OutputExecutionOwnerKind.INPUT, "owner-uuid-9"),
        )

        val builtInOutcome = builtIn.sendText(TextOutputRequest(secretText, profile))
        val adapterOutcome = adapter.sendText(TextOutputRequest(secretText, profile))
        val enterOutcome = adapter.sendKey(TextKeyRequest(TextOutputKey.ENTER, profile))

        assertTrue(builtInOutcome is TextDeliveryOutcome.Delivered)
        assertTrue(adapterOutcome is KeyboardOutputSubmission.Completed)
        assertTrue(enterOutcome is KeyboardOutputSubmission.Completed)

        // Outcome operation identities are monotonic host IDs, never the submitted content.
        assertFalse(builtInOutcome.toString().contains(secretText))
        assertFalse(adapterOutcome.toString().contains(secretText))
        assertFalse(enterOutcome.toString().contains(secretText))

        // Admission diagnostics carry identity/phase/length only; never text, profile, or key.
        assertTrue(sink.diagnostics.isNotEmpty())
        for (diagnostic in sink.diagnostics) {
            val rendered = diagnostic.toString()
            assertFalse("diagnostic leaked text: $rendered", rendered.contains(secretText))
            assertFalse("diagnostic leaked profile: $rendered", rendered.contains("linux:us"))
            assertFalse("diagnostic leaked key: $rendered", rendered.contains("ENTER"))
        }
        // Identity/length metadata IS present for both the built-in (null generation) and Lua paths.
        val instances = sink.diagnostics.map { it.attribution.channelInstanceId }.toSet()
        assertTrue(instances.containsAll(setOf("built-in", "lua-instance")))
        val luaDiagnostic = sink.diagnostics.first { it.attribution.channelInstanceId == "lua-instance" }
        assertEquals(RuntimeGeneration(5), luaDiagnostic.attribution.generation)
        assertEquals(OutputExecutionOwnerKind.INPUT, luaDiagnostic.attribution.owner.kind)
        assertEquals(secretText.length.toLong(), luaDiagnostic.payloadBytes)
    }

    // --- helpers ---------------------------------------------------------------------------

    private fun kotlinx.coroutines.test.TestScope.service(
        connection: HoldingConnection,
        scheduler: OutputAdmissionScheduler,
        deliveryTimeoutMs: Long = 1_000,
    ): SleepwalkerTextOutputService = SleepwalkerTextOutputService(
        scope = backgroundScope,
        connection = connection,
        hid = LowLevelHidImpl(),
        keymapDatabase = SeedKeymapDatabase,
        connect = { _: Long -> SleepwalkerConnectionResult.Connected },
        preparationTimeoutMs = 1_000,
        deliveryTimeoutMs = deliveryTimeoutMs,
        admission = scheduler,
    )

    private class RecordingSink : OutputAdmissionDiagnosticSink {
        val diagnostics = mutableListOf<OutputAdmissionDiagnostic>()
        override fun record(diagnostic: OutputAdmissionDiagnostic) {
            diagnostics += diagnostic
        }
    }

    private class HoldingConnection : SleepwalkerBleConnection() {
        val sent = mutableListOf<LowLevelOp>()
        val firstTextOperation = CompletableDeferred<Unit>()
        var holdFirstTextOperation: CompletableDeferred<Unit>? = null
        private var sawTextOperation = false

        fun setState(state: TextOutputTransportState) {
            _connectionState.value = state
        }

        override fun disconnect() {
            _connectionState.value = TextOutputTransportState.Disconnected
        }

        override suspend fun sendOp(op: LowLevelOp) {
            val safetyOperation = op.opcode == Opcodes.ARM || op.opcode == Opcodes.KILL || op.opcode == Opcodes.DISARM
            if (!safetyOperation && !sawTextOperation) {
                sawTextOperation = true
                firstTextOperation.complete(Unit)
                sent += op
                holdFirstTextOperation?.await()
                return
            }
            sent += op
        }

        override suspend fun awaitAck(seqId: Int, timeoutMs: Long): Boolean = true
    }
}
