package io.talkcan.lua.actor

import io.talkcan.channel.capability.CapabilityScopeIdentity
import io.talkcan.channel.capability.RuntimeGeneration
import io.talkcan.model.ChannelImplementationId
import io.talkcan.service.ChannelExecutionStatus
import io.talkcan.service.ChannelPreparationAvailability
import io.talkcan.service.ChannelPreparationReason
import io.talkcan.lua.LuaCoroutineId
import io.talkcan.lua.LuaKernelBridge
import io.talkcan.lua.LuaKernelConfig
import io.talkcan.lua.LuaKernelOutcome
import io.talkcan.lua.LuaOperationHandle
import io.talkcan.lua.LuaOperationId
import io.talkcan.lua.LuaStateGeneration
import io.talkcan.lua.LuaStateId
import io.talkcan.lua.LuaStateHandle
import io.talkcan.lua.LuaCallbackHandle
import io.talkcan.lua.LuaValue
import io.talkcan.lua.LuaSpawnAdmission
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.withLock
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
@OptIn(ExperimentalCoroutinesApi::class)
class ActorRuntimeContractTest {
    @Test
    fun `stale foreign and closed terminal requests never re-enter the kernel`() = runTest {
        val actorScope = scope("current", 7)
        val bridge = RecordingKernelBridge()
        val actor = actor(actorScope, bridge, RecordingGate(), this)
        stageReady(actor)

        try {
            val currentTask = ActorTaskIdentity(actorScope, ActorTaskId.next())
            val current = actor.yieldOperation(currentTask, kernelHandle(1L))
            val foreignScope = scope("foreign", 8)
            val foreign = ActorOperationIdentity(
                scope = foreignScope,
                taskId = ActorTaskIdentity(foreignScope, currentTask.taskId),
                operationId = current.operationId,
            )
            val stale = current.copy(operationId = ActorOperationId.next())

            assertEquals(
                ActorOperationOutcome.InvalidOwner,
                actor.resumeOperation(foreign, ActorTerminal.Completed(foreign, "foreign")),
            )
            assertEquals(
                ActorOperationOutcome.Stale,
                actor.cancelOperation(stale),
            )
            assertEquals(0, bridge.resumeCalls.get())

            assertEquals(ActorCloseResult.Closed, actor.close())
            assertEquals(
                "A token closed with its actor must reject a late terminal request as closed.",
                ActorOperationOutcome.Closed,
                actor.cancelOperation(current),
            )
            assertEquals(
                "Foreign, stale, and closed terminal requests must not produce a kernel resume.",
                0,
                bridge.resumeCalls.get(),
            )
        } finally {
            actor.close()
        }
    }

    @Test
    fun `bounded mailbox starts admitted events FIFO and never schedules busy overflow`() = runTest {
        val actorScope = scope("mailbox", 1)
        val mailbox = ActorMailbox(capacity = 2)
        val scheduler = ActorScheduler()
        val effects = mutableListOf<String>()
        val first = event(actorScope, "first")
        val second = event(actorScope, "second")
        val overflow = event(actorScope, "overflow")

        mailbox.open()
        assertEquals(ActorMailboxResult.Admitted, mailbox.admit(first))
        assertEquals(ActorMailboxResult.Admitted, mailbox.admit(second))
        assertEquals(ActorMailboxResult.Busy, mailbox.admit(overflow))

        while (true) {
            val next = mailbox.poll() ?: break
            scheduler.ready(
                ActorCoroutine(
                    ownerTask = ActorTaskIdentity(actorScope, ActorTaskId.next()),
                    owningOperation = null,
                ) {
                    effects += ((next as ActorEventEnvelope.Input).payload as ActorInputPayload.Prepare).inputId
                    ActorCoroutineResult.Completed(next.kind.name)
                },
            )
        }
        assertEquals(0, mailbox.size)

        runOneSlice(scheduler)
        runOneSlice(scheduler)

        assertEquals(listOf("first", "second"), effects)
        assertFalse(effects.contains("overflow"))
        assertNull(scheduler.tryEnter())
    }


    @Test
    fun `completion queued while another coroutine is entered runs only after release`() = runTest {
        val actorScope = scope("single-entry", 4)
        val scheduler = ActorScheduler()
        val entered = CompletableDeferred<Unit>()
        val mayYield = CompletableDeferred<Unit>()
        val effects = mutableListOf<String>()
        val resumed = ActorCoroutine(
            ownerTask = ActorTaskIdentity(actorScope, ActorTaskId.next()),
            owningOperation = operation(actorScope),
        ) {
            effects += "A-resumed"
            ActorCoroutineResult.Completed("A")
        }
        val running = ActorCoroutine(
            ownerTask = ActorTaskIdentity(actorScope, ActorTaskId.next()),
            owningOperation = null,
        ) {
            effects += "B-entered"
            entered.complete(Unit)
            mayYield.await()
            effects += "B-yielded"
            ActorCoroutineResult.Completed("B")
        }

        scheduler.ready(running)
        val runningSlice = async {
            val coroutine = scheduler.tryEnter() ?: throw AssertionError("Expected B to enter")
            coroutine.slice(coroutine)
        }
        runCurrent()
        assertTrue(entered.isCompleted)

        scheduler.ready(resumed)
        assertNull(
            "A completion must wait in the ready queue while B is entered.",
            scheduler.tryEnter(),
        )

        mayYield.complete(Unit)
        runCurrent()
        assertEquals(ActorCoroutineResult.Completed("B"), runningSlice.await())
        scheduler.release()

        assertEquals(ActorCoroutineResult.Completed("A"), runOneSlice(scheduler))
        assertEquals(listOf("B-entered", "B-yielded", "A-resumed"), effects)
    }

    @Test
    fun `different actors make progress while one actor holds its Lua entry`() = runTest {
        val firstScope = scope("first", 1)
        val secondScope = scope("second", 1)
        val firstScheduler = ActorScheduler()
        val secondScheduler = ActorScheduler()
        val firstMayYield = CompletableDeferred<Unit>()
        val effects = mutableListOf<String>()

        firstScheduler.ready(
            ActorCoroutine(ActorTaskIdentity(firstScope, ActorTaskId.next()), null) {
                effects += "first-entered"
                firstMayYield.await()
                effects += "first-finished"
                ActorCoroutineResult.Completed("first")
            },
        )
        secondScheduler.ready(
            ActorCoroutine(ActorTaskIdentity(secondScope, ActorTaskId.next()), null) {
                effects += "second-completed"
                ActorCoroutineResult.Completed("second")
            },
        )

        val held = async {
            val coroutine = firstScheduler.tryEnter() ?: throw AssertionError("Expected first actor to enter")
            coroutine.slice(coroutine)
        }
        runCurrent()
        assertEquals(ActorCoroutineResult.Completed("second"), runOneSlice(secondScheduler))
        assertEquals(listOf("first-entered", "second-completed"), effects)

        firstMayYield.complete(Unit)
        runCurrent()
        assertEquals(ActorCoroutineResult.Completed("first"), held.await())
        firstScheduler.release()
        assertEquals(listOf("first-entered", "second-completed", "first-finished"), effects)
    }

    @Test
    fun `yielded input releases the actor slot while only its execution owner may resume`() = runTest {
        val actorScope = scope("input-yield", 50)
        val bridge = RecordingKernelBridge()
        val actor = actor(actorScope, bridge, RecordingGate(), this)
        stageReady(actor)

        try {
            assertEquals(ActorMailboxResult.Admitted, actor.admitEvent(event(actorScope, "first")))
            val ownerTask = ActorTaskIdentity(actorScope, ActorTaskId.next())
            var yieldedIdentity: ActorOperationIdentity? = null
            val first = actor.dispatchNext {
                yieldedIdentity = actor.yieldOperation(ownerTask, kernelHandle(501L), deadlineMillis = 1_000)
                ActorCoroutineResult.Yielded(checkNotNull(yieldedIdentity))
            }
            val pending = checkNotNull(yieldedIdentity)
            assertEquals("Input suspension must return its exact opaque operation owner.", ActorDispatchResult.Yielded(pending), first)

            // A semantic audio wait releases the single serialized Lua entry;
            // unrelated actor work can run before the original owner resumes.
            assertEquals(ActorMailboxResult.Admitted, actor.admitEvent(event(actorScope, "second")))
            assertEquals(
                ActorDispatchResult.Completed("second"),
                actor.dispatchNext { ActorCoroutineResult.Completed("second") },
            )

            val foreignScope = scope("foreign-input", 50)
            val foreign = pending.copy(
                scope = foreignScope,
                taskId = ActorTaskIdentity(foreignScope, pending.taskId.taskId),
            )
            assertEquals(
                "A foreign execution owner must not be able to resume a pending input operation.",
                ActorOperationOutcome.InvalidOwner,
                actor.resumeOperation(foreign, ActorTerminal.Completed(foreign, "forged")),
            )
            assertEquals(0, bridge.resumeCalls.get())

            assertEquals(
                ActorOperationOutcome.Resumed(ActorTerminal.Completed(pending, "ok")),
                actor.resumeOperation(pending, ActorTerminal.Completed(pending, "ok")),
            )
            assertEquals(1, bridge.resumeCalls.get())
            assertEquals(RecordingKernelBridge.ResumeCall(kernelHandle(501L), true, "ok"), bridge.lastResumeCall())
            assertEquals(
                "A duplicate terminal must not re-enter the owner coroutine.",
                ActorOperationOutcome.Stale,
                actor.resumeOperation(pending, ActorTerminal.Completed(pending, "late")),
            )
            assertEquals(1, bridge.resumeCalls.get())
        } finally {
            actor.close()
        }
    }

