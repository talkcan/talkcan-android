package io.talkcan.channel.capability

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OutputAdmissionSchedulerTest {

    // --- 6.6 explicit, positive, validated bounds -------------------------------------------

    @Test
    fun boundsMustBePositive() {
        val failures = listOf(
            runCatching { OutputAdmissionBounds(maxActivePerProcess = 0) },
            runCatching { OutputAdmissionBounds(maxQueuedPerInstance = 0) },
            runCatching { OutputAdmissionBounds(maxQueuedPerGeneration = 0) },
            runCatching { OutputAdmissionBounds(maxQueuedPerProcess = 0) },
            runCatching { OutputAdmissionBounds(maxInFlightPerProcess = 0) },
            runCatching { OutputAdmissionBounds(maxPayloadBytesPerInstance = 0) },
            runCatching { OutputAdmissionBounds(maxPayloadBytesPerGeneration = 0) },
            runCatching { OutputAdmissionBounds(maxPayloadBytesPerProcess = 0) },
        )
        assertTrue(failures.all { it.isFailure })
    }

    @Test
    fun defaultBoundsAlignWithHostCentralizedLimits() {
        val bounds = OutputAdmissionBounds.DEFAULT
        assertEquals(1, bounds.maxActivePerProcess)
        assertEquals(16, bounds.maxQueuedPerProcess)
        assertEquals(16_384L, bounds.maxPayloadBytesPerInstance)
        assertEquals(65_536L, bounds.maxPayloadBytesPerProcess)
    }

    @Test
    fun negativePayloadIsRejected() = runTest {
        val scheduler = OutputAdmissionScheduler()
        val result = runCatching {
            scheduler.admit(attribution("instance"), payloadBytes = -1) { "x" }
        }
        assertTrue(result.isFailure)
    }

    @Test
    fun ownerAndAttributionRejectBlankIdentity() {
        assertTrue(runCatching { OutputExecutionOwner(OutputExecutionOwnerKind.INPUT, " ") }.isFailure)
        assertTrue(
            runCatching {
                OutputOperationAttribution("", RuntimeGeneration(0), OutputExecutionOwner(OutputExecutionOwnerKind.INPUT, "o"))
            }.isFailure,
        )
    }

    // --- 6.11 deterministic FIFO ordering ---------------------------------------------------

    @Test
    fun singleOperationIsAdmittedAndReturnsEffectValue() = runTest {
        val scheduler = OutputAdmissionScheduler()
        val result = scheduler.admit(attribution("solo"), payloadBytes = 4) { "value" }
        assertEquals(AdmissionResult.Admitted("value"), result)
        assertEquals(0, scheduler.activeCount())
        assertEquals(0, scheduler.queuedCount())
    }

    @Test
    fun admittedOperationsRunInDeterministicGlobalFifoOrderAcrossInstances() = runTest {
        val scheduler = OutputAdmissionScheduler()
        val order = mutableListOf<String>()
        val (gateA, jobA) = holdActive(scheduler, attribution("instance-1", generation = 1), onEffect = { order.add("A") })
        runCurrent()
        val jobB = async { scheduler.admit(attribution("instance-2", generation = 2), 1) { order.add("B"); "B" } }
        val jobC = async { scheduler.admit(attribution("instance-3", generation = 3), 1) { order.add("C"); "C" } }
        runCurrent()
        assertEquals(listOf("A"), order)

        gateA.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("A", "B", "C"), order)
        assertEquals(AdmissionResult.Admitted("done"), jobA.await())
        assertEquals(AdmissionResult.Admitted("B"), jobB.await())
        assertEquals(AdmissionResult.Admitted("C"), jobC.await())
        assertEquals(0, scheduler.activeCount())
        assertEquals(0, scheduler.queuedCount())
    }

    // --- 6.11 bounded capacity before effect ------------------------------------------------

    @Test
    fun processQueueCapacityReturnsBusyBeforeEffectAndDrainsInOrder() = runTest {
        val scheduler = OutputAdmissionScheduler(OutputAdmissionBounds(maxQueuedPerProcess = 2))
        val (gateA, jobA) = holdActive(scheduler, attribution("a", generation = 1))
        runCurrent()
        val jobB = async { scheduler.admit(attribution("b", generation = 2), 1) { "B" } }
        val jobC = async { scheduler.admit(attribution("c", generation = 3), 1) { "C" } }
        runCurrent()
        assertEquals(2, scheduler.queuedCount())

        val busy = scheduler.admit(attribution("d", generation = 4), 1) { "D" }
        assertSame(AdmissionResult.Busy, busy)

        gateA.complete(Unit)
        advanceUntilIdle()
        assertEquals(AdmissionResult.Admitted("B"), jobB.await())
        assertEquals(AdmissionResult.Admitted("C"), jobC.await())
        assertEquals(0, scheduler.queuedCount())
        assertEquals(0, scheduler.activeCount())
        jobA.await()
    }

    @Test
    fun inFlightWaiterCeilingReturnsBusy() = runTest {
        val scheduler = OutputAdmissionScheduler(
            OutputAdmissionBounds(maxInFlightPerProcess = 2, maxQueuedPerProcess = 8),
        )
        val (gateA, jobA) = holdActive(scheduler, attribution("a", generation = 1))
        runCurrent()
        val jobB = async { scheduler.admit(attribution("b", generation = 2), 1) { "B" } }
        runCurrent()
        // active(A) + queued(B) = 2 in flight; the third hits the ceiling.
        assertSame(AdmissionResult.Busy, scheduler.admit(attribution("c", generation = 3), 1) { "C" })
        gateA.complete(Unit)
        advanceUntilIdle()
        jobA.await(); jobB.await()
    }

    @Test
    fun oversizedSingleOperationIsRejectedPermanentlyNotQueued() = runTest {
        val scheduler = OutputAdmissionScheduler(OutputAdmissionBounds(maxPayloadBytesPerInstance = 10))
        var ran = false
        val result = scheduler.admit(attribution("big", generation = 1), payloadBytes = 11) { ran = true; "x" }
        assertSame(AdmissionResult.Rejected, result)
        assertFalse(ran)
        assertEquals(0, scheduler.queuedCount())
    }

    @Test
    fun accumulatedPayloadBeyondBoundIsBusyUntilSpaceFrees() = runTest {
        val scheduler = OutputAdmissionScheduler(
            OutputAdmissionBounds(maxPayloadBytesPerProcess = 10, maxQueuedPerProcess = 8),
        )
        val (gateA, jobA) = holdActive(scheduler, attribution("a", generation = 1), payloadBytes = 6)
        runCurrent()
        val jobB = async { scheduler.admit(attribution("b", generation = 2), payloadBytes = 4) { "B" } }
        runCurrent()
        // 6 (active) + 4 (queued) = 10 reserved; one more byte cannot fit.
        assertSame(AdmissionResult.Busy, scheduler.admit(attribution("c", generation = 3), payloadBytes = 1) { "C" })

        gateA.complete(Unit)
        advanceUntilIdle()
        jobA.await(); jobB.await()
        // A's 6 bytes released; 5 more now fit alongside nothing else reserved.
        assertEquals(AdmissionResult.Admitted("D"), scheduler.admit(attribution("d", generation = 4), payloadBytes = 5) { "D" })
    }

    // --- 6.11 fairness / cross-instance isolation ------------------------------------------

    @Test
    fun oneProducerCannotExceedItsPerInstanceQueueEvenWithProcessRoom() = runTest {
        val scheduler = OutputAdmissionScheduler(
            OutputAdmissionBounds(maxQueuedPerInstance = 2, maxQueuedPerProcess = 8),
        )
        val (gateA, jobA) = holdActive(scheduler, attribution("flood", generation = 1))
        runCurrent()
        val jobB = async { scheduler.admit(attribution("flood", generation = 1), 1) { "B" } }
        val jobC = async { scheduler.admit(attribution("flood", generation = 1), 1) { "C" } }
        runCurrent()
        assertSame(AdmissionResult.Busy, scheduler.admit(attribution("flood", generation = 1), 1) { "D" })
        gateA.complete(Unit)
        advanceUntilIdle()
        jobA.await(); jobB.await(); jobC.await()
    }

    @Test
    fun perInstanceQueueFullDoesNotBlockASiblingInstance() = runTest {
        val scheduler = OutputAdmissionScheduler(
            OutputAdmissionBounds(maxQueuedPerInstance = 1, maxQueuedPerProcess = 8),
        )
        val (gateA, jobA) = holdActive(scheduler, attribution("a", generation = 1))
        runCurrent()
        val jobB = async { scheduler.admit(attribution("a", generation = 1), 1) { "B" } }
        runCurrent()
        // instance "a" queue is full (1); instance "b" still has room.
        assertSame(AdmissionResult.Busy, scheduler.admit(attribution("a", generation = 1), 1) { "C" })
        val jobD = async { scheduler.admit(attribution("b", generation = 2), 1) { "D" } }
        runCurrent()
        // instance "b" is admitted and queues; the sibling instance is not blocked by instance "a".
        assertEquals(2, scheduler.queuedCount())
        gateA.complete(Unit)
        advanceUntilIdle()
        assertEquals(AdmissionResult.Admitted("D"), jobD.await())
        jobA.await(); jobB.await()
    }

    @Test
    fun perGenerationQueueFullDoesNotBlockASiblingGeneration() = runTest {
        val scheduler = OutputAdmissionScheduler(
            OutputAdmissionBounds(maxQueuedPerGeneration = 1, maxQueuedPerProcess = 8),
        )
        val (gateA, jobA) = holdActive(scheduler, attribution("a", generation = 7))
        runCurrent()
        val jobB = async { scheduler.admit(attribution("b", generation = 7), 1) { "B" } }
        runCurrent()
        assertSame(AdmissionResult.Busy, scheduler.admit(attribution("c", generation = 7), 1) { "C" })
        val jobD = async { scheduler.admit(attribution("d", generation = 8), 1) { "D" } }
        runCurrent()
        // generation 8 is admitted and queues; the sibling generation is not blocked by generation 7.
        assertEquals(2, scheduler.queuedCount())
        gateA.complete(Unit)
        advanceUntilIdle()
        assertEquals(AdmissionResult.Admitted("D"), jobD.await())
        jobA.await(); jobB.await()
    }

    // --- 6.11 / 6.9 queued revocation is effect-not-begun ----------------------------------

    @Test
    fun generationRevocationRemovesQueuedOperationsWithoutEffectAndPreservesSiblings() = runTest {
        val scheduler = OutputAdmissionScheduler(OutputAdmissionBounds(maxQueuedPerProcess = 8))
        val effects = mutableListOf<String>()
        val (gateA, jobA) = holdActive(scheduler, attribution("holder", generation = 100), onEffect = { effects.add("holder") })
        runCurrent()
        val jobRevoked = async { scheduler.admit(attribution("victim", generation = 101), 1) { effects.add("victim"); "V" } }
        val jobSibling = async { scheduler.admit(attribution("sibling", generation = 102), 1) { effects.add("sibling"); "S" } }
        runCurrent()
        assertEquals(2, scheduler.queuedCount())

        scheduler.revokeGeneration(RuntimeGeneration(101))
        runCurrent()
        assertEquals(AdmissionResult.Revoked, jobRevoked.await())
        assertFalse(effects.contains("victim"))
        assertEquals(1, scheduler.queuedCount())

        gateA.complete(Unit)
        advanceUntilIdle()
        assertEquals(AdmissionResult.Admitted("S"), jobSibling.await())
        assertTrue(effects.containsAll(listOf("holder", "sibling")))
        jobA.await()
    }

    @Test
    fun scopeRevocationMatchesInstanceAndGenerationOnly() = runTest {
        val scheduler = OutputAdmissionScheduler(OutputAdmissionBounds(maxQueuedPerProcess = 8))
        val (gateA, jobA) = holdActive(scheduler, attribution("holder", generation = 1))
        runCurrent()
        val target = CapabilityScopeIdentity("instance-x", RuntimeGeneration(5))
        val jobRevoked = async { scheduler.admit(attribution("instance-x", generation = 5), 1) { "V" } }
        val sameInstanceOtherGen = async { scheduler.admit(attribution("instance-x", generation = 6), 1) { "Keep1" } }
        val otherInstanceSameGen = async { scheduler.admit(attribution("instance-y", generation = 5), 1) { "Keep2" } }
        runCurrent()

        scheduler.revokeScope(target)
        runCurrent()
        assertEquals(AdmissionResult.Revoked, jobRevoked.await())

        gateA.complete(Unit)
        advanceUntilIdle()
        assertEquals(AdmissionResult.Admitted("Keep1"), sameInstanceOtherGen.await())
        assertEquals(AdmissionResult.Admitted("Keep2"), otherInstanceSameGen.await())
        jobA.await()
    }

    @Test
    fun revokingAnAlreadyTerminalEntryIsAnIdempotentNoOp() = runTest {
        val scheduler = OutputAdmissionScheduler()
        val (gateA, jobA) = holdActive(scheduler, attribution("holder", generation = 1))
        runCurrent()
        val jobB = async { scheduler.admit(attribution("victim", generation = 9), 1) { "B" } }
        runCurrent()
        scheduler.revokeGeneration(RuntimeGeneration(9))
        runCurrent()
        assertEquals(AdmissionResult.Revoked, jobB.await())
        // Second revocation finds nothing; counts stay consistent.
        scheduler.revokeGeneration(RuntimeGeneration(9))
        gateA.complete(Unit)
        advanceUntilIdle()
        assertEquals(0, scheduler.queuedCount())
        assertEquals(0, scheduler.activeCount())
        jobA.await()
    }

    // --- 6.11 cancellation / timeout while queued is effect-not-begun ----------------------

    @Test
    fun callerCancellationWhileQueuedSkipsEffectAndReleasesTheSlot() = runTest {
        val scheduler = OutputAdmissionScheduler(OutputAdmissionBounds(maxQueuedPerProcess = 8))
        var victimRan = false
        val (gateA, jobA) = holdActive(scheduler, attribution("holder", generation = 1))
        runCurrent()
        val victim = async { scheduler.admit(attribution("victim", generation = 2), 1) { victimRan = true; "V" } }
        runCurrent()
        assertEquals(1, scheduler.queuedCount())

        victim.cancelAndJoin()
        runCurrent()
        assertFalse(victimRan)
        assertEquals(0, scheduler.queuedCount())

        // The freed slot admits a successor that runs once the active holder completes.
        val successor = async { scheduler.admit(attribution("next", generation = 3), 1) { "N" } }
        runCurrent()
        gateA.complete(Unit)
        advanceUntilIdle()
        assertEquals(AdmissionResult.Admitted("N"), successor.await())
        jobA.await()
    }

    @Test
    fun callerTimeoutWhileQueuedIsEffectNotBegun() = runTest {
        val scheduler = OutputAdmissionScheduler(OutputAdmissionBounds(maxQueuedPerProcess = 8))
        var timedOutRan = false
        val (gateA, jobA) = holdActive(scheduler, attribution("holder", generation = 1))
        runCurrent()
        val deferred = async {
            runCatching {
                withTimeout(50) {
                    scheduler.admit(attribution("slow", generation = 2), 1) { timedOutRan = true; "S" }
                }
            }
        }
        runCurrent()
        assertEquals(1, scheduler.queuedCount())
        advanceTimeBy(60)
        runCurrent()
        val result = deferred.await()
        assertTrue(result.isFailure)
        assertFalse(timedOutRan)
        assertEquals(0, scheduler.queuedCount())
        gateA.complete(Unit)
        advanceUntilIdle()
        jobA.await()
    }

    @Test
    fun activeEffectCancellationReleasesTheSlotForSiblings() = runTest {
        val scheduler = OutputAdmissionScheduler()
        val gate = CompletableDeferred<Unit>()
        val active = async {
            scheduler.admit(attribution("active", generation = 1), 1) { gate.await(); "A" }
        }
        runCurrent()
        assertEquals(1, scheduler.activeCount())

        active.cancelAndJoin()
        runCurrent()
        assertEquals(0, scheduler.activeCount())

        assertEquals(AdmissionResult.Admitted("B"), scheduler.admit(attribution("next", generation = 2), 1) { "B" })
    }

    // --- 6.11 shutdown / terminal races ----------------------------------------------------

    @Test
    fun shutdownTerminalizesQueuedAsClosedLeavesActiveAndRefusesNewAdmission() = runTest {
        val scheduler = OutputAdmissionScheduler(OutputAdmissionBounds(maxQueuedPerProcess = 8))
        var queuedRan = false
        val (gateA, jobA) = holdActive(scheduler, attribution("holder", generation = 1))
        runCurrent()
        val queued = async { scheduler.admit(attribution("queued", generation = 2), 1) { queuedRan = true; "Q" } }
        runCurrent()

        scheduler.shutdown()
        runCurrent()
        assertEquals(AdmissionResult.Closed, queued.await())
        assertFalse(queuedRan)

        assertSame(AdmissionResult.Closed, scheduler.admit(attribution("late", generation = 3), 1) { "L" })

        gateA.complete(Unit)
        advanceUntilIdle()
        // The active operation still reaches its own terminal outcome.
        assertEquals(AdmissionResult.Admitted("done"), jobA.await())
        // Idempotent shutdown.
        scheduler.shutdown()
    }

    @Test
    fun terminalCompletionDrainsExactlyOnceAndLeavesCountsClean() = runTest {
        val scheduler = OutputAdmissionScheduler()
        val (gateA, jobA) = holdActive(scheduler, attribution("a", generation = 1))
        runCurrent()
        val jobB = async { scheduler.admit(attribution("b", generation = 2), 1) { "B" } }
        val jobC = async { scheduler.admit(attribution("c", generation = 3), 1) { "C" } }
        runCurrent()

        gateA.complete(Unit)
        advanceUntilIdle()
        assertEquals(AdmissionResult.Admitted("done"), jobA.await())
        assertEquals(AdmissionResult.Admitted("B"), jobB.await())
        assertEquals(AdmissionResult.Admitted("C"), jobC.await())
        assertEquals(0, scheduler.activeCount())
        assertEquals(0, scheduler.queuedCount())

        // A further operation still admits after the drain — nothing leaked.
        assertEquals(AdmissionResult.Admitted("D"), scheduler.admit(attribution("d", generation = 4), 1) { "D" })
    }

    // --- 6.12 content never enters admission diagnostics -----------------------------------

    @Test
    fun diagnosticsCarryAttributionPhaseLengthAndSequenceButNeverSubmittedContent() = runTest {
        val sink = RecordingSink()
        val scheduler = OutputAdmissionScheduler(diagnostics = sink)
        val secretText = "TOP-SECRET-TRANSCRIPT-xyzzy"
        val secretProfile = "linux:secret-layout"
        val owner = OutputExecutionOwner(OutputExecutionOwnerKind.MANAGED_TASK, "owner-uuid-123")
        val attribution = OutputOperationAttribution("instance-1", RuntimeGeneration(3), owner)

        val result = scheduler.admit(attribution, payloadBytes = secretText.length.toLong()) {
            // The physical effect "knows" the content; none of it may reach diagnostics.
            "$secretText via $secretProfile"
        }
        assertTrue(result is AdmissionResult.Admitted)
        assertTrue(sink.diagnostics.isNotEmpty())

        for (diagnostic in sink.diagnostics) {
            val rendered = diagnostic.toString()
            assertFalse("diagnostic leaked text: $rendered", rendered.contains(secretText))
            assertFalse("diagnostic leaked profile: $rendered", rendered.contains(secretProfile))
            // Identity/length/phase metadata IS present and bounded.
            assertEquals("instance-1", diagnostic.attribution.channelInstanceId)
            assertEquals(RuntimeGeneration(3), diagnostic.attribution.generation)
            assertEquals(OutputExecutionOwnerKind.MANAGED_TASK, diagnostic.attribution.owner.kind)
            assertEquals("owner-uuid-123", diagnostic.attribution.owner.opaqueId)
            assertEquals(secretText.length.toLong(), diagnostic.payloadBytes)
        }
        val phases = sink.diagnostics.map { it.phase }.toSet()
        assertTrue(phases.contains(OutputAdmissionPhase.QUEUED))
        assertTrue(phases.contains(OutputAdmissionPhase.TERMINAL))
    }

    @Test
    fun busyAndRejectedDiagnosticsRemainContentFree() = runTest {
        val sink = RecordingSink()
        val scheduler = OutputAdmissionScheduler(
            OutputAdmissionBounds(maxPayloadBytesPerInstance = 4, maxQueuedPerProcess = 8),
            diagnostics = sink,
        )
        val secret = "SECRET-PAYLOAD"
        assertSame(AdmissionResult.Rejected, scheduler.admit(attribution("x", generation = 1), payloadBytes = 999) { secret })
        for (diagnostic in sink.diagnostics) {
            assertFalse(diagnostic.toString().contains(secret))
        }
        assertTrue(sink.diagnostics.any { it.phase == OutputAdmissionPhase.REJECTED })
    }

    // --- helpers ---------------------------------------------------------------------------

    private fun attribution(
        instance: String,
        generation: Long? = null,
        kind: OutputExecutionOwnerKind = OutputExecutionOwnerKind.MANAGED_TASK,
        owner: String = "owner-$instance",
    ): OutputOperationAttribution = OutputOperationAttribution(
        channelInstanceId = instance,
        generation = generation?.let(::RuntimeGeneration),
        owner = OutputExecutionOwner(kind, owner),
    )

    private fun CoroutineScope.holdActive(
        scheduler: OutputAdmissionScheduler,
        attribution: OutputOperationAttribution,
        payloadBytes: Long = 1,
        onEffect: () -> Unit = {},
    ): Pair<CompletableDeferred<Unit>, Deferred<AdmissionResult<String>>> {
        val gate = CompletableDeferred<Unit>()
        val job = async {
            scheduler.admit(attribution, payloadBytes) {
                onEffect()
                gate.await()
                "done"
            }
        }
        return gate to job
    }

    private class RecordingSink : OutputAdmissionDiagnosticSink {
        val diagnostics = mutableListOf<OutputAdmissionDiagnostic>()
        override fun record(diagnostic: OutputAdmissionDiagnostic) {
            diagnostics += diagnostic
        }
    }
}
