package io.talkcan.service

import io.talkcan.channel.capability.RuntimeGeneration
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeInvocationBoundaryTest {
    @Test
    fun yieldedActorSliceReleasesHostAdmissionAndContinuationStaysOutsideHostFifo() = runTest {
        val harness = harness(queueCapacity = 1)
        val actor = RecordingActorRuntime(harness.gate)
        val allowSecondSlice = CompletableDeferred<Unit>()
        val secondSliceEntered = CompletableDeferred<Unit>()

        val first = async {
            harness.gate.invoke(RuntimeInvocationPhase.PREPARE_INPUT) {
                actor.admit("A") {
                    actor.events += "lua:A:yielded"
                    "yielded"
                }
            }
        }
        runCurrent()

        assertEquals("yielded", assertSuccess(first.await()))
        val second = async {
            harness.gate.invoke(RuntimeInvocationPhase.HANDLE_SOS) {
                actor.admit("B") {
                    secondSliceEntered.complete(Unit)
                    allowSecondSlice.await()
                    "B completed"
                }
            }
        }
        runCurrent()
        assertTrue(secondSliceEntered.isCompleted)

        val continuation = async {
            actor.resume("A") { "A resumed" }
        }
        val queuedHostCallback = async {
            harness.gate.invoke(RuntimeInvocationPhase.READINESS_REFRESH) {
                actor.admit("C") { "C completed" }
            }
        }
        runCurrent()

        assertFalse(continuation.isCompleted)
        allowSecondSlice.complete(Unit)
        runCurrent()

        assertEquals("B completed", assertSuccess(second.await()))
        assertEquals("A resumed", assertSuccess(continuation.await()))
        assertEquals("C completed", assertSuccess(queuedHostCallback.await()))
        assertEquals(listOf("A", "B", "C"), actor.adapterAdmissions)
        assertEquals(listOf("A"), actor.continuations)
        assertEquals(1, actor.maximumConcurrentLuaEntries)
        assertEquals(
            listOf(
                "adapter:A",
                "lua:A:entered",
                "lua:A:yielded",
                "lua:A:exited",
                "adapter:B",
                "lua:B:entered",
                "lua:B:exited",
                "lua:continuation:A:entered",
                "lua:continuation:A:exited",
                "adapter:C",
                "lua:C:entered",
                "lua:C:exited",
            ),
            actor.events,
        )
        harness.close()
    }

    @Test
    fun actorMailboxOverflowMapsToBusyAndNeverExecutesAfterCapacityReturns() = runTest {
        val harness = harness(queueCapacity = 1)
        val actor = RecordingActorRuntime(harness.gate)
        val releaseFirstSlice = CompletableDeferred<Unit>()

        val first = async {
            harness.gate.invoke(RuntimeInvocationPhase.PREPARE_INPUT) {
                actor.admit("first") {
                    releaseFirstSlice.await()
                    "first completed"
                }
            }
        }
        runCurrent()
        val queued = async {
            harness.gate.invoke(RuntimeInvocationPhase.HANDLE_SOS) {
                actor.admit("queued") { "queued completed" }
            }
        }
        runCurrent()

        val overflow = harness.gate.invoke(RuntimeInvocationPhase.READINESS_REFRESH) {
            actor.admit("overflow") { "must not execute" }
        }

        assertEquals(RuntimeInvocationOutcome.Busy, overflow)
        assertEquals(listOf("first"), actor.adapterAdmissions)
        releaseFirstSlice.complete(Unit)
        runCurrent()

        assertEquals("first completed", assertSuccess(first.await()))
        assertEquals("queued completed", assertSuccess(queued.await()))
        assertEquals(listOf("first", "queued"), actor.adapterAdmissions)
        assertEquals(1, actor.maximumConcurrentLuaEntries)
        harness.close()
    }

    @Test
    fun timeoutCancellationAndRuntimeFailureRemainDistinctAndOnlyTimedGenerationRejectsLateEffects() = runTest {
        val timed = harness(instanceId = "timed", generation = 1, callbackTimeoutMillis = 10)
        val cancelled = harness(instanceId = "cancelled", generation = 2)
        val failed = harness(instanceId = "failed", generation = 3)
        val unaffected = harness(instanceId = "unaffected", generation = 4)
        val timedActor = RecordingActorRuntime(timed.gate)
        val allowTimedSliceToReturn = CompletableDeferred<Unit>()

        val timingOut = async {
            timed.gate.invoke(RuntimeInvocationPhase.HANDLE_SOS) {
                timedActor.admit("timed") {
                    withContext(NonCancellable) {
                        allowTimedSliceToReturn.await()
                        timedActor.commitEffect("late")
                    }
                    "late completion"
                }
            }
        }
        runCurrent()
        advanceTimeBy(10)
        runCurrent()

        assertEquals(RuntimeInvocationOutcome.TimedOut, timingOut.await())
        assertEquals(
            RuntimeInvocationOutcome.Cancelled,
            cancelled.gate.invoke(RuntimeInvocationPhase.HANDLE_SOS) {
                throw kotlinx.coroutines.CancellationException("caller cancelled")
            },
        )
        assertFailure(
            failed.gate.invoke(RuntimeInvocationPhase.HANDLE_SOS) {
                throw IllegalStateException("ordinary runtime failure")
            },
            RuntimeInvocationOutcome.RuntimeFailure::class.java,
            RuntimeInvocationPhase.HANDLE_SOS,
            "Runtime callback failed",
            "failed",
            RuntimeGeneration(3),
        )
        assertEquals(
            "peer remains live",
            assertSuccess(unaffected.gate.invoke(RuntimeInvocationPhase.HANDLE_SOS) { "peer remains live" }),
        )

        allowTimedSliceToReturn.complete(Unit)
        runCurrent()
        assertEquals(emptyList<String>(), timedActor.effects)
        assertEquals(RuntimeInvocationOutcome.Closed, timedActor.commitEffect("after timeout"))

        timed.close()
        cancelled.close()
        failed.close()
        unaffected.close()
    }
    @Test
    fun callbacksForOneGenerationRunFifoWithoutOverlap() = runTest {
        val harness = harness()
        val events = mutableListOf<String>()
        val firstMayFinish = CompletableDeferred<Unit>()

        val first = async {
            harness.gate.invoke(RuntimeInvocationPhase.PREPARE_INPUT) {
                events += "first-start"
                firstMayFinish.await()
                events += "first-finish"
                "first"
            }
        }
        runCurrent()
        val second = async {
            harness.gate.invoke(RuntimeInvocationPhase.HANDLE_SOS) {
                events += "second"
                "second"
            }
        }
        runCurrent()

        assertEquals(listOf("first-start"), events)
        firstMayFinish.complete(Unit)
        runCurrent()

        assertEquals("first", assertSuccess(first.await()))
        assertEquals("second", assertSuccess(second.await()))
        assertEquals(listOf("first-start", "first-finish", "second"), events)
        harness.close()
    }

    @Test
    fun separateGenerationsMakeProgressIndependently() = runTest {
        val first = harness(instanceId = "first", generation = 1)
        val second = harness(instanceId = "second", generation = 2)
        val firstMayFinish = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()

        val blocked = async {
            first.gate.invoke(RuntimeInvocationPhase.HANDLE_SOS) {
                events += "first-start"
                firstMayFinish.await()
                events += "first-finish"
            }
        }
        runCurrent()
        val independent = async {
            second.gate.invoke(RuntimeInvocationPhase.HANDLE_SOS) {
                events += "second"
                "completed independently"
            }
        }
        runCurrent()

        assertEquals("completed independently", assertSuccess(independent.await()))
        assertEquals(listOf("first-start", "second"), events)
        firstMayFinish.complete(Unit)
        runCurrent()
        assertSuccess(blocked.await())
        first.close()
        second.close()
    }

    @Test
    fun saturatedGenerationRejectsOverflowWithoutExecutingItLater() = runTest {
        val harness = harness(queueCapacity = 1)
        val firstMayFinish = CompletableDeferred<Unit>()
        var overflowExecuted = false

        val first = async {
            harness.gate.invoke(RuntimeInvocationPhase.PREPARE_INPUT) {
                firstMayFinish.await()
                "first"
            }
        }
        runCurrent()
        val queued = async {
            harness.gate.invoke(RuntimeInvocationPhase.HANDLE_SOS) { "queued" }
        }
        runCurrent()

        val overflow = harness.gate.invoke(RuntimeInvocationPhase.READINESS_REFRESH) {
            overflowExecuted = true
            "overflow"
        }

        assertEquals(RuntimeInvocationOutcome.Busy, overflow)
        assertFalse(overflowExecuted)
        firstMayFinish.complete(Unit)
        runCurrent()
        assertEquals("first", assertSuccess(first.await()))
        assertEquals("queued", assertSuccess(queued.await()))
        assertFalse(overflowExecuted)
        harness.close()
    }

    @Test
    fun callbackDeadlineCancelsWorkAndInvalidatesItsGeneration() = runTest {
        val harness = harness(callbackTimeoutMillis = 10)
        val callbackStarted = CompletableDeferred<Unit>()

        val invocation = async {
            harness.gate.invoke(RuntimeInvocationPhase.PREPARE_INPUT) {
                callbackStarted.complete(Unit)
                awaitCancellation()
            }
        }
        runCurrent()
        assertTrue(callbackStarted.isCompleted)

        advanceTimeBy(10)
        runCurrent()

        assertEquals(RuntimeInvocationOutcome.TimedOut, invocation.await())
        assertFalse(harness.gate.isLive)
        assertEquals(
            RuntimeInvocationOutcome.Closed,
            harness.gate.invoke(RuntimeInvocationPhase.HANDLE_SOS) { "must not run" },
        )
        harness.close()
    }

    @Test
    fun callerCancellationOfQueuedWorkSkipsCallback() = runTest {
        val harness = harness(queueCapacity = 1)
        val firstMayFinish = CompletableDeferred<Unit>()
        var queuedCallbackExecuted = false

        val first = async {
            harness.gate.invoke(RuntimeInvocationPhase.PREPARE_INPUT) {
                firstMayFinish.await()
            }
        }
        runCurrent()
        val cancelled = async {
            withTimeoutOrNull(1) {
                harness.gate.invoke(RuntimeInvocationPhase.HANDLE_SOS) {
                    queuedCallbackExecuted = true
                }
            }
        }
        runCurrent()
        advanceTimeBy(1)
        runCurrent()

        assertNull(cancelled.await())
        firstMayFinish.complete(Unit)
        runCurrent()
        assertSuccess(first.await())
        assertFalse(queuedCallbackExecuted)
        harness.close()
    }

    @Test
    fun callerCancellationOfExecutingWorkCancelsCallback() = runTest {
        val harness = harness()
        val callbackStarted = CompletableDeferred<Unit>()
        val callbackCancelled = CompletableDeferred<Unit>()

        val cancelled = async {
            withTimeoutOrNull(1) {
                harness.gate.invoke(RuntimeInvocationPhase.HANDLE_SOS) {
                    callbackStarted.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        callbackCancelled.complete(Unit)
                    }
                }
            }
        }
        runCurrent()
        assertTrue(callbackStarted.isCompleted)

        advanceTimeBy(1)
        runCurrent()

        assertNull(cancelled.await())
        assertTrue(callbackCancelled.isCompleted)
        harness.close()
    }

    @Test
    fun callbackCancellationIsClassifiedSeparatelyFromRuntimeFailure() = runTest {
        val harness = harness()

        val cancelled = harness.gate.invoke(RuntimeInvocationPhase.HANDLE_SOS) {
            throw kotlinx.coroutines.CancellationException("runtime stopped")
        }

        assertEquals(RuntimeInvocationOutcome.Cancelled, cancelled)
        assertEquals("still live", assertSuccess(
            harness.gate.invoke(RuntimeInvocationPhase.HANDLE_SOS) { "still live" },
        ))
        harness.close()
    }

    @Test
    fun providerAndRuntimeExceptionsAreNormalizedWithoutPoisoningQueue() = runTest {
        val harness = harness(instanceId = "channel-7", generation = 3)

        val provider = harness.gate.invoke(
            phase = RuntimeInvocationPhase.CONSTRUCT,
            origin = RuntimeInvocationOrigin.PROVIDER,
        ) {
            throw IllegalStateException("provider secret")
        }
        val runtime = harness.gate.invoke(RuntimeInvocationPhase.HANDLE_SOS) {
            throw IllegalStateException("runtime secret")
        }

        assertFailure(
            provider,
            RuntimeInvocationOutcome.ProviderFailure::class.java,
            RuntimeInvocationPhase.CONSTRUCT,
            "Provider callback failed",
            "channel-7",
            RuntimeGeneration(3),
        )
        assertFailure(
            runtime,
            RuntimeInvocationOutcome.RuntimeFailure::class.java,
            RuntimeInvocationPhase.HANDLE_SOS,
            "Runtime callback failed",
            "channel-7",
            RuntimeGeneration(3),
        )
        assertEquals("next callback", assertSuccess(
            harness.gate.invoke(RuntimeInvocationPhase.HANDLE_SOS) { "next callback" },
        ))
        harness.close()
    }

    @Test(timeout = 5_000)
    fun callbacksRunOnHostWorkerRatherThanCallingThread() {
        val workers = RuntimeWorkerDispatcher.create(
            workerCount = 1,
            queueCapacity = 1,
            threadNamePrefix = "invocation-boundary-test-worker",
        )
        val parentScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val closeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val boundary = RuntimeInvocationBoundary(workers, policy())
        val gate = boundary.openGeneration("thread-check", RuntimeGeneration(0), parentScope, closeScope)
        val callerThread = Thread.currentThread().name

        try {
            val callbackThread = runBlocking {
                assertSuccess(gate.invoke(RuntimeInvocationPhase.HANDLE_SOS) {
                    Thread.currentThread().name
                })
            }

            assertNotEquals(callerThread, callbackThread)
            assertTrue(callbackThread.startsWith("invocation-boundary-test-worker-"))
            runBlocking { assertSuccess(gate.close { }) }
        } finally {
            parentScope.cancel()
            closeScope.cancel()
            boundary.close()
        }
    }

    @Test
    fun repeatedCloseInvokesTerminalCloseOnceAndReturnsSameTerminalOutcome() = runTest {
        val harness = harness()
        val closeCalls = AtomicInteger()

        val first = harness.gate.close { closeCalls.incrementAndGet() }
        val second = harness.gate.close { closeCalls.incrementAndGet() }

        assertSuccess(first)
        assertSuccess(second)
        assertEquals(1, closeCalls.get())
    }

    @Test
    fun concurrentCloseCallersJoinOneElectedTerminalClose() = runTest {
        val harness = harness()
        val closeEntered = CompletableDeferred<Unit>()
        val allowClose = CompletableDeferred<Unit>()
        val closeCalls = AtomicInteger()

        val first = async {
            harness.gate.close {
                closeCalls.incrementAndGet()
                closeEntered.complete(Unit)
                allowClose.await()
            }
        }
        runCurrent()
        assertTrue(closeEntered.isCompleted)
        val second = async { harness.gate.close { closeCalls.incrementAndGet() } }
        runCurrent()

        assertFalse(second.isCompleted)
        allowClose.complete(Unit)
        runCurrent()

        assertSuccess(first.await())
        assertSuccess(second.await())
        assertEquals(1, closeCalls.get())
    }

    @Test
    fun closeCompletesWithinBoundWhenExecutingCallbackIgnoresCancellation() = runTest {
        val harness = harness(closeTimeoutMillis = 10)
        val callbackStarted = CompletableDeferred<Unit>()
        val allowLateCompletion = CompletableDeferred<Unit>()
        var lateEffectCount = 0

        val invocation = async {
            harness.gate.invoke(RuntimeInvocationPhase.HANDLE_SOS) {
                callbackStarted.complete(Unit)
                withContext(NonCancellable) {
                    allowLateCompletion.await()
                    harness.gate.commitIfLive { lateEffectCount += 1 }
                }
            }
        }
        runCurrent()
        assertTrue(callbackStarted.isCompleted)

        val close = async { harness.gate.close { } }
        runCurrent()
        advanceTimeBy(10)
        runCurrent()

        assertSuccess(close.await())
        assertEquals(RuntimeInvocationOutcome.Closed, invocation.await())
        allowLateCompletion.complete(Unit)
        runCurrent()
        assertEquals(0, lateEffectCount)
    }

    @Test
    fun committedInputReleaseOutlivesGenericCallbackDeadlineAndCompletesWithinItsExplicitDeadline() = runTest {
        val harness = harness(
            callbackTimeoutMillis = 5_000,
            inputReleasedTimeoutMillis = 8_000,
        )
        val target = harness.gate.openCommittedTarget()
            ?: throw AssertionError("Expected a live generation to mint a target")
        val events = mutableListOf<String>()

        harness.gate.stopAdmission()
        val release = async {
            target.invoke(RuntimeInvocationPhase.INPUT_RELEASED) {
                events += "release-started"
                delay(6_000)
                events += "release-effect-returned"
                "delivered"
            }
        }
        runCurrent()

        assertEquals(listOf("release-started"), events)
        advanceTimeBy(5_001)
        runCurrent()
        assertFalse(release.isCompleted)
        assertTrue(harness.gate.isLive)

        advanceTimeBy(999)
        runCurrent()
        assertEquals("delivered", assertSuccess(release.await()))
        assertEquals(listOf("release-started", "release-effect-returned"), events)

        target.release()
        harness.close()
    }

    @Test
    fun committedTargetCannotBeUsedByAnotherGenerationOrAfterRelease() = runTest {
        val first = harness(instanceId = "one", generation = 1)
        val second = harness(instanceId = "two", generation = 2)
        val target = first.gate.openCommittedTarget()
            ?: throw AssertionError("Expected a live generation to mint a target")

        first.gate.stopAdmission()
        assertEquals("released", assertSuccess(
            target.invoke(RuntimeInvocationPhase.INPUT_RELEASED) { "released" },
        ))
        assertEquals(
            RuntimeInvocationOutcome.Closed,
            second.gate.invokeCommittedTarget(
                target,
                RuntimeInvocationPhase.INPUT_RELEASED,
            ) { "cross generation" },
        )

        target.release()
        assertEquals(
            RuntimeInvocationOutcome.Closed,
            target.invoke(RuntimeInvocationPhase.INPUT_RELEASED) { "released target" },
        )
        first.close()
        second.close()
    }

    @Test
    fun invalidatedGenerationRejectsNewTargetsCallbacksAndCommits() = runTest {
        val harness = harness()
        var committedEffects = 0
        val existingTarget = harness.gate.openCommittedTarget()
            ?: throw AssertionError("Expected target before invalidation")

        harness.gate.invalidate()

        assertFalse(harness.gate.isLive)
        assertNull(harness.gate.openCommittedTarget())
        assertEquals(
            RuntimeInvocationOutcome.Closed,
            harness.gate.invoke(RuntimeInvocationPhase.HANDLE_SOS) { "late callback" },
        )
        assertEquals(
            RuntimeInvocationOutcome.Closed,
            existingTarget.invoke(RuntimeInvocationPhase.INPUT_RELEASED) { "late terminal" },
        )
        assertEquals(RuntimeInvocationOutcome.Closed, harness.gate.commitIfLive { committedEffects += 1 })
        assertEquals(0, committedEffects)
        harness.close()
    }

    @Test
    fun timeoutSuppressesLateCommitAndLateCallbackEffect() = runTest {
        val harness = harness(callbackTimeoutMillis = 10)
        val started = CompletableDeferred<Unit>()
        val allowLateReturn = CompletableDeferred<Unit>()
        var effects = 0

        val invocation = async {
            harness.gate.invoke(RuntimeInvocationPhase.HANDLE_SOS) {
                started.complete(Unit)
                withContext(NonCancellable) {
                    allowLateReturn.await()
                    harness.gate.commitIfLive { effects += 1 }
                    "late result"
                }
            }
        }
        runCurrent()
        assertTrue(started.isCompleted)
        advanceTimeBy(10)
        runCurrent()

        assertEquals(RuntimeInvocationOutcome.TimedOut, invocation.await())
        allowLateReturn.complete(Unit)
        runCurrent()

        assertEquals(0, effects)
        assertEquals(RuntimeInvocationOutcome.Closed, harness.gate.commitIfLive { effects += 1 })
        harness.close()
    }

    @Test
    fun terminalCloseFailureIsNormalizedAndTerminalCallbackIsNotRetried() = runTest {
        val harness = harness(instanceId = "failing-close", generation = 8)
        val closeCalls = AtomicInteger()

        val first = harness.gate.close {
            closeCalls.incrementAndGet()
            throw IllegalStateException("close secret")
        }
        val second = harness.gate.close { closeCalls.incrementAndGet() }

        assertFailure(
            first,
            RuntimeInvocationOutcome.RuntimeFailure::class.java,
            RuntimeInvocationPhase.CLOSE,
            "Runtime callback failed",
            "failing-close",
            RuntimeGeneration(8),
        )
        assertFailure(
            second,
            RuntimeInvocationOutcome.RuntimeFailure::class.java,
            RuntimeInvocationPhase.CLOSE,
            "Runtime callback failed",
            "failing-close",
            RuntimeGeneration(8),
        )
        assertEquals(1, closeCalls.get())
    }

    @Test(timeout = 5_000)
    fun continuationResumedFromInsideACommittedCallbackDoesNotSelfDeadlockTheGeneration() = runTest {
        val harness = harness()
        val target = harness.gate.openCommittedTarget()
            ?: throw AssertionError("Expected an open committed target")
        var continuationResult: RuntimeInvocationOutcome<String>? = null

        val invoke = async {
            target.invoke(RuntimeInvocationPhase.INPUT_RELEASED) {
                // Mirrors the production ECHO path: handle_input yields on playback.schedule
                // and the host resumes the yielded slice inline (executePlayback ->
                // resumeInput -> resumeProgramImageCoroutine -> invokeContinuation) while
                // the INPUT_RELEASED callback still holds the gate's execution serialization.
                continuationResult = harness.gate.invokeContinuation { "slice-resumed" }
                "input-released"
            }
        }
        runCurrent()

        assertEquals("input-released", assertSuccess(invoke.await()))
        assertEquals(
            "slice-resumed",
            assertSuccess(continuationResult ?: RuntimeInvocationOutcome.Closed),
        )
        // The generation must remain live: no timeout invalidation fired.
        assertEquals(
            "later",
            assertSuccess(harness.gate.invoke(RuntimeInvocationPhase.HANDLE_SOS) { "later" }),
        )
        harness.close()
    }

    private fun kotlinx.coroutines.test.TestScope.harness(
        instanceId: String = "channel",
        generation: Long = 0,
        queueCapacity: Int = 2,
        callbackTimeoutMillis: Long = 100,
        inputReleasedTimeoutMillis: Long = 100,
        closeTimeoutMillis: Long = 100,
    ): InvocationHarness {
        val boundary = RuntimeInvocationBoundary(
            RuntimeWorkerDispatcher.fromDispatcher(StandardTestDispatcher(testScheduler)),
            policy(queueCapacity, callbackTimeoutMillis, inputReleasedTimeoutMillis, closeTimeoutMillis),
        )
        return InvocationHarness(
            boundary = boundary,
            gate = boundary.openGeneration(instanceId, RuntimeGeneration(generation), this),
        )
    }

    private fun policy(
        queueCapacity: Int = 2,
        callbackTimeoutMillis: Long = 100,
        inputReleasedTimeoutMillis: Long = 100,
        closeTimeoutMillis: Long = 100,
    ) = RuntimeInvocationPolicy(
        perGenerationQueueCapacity = queueCapacity,
        callbackTimeoutMillis = callbackTimeoutMillis,
        inputReleasedTimeoutMillis = inputReleasedTimeoutMillis,
        closeTimeoutMillis = closeTimeoutMillis,
    )

    /** Records externally observable adapter admission, native-entry, continuation, and effect order. */
    private class RecordingActorRuntime(
        private val gate: RuntimeGenerationInvocationGate,
    ) {
        val events = mutableListOf<String>()
        val adapterAdmissions = mutableListOf<String>()
        val continuations = mutableListOf<String>()
        val effects = mutableListOf<String>()
        private val activeLuaEntries = AtomicInteger(0)
        var maximumConcurrentLuaEntries: Int = 0
            private set

        suspend fun admit(label: String, slice: suspend () -> String): String {
            adapterAdmissions += label
            events += "adapter:$label"
            return enterLua(label, slice)
        }

        suspend fun resume(label: String, slice: suspend () -> String): RuntimeInvocationOutcome<String> {
            continuations += label
            return gate.invokeContinuation {
                enterLua("continuation:$label", slice)
            }
        }

        fun commitEffect(label: String): RuntimeInvocationOutcome<Unit> = gate.commitIfLive {
            effects += label
        }

        private suspend fun enterLua(label: String, slice: suspend () -> String): String {
            val active = activeLuaEntries.incrementAndGet()
            maximumConcurrentLuaEntries = maxOf(maximumConcurrentLuaEntries, active)
            if (active != 1) {
                throw AssertionError("Native Lua entry overlapped for $label")
            }
            events += "lua:$label:entered"
            return try {
                slice()
            } finally {
                events += "lua:$label:exited"
                activeLuaEntries.decrementAndGet()
            }
        }
    }

    private class InvocationHarness(
        private val boundary: RuntimeInvocationBoundary,
        val gate: RuntimeGenerationInvocationGate,
    ) {
        suspend fun close() {
            val outcome = gate.close { }
            if (outcome !is RuntimeInvocationOutcome.Success) {
                throw AssertionError("Expected successful invocation, got $outcome")
            }
            boundary.close()
        }
    }

    private fun <T> assertSuccess(outcome: RuntimeInvocationOutcome<T>): T =
        (outcome as? RuntimeInvocationOutcome.Success<T>)?.value
            ?: throw AssertionError("Expected successful invocation, got $outcome")

    private fun assertFailure(
        outcome: RuntimeInvocationOutcome<*>,
        expectedType: Class<out RuntimeInvocationOutcome<*>>,
        expectedPhase: RuntimeInvocationPhase,
        expectedMessage: String,
        expectedInstanceId: String,
        expectedGeneration: RuntimeGeneration,
    ) {
        assertTrue("Expected $expectedType, got $outcome", expectedType.isInstance(outcome))
        val failure = when (outcome) {
            is RuntimeInvocationOutcome.ProviderFailure -> outcome.failure
            is RuntimeInvocationOutcome.RuntimeFailure -> outcome.failure
            else -> throw AssertionError("Expected normalized failure, got $outcome")
        }
        assertEquals(expectedPhase, failure.phase)
        assertEquals(expectedMessage, failure.message)
        assertEquals(expectedInstanceId, failure.instanceId)
        assertEquals(expectedGeneration, failure.generation)
    }
}