    @Test
    fun `task without a whole task deadline survives repeated waits beyond a former generic deadline`() = runTest {
        val actorScope = scope("lifecycle-long-task", 45)
        val formerGenericDeadlineMillis = 5L
        val taskScope = ActorTaskScope(
            parentScope = this,
            scopeIdentity = actorScope,
            maxConcurrentTasks = 1,
            perTaskDeadlineMillis = null,
        )
        val checkpoints = mutableListOf<Int>()

        try {
            checkNotNull(taskScope.launch {
                repeat(3) { checkpoint ->
                    kotlinx.coroutines.delay(formerGenericDeadlineMillis + 1)
                    checkpoints += checkpoint
                }
            })
            runCurrent()

            advanceTimeBy((formerGenericDeadlineMillis + 1) * 3)
            runCurrent()

            assertEquals(
                "A managed task without a whole-task deadline must keep running until its function returns, even after cumulative waits exceed the former generic deadline.",
                listOf(0, 1, 2),
                checkpoints,
            )
        } finally {
            taskScope.cancelAll()
            taskScope.joinAllWithin(100)
        }
    }

    @Test
    fun `managed task cancellation is delivered and removes the task without a stale successor`() = runTest {
        val actorScope = scope("task-cancel", 51)
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val taskScope = ActorTaskScope(
            parentScope = this,
            scopeIdentity = actorScope,
            maxConcurrentTasks = 1,
            perTaskDeadlineMillis = null,
        )

        try {
            assertTrue(
                "A live generation must admit the managed task before cancellation.",
                taskScope.launch {
                    entered.complete(Unit)
                    try {
                        CompletableDeferred<Unit>().await()
                    } finally {
                        cancelled.complete(Unit)
                    }
                } != null,
            )
            runCurrent()
            entered.await()

            taskScope.cancelAll()
            assertTrue(taskScope.joinAllWithin(100))
            assertTrue("Cancellation must reach the task body cleanup.", cancelled.isCompleted)
            assertEquals(0, taskScope.activeTaskCount)
            assertEquals(0, taskScope.totalTaskCount)
            assertFalse("A closed task scope must not admit a successor.", taskScope.isActive)
        } finally {
            taskScope.cancelAll()
            taskScope.joinAllWithin(100)
        }
    }
    @Test
    fun `cancelled managed task disposes suspended artifact and ignores late completion`() = runTest {
        val actorScope = scope("task-cancel-late", 52)
        val entered = AtomicInteger()
        val disposed = AtomicInteger()
        val continuation = AtomicReference<kotlinx.coroutines.CancellableContinuation<String>?>(null)
        val taskScope = ActorTaskScope(
            parentScope = this,
            scopeIdentity = actorScope,
            maxConcurrentTasks = 1,
            perTaskDeadlineMillis = null,
        )

        try {
            assertTrue(
                taskScope.launch {
                    try {
                        suspendCancellableCoroutine<String> { continuation.set(it) }
                        entered.incrementAndGet()
                    } finally {
                        disposed.incrementAndGet()
                    }
                } != null,
            )
            runCurrent()
            assertTrue("Managed task must suspend before cancellation.", continuation.get() != null)

            taskScope.cancelAll()
            assertTrue(taskScope.joinAllWithin(100))
            assertEquals("Cancellation must dispose the suspended task exactly once.", 1, disposed.get())
            assertEquals(0, taskScope.activeTaskCount)
            assertEquals(0, taskScope.totalTaskCount)

            runCatching { continuation.get()?.resume("late") }
            runCurrent()
            assertEquals("Late completion must not re-enter the cancelled coroutine.", 0, entered.get())
            assertEquals("Late completion must not dispose twice.", 1, disposed.get())
            assertFalse("A cancelled task scope must not admit a successor.", taskScope.isActive)
        } finally {
            taskScope.cancelAll()
            taskScope.joinAllWithin(100)
        }
    }

    @Test
    fun `operation-specific long sleep completes before its own deadline despite exceeding generic wait deadline`() = runTest {
        val actorScope = scope("long-sleep", 46)
        val bridge = RecordingKernelBridge()
        val formerGenericDeadlineMillis = 5L
        val sleepDeadlineMillis = 30L
        val actor = actor(
            actorScope,
            bridge,
            RecordingGate(),
            this,
            policy = policy(operationWaitDeadlineMillis = formerGenericDeadlineMillis),
        )
        stageReady(actor)
        val pending = actor.yieldOperation(
            task = ActorTaskIdentity(actorScope, ActorTaskId.next()),
            kernelHandle = kernelHandle(101L),
            deadlineMillis = sleepDeadlineMillis,
        )

        try {
            advanceTimeBy(sleepDeadlineMillis - 1)
            runCurrent()
            assertEquals(
                "The former generic wait deadline must not terminate a longer operation-specific sleep.",
                0,
                bridge.resumeCalls.get(),
            )

            assertTrue(
                "Completion before the operation-specific deadline must resume the coroutine successfully.",
                actor.resumeOperation(pending, ActorTerminal.Completed(pending, "timer fired")) is ActorOperationOutcome.Resumed,
            )
            assertEquals(1, bridge.resumeCalls.get())
            assertEquals(RecordingKernelBridge.ResumeCall(kernelHandle(101L), true, "timer fired"), bridge.lastResumeCall())

            advanceTimeBy(1)
            runCurrent()
            assertEquals(
                "The retired deadline timer must not manufacture a second resume after completion wins.",
                1,
                bridge.resumeCalls.get(),
            )
        } finally {
            actor.close()
        }
    }

    @Test
    fun `operation deadline wins with normalized timeout and rejects later completion`() = runTest {
        val actorScope = scope("deadline-first", 47)
        val bridge = RecordingKernelBridge()
        val actor = actor(actorScope, bridge, RecordingGate(), this, policy = policy(operationWaitDeadlineMillis = 5))
        stageReady(actor)
        val pending = actor.yieldOperation(
            task = ActorTaskIdentity(actorScope, ActorTaskId.next()),
            kernelHandle = kernelHandle(102L),
            deadlineMillis = 30,
        )

        try {
            advanceTimeBy(30)
            runCurrent()

            assertEquals(
                "Deadline-first terminal admission must resume Lua with the stable timeout error.",
                RecordingKernelBridge.ResumeCall(kernelHandle(102L), false, "E_TIMEOUT"),
                bridge.lastResumeCall(),
            )
            assertEquals(
                "A completion that arrives after timeout must be stale and must not re-enter Lua.",
                ActorOperationOutcome.Stale,
                actor.resumeOperation(pending, ActorTerminal.Completed(pending, "late timer")),
            )
            assertEquals(1, bridge.resumeCalls.get())
        } finally {
            actor.close()
        }
    }

    @Test
    fun `live explicit cancellation enters the cancellation path once while actor remains usable`() = runTest {
        val actorScope = scope("live-cancellation", 48)
        val bridge = RecordingKernelBridge()
        val actor = actor(actorScope, bridge, RecordingGate(), this)
        stageReady(actor)
        val pending = actor.yieldOperation(
            task = ActorTaskIdentity(actorScope, ActorTaskId.next()),
            kernelHandle = kernelHandle(103L),
            deadlineMillis = 30,
        )

        try {
            assertTrue(
                "Explicit cancellation in a live generation must terminalize through the cancellation path.",
                actor.cancelOperation(pending) is ActorOperationOutcome.Resumed,
            )
            assertEquals(1, bridge.cancelCalls.get())
            assertEquals(0, bridge.resumeCalls.get())
            assertTrue("Cancelling one operation must not fail or close a live actor.", actor.isReady)
            assertEquals(
                "A duplicate completion after live cancellation must not re-enter Lua.",
                ActorOperationOutcome.Stale,
                actor.resumeOperation(pending, ActorTerminal.Completed(pending, "late")),
            )
            assertEquals(1, bridge.cancelCalls.get())
            assertEquals(0, bridge.resumeCalls.get())
        } finally {
            actor.close()
        }
    }

