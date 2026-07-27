package io.talkcan.model

import io.talkcan.channel.capability.CapabilityAcquisition
import io.talkcan.channel.capability.CapabilityAcquisitionPolicy
import io.talkcan.channel.capability.CapabilityAvailability
import io.talkcan.channel.capability.CapabilityAvailabilityResult
import io.talkcan.channel.capability.CapabilityKey
import io.talkcan.channel.capability.CapabilityScopeIdentity
import io.talkcan.channel.capability.ChannelCapability
import io.talkcan.channel.capability.ChannelCapabilityHost
import io.talkcan.channel.capability.ChannelCapabilityPort
import io.talkcan.channel.capability.ChannelCapabilityScope
import io.talkcan.channel.capability.HostedCapabilityAcquisition
import io.talkcan.channel.capability.OpaqueAudioRecording
import io.talkcan.channel.capability.RuntimeGeneration
import io.talkcan.channel.capability.RevocableChannelCapabilityScope
import io.talkcan.audio.ChannelInputAcceptance
import io.talkcan.service.RuntimeGenerationInvocationGate
import io.talkcan.service.RuntimeInvocationBoundary
import io.talkcan.service.RuntimeInvocationPhase
import io.talkcan.service.RuntimeInvocationPolicy
import io.talkcan.service.RuntimeInvocationOutcome
import io.talkcan.service.RuntimeWorkerDispatcher
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GenerationExecutionContextImplTest {
    @Test
    fun `same provider contexts retain instance identity and closing one leaves its sibling live`() = runTest {
        val first = context("first-instance", generation = 3)
        val second = context("second-instance", generation = 3)
        try {
            assertEquals("first-instance", first.context.instanceId)
            assertEquals("second-instance", second.context.instanceId)
            assertNotSame(first.context, second.context)
            first.context.authorizeStagedTasksAfterReady()
            second.context.authorizeStagedTasksAfterReady()

            val firstCallbacks = AtomicInteger()
            val secondCallbacks = AtomicInteger()
            accepted(first.context.scheduleTimer(100.0) { firstCallbacks.incrementAndGet() })
            accepted(second.context.scheduleTimer(0.0) { secondCallbacks.incrementAndGet() })

            first.context.closeAndDrain()
            runCurrent()

            assertEquals("retiring one instance must suppress only its own timer", 0, firstCallbacks.get())
            assertEquals("a same-provider sibling must retain its timer authority", 1, secondCallbacks.get())
            assertFalse(first.context.isActive())
            assertTrue(second.context.isActive())
        } finally {
            first.close()
            second.close()
        }
    }

    @Test
    fun `timer and task saturation are distinct from post-close rejection`() = runTest {
        val timers = context("timer-capacity")
        val tasks = context("task-capacity")
        try {
            val timerRejection = firstRejection {
                timers.context.scheduleTimer(60_000.0) { }
            }
            assertEquals(GenerationAdmissionRejection.CAPACITY_EXHAUSTED, timerRejection.reason)
            timers.context.closeAndDrain()
            assertEquals(
                GenerationAdmissionRejection.CLOSED,
                rejected(timers.context.scheduleTimer(0.0) { }).reason,
            )

            val taskRejection = firstRejection {
                tasks.context.admitTask { }
            }
            assertEquals(GenerationAdmissionRejection.CAPACITY_EXHAUSTED, taskRejection.reason)
            tasks.context.closeAndDrain()
            assertEquals(
                GenerationAdmissionRejection.CLOSED,
                rejected(tasks.context.admitTask { }).reason,
            )
        } finally {
            timers.close()
            tasks.close()
        }
    }

    @Test
    fun `staged tasks reserve capacity without execution then authorize or discard deterministically`() = runTest {
        val authorized = context("authorized")
        val discarded = context("discarded")
        try {
            val authorizedRuns = AtomicInteger()
            accepted(authorized.context.admitTask { authorizedRuns.incrementAndGet() })
            runCurrent()
            assertEquals("a staged task must not run before readiness publication", 0, authorizedRuns.get())

            val authorizedJobs = authorized.context.authorizeStagedTasksAfterReady()
            runCurrent()
            assertEquals("authorized staged work remains inert until the registry starts its returned jobs", 0, authorizedRuns.get())
            authorizedJobs.forEach { it.start() }
            runCurrent()
            assertEquals("starting the authorized jobs after readiness must make the staged task runnable", 1, authorizedRuns.get())

            val discardedRuns = AtomicInteger()
            accepted(discarded.context.admitTask { discardedRuns.incrementAndGet() })
            discarded.context.discardStagedTasks()
            val discardedJobs = discarded.context.authorizeStagedTasksAfterReady()
            assertTrue("discard must remove every staged job before authorization", discardedJobs.isEmpty())
            runCurrent()
            assertEquals("discarded staged tasks must never execute", 0, discardedRuns.get())
        } finally {
            authorized.close()
            discarded.close()
        }
    }

    @Test
    fun `active task admission does not preempt its current invocation slice`() = runTest {
        val harness = context("non-preemptive")
        try {
            harness.context.authorizeStagedTasksAfterReady()
            val taskRan = AtomicInteger()

            val outcome = harness.gate.invoke(RuntimeInvocationPhase.HANDLE_SOS) {
                accepted(harness.context.admitTask { taskRan.incrementAndGet() })
                assertEquals("admitted work must not run inside the admitting invocation", 0, taskRan.get())
            }

            assertTrue("the admitting invocation must complete normally", outcome is RuntimeInvocationOutcome.Success)
            runCurrent()
            assertEquals("admitted work must run after the invocation slice", 1, taskRan.get())
        } finally {
            harness.close()
        }
    }

    @Test
    fun `timers fire once and explicit disposal prevents their callback`() = runTest {
        val harness = context("timers")
        try {
            harness.context.authorizeStagedTasksAfterReady()
            val delivered = AtomicInteger()
            val deliveredHandle = accepted(harness.context.scheduleTimer(10.0) { delivered.incrementAndGet() })
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals("an accepted timer must invoke its callback once", 1, delivered.get())

            deliveredHandle.dispose()
            deliveredHandle.dispose()
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals("a one-shot timer must not invoke again after disposal", 1, delivered.get())

            val disposed = AtomicInteger()
            val disposedHandle = accepted(harness.context.scheduleTimer(10.0) { disposed.incrementAndGet() })
            disposedHandle.dispose()
            disposedHandle.dispose()
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals("idempotent disposal must suppress a pending timer callback", 0, disposed.get())
        } finally {
            harness.close()
        }
    }

    @Test
    fun `close drains active work and permanently suppresses stale callbacks and admissions`() = runTest {
        val harness = context("closing")
        try {
            harness.context.authorizeStagedTasksAfterReady()
            val taskStarted = CompletableDeferred<Unit>()
            val taskFinallyRan = CompletableDeferred<Unit>()
            val staleTimerCallbacks = AtomicInteger()
            accepted(harness.context.admitTask {
                taskStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    taskFinallyRan.complete(Unit)
                }
            })
            accepted(harness.context.scheduleTimer(60.0) { staleTimerCallbacks.incrementAndGet() })
            runCurrent()
            assertTrue(taskStarted.isCompleted)

            harness.context.closeAndDrain()
            harness.context.closeAndDrain()
            assertTrue("closeAndDrain must await cancellation of active task work", taskFinallyRan.isCompleted)
            assertFalse(harness.context.isActive())
            advanceTimeBy(60_000)
            runCurrent()

            assertEquals("a timer accepted before close must not enter a retired generation", 0, staleTimerCallbacks.get())
            assertEquals(
                GenerationAdmissionRejection.CLOSED,
                rejected(harness.context.scheduleTimer(0.0) { }).reason,
            )
            assertEquals(
                GenerationAdmissionRejection.CLOSED,
                rejected(harness.context.admitTask { }).reason,
            )
        } finally {
            harness.close()
        }
    }

    private fun TestScope.context(
        instanceId: String,
        generation: Long = 0,
    ): ContextHarness {
        val boundary = RuntimeInvocationBoundary(
            RuntimeWorkerDispatcher.fromDispatcher(StandardTestDispatcher(testScheduler)),
            RuntimeInvocationPolicy(
                perGenerationQueueCapacity = 16,
                callbackTimeoutMillis = 1_000,
                inputReleasedTimeoutMillis = 1_000,
                closeTimeoutMillis = 1_000,
            ),
        )
        val gate = boundary.openGeneration(instanceId, RuntimeGeneration(generation), this)
        return ContextHarness(
            boundary = boundary,
            gate = gate,
            context = GenerationExecutionContextImpl(instanceId, gate, this),
        )
    }

    private fun firstRejection(
        admission: () -> GenerationAdmission<*>,
    ): GenerationAdmission.Rejected {
        repeat(512) {
            val result = admission()
            if (result is GenerationAdmission.Rejected) return result
        }
        throw AssertionError("Expected bounded admission to reject before the safety limit")
    }

    private fun <T> accepted(admission: GenerationAdmission<T>): T =
        (admission as? GenerationAdmission.Accepted<T>)?.value
            ?: throw AssertionError("Expected accepted admission, got $admission")

    private fun rejected(admission: GenerationAdmission<*>): GenerationAdmission.Rejected =
        admission as? GenerationAdmission.Rejected
            ?: throw AssertionError("Expected rejected admission, got $admission")

    private fun opaque(value: String): OpaqueJsonObject = OpaqueJsonObject.parse(value).getOrThrow()

    private class ContextHarness(
        private val boundary: RuntimeInvocationBoundary,
        val gate: RuntimeGenerationInvocationGate,
        val context: GenerationExecutionContextImpl,
    ) {
        suspend fun close() {
            context.closeAndDrain()
            gate.close { }
            boundary.close()
        }
    }
}