    @Test
    fun `closing a suspended operation suppresses timeout and completion resumes`() = runTest {
        val actorScope = scope("close-sleep", 49)
        val bridge = RecordingKernelBridge()
        val actor = actor(actorScope, bridge, RecordingGate(), this)
        stageReady(actor)
        val pending = actor.yieldOperation(
            task = ActorTaskIdentity(actorScope, ActorTaskId.next()),
            kernelHandle = kernelHandle(104L),
            deadlineMillis = 30,
        )

        assertEquals(ActorCloseResult.Closed, actor.close())
        advanceTimeBy(30)
        runCurrent()

        assertEquals(
            "Generation close must discard a sleeping coroutine rather than resuming it with timeout or cancellation.",
            0,
            bridge.resumeCalls.get() + bridge.cancelCalls.get(),
        )
        assertEquals(
            "A post-close completion must remain terminally closed.",
            ActorOperationOutcome.Closed,
            actor.resumeOperation(pending, ActorTerminal.Completed(pending, "late")),
        )
        assertEquals(0, bridge.resumeCalls.get() + bridge.cancelCalls.get())
    }

    @Test
    fun `yielded operation times out exactly once without consuming active execution budget`() = runTest {
        val actorScope = scope("deadline", 5)
        val bridge = RecordingKernelBridge()
        val actor = actor(
            actorScope,
            bridge,
            RecordingGate(),
            this,
            policy = policy(activeSliceBudgetMillis = 1, operationWaitDeadlineMillis = 7),
        )
        stageReady(actor)
        val pending = actor.yieldOperation(ActorTaskIdentity(actorScope, ActorTaskId.next()), kernelHandle(1L))

        try {
            advanceTimeBy(7)
            runCurrent()

            assertEquals(
                "A yielded operation must have already accepted its timeout terminal outcome.",
                ActorOperationOutcome.Stale,
                actor.resumeOperation(pending, ActorTerminal.Completed(pending, "late completion")),
            )
            assertEquals(
                "The timeout winner must resume its owner exactly once before a late completion is rejected.",
                1,
                bridge.resumeCalls.get(),
            )
            assertTrue("Yielded wait time must not consume the active Lua execution budget.", actor.isReady)
        } finally {
            actor.close()
        }
    }

    @Test
    fun `local failure leaves a peer ready but fatal failure latches admission closed`() = runTest {
        val failedScope = scope("failed", 6)
        val peerScope = scope("peer", 6)
        val failed = actor(failedScope, RecordingKernelBridge(), RecordingGate(), this)
        val peer = actor(peerScope, RecordingKernelBridge(), RecordingGate(), this)
        stageReady(failed)
        stageReady(peer)

        try {
            assertEquals(
                ActorFailureResult.LocalContained,
                failed.classifyFailure(ActorFailureClassification.OrdinaryEvent("bad event")),
            )
            assertTrue(failed.isReady)
            assertEquals(ActorMailboxResult.Admitted, failed.admitEvent(event(failedScope, "local")))

            assertEquals(
                ActorFailureResult.FatalLatched,
                failed.classifyFailure(ActorFailureClassification.Instruction("budget exceeded")),
            )
            assertTrue(failed.isFailed)
            assertEquals(
                "A fatal latch must prohibit further Lua-entry admission.",
                ActorMailboxResult.Closed,
                failed.admitEvent(event(failedScope, "must-not-enter")),
            )
            assertTrue(peer.isReady)
            assertEquals(ActorMailboxResult.Admitted, peer.admitEvent(event(peerScope, "peer-still-live")))
        } finally {
            failed.close()
            peer.close()
        }
    }

    @Test
    fun `barrier released completion race has one terminal winner and no duplicate kernel effect`() = runTest {
        val actorScope = scope("race", 9)
        val bridge = RecordingKernelBridge()
        val actor = actor(actorScope, bridge, RecordingGate(), this)
        stageReady(actor)
        val pending = actor.yieldOperation(ActorTaskIdentity(actorScope, ActorTaskId.next()), kernelHandle(1L))
        val barrier = CyclicBarrier(3)
        val first = AtomicReference<ActorOperationOutcome>()
        val second = AtomicReference<ActorOperationOutcome>()

        val firstThread = Thread {
            barrier.await()
            first.set(runBlocking { actor.resumeOperation(pending, ActorTerminal.Completed(pending, "first")) })
        }
        val secondThread = Thread {
            barrier.await()
            second.set(runBlocking { actor.cancelOperation(pending) })
        }
        firstThread.start()
        secondThread.start()
        barrier.await()
        firstThread.join()
        secondThread.join()

        try {
            val outcomes = listOf(first.get(), second.get())
            val winner = outcomes.singleOrNull { it is ActorOperationOutcome.Resumed } as? ActorOperationOutcome.Resumed
                ?: throw AssertionError("Expected exactly one terminal winner")
            assertEquals(1, outcomes.count { it is ActorOperationOutcome.Resumed })
            assertEquals(1, outcomes.count { it is ActorOperationOutcome.AlreadyCompleted || it is ActorOperationOutcome.Stale })
            assertEquals(
                "Only the winning terminal request may enter the Lua bridge.",
                1,
                bridge.resumeCalls.get() + bridge.cancelCalls.get(),
            )
            when (winner.terminal) {
                is ActorTerminal.Completed -> {
                    assertEquals("Completion winner must resume", 1, bridge.resumeCalls.get())
                    assertEquals("Completion winner must not cancel", 0, bridge.cancelCalls.get())
                }
                is ActorTerminal.Cancelled -> {
                    assertEquals("Cancellation winner must cancel", 1, bridge.cancelCalls.get())
                    assertEquals("Cancellation winner must not resume", 0, bridge.resumeCalls.get())
                }
                else -> throw AssertionError("Race may only complete or cancel the operation")
            }
        } finally {
            actor.close()
        }
    }

    @Test
    fun `kernel construction failure latches only the failed actor and remains closable`() = runTest {
        val failedScope = scope("construct-failed", 10)
        val peerScope = scope("construct-peer", 10)
        val failedBridge = RecordingKernelBridge().apply {
            createOutcome = LuaKernelOutcome.RuntimeFailure(
                stateId = 1,
                generation = 1,
                diagnostic = "state creation failed",
            )
        }
        val failed = actor(
            failedScope,
            failedBridge,
            RecordingGate(),
            this,
            policy = policy(instructionBudget = 7, hookInterval = 2),
        )
        val peer = actor(peerScope, RecordingKernelBridge(), RecordingGate(), this)

        try {
            assertTrue(failed.construct() is ActorConstructResult.FatalFailure)
            assertTrue(failed.isFailed)
            assertEquals(ActorCloseResult.Closed, failed.close())

            stageReady(peer)
            assertTrue(peer.isReady)
            assertEquals(ActorMailboxResult.Admitted, peer.admitEvent(event(peerScope, "peer-survives")))
        } finally {
            failed.close()
            peer.close()
        }
    }

    @Test
    fun `instruction interruption under an explicit active budget latches the actor and leaves its peer live`() = runTest {
        val interruptedScope = scope("interrupted", 11)
        val peerScope = scope("instruction-peer", 11)
        val interruptedBridge = RecordingKernelBridge().apply {
            startOutcome = LuaKernelOutcome.Interrupted(
                stateId = 1,
                generation = 1,
                diagnostic = "instruction budget exhausted",
                elapsedNanos = 1,
            )
        }
        val interrupted = actor(
            interruptedScope,
            interruptedBridge,
            RecordingGate(),
            this,
            policy = policy(instructionBudget = 3, hookInterval = 1, activeSliceBudgetMillis = 1),
        )
        val peer = actor(peerScope, RecordingKernelBridge(), RecordingGate(), this)

        try {
            assertTrue(interrupted.construct() is ActorConstructResult.Success)
            assertEquals(ActorLoadResult.Loaded, interrupted.loadSource("return function() end", "main"))
            assertTrue(interrupted.startup() is ActorStartupResult.FatalFailure)
            assertTrue(interrupted.isFailed)
            assertEquals(ActorCloseResult.Closed, interrupted.close())

            stageReady(peer)
            assertTrue(peer.isReady)
        } finally {
            interrupted.close()
            peer.close()
        }
    }

    @Test
    fun `close invalidates queued work invokes kernel close once and rejects all later admission`() = runTest {
        val actorScope = scope("close", 12)
        val bridge = RecordingKernelBridge()
        val actor = actor(actorScope, bridge, RecordingGate(), this, policy = policy(mailboxCapacity = 1))
        stageReady(actor)

        assertEquals(ActorMailboxResult.Admitted, actor.admitEvent(event(actorScope, "queued")))
        assertEquals(ActorCloseResult.Closed, actor.close())
        assertEquals(ActorCloseResult.AlreadyClosed, actor.close())
        assertEquals(0, actor.mailboxDepth)
        assertEquals(1, bridge.closeCalls.get())
        assertEquals(ActorMailboxResult.Closed, actor.admitEvent(event(actorScope, "late")))
    }
    @Test
    fun `suspended startup remains not ready and does not retain ordinary events`() = runTest {
        val actorScope = scope("startup", 13)
        val bridge = RecordingKernelBridge().apply {
            startOutcome = LuaKernelOutcome.Yielded(
                stateId = 1,
                generation = 1,
                coroutineId = 1,
                operationId = 99,
                value = "startup-operation",
            )
        }
        val actor = actor(actorScope, bridge, RecordingGate(), this)

        try {
            assertTrue(actor.construct() is ActorConstructResult.Success)
            assertEquals(ActorLoadResult.Loaded, actor.loadSource("return function() end", "main"))
            val suspended = actor.startup() as? ActorStartupResult.Suspended
                ?: throw AssertionError("Expected startup to yield its opaque operation identity")
            assertFalse(actor.isReady)
            assertEquals(ActorMailboxResult.NotReady, actor.admitEvent(event(actorScope, "ordinary")))
            assertEquals(0, actor.mailboxDepth)

            assertEquals(
                ActorStartupResult.Ready,
                actor.resumeStartup(
                    suspended.operationIdentity,
                    ActorTerminal.Completed(suspended.operationIdentity, "startup-completed"),
                ),
            )
            assertTrue(actor.isReady)
            assertEquals(ActorMailboxResult.Admitted, actor.admitEvent(event(actorScope, "after-ready")))
        } finally {
            actor.close()
        }
    }

    @Test
    fun `generation retirement invalidates queued and suspended work without a late kernel resume`() = runTest {
        val actorScope = scope("retiring", 14)
        val bridge = RecordingKernelBridge()
        val actor = actor(actorScope, bridge, RecordingGate(), this, policy = policy(mailboxCapacity = 1))
        stageReady(actor)
        val pending = actor.yieldOperation(ActorTaskIdentity(actorScope, ActorTaskId.next()), kernelHandle(1L))
        assertEquals(ActorMailboxResult.Admitted, actor.admitEvent(event(actorScope, "queued")))

        try {
            actor.retire()
            assertEquals(0, actor.mailboxDepth)
            assertEquals(ActorMailboxResult.Closed, actor.admitEvent(event(actorScope, "late")))
            assertEquals(
                ActorOperationOutcome.Stale,
                actor.resumeOperation(pending, ActorTerminal.Completed(pending, "late completion")),
            )
            assertEquals("Generation close must not deliver cancellation to Lua.", 0, bridge.cancelCalls.get())
            assertEquals("Late completion must not consume retired mailbox work.", 0, actor.mailboxDepth)
            assertEquals(0, bridge.resumeCalls.get())
        } finally {
            actor.close()
        }
    }

    @Test
    fun `host projection latches fatal failure and a reconstructed generation starts fresh`() = runTest {
        val failedScope = scope("projection", 15)
        val implementation = ChannelImplementationId("test:actor")
        val failed = actor(failedScope, RecordingKernelBridge(), RecordingGate(), this)
        val replacement = actor(scope("projection", 16), RecordingKernelBridge(), RecordingGate(), this)

        try {
            assertEquals(
                ChannelPreparationAvailability.Recoverable(ChannelPreparationReason.ProviderInitialising),
                failed.projectSnapshot("projection", "Actor", implementation).preparation,
            )
            assertEquals(
                ChannelExecutionStatus.IDLE,
                failed.projectSnapshot("projection", "Actor", implementation).executionStatus,
            )

            stageReady(failed)
            assertEquals(
                ChannelPreparationAvailability.Available,
                failed.projectSnapshot("projection", "Actor", implementation).preparation,
            )

            assertEquals(
                ActorFailureResult.FatalLatched,
                failed.classifyFailure(ActorFailureClassification.NativeEngine("native engine failure")),
            )
            assertEquals(
                ChannelPreparationAvailability.Unavailable(ChannelPreparationReason.RuntimeFailed()),
                failed.projectSnapshot("projection", "Actor", implementation).preparation,
            )
            assertEquals(
                ChannelExecutionStatus.FAILED,
                failed.projectSnapshot("projection", "Actor", implementation).executionStatus,
            )

            failed.classifyFailure(ActorFailureClassification.OrdinaryEvent("late local failure"))
            assertEquals(
                "A later local event must not clear a host-projected fatal latch.",
                ChannelPreparationAvailability.Unavailable(ChannelPreparationReason.RuntimeFailed()),
                failed.projectSnapshot("projection", "Actor", implementation).preparation,
            )

            assertEquals(
                "A new generation must begin with a fresh, non-failed readiness latch.",
                ChannelPreparationAvailability.Recoverable(ChannelPreparationReason.ProviderInitialising),
                replacement.projectSnapshot("projection", "Actor", implementation).preparation,
            )
            assertEquals(
                ChannelExecutionStatus.IDLE,
                replacement.projectSnapshot("projection", "Actor", implementation).executionStatus,
            )
        } finally {
            failed.close()
            replacement.close()
        }
    }

    // ── Regression tests for generation-0 caller defects ──

    @Test
    fun `suspended startup enters bridge resume with original coroutine ID before publishing ready`() = runTest {
        val actorScope = scope("startup-resume", 17)
        val bridge = RecordingKernelBridge().apply {
            startOutcome = LuaKernelOutcome.Yielded(
                stateId = 1,
                generation = 1,
                coroutineId = 42,
                operationId = 99,
                value = "startup-operation",
            )
        }
        val actor = actor(actorScope, bridge, RecordingGate(), this)

        try {
            assertTrue(actor.construct() is ActorConstructResult.Success)
            assertEquals(ActorLoadResult.Loaded, actor.loadSource("return function() end", "main"))
            val suspended = actor.startup() as? ActorStartupResult.Suspended
                ?: throw AssertionError("Expected startup to yield")
            assertFalse("Suspended startup must not be ready", actor.isReady)

            assertEquals(
                "Resume with Completed must produce Ready",
                ActorStartupResult.Ready,
                actor.resumeStartup(
                    suspended.operationIdentity,
                    ActorTerminal.Completed(suspended.operationIdentity, "startup-completed"),
                ),
            )
            assertTrue(actor.isReady)

            assertEquals("bridge.resume must be called exactly once", 1, bridge.resumeCalls.get())
            assertEquals("bridge.cancel must not be called", 0, bridge.cancelCalls.get())
            assertEquals(1, bridge.resumedHandles.size)
            with(bridge.resumedHandles[0]) {
                assertEquals("Original coroutine ID from yield must be preserved", 42L, coroutineId.value)
                assertEquals("Original operation ID from yield must be preserved", 99L, operationId.value)
            }
        } finally {
            actor.close()
        }
    }

    @Test
    fun `suspended startup cancel does not publish ready and latches failed`() = runTest {
        val actorScope = scope("startup-cancel", 18)
        val bridge = RecordingKernelBridge().apply {
            startOutcome = LuaKernelOutcome.Yielded(
                stateId = 1,
                generation = 1,
                coroutineId = 43,
                operationId = 100,
                value = "startup-op-cancel",
            )
        }
        val actor = actor(actorScope, bridge, RecordingGate(), this)

        try {
            assertTrue(actor.construct() is ActorConstructResult.Success)
            assertEquals(ActorLoadResult.Loaded, actor.loadSource("return function() end", "main"))
            val suspended = actor.startup() as? ActorStartupResult.Suspended
                ?: throw AssertionError("Expected startup to yield")
            assertFalse("Suspended startup must not be ready", actor.isReady)

            assertTrue(
                "Cancel terminal must return FatalFailure, not Ready",
                actor.resumeStartup(
                    suspended.operationIdentity,
                    ActorTerminal.Cancelled(suspended.operationIdentity),
                ) is ActorStartupResult.FatalFailure,
            )
            assertFalse("Cancel must NOT make the actor ready", actor.isReady)
            assertTrue("Cancel must latch the actor as failed", actor.isFailed)

            assertEquals("Cancel must NOT call bridge.resume", 0, bridge.resumeCalls.get())
            assertEquals("Cancel must call bridge.cancel exactly once", 1, bridge.cancelCalls.get())
            assertEquals("Coroutine ID must match original", 43L, bridge.cancelledHandles[0].coroutineId.value)
        } finally {
            actor.close()
        }
    }

    @Test
    fun `failed startup terminal with bridge failure does not publish ready and latches failed`() = runTest {
        val actorScope = scope("startup-failure", 19)
        val bridge = RecordingKernelBridge().apply {
            startOutcome = LuaKernelOutcome.Yielded(
                stateId = 1,
                generation = 1,
                coroutineId = 44,
                operationId = 101,
                value = "startup-op-fail",
            )
            resumeOutcome = LuaKernelOutcome.RuntimeFailure(
                stateId = 1,
                generation = 1,
                diagnostic = "startup crashed",
            )
        }
        val actor = actor(actorScope, bridge, RecordingGate(), this)

        try {
            assertTrue(actor.construct() is ActorConstructResult.Success)
            assertEquals(ActorLoadResult.Loaded, actor.loadSource("return function() end", "main"))
            val suspended = actor.startup() as? ActorStartupResult.Suspended
                ?: throw AssertionError("Expected startup to yield")

            assertTrue(
                "A failed terminal followed by bridge failure must return FatalFailure, not Ready",
                actor.resumeStartup(
                    suspended.operationIdentity,
                    ActorTerminal.Failed(suspended.operationIdentity, "startup crashed"),
                ) is ActorStartupResult.FatalFailure,
            )
            assertFalse("Failure must NOT make the actor ready", actor.isReady)
            assertTrue("Failure must latch the actor as failed", actor.isFailed)
            assertEquals("Failed terminal must resume Lua once with its normalized failure", 1, bridge.resumeCalls.get())
        } finally {
            actor.close()
        }
    }

    @Test
    fun `forged operation identity with same scope and operationId but different taskId returns InvalidOwner then rightful owner succeeds`() = runTest {
        val actorScope = scope("forged", 20)
        val bridge = RecordingKernelBridge()
        val actor = actor(actorScope, bridge, RecordingGate(), this)
        stageReady(actor)

        try {
            val taskT1 = ActorTaskIdentity(actorScope, ActorTaskId.next())
            val original = actor.yieldOperation(taskT1, kernelHandle(1L))

            // Forge identity with same scope and operationId but DIFFERENT taskId
            val differentTask = ActorTaskIdentity(actorScope, ActorTaskId.next())
            val forged = ActorOperationIdentity(actorScope, differentTask, original.operationId)

            assertEquals(
                "Forged identity with wrong taskId must be rejected as InvalidOwner",
                ActorOperationOutcome.InvalidOwner,
                actor.resumeOperation(forged, ActorTerminal.Completed(forged, "forged")),
            )
            assertEquals("Forged attempt must not produce a bridge resume", 0, bridge.resumeCalls.get())

            // The rightful owner must still succeed after the forged attempt
            assertTrue(
                "Rightful owner must complete after forged rejection",
                actor.resumeOperation(original, ActorTerminal.Completed(original, "rightful")) is ActorOperationOutcome.Resumed,
            )
            assertEquals("Rightful owner must produce exactly one bridge resume", 1, bridge.resumeCalls.get())
        } finally {
            actor.close()
        }
    }

    @Test
    fun `concurrent task launches cannot exceed configured bound`() = runBlocking {
        val actorScope = scope("tasks", 21)
        val maxTasks = 2
        val totalLaunches = 4
        val block = CompletableDeferred<Unit>()

        val realScope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob(),
        )
        val taskScope = ActorTaskScope(realScope, actorScope, maxTasks, perTaskDeadlineMillis = 10000)

        try {
            val identities = (0 until totalLaunches).map {
                async<ActorTaskIdentity?>(kotlinx.coroutines.Dispatchers.Default) {
                    taskScope.launch { _ -> block.await() }
                }
            }.awaitAll()

            val admitted = identities.filterNotNull()
            assertTrue(
                "At most $maxTasks admitted concurrently, got ${admitted.size}",
                admitted.size <= maxTasks,
            )
            assertTrue(
                "activeTaskCount (${taskScope.activeTaskCount}) must not exceed $maxTasks",
                taskScope.activeTaskCount <= maxTasks,
            )
        } finally {
            block.complete(Unit)
            taskScope.cancelAll()
            taskScope.joinAllWithin(2000)
            realScope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
        }
    }

    @Test
    fun `concurrent close invokes bridge close exactly once and all callers observe closed`() = runTest {
        val actorScope = scope("close-race", 22)
        val bridge = RecordingKernelBridge()
        val actor = actor(actorScope, bridge, RecordingGate(), this)
        stageReady(actor)

        val total = 3

        try {
            val outcomes = (0 until total).map {
                async(kotlinx.coroutines.Dispatchers.Default) {
                    actor.close()
                }
            }.awaitAll()

            assertEquals("bridge.close must be called exactly once", 1, bridge.closeCalls.get())
            assertEquals("Exactly one caller observes Closed", 1, outcomes.count { it == ActorCloseResult.Closed })
            assertEquals("Remaining callers observe AlreadyClosed", total - 1, outcomes.count { it == ActorCloseResult.AlreadyClosed })
        } finally {
            // Already closed
        }
    }

    @Test
    fun `close from constructing projects closed and disabled`() = runTest {
        val actorScope = scope("close-constructing", 23)
        val bridge = RecordingKernelBridge()
        val actor = actor(actorScope, bridge, RecordingGate(), this)
        actor.construct()

        assertEquals(ActorCloseResult.Closed, actor.close())
        assertEquals("bridge.close must be called exactly once", 1, bridge.closeCalls.get())

        val snapshot = actor.projectSnapshot("closed", "Actor", ChannelImplementationId("test:actor"))
        assertEquals(
            "Close from CONSTRUCTING must project as Unavailable(RuntimeClosed)",
            ChannelPreparationAvailability.Unavailable(ChannelPreparationReason.RuntimeClosed),
            snapshot.preparation,
        )
        assertFalse("Close from CONSTRUCTING must project as disabled", snapshot.enabled)
    }

    @Test
    fun `close from staged projects closed and disabled`() = runTest {
        val actorScope = scope("close-staged", 24)
        val bridge = RecordingKernelBridge()
        val actor = actor(actorScope, bridge, RecordingGate(), this)
        actor.construct()
        assertEquals(ActorLoadResult.Loaded, actor.loadSource("return function() end", "main"))

        assertEquals(ActorCloseResult.Closed, actor.close())
        assertEquals("bridge.close must be called exactly once", 1, bridge.closeCalls.get())

        val snapshot = actor.projectSnapshot("closed", "Actor", ChannelImplementationId("test:actor"))
        assertEquals(
            "Close from STAGED must project as Unavailable(RuntimeClosed)",
            ChannelPreparationAvailability.Unavailable(ChannelPreparationReason.RuntimeClosed),
            snapshot.preparation,
        )
        assertFalse("Close from STAGED must project as disabled", snapshot.enabled)
    }

    @Test
    fun `close from starting with suspended startup projects closed and disabled`() = runTest {
        val actorScope = scope("close-starting", 25)
        val bridge = RecordingKernelBridge().apply {
            startOutcome = LuaKernelOutcome.Yielded(
                stateId = 1,
                generation = 1,
                coroutineId = 1,
                operationId = 99,
                value = "startup-op",
            )
        }
        val actor = actor(actorScope, bridge, RecordingGate(), this)
        actor.construct()
        assertEquals(ActorLoadResult.Loaded, actor.loadSource("return function() end", "main"))
        assertTrue("startup must yield", actor.startup() is ActorStartupResult.Suspended)

        assertEquals(ActorCloseResult.Closed, actor.close())
        assertEquals("bridge.close must be called exactly once", 1, bridge.closeCalls.get())

        val snapshot = actor.projectSnapshot("closed", "Actor", ChannelImplementationId("test:actor"))
        assertEquals(
            "Close from STARTING must project as Unavailable(RuntimeClosed)",
            ChannelPreparationAvailability.Unavailable(ChannelPreparationReason.RuntimeClosed),
            snapshot.preparation,
        )
        assertFalse("Close from STARTING must project as disabled", snapshot.enabled)
    }

    @Test
    fun `more yielded operations than maxConcurrentTasks all receive timed deadlines and none left suspended`() = runTest {
        val actorScope = scope("deadlines", 26)
        val bridge = RecordingKernelBridge()
        val actor = actor(
            actorScope,
            bridge,
            RecordingGate(),
            this,
            policy = policy(
                maxConcurrentTasks = 1,
                operationWaitDeadlineMillis = 5,
                closeTimeoutMillis = 7,
            ),
        )
        stageReady(actor)

        try {
            val ops = (1..3).map {
                actor.yieldOperation(ActorTaskIdentity(actorScope, ActorTaskId.next()), kernelHandle(it.toLong()))
            }

            // Advance past all deadlines
            advanceTimeBy(5)
            runCurrent()

            // Every operation must have timed out — none left suspended forever
            ops.forEach { op ->
                assertEquals(
                    "Every yielded operation must have received a timeout, rejecting late completion",
                    ActorOperationOutcome.Stale,
                    actor.resumeOperation(op, ActorTerminal.Completed(op, "late")),
                )
            }
            // All N must have gotten a bridge resume call (one per timeout terminal)
            assertEquals(
                "All $ops operations must have produced a bridge resume call",
                ops.size,
                bridge.resumeCalls.get(),
            )
        } finally {
            actor.close()
        }
    }

    @Test
    fun `admitted mailbox events drain FIFO with exactly one slice execution per event until empty`() = runTest {
        val actorScope = scope("drain-fifo", 27)
        val mailbox = ActorMailbox(capacity = 4)
        val scheduler = ActorScheduler()
        var executionCount = 0

        mailbox.open()
        assertEquals(ActorMailboxResult.Admitted, mailbox.admit(event(actorScope, "first")))
        assertEquals(ActorMailboxResult.Admitted, mailbox.admit(event(actorScope, "second")))
        assertEquals(ActorMailboxResult.Admitted, mailbox.admit(event(actorScope, "third")))
        assertEquals(3, mailbox.size)

        // Drain FIFO — each polled event must produce exactly one scheduled coroutine
        while (true) {
            val ev = mailbox.poll() ?: break
            scheduler.ready(
                ActorCoroutine(
                    ownerTask = ActorTaskIdentity(actorScope, ActorTaskId.next()),
                    owningOperation = null,
                ) {
                    executionCount++
                    ActorCoroutineResult.Completed("done")
                },
            )
        }
        assertEquals("All events polled from mailbox", 0, mailbox.size)

        // Run each exactly-once slice until scheduler empty
        var ran = 0
        while (true) {
            val coroutine = scheduler.tryEnter() ?: break
            try {
                coroutine.slice(coroutine)
            } finally {
                scheduler.release()
            }
            ran++
        }
        assertEquals("Exactly one slice per admitted event", 3, ran)
        assertEquals("Exactly one execution per admitted event", 3, executionCount)
    }


    @Test
    fun `completion terminalized before a closed cancelled or timed out gate never reports Resumed or enters bridge`() = runTest {
        val cases = listOf(
            ContinuationRejection.Closed to ActorOperationOutcome.Closed,
            ContinuationRejection.Cancelled to ActorOperationOutcome.Stale,
            ContinuationRejection.TimedOut to ActorOperationOutcome.Stale,
        )

        cases.forEachIndexed { index, (rejection, expected) ->
            val actorScope = scope("continuation-gate-$index", 32L + index)
            val bridge = RecordingKernelBridge()
            val gate = ScriptableGate(rejection)
            val actor = actor(actorScope, bridge, gate, this)
            stageReady(actor)
            val pending = actor.yieldOperation(
                ActorTaskIdentity(actorScope, ActorTaskId.next()),
                kernelHandle(1L, 100L + index),
            )

            try {
                assertEquals(
                    "A gate that rejects the continuation after token admission must report its non-success terminal outcome.",
                    expected,
                    actor.resumeOperation(pending, ActorTerminal.Completed(pending, "winner")),
                )
                assertEquals(
                    "A terminalized operation whose continuation never ran must not enter the bridge.",
                    0,
                    bridge.resumeCalls.get() + bridge.cancelCalls.get(),
                )

                gate.rejection = null
                assertEquals(
                    "The terminalized token must not become admissible after its gate rejection.",
                    ActorOperationOutcome.Stale,
                    actor.resumeOperation(pending, ActorTerminal.Completed(pending, "late")),
                )
                assertEquals(
                    "A late request must not manufacture a bridge effect.",
                    0,
                    bridge.resumeCalls.get() + bridge.cancelCalls.get(),
                )
            } finally {
                actor.close()
            }
        }
    }

    @Test
    fun `scheduler close preserves held slice claim and never admits a second slice`() = runTest {
        val actorScope = scope("scheduler-close-race", 35)
        val scheduler = ActorScheduler()
        val entered = CompletableDeferred<Unit>()
        val releaseA = CompletableDeferred<Unit>()
        val sliceA = ActorCoroutine(
            ownerTask = ActorTaskIdentity(actorScope, ActorTaskId.next()),
            owningOperation = null,
        ) {
            entered.complete(Unit)
            releaseA.await()
            ActorCoroutineResult.Completed("A")
        }
        val sliceB = ActorCoroutine(
            ownerTask = ActorTaskIdentity(actorScope, ActorTaskId.next()),
            owningOperation = null,
        ) {
            ActorCoroutineResult.Completed("B")
        }

        scheduler.ready(sliceA)
        scheduler.ready(sliceB)
        val heldA = async {
            val coroutine = scheduler.tryEnter() ?: throw AssertionError("Expected slice A to enter")
            try {
                coroutine.slice(coroutine)
            } finally {
                scheduler.release()
            }
        }
        runCurrent()
        assertTrue("Slice A must hold the single-entry claim.", entered.isCompleted)
        assertTrue("Slice A must retain the single-entry claim while suspended.", scheduler.isEntered)
        assertNull("Slice B must not enter while slice A holds the claim.", scheduler.tryEnter())
        assertFalse("Exact entry for B must fail while A holds the claim.", scheduler.tryEnterExact(sliceB))

        scheduler.close()
        assertTrue("Close must not clear the active slice A claim.", scheduler.isEntered)
        assertTrue("The scheduler must be terminally closed.", scheduler.isClosed)
        assertNull("No generic entry is permitted after close.", scheduler.tryEnter())
        assertFalse("No exact entry is permitted after close.", scheduler.tryEnterExact(sliceB))

        releaseA.complete(Unit)
        runCurrent()
        assertEquals(ActorCoroutineResult.Completed("A"), heldA.await())
        assertFalse("Only slice A may release its active claim.", scheduler.isEntered)
        assertNull("Slice B must remain barred after A releases a closed scheduler.", scheduler.tryEnter())
        assertFalse("Exact entry remains barred after A releases a closed scheduler.", scheduler.tryEnterExact(sliceB))
    }

    @Test
    fun `close waits for held dispatch slice before bridge close and closes scheduler to later entry`() = runTest {
        val actorScope = scope("close-held-dispatch", 36)
        val bridge = RecordingKernelBridge()
        val actor = actor(actorScope, bridge, RecordingGate(), this)
        stageReady(actor)
        assertEquals(ActorMailboxResult.Admitted, actor.admitEvent(event(actorScope, "held")))
        val entered = CompletableDeferred<Unit>()
        val releaseSlice = CompletableDeferred<Unit>()

        val dispatch = async {
            actor.dispatchNext {
                entered.complete(Unit)
                releaseSlice.await()
                ActorCoroutineResult.Completed("finished")
            }
        }
        runCurrent()
        assertTrue("The dispatch slice must have entered before close races it.", entered.isCompleted)
        assertTrue("The dispatch slice must hold the scheduler entry.", actor.actorScheduler.isEntered)

        val closing = async(start = CoroutineStart.UNDISPATCHED) { actor.close() }
        assertEquals("bridge.close must wait for the held dispatch slice to release.", 0, bridge.closeCalls.get())
        assertTrue("Close must not clear the held scheduler claim while waiting.", actor.actorScheduler.isEntered)

        releaseSlice.complete(Unit)
        runCurrent()
        assertEquals(ActorDispatchResult.Completed("finished"), dispatch.await())
        assertEquals(ActorCloseResult.Closed, closing.await())
        assertEquals("bridge.close executes once only after the dispatch slice released.", 1, bridge.closeCalls.get())
        assertTrue("Close must terminally close the scheduler.", actor.actorScheduler.isClosed)
        assertNull("No slice may enter after runtime close.", actor.actorScheduler.tryEnter())
    }

    @Test
    fun `foreign scope event is rejected without queueing or effects while rightful event dispatches`() = runTest {
        val actorScope = scope("event-owner", 37)
        val bridge = RecordingKernelBridge()
        val actor = actor(actorScope, bridge, RecordingGate(), this, policy = policy(mailboxCapacity = 1))
        stageReady(actor)

        try {
            assertEquals(
                "A foreign-generation event is an ownership violation, never a normal admission.",
                ActorMailboxResult.InvalidOwner,
                actor.admitEvent(event(scope("event-foreign", 38), "foreign")),
            )
            assertEquals("The foreign event must not occupy mailbox capacity.", 0, actor.mailboxDepth)

            assertEquals(
                "A rightful event must still fit in the untouched single-slot mailbox.",
                ActorMailboxResult.Admitted,
                actor.admitEvent(event(actorScope, "rightful")),
            )
            val effects = mutableListOf<String>()
            assertEquals(
                ActorDispatchResult.Completed("rightful-finished"),
                actor.dispatchNext { envelope ->
                    effects += ((envelope as ActorEventEnvelope.Input).payload as ActorInputPayload.Prepare).inputId
                    ActorCoroutineResult.Completed("rightful-finished")
                },
            )
            assertEquals("Dispatch must invoke only the rightful event slice.", listOf("rightful"), effects)
            assertEquals("Foreign admission must cause no native resume effect.", 0, bridge.resumeCalls.get())
            assertEquals("Foreign admission must cause no native cancel effect.", 0, bridge.cancelCalls.get())
        } finally {
            actor.close()
        }
    }

    @Test
    fun `ordinary yield retains exact native handle after startup counter divergence and resumes once`() = runTest {
        val actorScope = scope("native-handle", 39)
        val bridge = RecordingKernelBridge().apply {
            startOutcome = LuaKernelOutcome.Yielded(
                stateId = 1,
                generation = 1,
                coroutineId = 41,
                operationId = 101,
                value = "startup",
            )
        }
        val actor = actor(actorScope, bridge, RecordingGate(), this)

        try {
            assertTrue(actor.construct() is ActorConstructResult.Success)
            assertEquals(ActorLoadResult.Loaded, actor.loadSource("return function() end", "main"))
            val startup = actor.startup() as? ActorStartupResult.Suspended
                ?: throw AssertionError("Expected startup to yield before the ordinary operation")
            assertEquals(
                ActorStartupResult.Ready,
                actor.resumeStartup(startup.operationIdentity, ActorTerminal.Completed(startup.operationIdentity, "ready")),
            )

            val nativeHandle = kernelHandle(coroutineId = 71, operationId = Long.MAX_VALUE)
            val pending = actor.yieldOperation(
                ActorTaskIdentity(actorScope, ActorTaskId.next()),
                nativeHandle,
            )
            val callsBeforeCompletion = bridge.resumeCalls.get()
            assertTrue(actor.resumeOperation(pending, ActorTerminal.Completed(pending, "ordinary")) is ActorOperationOutcome.Resumed)
            assertEquals("The ordinary completion produces one additional native effect.", callsBeforeCompletion + 1, bridge.resumeCalls.get())
            assertEquals(
                "Completion must pass the exact yielded kernel handle, not ActorOperationId coerced to LuaOperationId.",
                nativeHandle,
                synchronized(bridge.resumedHandles) { bridge.resumedHandles.last() },
            )
            assertEquals(
                "A duplicate completion must not reproduce the native effect.",
                ActorOperationOutcome.Stale,
                actor.resumeOperation(pending, ActorTerminal.Completed(pending, "late")),
            )
            assertEquals("Duplicate completion must leave native effects at one.", callsBeforeCompletion + 1, bridge.resumeCalls.get())
        } finally {
            actor.close()
        }
    }
    @Test
    fun `close timeout interrupts and serializes held native continuation before exactly once close`() = runTest {
        val actorScope = scope("close-held-continuation", 40)
        val bridge = RecordingKernelBridge().apply { holdResumeNative = true }
        val actor = actor(
            actorScope,
            bridge,
            RecordingGate(),
            this,
            policy = policy(closeTimeoutMillis = 3),
        )
        stageReady(actor)
        val pending = actor.yieldOperation(
            ActorTaskIdentity(actorScope, ActorTaskId.next()),
            kernelHandle(coroutineId = 81, operationId = 401),
        )

        val resuming = async(kotlinx.coroutines.Dispatchers.Default) {
            actor.resumeOperation(pending, ActorTerminal.Completed(pending, "interrupted-native"))
        }
        bridge.awaitNativeResumeEntry()

        val closing = async { actor.close() }
        advanceTimeBy(3)
        runCurrent()

        assertEquals("A bounded close must explicitly interrupt an unquiescent Lua state.", 1, bridge.interruptCalls.get())
        assertEquals(
            "Close must report its explicit timeout terminal outcome after interrupting the native state.",
            ActorCloseResult.ClosedWithTimeout,
            closing.await(),
        )
        assertEquals(
            "Interrupt must precede the serialized native close.",
            listOf("resume", "interrupt", "close"),
            bridge.nativeCallOrder,
        )
        assertEquals("No two native bridge bodies may overlap during timeout cleanup.", 1, bridge.maxConcurrentNativeBodies.get())
        assertEquals("Timeout cleanup must close the native state exactly once.", 1, bridge.closeCalls.get())
        assertEquals(
            "The interrupted continuation cannot report a successful native resume.",
            ActorOperationOutcome.Closed,
            resuming.await(),
        )
        assertEquals("Interrupted native continuation must not produce a late resume effect.", 1, bridge.resumeCalls.get())
        assertEquals(
            "A post-close terminal request stays closed and cannot manufacture a late effect.",
            ActorOperationOutcome.Closed,
            actor.resumeOperation(pending, ActorTerminal.Completed(pending, "late")),
        )
        assertEquals("Late completion must preserve the single terminal native close.", 1, bridge.closeCalls.get())
    }

    @Test
    fun `durable callback embedded scope is validated at admission and dequeue while rightful callback dispatches`() = runTest {
        val actorScope = scope("durable-owner", 41)
        val bridge = RecordingKernelBridge()
        val actor = actor(actorScope, bridge, RecordingGate(), this, policy = policy(mailboxCapacity = 2))
        stageReady(actor)

        try {
            listOf(scope("durable-stale", 40), scope("durable-foreign", 42)).forEach { embeddedScope ->
                assertEquals(
                    "A current envelope cannot smuggle a durable callback owned by $embeddedScope.",
                    ActorMailboxResult.InvalidOwner,
                    actor.admitEvent(durableCallback(actorScope, embeddedScope, "rejected")),
                )
            }
            assertEquals("Rejected embedded owners must not consume mailbox capacity.", 0, actor.mailboxDepth)

            val legacyQueuedForeign = durableCallback(actorScope, scope("durable-legacy", 43), "legacy")
            assertEquals(
                "Dequeue must independently reject a legacy/fabricated queued callback with a foreign embedded owner.",
                ActorMailboxResult.Admitted,
                unsafeMailbox(actor).admit(legacyQueuedForeign),
            )
            val effects = mutableListOf<String>()
            assertEquals(
                ActorDispatchResult.InvalidOwner,
                actor.dispatchNext { envelope ->
                    effects += "unexpected:${envelope.kind}"
                    ActorCoroutineResult.Completed("must-not-run")
                },
            )
            assertTrue("A rejected dequeued callback must not invoke its slice.", effects.isEmpty())

            val rightful = durableCallback(actorScope, actorScope, "rightful")
            assertEquals(ActorMailboxResult.Admitted, actor.admitEvent(rightful))
            assertEquals(
                ActorDispatchResult.Completed("rightful-finished"),
                actor.dispatchNext { envelope ->
                    val callback = envelope as? ActorEventEnvelope.DurableRunCallback
                        ?: throw AssertionError("Expected the rightful durable callback")
                    effects += (callback.payload as ActorDurableRunPayload.Completed).value
                    ActorCoroutineResult.Completed("rightful-finished")
                },
            )
            assertEquals("Only the correctly owned durable callback may dispatch.", listOf("rightful"), effects)
            assertEquals("Ownership rejection must not create a native continuation effect.", 0, bridge.resumeCalls.get() + bridge.cancelCalls.get())
        } finally {
            actor.close()
        }
    }

    @Test
    fun `maximum and near overflow close deadlines wait for active slice release rather than timing out immediately`() = runTest {
        val closeTimeouts = listOf(Long.MAX_VALUE / 1_000_000L + 1L, Long.MAX_VALUE)

        closeTimeouts.forEachIndexed { index, closeTimeoutMillis ->
            val actorScope = scope("close-overflow-$index", 44L + index)
            val bridge = RecordingKernelBridge()
            val actor = actor(
                actorScope,
                bridge,
                RecordingGate(),
                this,
                policy = policy(closeTimeoutMillis = closeTimeoutMillis),
            )
            stageReady(actor)
            assertEquals(ActorMailboxResult.Admitted, actor.admitEvent(event(actorScope, "held")))
            val entered = CompletableDeferred<Unit>()
            val releaseSlice = CompletableDeferred<Unit>()
            val dispatch = async {
                actor.dispatchNext {
                    entered.complete(Unit)
                    releaseSlice.await()
                    ActorCoroutineResult.Completed("released")
                }
            }
            runCurrent()
            assertTrue("The active slice must hold Lua entry before close tests its deadline.", entered.isCompleted)

            val closing = async(start = CoroutineStart.UNDISPATCHED) { actor.close() }
            assertFalse("$closeTimeoutMillis ms must not overflow into an immediate close timeout.", closing.isCompleted)
            assertEquals("Native close must wait for the active slice at $closeTimeoutMillis ms.", 0, bridge.closeCalls.get())

            releaseSlice.complete(Unit)
            runCurrent()
            assertEquals(ActorDispatchResult.Completed("released"), dispatch.await())
            assertEquals(ActorCloseResult.Closed, closing.await())
            assertEquals("The released actor closes its native state exactly once.", 1, bridge.closeCalls.get())
        }
    }

    private fun TestScope.actor(
        scope: CapabilityScopeIdentity,
        bridge: RecordingKernelBridge,
        gate: ActorGenerationGate,
        parentScope: TestScope,
        policy: ActorPolicy = policy(),
    ): ActorRuntime = ActorRuntime(
        scope = scope,
        bridge = bridge,
        gate = gate,
        policy = policy,
        capabilityScope = null,
        parentScope = parentScope,
    )

    private suspend fun stageReady(actor: ActorRuntime) {
        assertTrue(actor.construct() is ActorConstructResult.Success)
        assertEquals(ActorLoadResult.Loaded, actor.loadSource("return function() end", "main"))
        assertEquals(ActorStartupResult.Ready, actor.startup())
    }

    private suspend fun runOneSlice(scheduler: ActorScheduler): ActorCoroutineResult {
        val coroutine = scheduler.tryEnter() ?: throw AssertionError("Expected a ready coroutine")
        return try {
            coroutine.slice(coroutine)
        } finally {
            scheduler.release()
        }
    }

    private fun scope(instanceId: String, generation: Long): CapabilityScopeIdentity =
        CapabilityScopeIdentity(instanceId, RuntimeGeneration(generation))

    private fun event(scope: CapabilityScopeIdentity, inputId: String): ActorEventEnvelope =
        ActorEventEnvelope.Input(
            identity = ActorEventIdentity(scope, ActorEventId.next()),
            payload = ActorInputPayload.Prepare(inputId),
        )

    private fun durableCallback(
        envelopeScope: CapabilityScopeIdentity,
        durableScope: CapabilityScopeIdentity,
        value: String,
    ): ActorEventEnvelope.DurableRunCallback = ActorEventEnvelope.DurableRunCallback(
        identity = ActorEventIdentity(envelopeScope, ActorEventId.next()),
        durableRun = ActorDurableRunIdentity(durableScope, ActorDurableRunId.next()),
        payload = ActorDurableRunPayload.Completed(value),
    )


    private fun operation(scope: CapabilityScopeIdentity): ActorOperationIdentity {
        val task = ActorTaskIdentity(scope, ActorTaskId.next())
        return ActorOperationIdentity(scope, task, ActorOperationId.next())
    }

    private fun kernelHandle(coroutineId: Long, operationId: Long = coroutineId): LuaOperationHandle =
        LuaOperationHandle(
            stateHandle = LuaStateHandle(LuaStateId(1L), LuaStateGeneration(1L)),
            coroutineId = LuaCoroutineId(coroutineId),
            operationId = LuaOperationId(operationId),
        )

    private fun policy(
        mailboxCapacity: Int = 2,
        hookInterval: Int = 3,
        instructionBudget: Long = 9,
        activeSliceBudgetMillis: Long = 5,
        operationWaitDeadlineMillis: Long = 11,
        callbackTimeoutMillis: Long = 13,
        closeTimeoutMillis: Long = 17,
        maxConcurrentTasks: Int = 2,
        perTaskDeadlineMillis: Long? = 19,
    ): ActorPolicy = ActorPolicy(
        luaKernelConfig = ActorKernelConfig(
            hookInterval = hookInterval,
            instructionBudget = instructionBudget,
        ),
        mailboxCapacity = mailboxCapacity,
        activeSliceBudgetMillis = activeSliceBudgetMillis,
        operationWaitDeadlineMillis = operationWaitDeadlineMillis,
        callbackTimeoutMillis = callbackTimeoutMillis,
        closeTimeoutMillis = closeTimeoutMillis,
        maxConcurrentTasks = maxConcurrentTasks,
        perTaskDeadlineMillis = perTaskDeadlineMillis,
    )

    private fun unsafeMailbox(actor: ActorRuntime): ActorMailbox {
        val field = ActorRuntime::class.java.getDeclaredField("mailbox")
        field.isAccessible = true
        return field.get(actor) as ActorMailbox
    }


    private class RecordingGate : ActorGenerationGate {
        @Volatile
        var live: Boolean = true

        @Volatile
        var admissionStopped: Boolean = false

        override fun isLive(): Boolean = live

        override fun isAdmissionStopped(): Boolean = admissionStopped

        override fun <T> commitIfLive(action: () -> T): ActorGateCommit<T> =
            if (live) ActorGateCommit.Success(action()) else ActorGateCommit.Closed

        override suspend fun <T> runContinuation(action: suspend () -> T): ActorGateResult<T> =
            if (live) ActorGateResult.Success(action()) else ActorGateResult.Closed
    }

    private enum class ContinuationRejection {
        Closed,
        Cancelled,
        TimedOut,
    }

    private class ScriptableGate(
        var rejection: ContinuationRejection? = null,
    ) : ActorGenerationGate {
        @Volatile
        var live: Boolean = true

        override fun isLive(): Boolean = live

        override fun isAdmissionStopped(): Boolean = false

        override fun <T> commitIfLive(action: () -> T): ActorGateCommit<T> =
            if (live) ActorGateCommit.Success(action()) else ActorGateCommit.Closed

        override suspend fun <T> runContinuation(action: suspend () -> T): ActorGateResult<T> = when (rejection) {
            ContinuationRejection.Closed -> ActorGateResult.Closed
            ContinuationRejection.Cancelled -> ActorGateResult.Cancelled
            ContinuationRejection.TimedOut -> ActorGateResult.TimedOut(1)
            null -> if (live) ActorGateResult.Success(action()) else ActorGateResult.Closed
        }
    }



    private class RecordingKernelBridge : LuaKernelBridge {
        val resumeCalls = AtomicInteger()
        val cancelCalls = AtomicInteger()
        val closeCalls = AtomicInteger()
        val interruptCalls = AtomicInteger()

        private val nativeLock = ReentrantLock()
        private val activeNativeBodies = AtomicInteger()
        val maxConcurrentNativeBodies = AtomicInteger()
        val nativeCallOrder = CopyOnWriteArrayList<String>()
        val nativeResumeEntered = CountDownLatch(1)
        private val nativeResumeReleased = CountDownLatch(1)

        @Volatile
        var holdResumeNative: Boolean = false

        fun awaitNativeResumeEntry() {
            nativeResumeEntered.await()
        }

        private fun enterNative(name: String) {
            val active = activeNativeBodies.incrementAndGet()
            maxConcurrentNativeBodies.updateAndGet { maxOf(it, active) }
            nativeCallOrder += name
        }

        private fun leaveNative() {
            activeNativeBodies.decrementAndGet()
        }

        data class ResumeCall(
            val operation: LuaOperationHandle,
            val success: Boolean,
            val value: String,
        )

        private val resumePayloads = mutableListOf<ResumeCall>()

        fun lastResumeCall(): ResumeCall? = synchronized(resumePayloads) { resumePayloads.lastOrNull() }
        val resumedHandles = mutableListOf<LuaOperationHandle>()
        val cancelledHandles = mutableListOf<LuaOperationHandle>()
        var createOutcome: LuaKernelOutcome? = null
        var startOutcome: LuaKernelOutcome? = null
        var resumeOutcome: LuaKernelOutcome? = null

        override fun create(config: LuaKernelConfig): LuaKernelOutcome = createOutcome ?: LuaKernelOutcome.Created(
            stateId = 1,
            generation = 1,
            luaVersion = "5.4",
            bindingVersion = "test",
            topology = "recording",
        )

        override fun load(
            handle: LuaStateHandle,
            source: String,
            entrypoint: String,
        ): LuaKernelOutcome = completed()

        override fun start(handle: LuaStateHandle): LuaKernelOutcome = startOutcome ?: completed()

        override fun resume(
            operation: LuaOperationHandle,
            success: Boolean,
            value: String,
            spawnAdmission: LuaSpawnAdmission,
        ): LuaKernelOutcome = nativeLock.withLock {
            enterNative("resume")
            try {
                resumeCalls.incrementAndGet()
                synchronized(resumePayloads) { resumePayloads += ResumeCall(operation, success, value) }
                synchronized(resumedHandles) { resumedHandles += operation }
                if (holdResumeNative) {
                    nativeResumeEntered.countDown()
                    nativeResumeReleased.await()
                }
                resumeOutcome ?: completed()
            } finally {
                leaveNative()
            }
        }

        override fun cancel(operation: LuaOperationHandle): LuaKernelOutcome {
            cancelCalls.incrementAndGet()
            synchronized(cancelledHandles) { cancelledHandles += operation }
            return LuaKernelOutcome.Cancelled(
                stateId = 1,
                generation = 1,
                operationId = operation.operationId.value,
            )
        }


        override fun interrupt(handle: LuaStateHandle): LuaKernelOutcome {
            nativeResumeReleased.countDown()
            return nativeLock.withLock {
                enterNative("interrupt")
                try {
                    interruptCalls.incrementAndGet()
                    LuaKernelOutcome.Interrupted(
                        stateId = 1,
                        generation = 1,
                        diagnostic = "interrupted",
                        elapsedNanos = 1,
                    )
                } finally {
                    leaveNative()
                }
            }
        }

        override fun snapshot(handle: LuaStateHandle): LuaKernelOutcome = LuaKernelOutcome.Snapshot(
            stateId = 1,
            generation = 1,
            elapsedNanos = 0,
            luaVersion = "5.4",
            bindingVersion = "test",
            topology = "recording",
        )

        override fun close(handle: LuaStateHandle): LuaKernelOutcome = nativeLock.withLock {
            enterNative("close")
            try {
                closeCalls.incrementAndGet()
                LuaKernelOutcome.Closed(stateId = 1, generation = 1)
            } finally {
                leaveNative()
            }
        }

        override fun loadProgramImage(
            handle: LuaStateHandle,
            entryPoint: String,
            sourceMap: Map<String, String>
        ): LuaKernelOutcome = completed()

        override fun invokeStartupCallback(
            handle: LuaStateHandle,
            callbackHandle: LuaCallbackHandle,
            config: LuaValue,
            spawnAdmission: LuaSpawnAdmission,
        ): LuaKernelOutcome = completed()

        override fun invokeCallback(
            handle: LuaStateHandle,
            callbackHandle: LuaCallbackHandle,
            arguments: LuaValue,
            spawnAdmission: LuaSpawnAdmission,
        ): LuaKernelOutcome = completed()
        override fun invokeInputCallback(
            handle: LuaStateHandle,
            callbackHandle: LuaCallbackHandle,
            arguments: LuaValue,
            capturedAudioToken: String,
            spawnAdmission: LuaSpawnAdmission,
        ): LuaKernelOutcome = invokeCallback(handle, callbackHandle, arguments, spawnAdmission)


        override fun startCoroutine(
            handle: LuaStateHandle,
            coroutineId: LuaCoroutineId,
            spawnAdmission: LuaSpawnAdmission,
        ): LuaKernelOutcome = completed()

        private fun completed(): LuaKernelOutcome = LuaKernelOutcome.Completed(
            stateId = 1,
            generation = 1,
            coroutineId = null,
            value = null,
            elapsedNanos = 0,
            luaVersion = "5.4",
            bindingVersion = "test",
            topology = "recording",
        )
    }
}
