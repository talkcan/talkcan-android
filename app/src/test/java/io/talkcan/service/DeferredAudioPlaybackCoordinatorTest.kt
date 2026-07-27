package io.talkcan.service

import io.talkcan.channel.capability.AgentOperationContext
import io.talkcan.channel.capability.AudioOperationArtifact
import io.talkcan.audio.RecordedPcm
import io.talkcan.channel.capability.CapabilityScopeIdentity
import io.talkcan.channel.capability.OpaqueAudioOperation
import io.talkcan.channel.capability.RuntimeGeneration
import io.talkcan.model.AgentOperationId
import io.talkcan.model.AgentRunId
import io.talkcan.model.DelayedPlaybackFailureReason
import io.talkcan.model.DelayedPlaybackOutcome
import java.util.ArrayDeque
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.junit.Assert.assertFalse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeferredAudioPlaybackCoordinatorTest {
    @Test
    fun staleOperationIsNotEnqueued() = runTest {
        val audio = RecordingAudio(ArrayDeque())
        val coordinator = coordinator(this, { "alpha" }, audio, operationIsCurrent = { false })

        val outcome = coordinator.scheduleAudio(context("alpha"), operation("stale"))

        assertEquals(DelayedPlaybackOutcome.Stale, outcome)
        advanceUntilIdle()
        assertTrue(audio.requests.isEmpty())
    }

    @Test
    fun unselectedChannelAudioRemainsPendingUntilSelected() = runTest {
        val audio = RecordingAudio(ArrayDeque())
        var selected = "bravo"
        val coordinator = coordinator(this, { selected }, audio)

        val outcome = coordinator.scheduleAudio(context("alpha"), operation("pending"))
        advanceUntilIdle()

        assertTrue(outcome is DelayedPlaybackOutcome.Pending)
        assertTrue(audio.requests.isEmpty())

        selected = "alpha"
        coordinator.onChannelSelected("alpha")
        advanceUntilIdle()

        assertEquals(1, audio.requests.size)
        assertEquals("alpha", audio.requests.single().channelInstanceId)
    }

    @Test
    fun selectedChannelAudioIsDeliveredOnceAndRemovedFromQueue() = runTest {
        val artifact = operation("delivered")
        val audio = RecordingAudio(ArrayDeque(listOf(DelayedPlaybackAudioResult.Completed)))
        val coordinator = coordinator(this, { "alpha" }, audio)

        val outcome = coordinator.scheduleAudio(context("alpha"), artifact)
        advanceUntilIdle()

        assertTrue(outcome is DelayedPlaybackOutcome.Pending)
        assertEquals(1, audio.requests.size)
        assertSame(artifact, audio.requests.single().audio)

        coordinator.onAudioAvailable()
        advanceUntilIdle()

        assertEquals(1, audio.requests.size)
    }

    @Test
    fun busyAudioRetriesTheSameArtifactWhenAdmissionReopens() = runTest {
        val artifact = operation("retry")
        val audio = RecordingAudio(
            ArrayDeque(listOf(DelayedPlaybackAudioResult.Busy, DelayedPlaybackAudioResult.Completed)),
        )
        val coordinator = coordinator(this, { "alpha" }, audio)

        coordinator.scheduleAudio(context("alpha"), artifact)
        advanceUntilIdle()

        assertEquals(1, audio.requests.size)

        coordinator.onAudioAvailable()
        advanceUntilIdle()

        assertEquals(2, audio.requests.size)
        assertSame(artifact, audio.requests[0].audio)
        assertSame(artifact, audio.requests[1].audio)
    }

    @Test
    fun selectionChangeDuringDeliveryDiscardsTheEntryWithoutPlayback() = runTest {
        val audio = RecordingAudio(ArrayDeque(listOf(DelayedPlaybackAudioResult.Completed)))
        var firstCall = true
        val coordinator = coordinator(
            this,
            selectedChannel = {
                if (firstCall) {
                    firstCall = false
                    "alpha"
                } else {
                    "bravo"
                }
            },
            audio = audio,
        )

        coordinator.scheduleAudio(context("alpha"), operation("discarded"))
        advanceUntilIdle()

        assertTrue(audio.requests.isEmpty())
    }

    @Test
    fun explicitlySkippedAudioIsRemovedFromTheQueue() = runTest {
        val audio = RecordingAudio(ArrayDeque(listOf(DelayedPlaybackAudioResult.ExplicitlySkipped)))
        val coordinator = coordinator(this, { "alpha" }, audio)

        coordinator.scheduleAudio(context("alpha"), operation("skipped"))
        advanceUntilIdle()

        assertEquals(1, audio.requests.size)

        coordinator.onAudioAvailable()
        advanceUntilIdle()

        assertEquals(1, audio.requests.size)
    }

    @Test
    fun interruptedCancelledAndFailedAudioStaysPendingForRetry() = runTest {
        data class FailureCase(
            val name: String,
            val result: DelayedPlaybackAudioResult,
        )
        val cases = listOf(
            FailureCase("interrupted", DelayedPlaybackAudioResult.Interrupted),
            FailureCase("cancelled", DelayedPlaybackAudioResult.Cancelled),
            FailureCase("failed", DelayedPlaybackAudioResult.Failed(DelayedPlaybackFailureReason.PLAYBACK_FAILED)),
        )

        cases.forEach { failure ->
            val audio = RecordingAudio(ArrayDeque(listOf(failure.result, DelayedPlaybackAudioResult.Completed)))
            val coordinator = coordinator(this, { "alpha" }, audio)

            coordinator.scheduleAudio(context("alpha"), operation(failure.name))
            advanceUntilIdle()

            assertEquals(failure.name, 1, audio.requests.size)

            coordinator.onAudioAvailable()
            advanceUntilIdle()

            assertEquals(failure.name, 2, audio.requests.size)
        }
    }

    @Test
    fun thrownAudioBoundaryKeepsEntryPendingAndRetriesOnNextPump() = runTest {
        val audio = ThrowingThenCompletingAudio()
        val coordinator = coordinator(this, { "alpha" }, audio)

        coordinator.scheduleAudio(context("alpha"), operation("exception"))
        advanceUntilIdle()

        assertEquals(1, audio.requests.size)

        coordinator.onAudioAvailable()
        advanceUntilIdle()

        assertEquals(2, audio.requests.size)
    }

    @Test
    fun multiplePendingEntriesAreDeliveredInFifoOrderForTheSelectedChannel() = runTest {
        val first = operation("first")
        val second = operation("second")
        val audio = RecordingAudio(
            ArrayDeque(listOf(DelayedPlaybackAudioResult.Completed, DelayedPlaybackAudioResult.Completed)),
        )
        val coordinator = coordinator(this, { "alpha" }, audio)

        coordinator.scheduleAudio(context("alpha"), first)
        coordinator.scheduleAudio(context("alpha"), second)
        advanceUntilIdle()

        assertEquals(2, audio.requests.size)
        assertSame(first, audio.requests[0].audio)
        assertSame(second, audio.requests[1].audio)
    }

    @Test
    fun closeClearsPendingAndCancelsDelivery() = runTest {
        val audio = RecordingAudio(ArrayDeque())
        val coordinator = coordinator(this, { "bravo" }, audio)

        coordinator.scheduleAudio(context("alpha"), operation("pending"))
        advanceUntilIdle()
        assertTrue(audio.requests.isEmpty())

        coordinator.close()
        coordinator.onAudioAvailable()
        advanceUntilIdle()

        assertTrue(audio.requests.isEmpty())
    }

    @Test
    fun delayedAudioIsNotPlayedBeforeEligibilityDelayElapses() = runTest {
        val audio = RecordingAudio(ArrayDeque(listOf(DelayedPlaybackAudioResult.Completed)))
        val virtualClock = VirtualClock(this)
        val coordinator = coordinator(this, { "alpha" }, audio, nowMillis = virtualClock::now)

        val outcome = coordinator.scheduleAudio(context("alpha"), operation("delayed"), eligibilityDelayMillis = 5_000L)
        runCurrent()

        assertTrue(outcome is DelayedPlaybackOutcome.Pending)
        assertTrue("audio must not play before eligibility delay", audio.requests.isEmpty())

        advanceTimeBy(4_999L)
        runCurrent()
        assertTrue("audio must still not play one millisecond before eligibility", audio.requests.isEmpty())
    }

    @Test
    fun delayedAdmissionReturnsToCallerWithoutSuspendingForDelay() = runTest {
        val audio = RecordingAudio(ArrayDeque())
        val virtualClock = VirtualClock(this)
        val coordinator = coordinator(this, { "alpha" }, audio, nowMillis = virtualClock::now)

        val caller = async {
            coordinator.scheduleAudio(context("alpha"), operation("caller-continues"), eligibilityDelayMillis = 5_000L)
        }
        runCurrent()

        assertTrue("the caller must not wait for host-side eligibility", caller.isCompleted)
        assertTrue(caller.await() is DelayedPlaybackOutcome.Pending)
        assertTrue(audio.requests.isEmpty())
    }


    @Test
    fun equalEligibilityPreservesAdmissionOrder() = runTest {
        val first = operation("equal-first")
        val second = operation("equal-second")
        val audio = RecordingAudio(
            ArrayDeque(listOf(DelayedPlaybackAudioResult.Completed, DelayedPlaybackAudioResult.Completed)),
        )
        val virtualClock = VirtualClock(this)
        val coordinator = coordinator(this, { "alpha" }, audio, nowMillis = virtualClock::now)

        coordinator.scheduleAudio(context("alpha"), first, eligibilityDelayMillis = 5_000L)
        coordinator.scheduleAudio(context("alpha"), second, eligibilityDelayMillis = 5_000L)
        runCurrent()
        assertTrue(audio.requests.isEmpty())

        advanceTimeBy(5_000L)
        runCurrent()

        assertEquals(2, audio.requests.size)
        assertSame(first, audio.requests[0].audio)
        assertSame(second, audio.requests[1].audio)
    }

    @Test
    fun routePortIsNotInvokedUntilQueueAdmissionHasCommitted() = runTest {
        val events = mutableListOf<String>()
        val audio = object : DeferredAudioPlaybackAudioPort {
            override suspend fun playOperationIfAdmitted(
                channelInstanceId: String,
                audio: OpaqueAudioOperation,
            ): DelayedPlaybackAudioResult {
                events += "route"
                return DelayedPlaybackAudioResult.Completed
            }
        }
        val coordinator = coordinator(this, { "alpha" }, audio)

        val outcome = coordinator.scheduleAudio(context("alpha"), operation("admission-order"))

        assertTrue(outcome is DelayedPlaybackOutcome.Pending)
        assertTrue("queue admission must precede physical route resolution", events.isEmpty())
        assertEquals(mapOf("alpha" to 1), coordinator.pendingCounts.value)

        advanceUntilIdle()

        assertEquals(listOf("route"), events)
        assertEquals(emptyMap<String, Int>(), coordinator.pendingCounts.value)
    }

    @Test
    fun inactiveSelectionRetainsEntriesWithoutPlaybackOrRouteCalls() = runTest {
        var selected = "alpha"
        val audio = RecordingAudio(
            ArrayDeque(listOf(DelayedPlaybackAudioResult.Completed, DelayedPlaybackAudioResult.Completed)),
            routeToken = { "route-${selected}" },
        )
        val coordinator = coordinator(this, { selected }, audio)
        val first = operation("inactive-first")
        val second = operation("inactive-second")

        coordinator.scheduleAudio(context("alpha"), first)
        coordinator.scheduleAudio(context("alpha"), second)
        selected = "bravo"
        coordinator.onChannelSelected("bravo")
        runCurrent()

        assertTrue("inactive entries must remain queued", audio.requests.isEmpty())
        assertTrue("inactive entries must not resolve or invoke a route", audio.routeCalls.isEmpty())
        assertEquals(mapOf("alpha" to 2), coordinator.pendingCounts.value)

        selected = "alpha"
        coordinator.onChannelSelected("alpha")
        advanceUntilIdle()

        assertEquals(2, audio.requests.size)
        assertSame(first, audio.requests[0].audio)
        assertSame(second, audio.requests[1].audio)
    }

    @Test
    fun reselectingChannelReevaluatesCurrentRouteAndAdmissionBeforeFifoPlayback() = runTest {
        var selected = "bravo"
        var route = "route-v1"
        val first = operation("reselected-first")
        val second = operation("reselected-second")
        val audio = RecordingAudio(
            ArrayDeque(
                listOf(
                    DelayedPlaybackAudioResult.Busy,
                    DelayedPlaybackAudioResult.Completed,
                    DelayedPlaybackAudioResult.Completed,
                ),
            ),
            routeToken = { route },
        )
        val coordinator = coordinator(this, { selected }, audio)

        coordinator.scheduleAudio(context("alpha"), first)
        coordinator.scheduleAudio(context("alpha"), second)
        runCurrent()
        assertTrue(audio.requests.isEmpty())
        assertTrue(audio.routeCalls.isEmpty())

        route = "route-v2"
        selected = "alpha"
        coordinator.onChannelSelected("alpha")
        runCurrent()

        assertEquals("head admission failure must not overtake with the tail", 1, audio.requests.size)
        assertEquals(listOf("route-v2"), audio.routeCalls)
        assertSame(first, audio.requests.single().audio)
        assertEquals(mapOf("alpha" to 2), coordinator.pendingCounts.value)

        coordinator.onAudioAvailable()
        advanceUntilIdle()

        assertEquals(3, audio.requests.size)
        assertEquals(listOf("route-v2", "route-v2", "route-v2"), audio.routeCalls)
        assertSame(first, audio.requests[1].audio)
        assertSame(second, audio.requests[2].audio)
    }

    @Test
    fun retryableHeadFailuresNeverAllowReadyTailToOvertake() = runTest {
        val failures = listOf(
            DelayedPlaybackAudioResult.Busy,
            DelayedPlaybackAudioResult.Interrupted,
            DelayedPlaybackAudioResult.Cancelled,
            DelayedPlaybackAudioResult.Failed(DelayedPlaybackFailureReason.PLAYBACK_FAILED),
        )
        failures.forEach { failure ->
            val first = operation("head-${failure::class.simpleName}")
            val second = operation("tail-${failure::class.simpleName}")
            val audio = RecordingAudio(
                ArrayDeque(listOf(failure, DelayedPlaybackAudioResult.Completed, DelayedPlaybackAudioResult.Completed)),
            )
            val coordinator = coordinator(this, { "alpha" }, audio)

            coordinator.scheduleAudio(context("alpha"), first)
            coordinator.scheduleAudio(context("alpha"), second)
            runCurrent()

            assertEquals("$failure must leave tail untouched", 1, audio.requests.size)
            assertSame(first, audio.requests.single().audio)

            coordinator.onAudioAvailable()
            advanceUntilIdle()

            assertEquals("$failure must retry head before tail", 3, audio.requests.size)
            assertSame(first, audio.requests[1].audio)
            assertSame(second, audio.requests[2].audio)
        }
    }

    @Test
    fun thrownHeadFailureNeverAllowsReadyTailToOvertake() = runTest {
        val first = operation("thrown-head")
        val second = operation("thrown-tail")
        val audio = ThrowingHeadThenCompletingAudio()
        val coordinator = coordinator(this, { "alpha" }, audio)

        coordinator.scheduleAudio(context("alpha"), first)
        coordinator.scheduleAudio(context("alpha"), second)
        runCurrent()

        assertEquals(1, audio.requests.size)
        assertSame(first, audio.requests.single().audio)

        coordinator.onAudioAvailable()
        advanceUntilIdle()

        assertEquals(3, audio.requests.size)
        assertSame(first, audio.requests[1].audio)
        assertSame(second, audio.requests[2].audio)
    }

    @Test
    fun siblingChannelQueuesRemainIsolatedAcrossSelectionChanges() = runTest {
        var selected = "alpha"
        val audio = RecordingAudio(
            ArrayDeque(listOf(DelayedPlaybackAudioResult.Completed, DelayedPlaybackAudioResult.Completed)),
            routeToken = { "route-${selected}" },
        )
        val coordinator = coordinator(this, { selected }, audio)
        val alpha = operation("sibling-alpha")
        val bravo = operation("sibling-bravo")

        coordinator.scheduleAudio(context("alpha"), alpha)
        coordinator.scheduleAudio(context("bravo"), bravo)
        runCurrent()

        assertEquals(1, audio.requests.size)
        assertSame(alpha, audio.requests.single().audio)
        assertEquals(mapOf("bravo" to 1), coordinator.pendingCounts.value)
        assertEquals(listOf("route-alpha"), audio.routeCalls)

        selected = "bravo"
        coordinator.onChannelSelected("bravo")
        advanceUntilIdle()

        assertEquals(2, audio.requests.size)
        assertSame(bravo, audio.requests[1].audio)
        assertEquals(listOf("route-alpha", "route-bravo"), audio.routeCalls)
    }

    @Test
    fun delayedAudioWakesAtEligibilityAndPlays() = runTest {
        val audio = RecordingAudio(ArrayDeque(listOf(DelayedPlaybackAudioResult.Completed)))
        val virtualClock = VirtualClock(this)
        val coordinator = coordinator(this, { "alpha" }, audio, nowMillis = virtualClock::now)

        coordinator.scheduleAudio(context("alpha"), operation("delayed"), eligibilityDelayMillis = 5_000L)
        runCurrent()
        assertTrue(audio.requests.isEmpty())

        advanceTimeBy(5_000L)
        runCurrent()

        assertEquals("audio must play once eligibility delay elapses", 1, audio.requests.size)
        assertEquals("alpha", audio.requests.single().channelInstanceId)
    }

    @Test
    fun delayedSameChannelEntriesDoNotOvertakeBeforeEligibility() = runTest {
        val first = operation("delayed-first")
        val second = operation("immediate-second")
        val audio = RecordingAudio(
            ArrayDeque(listOf(DelayedPlaybackAudioResult.Completed, DelayedPlaybackAudioResult.Completed)),
        )
        val virtualClock = VirtualClock(this)
        val coordinator = coordinator(this, { "alpha" }, audio, nowMillis = virtualClock::now)

        coordinator.scheduleAudio(context("alpha"), first, eligibilityDelayMillis = 5_000L)
        coordinator.scheduleAudio(context("alpha"), second, eligibilityDelayMillis = 0L)
        runCurrent()

        assertTrue("second entry must not overtake delayed first entry", audio.requests.isEmpty())

        advanceTimeBy(5_000L)
        runCurrent()

        assertEquals(2, audio.requests.size)
        assertSame("FIFO order preserved: delayed first plays before immediate second", first, audio.requests[0].audio)
        assertSame(second, audio.requests[1].audio)
    }

    @Test
    fun delayedAudioOnInactiveChannelIsRetainedAndDeliveredAfterSelection() = runTest {
        val audio = RecordingAudio(ArrayDeque(listOf(DelayedPlaybackAudioResult.Completed)))
        val virtualClock = VirtualClock(this)
        var selected = "bravo"
        val coordinator = coordinator(this, { selected }, audio, nowMillis = virtualClock::now)

        val outcome = coordinator.scheduleAudio(context("alpha"), operation("retained"), eligibilityDelayMillis = 5_000L)
        runCurrent()
        assertTrue(outcome is DelayedPlaybackOutcome.Pending)
        assertTrue("must not play on unselected channel", audio.requests.isEmpty())

        advanceTimeBy(5_000L)
        runCurrent()
        assertTrue("must retain even after eligibility while channel unselected", audio.requests.isEmpty())

        selected = "alpha"
        coordinator.onChannelSelected("alpha")
        advanceUntilIdle()

        assertEquals(1, audio.requests.size)
        assertEquals("alpha", audio.requests.single().channelInstanceId)
    }

    @Test
    fun closeCancelsDelayedWakeAndPreventsSubsequentPlayback() = runTest {
        val audio = RecordingAudio(ArrayDeque(listOf(DelayedPlaybackAudioResult.Completed)))
        val virtualClock = VirtualClock(this)
        val coordinator = coordinator(this, { "alpha" }, audio, nowMillis = virtualClock::now)

        coordinator.scheduleAudio(context("alpha"), operation("cancelled-wake"), eligibilityDelayMillis = 5_000L)
        runCurrent()
        assertTrue(audio.requests.isEmpty())

        coordinator.close()
        advanceTimeBy(5_000L)
        runCurrent()
        coordinator.onAudioAvailable()
        advanceUntilIdle()

        assertTrue("cancelled wake must not trigger playback after close", audio.requests.isEmpty())
    }

    @Test
    fun delayedPendingCountsAppearOnlyAtEligibilityOnInactiveChannel() = runTest {
        val audio = RecordingAudio(ArrayDeque(listOf(DelayedPlaybackAudioResult.Completed)))
        val virtualClock = VirtualClock(this)
        val coordinator = coordinator(this, { "bravo" }, audio, nowMillis = virtualClock::now)

        coordinator.scheduleAudio(context("alpha"), operation("delayed"), eligibilityDelayMillis = 5_000L)
        runCurrent()

        assertEquals("delayed Echo must not count before eligibility", emptyMap<String, Int>(), coordinator.pendingCounts.value)

        advanceTimeBy(4_999L)
        runCurrent()
        assertEquals("delayed Echo must still not count one millisecond before eligibility", emptyMap<String, Int>(), coordinator.pendingCounts.value)

        advanceTimeBy(1L)
        runCurrent()
        assertEquals("delayed Echo must count exactly at eligibility while its channel is inactive", mapOf("alpha" to 1), coordinator.pendingCounts.value)
        assertTrue("eligible inactive Echo must remain queued", audio.requests.isEmpty())
    }

    @Test
    fun pendingCountsRetainsInactiveChannelAcrossDelayAndSelection() = runTest {
        val audio = RecordingAudio(ArrayDeque(listOf(DelayedPlaybackAudioResult.Completed)))
        val virtualClock = VirtualClock(this)
        var selected = "bravo"
        val coordinator = coordinator(this, { selected }, audio, nowMillis = virtualClock::now)

        coordinator.scheduleAudio(context("alpha"), operation("retained"), eligibilityDelayMillis = 5_000L)
        runCurrent()
        assertEquals("ineligible inactive entry must not count", emptyMap<String, Int>(), coordinator.pendingCounts.value)

        advanceTimeBy(4_999L)
        runCurrent()
        assertEquals("ineligible inactive entry must still not count one millisecond before eligibility", emptyMap<String, Int>(), coordinator.pendingCounts.value)

        advanceTimeBy(1L)
        runCurrent()
        assertEquals("eligible inactive entry must count", mapOf("alpha" to 1), coordinator.pendingCounts.value)
        assertTrue("must not play on unselected channel", audio.requests.isEmpty())

        selected = "alpha"
        coordinator.onChannelSelected("alpha")
        advanceUntilIdle()

        assertEquals("count cleared after delivery on selection", emptyMap<String, Int>(), coordinator.pendingCounts.value)
    }

    @Test
    fun pendingCountsDecrementsOnCompletion() = runTest {
        val audio = RecordingAudio(ArrayDeque(listOf(DelayedPlaybackAudioResult.Completed)))
        val coordinator = coordinator(this, { "alpha" }, audio)

        coordinator.scheduleAudio(context("alpha"), operation("done"), eligibilityDelayMillis = 0L)
        assertEquals("zero-delay entry counts immediately before its pump runs", mapOf("alpha" to 1), coordinator.pendingCounts.value)

        advanceUntilIdle()

        assertEquals("completed entry removed from counts", emptyMap<String, Int>(), coordinator.pendingCounts.value)
    }

    @Test
    fun pendingCountsDecrementsOnExplicitSkip() = runTest {
        val audio = RecordingAudio(ArrayDeque(listOf(DelayedPlaybackAudioResult.ExplicitlySkipped)))
        val coordinator = coordinator(this, { "alpha" }, audio)

        coordinator.scheduleAudio(context("alpha"), operation("skipped"))
        assertEquals("count present before delivery", mapOf("alpha" to 1), coordinator.pendingCounts.value)

        coordinator.onAudioAvailable()
        advanceUntilIdle()

        assertEquals("explicitly skipped entry removed from counts", emptyMap<String, Int>(), coordinator.pendingCounts.value)
    }

    @Test
    fun pendingCountsRetainsAcrossRetryableFailures() = runTest {
        data class FailureCase(val name: String, val result: DelayedPlaybackAudioResult)
        val cases = listOf(
            FailureCase("busy", DelayedPlaybackAudioResult.Busy),
            FailureCase("interrupted", DelayedPlaybackAudioResult.Interrupted),
            FailureCase("cancelled", DelayedPlaybackAudioResult.Cancelled),
            FailureCase("failed", DelayedPlaybackAudioResult.Failed(DelayedPlaybackFailureReason.PLAYBACK_FAILED)),
        )

        cases.forEach { failure ->
            val audio = RecordingAudio(ArrayDeque(listOf(failure.result, DelayedPlaybackAudioResult.Completed)))
            val coordinator = coordinator(this, { "alpha" }, audio)

            coordinator.scheduleAudio(context("alpha"), operation(failure.name))
            runCurrent()
            assertEquals("${failure.name}: count present after schedule", mapOf("alpha" to 1), coordinator.pendingCounts.value)

            advanceUntilIdle()

            assertEquals("${failure.name}: count retained after retryable failure", mapOf("alpha" to 1), coordinator.pendingCounts.value)
        }
    }

    @Test
    fun pendingCountsClearsOnClose() = runTest {
        val audio = RecordingAudio(ArrayDeque())
        val coordinator = coordinator(this, { "bravo" }, audio)

        coordinator.scheduleAudio(context("alpha"), operation("pending"))
        runCurrent()
        assertEquals(mapOf("alpha" to 1), coordinator.pendingCounts.value)

        coordinator.close()

        assertEquals("close clears all pending counts", emptyMap<String, Int>(), coordinator.pendingCounts.value)
    }

    @Test
    fun pendingCountsAccumulateAcrossChannelsAndOmitZeroEntries() = runTest {
        val audio = RecordingAudio(
            ArrayDeque(listOf(DelayedPlaybackAudioResult.Completed, DelayedPlaybackAudioResult.Completed)),
        )
        val coordinator = coordinator(this, { "alpha" }, audio)

        coordinator.scheduleAudio(context("alpha"), operation("alpha-a"))
        coordinator.scheduleAudio(context("bravo"), operation("bravo-a"))
        assertEquals("additive counting across channels", mapOf("alpha" to 1, "bravo" to 1), coordinator.pendingCounts.value)

        advanceUntilIdle()

        assertEquals("completed channel omitted, unselected channel retained", mapOf("bravo" to 1), coordinator.pendingCounts.value)
    }

    @Test
    fun `quota limits reject zero and negative bounds`() {
        val base = DeferredAudioPlaybackCoordinator.Limits(
            maxEntriesPerInstance = 1,
            maxBytesPerInstance = 1L,
            maxEntriesPerGeneration = 1,
            maxBytesPerGeneration = 1L,
        )
        assertEquals(1, base.maxEntriesPerInstance)
        assertRejects { base.copy(maxEntriesPerInstance = 0) }
        assertRejects { base.copy(maxEntriesPerInstance = -1) }
        assertRejects { base.copy(maxEntriesPerGeneration = 0) }
        assertRejects { base.copy(maxEntriesPerGeneration = -1) }
        assertRejects { base.copy(maxBytesPerGeneration = 0L) }
        assertRejects { base.copy(maxBytesPerGeneration = -1L) }
        assertRejects { base.copy(maxBytesPerInstance = 0L) }
        assertRejects { base.copy(maxBytesPerInstance = -1L) }
    }

    @Test
    fun `process quota limits reject zero and negative bounds`() {
        DeferredAudioPlaybackCoordinator.ProcessQuota(1, 1L)
        assertRejects { DeferredAudioPlaybackCoordinator.ProcessQuota(0, 1L) }
        assertRejects { DeferredAudioPlaybackCoordinator.ProcessQuota(-1, 1L) }
        assertRejects { DeferredAudioPlaybackCoordinator.ProcessQuota(1, 0L) }
        assertRejects { DeferredAudioPlaybackCoordinator.ProcessQuota(1, -1L) }
    }

    @Test
    fun `instance entry count accepts exact boundary and rejects plus one atomically`() = runTest {
        val processQuota = wideProcessQuota()
        val audio = RecordingAudio(ArrayDeque())
        val coordinator = coordinator(
            this,
            { "inactive" },
            audio,
            limits = wideLimits { copy(maxEntriesPerInstance = 2) },
            processQuota = processQuota,
        )
        val first = operation("instance-1")
        val second = operation("instance-2")
        val rejected = operation("instance-3")

        assertTrue(coordinator.scheduleAudio(context("alpha"), first) is DelayedPlaybackOutcome.Pending)
        assertTrue(coordinator.scheduleAudio(context("alpha"), second) is DelayedPlaybackOutcome.Pending)
        val outcome = coordinator.scheduleAudio(context("alpha"), rejected)

        assertEquals(DelayedPlaybackOutcome.Busy, outcome)
        assertFalse(rejected.isDisposed)
        assertEquals(2, processQuota.liveEntries())
        assertEquals(mapOf("alpha" to 2), coordinator.pendingCounts.value)
    }

    @Test
    fun `instance retained bytes accepts exact boundary and rejects plus one`() = runTest {
        val processQuota = wideProcessQuota()
        val coordinator = coordinator(
            this,
            { "inactive" },
            RecordingAudio(ArrayDeque()),
            limits = wideLimits { copy(maxBytesPerInstance = 4L) },
            processQuota = processQuota,
        )
        assertTrue(coordinator.scheduleAudio(context("alpha"), operation("bytes-1", sampleCount = 1)) is DelayedPlaybackOutcome.Pending)
        assertTrue(coordinator.scheduleAudio(context("bravo"), operation("bytes-2", sampleCount = 1)) is DelayedPlaybackOutcome.Pending)
        val rejected = operation("bytes-3", sampleCount = 3)

        assertEquals(DelayedPlaybackOutcome.Busy, coordinator.scheduleAudio(context("charlie"), rejected))
        assertFalse(rejected.isDisposed)
        assertEquals(4L, coordinator.accounting().retainedBytes)
        assertEquals(4L, processQuota.retainedBytes())
    }

    @Test
    fun `generation entry count is isolated between sibling generations`() = runTest {
        val coordinator = coordinator(
            this,
            { "inactive" },
            RecordingAudio(ArrayDeque()),
            limits = wideLimits { copy(maxEntriesPerGeneration = 1) },
            processQuota = wideProcessQuota(),
        )
        val generation0 = RuntimeGeneration(0)
        val generation1 = RuntimeGeneration(1)
        assertTrue(coordinator.scheduleAudio(context("alpha", generation0), operation("g0", 1, generation0)) is DelayedPlaybackOutcome.Pending)
        val g0Rejected = operation("g0-over", 1, generation0)
        assertEquals(DelayedPlaybackOutcome.Busy, coordinator.scheduleAudio(context("alpha", generation0), g0Rejected))
        assertFalse(g0Rejected.isDisposed)
        val g1 = operation("g1", 1, generation1)
        assertTrue(coordinator.scheduleAudio(context("alpha", generation1), g1) is DelayedPlaybackOutcome.Pending)
        assertFalse(g1.isDisposed)
        assertEquals(2, coordinator.accounting().liveEntries)
        assertEquals(2, coordinator.accounting().perChannel["alpha"]?.entries)
    }

    @Test
    fun `generation retained bytes accepts exact boundary and rejects plus one`() = runTest {
        val processQuota = wideProcessQuota()
        val limits = wideLimits { copy(maxBytesPerGeneration = 4L) }
        val coordinator = coordinator(this, { "inactive" }, RecordingAudio(ArrayDeque()), limits, processQuota)
        val generation = RuntimeGeneration(0)
        assertTrue(coordinator.scheduleAudio(context("alpha", generation), operation("g-bytes-1", 1, generation)) is DelayedPlaybackOutcome.Pending)
        assertTrue(coordinator.scheduleAudio(context("alpha", generation), operation("g-bytes-2", 1, generation)) is DelayedPlaybackOutcome.Pending)
        val rejected = operation("g-bytes-3", 3, generation)

        assertEquals(DelayedPlaybackOutcome.Busy, coordinator.scheduleAudio(context("alpha", generation), rejected))
        assertFalse(rejected.isDisposed)
        assertEquals(4L, coordinator.accounting().retainedBytes)
        assertEquals(4L, processQuota.retainedBytes())
    }


    @Test
    fun `process retained bytes accepts exact boundary and rejects plus one`() = runTest {
        val processQuota = DeferredAudioPlaybackCoordinator.ProcessQuota(maxEntries = Int.MAX_VALUE, maxBytes = 4L)
        val coordinator = coordinator(this, { "inactive" }, RecordingAudio(ArrayDeque()), wideLimits(), processQuota)
        assertTrue(coordinator.scheduleAudio(context("alpha"), operation("p-bytes-1", 1)) is DelayedPlaybackOutcome.Pending)
        assertTrue(coordinator.scheduleAudio(context("bravo"), operation("p-bytes-2", 1)) is DelayedPlaybackOutcome.Pending)
        val rejected = operation("p-bytes-3", 3)
        assertEquals(DelayedPlaybackOutcome.Busy, coordinator.scheduleAudio(context("charlie"), rejected))
        assertFalse(rejected.isDisposed)
        assertEquals(4L, processQuota.retainedBytes())
        assertEquals(4L, coordinator.accounting().retainedBytes)
    }
    @Test
    fun `process count and bytes are shared across coordinator instances`() = runTest {
        val processQuota = DeferredAudioPlaybackCoordinator.ProcessQuota(maxEntries = 1, maxBytes = 2L)
        val limits = wideLimits()
        val first = coordinator(this, { "inactive" }, RecordingAudio(ArrayDeque()), limits, processQuota)
        val second = coordinator(this, { "inactive" }, RecordingAudio(ArrayDeque()), limits, processQuota)
        assertTrue(first.scheduleAudio(context("alpha"), operation("first")) is DelayedPlaybackOutcome.Pending)
        val rejected = operation("second")
        assertEquals(DelayedPlaybackOutcome.Busy, second.scheduleAudio(context("bravo"), rejected))
        assertFalse(rejected.isDisposed)
        assertEquals(1, processQuota.liveEntries())
        assertEquals(2L, processQuota.retainedBytes())
        assertEquals(1, first.accounting().liveEntries)
        assertEquals(0, second.accounting().liveEntries)
    }

    @Test
    fun `rejected audio is not partially queued, evicted, or rerouted and remains reusable`() = runTest {
        val audio = RecordingAudio(ArrayDeque(listOf(DelayedPlaybackAudioResult.Completed)))
        var selected = "bravo"
        val coordinator = coordinator(
            this,
            { selected },
            audio,
            limits = wideLimits { copy(maxEntriesPerGeneration = 1) },
            processQuota = wideProcessQuota(),
        )
        val kept = operation("kept")
        assertTrue(coordinator.scheduleAudio(context("alpha"), kept) is DelayedPlaybackOutcome.Pending)
        val rejected = operation("rejected")
        assertEquals(DelayedPlaybackOutcome.Busy, coordinator.scheduleAudio(context("alpha"), rejected))
        assertFalse(rejected.isDisposed)
        assertEquals(1, coordinator.accounting().liveEntries)
        assertEquals(1, coordinator.pendingCounts.value["alpha"])
        selected = "alpha"
        coordinator.onChannelSelected("alpha")
        advanceUntilIdle()
        assertEquals(1, audio.requests.size)
        assertSame(kept, audio.requests.single().audio)
        assertEquals(DelayedPlaybackOutcome.Pending::class, coordinator.scheduleAudio(context("alpha"), rejected)::class)
        assertFalse(rejected.isDisposed)
    }



    /**
     * Task 10.5: Successful queue admission consumes the artifact after completion.
     *
     * The "consume" semantic means that after successful scheduleAudio and subsequent
     * playback completion, the audio operation artifact is disposed exactly once.
     * The caller cannot reuse the handle after consumption.
     */
    @Test
    fun `successful admission consumes the artifact after completion`() = runTest {
        val processQuota = wideProcessQuota()
        val audio = RecordingAudio(ArrayDeque(listOf(DelayedPlaybackAudioResult.Completed)))
        val coordinator = coordinator(this, { "alpha" }, audio, processQuota = processQuota)
        val artifact = operation("consume-after-complete")

        val outcome = coordinator.scheduleAudio(context("alpha"), artifact)
        assertTrue("Admission must succeed", outcome is DelayedPlaybackOutcome.Pending)

        // Before completion: artifact is admitted but not yet consumed
        assertFalse("Artifact must not be disposed during pending playback", artifact.isDisposed)

        advanceUntilIdle()

        // After completion: the artifact is consumed (disposed)
        assertTrue(
            "Artifact must be disposed after successful completion (consume semantic)",
            artifact.isDisposed,
        )
        assertZeroAccounting(coordinator, processQuota)
    }

    /**
     * Task 10.5: Explicitly skipped playback consumes the artifact.
     *
     * Explicit skip is a terminal outcome; the artifact must be disposed
     * exactly once just like normal completion.
     */
    @Test
    fun `explicitly skipped playback consumes the artifact`() = runTest {
        val processQuota = wideProcessQuota()
        val audio = RecordingAudio(ArrayDeque(listOf(DelayedPlaybackAudioResult.ExplicitlySkipped)))
        val coordinator = coordinator(this, { "alpha" }, audio, processQuota = processQuota)
        val artifact = operation("skipped-artifact")

        val outcome = coordinator.scheduleAudio(context("alpha"), artifact)
        assertTrue("Admission must succeed", outcome is DelayedPlaybackOutcome.Pending)

        coordinator.onAudioAvailable()
        advanceUntilIdle()

        assertTrue(
            "Artifact must be disposed after explicit skip (consume semantic)",
            artifact.isDisposed,
        )
        assertZeroAccounting(coordinator, processQuota)
    }

    /**
     * Task 10.5: Interrupted playback retains the artifact for retry (not consumed).
     *
     * Interrupted/Busy/Cancelled are retryable outcomes. The artifact stays
     * in the queue and is NOT disposed until terminal completion.
     */
    @Test
    fun `interrupted playback retains artifact without disposal`() = runTest {
        val processQuota = wideProcessQuota()
        val audio = RecordingAudio(
            ArrayDeque(listOf(
                DelayedPlaybackAudioResult.Interrupted,
                DelayedPlaybackAudioResult.Completed,
            )),
        )
        val coordinator = coordinator(this, { "alpha" }, audio, processQuota = processQuota)
        val artifact = operation("interrupted-artifact")

        coordinator.scheduleAudio(context("alpha"), artifact)
        runCurrent()
        assertEquals(1, audio.requests.size)

        // Interrupted outcome: artifact is NOT disposed, stays in queue
        assertFalse("Interrupted must not dispose artifact", artifact.isDisposed)
        assertEquals(1, coordinator.accounting().liveEntries)

        coordinator.onAudioAvailable()
        advanceUntilIdle()

        // After retry completion: artifact is disposed
        assertTrue("Artifact disposed after retry completion", artifact.isDisposed)
        assertZeroAccounting(coordinator, processQuota)
    }
    @Test
    fun `accounting returns zero after completion`() = runTest {
        val processQuota = wideProcessQuota()
        val audio = RecordingAudio(ArrayDeque(listOf(DelayedPlaybackAudioResult.Completed)))
        val coordinator = coordinator(this, { "alpha" }, audio, processQuota = processQuota)
        coordinator.scheduleAudio(context("alpha"), operation("complete"))
        advanceUntilIdle()
        assertZeroAccounting(coordinator, processQuota)
    }

    @Test
    fun `accounting returns zero after explicit skip`() = runTest {
        val processQuota = wideProcessQuota()
        val audio = RecordingAudio(ArrayDeque(listOf(DelayedPlaybackAudioResult.ExplicitlySkipped)))
        val coordinator = coordinator(this, { "alpha" }, audio, processQuota = processQuota)
        coordinator.scheduleAudio(context("alpha"), operation("skip"))
        advanceUntilIdle()
        assertZeroAccounting(coordinator, processQuota)
    }

    @Test
    fun `selection mismatch retains queued entry until terminal cleanup`() = runTest {
        val processQuota = wideProcessQuota()
        var selectedCalls = 0
        val audio = RecordingAudio(ArrayDeque())
        val coordinator = coordinator(
            this,
            { if (selectedCalls++ == 0) "alpha" else "bravo" },
            audio,
            processQuota = processQuota,
        )

        coordinator.scheduleAudio(context("alpha"), operation("retained"))
        advanceUntilIdle()

        assertTrue(audio.requests.isEmpty())
        assertTrue(audio.routeCalls.isEmpty())
        assertEquals(1, coordinator.accounting().liveEntries)
        assertEquals(1, processQuota.liveEntries())
        assertEquals(mapOf("alpha" to 1), coordinator.pendingCounts.value)

        coordinator.close()
        assertZeroAccounting(coordinator, processQuota)
    }

    @Test
    fun `accounting returns zero after generation revocation`() = runTest {
        val processQuota = wideProcessQuota()
        val coordinator = coordinator(this, { "inactive" }, RecordingAudio(ArrayDeque()), processQuota = processQuota)
        val generation = RuntimeGeneration(0)
        coordinator.scheduleAudio(context("alpha", generation), operation("revoke", 1, generation))
        coordinator.onGenerationTermination(
            CapabilityScopeIdentity("alpha", generation),
            io.talkcan.channel.capability.CapabilityLeaseTermination.REVOKED,
        )
        assertZeroAccounting(coordinator, processQuota)
    }

    @Test
    fun `generation revocation during backend completion disposes predecessor once and preserves successor and sibling`() = runTest {
        val backend = GatedAudio()
        val coordinator = coordinator(this, { "alpha" }, backend)
        val generation0 = RuntimeGeneration(0)
        val generation1 = RuntimeGeneration(1)
        val predecessor = operation("predecessor", generation = generation0)
        val successor = operation("successor", generation = generation1)
        val sibling = operation("sibling", generation = generation0)

        coordinator.scheduleAudio(context("alpha", generation0), predecessor)
        coordinator.scheduleAudio(context("alpha", generation1), successor)
        coordinator.scheduleAudio(context("bravo", generation0), sibling)
        runCurrent()
        backend.started.await()

        val identity = CapabilityScopeIdentity("alpha", generation0)
        coordinator.onGenerationTermination(identity, io.talkcan.channel.capability.CapabilityLeaseTermination.REVOKED)
        coordinator.onGenerationTermination(identity, io.talkcan.channel.capability.CapabilityLeaseTermination.REVOKED)

        assertEquals("predecessor revocation must release its accounting while backend is suspended", 2, coordinator.accounting().liveEntries)
        backend.complete(DelayedPlaybackAudioResult.Completed)
        advanceUntilIdle()

        assertTrue(predecessor.isDisposed)
        assertFalse(successor.isDisposed)
        assertFalse(sibling.isDisposed)
        assertEquals(2, coordinator.accounting().liveEntries)

        coordinator.close()
        assertTrue(successor.isDisposed)
        assertTrue(sibling.isDisposed)
        assertZeroAccounting(coordinator, DeferredAudioPlaybackCoordinator.ProcessQuota.DEFAULT)
    }

    @Test
    fun `close racing generation revocation and late backend completion is idempotent`() = runTest {
        val backend = GatedAudio()
        val processQuota = wideProcessQuota()
        val coordinator = coordinator(this, { "alpha" }, backend, processQuota = processQuota)
        val generation = RuntimeGeneration(0)
        val artifact = operation("close-race", generation = generation)
        coordinator.scheduleAudio(context("alpha", generation), artifact)
        runCurrent()
        backend.started.await()

        val identity = CapabilityScopeIdentity("alpha", generation)
        coordinator.onGenerationTermination(identity, io.talkcan.channel.capability.CapabilityLeaseTermination.REVOKED)
        coordinator.onGenerationTermination(identity, io.talkcan.channel.capability.CapabilityLeaseTermination.REVOKED)
        coordinator.close()
        coordinator.close()
        backend.complete(DelayedPlaybackAudioResult.Completed)
        advanceUntilIdle()

        assertTrue("late completion must not resurrect or double-dispose the predecessor", artifact.isDisposed)
        assertZeroAccounting(coordinator, processQuota)
    }

    @Test
    fun `accounting returns zero after close`() = runTest {
        val processQuota = wideProcessQuota()
        val coordinator = coordinator(this, { "inactive" }, RecordingAudio(ArrayDeque()), processQuota = processQuota)
        coordinator.scheduleAudio(context("alpha"), operation("close"))
        coordinator.close()
        assertZeroAccounting(coordinator, processQuota)
    }

    @Test
    fun `concurrent admissions have one atomic process quota winner`() = runTest {
        val processQuota = DeferredAudioPlaybackCoordinator.ProcessQuota(maxEntries = 1, maxBytes = Long.MAX_VALUE)
        val limits = wideLimits()
        val first = coordinator(this, { "inactive" }, RecordingAudio(ArrayDeque()), limits, processQuota)
        val second = coordinator(this, { "inactive" }, RecordingAudio(ArrayDeque()), limits, processQuota)
        val outcomes = coroutineScope {
            listOf(
                async(Dispatchers.Default) { first.scheduleAudio(context("alpha"), operation("concurrent-a")) },
                async(Dispatchers.Default) { second.scheduleAudio(context("bravo"), operation("concurrent-b")) },
            ).awaitAll()
        }
        assertEquals(1, outcomes.count { it is DelayedPlaybackOutcome.Pending })
        assertEquals(1, outcomes.count { it == DelayedPlaybackOutcome.Busy })
        assertEquals(1, processQuota.liveEntries())
    }

    private fun wideLimits(
        block: DeferredAudioPlaybackCoordinator.Limits.() -> DeferredAudioPlaybackCoordinator.Limits = { this },
    ): DeferredAudioPlaybackCoordinator.Limits = DeferredAudioPlaybackCoordinator.Limits(
        maxEntriesPerInstance = Int.MAX_VALUE,
        maxBytesPerInstance = Long.MAX_VALUE,
        maxEntriesPerGeneration = Int.MAX_VALUE,
        maxBytesPerGeneration = Long.MAX_VALUE,
    ).block()

    private fun wideProcessQuota(): DeferredAudioPlaybackCoordinator.ProcessQuota =
        DeferredAudioPlaybackCoordinator.ProcessQuota(Int.MAX_VALUE, Long.MAX_VALUE)

    private fun assertRejects(block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected IllegalArgumentException but no exception was thrown")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    private fun assertZeroAccounting(
        coordinator: DeferredAudioPlaybackCoordinator,
        processQuota: DeferredAudioPlaybackCoordinator.ProcessQuota,
    ) {
        val accounting = coordinator.accounting()
        assertEquals(0, accounting.liveEntries)
        assertEquals(0L, accounting.retainedBytes)
        assertTrue(accounting.perChannel.isEmpty())
        assertEquals(0, processQuota.liveEntries())
        assertEquals(0L, processQuota.retainedBytes())
    }
    private fun coordinator(
        scope: kotlinx.coroutines.CoroutineScope,
        selectedChannel: suspend () -> String?,
        audio: DeferredAudioPlaybackAudioPort,
        limits: DeferredAudioPlaybackCoordinator.Limits = DeferredAudioPlaybackCoordinator.Limits.DEFAULT,
        processQuota: DeferredAudioPlaybackCoordinator.ProcessQuota = DeferredAudioPlaybackCoordinator.ProcessQuota.DEFAULT,
        operationIsCurrent: suspend (AgentOperationContext) -> Boolean = { true },
        nowMillis: () -> Long = { System.currentTimeMillis() },
    ) = DeferredAudioPlaybackCoordinator(
        scope = scope,
        selectedChannel = selectedChannel,
        operationIsCurrent = operationIsCurrent,
        audio = audio,
        nowMillis = nowMillis,
        limits = limits,
        processQuota = processQuota,
    )

    private fun context(channelInstanceId: String, generation: RuntimeGeneration = RuntimeGeneration(0)): AgentOperationContext = AgentOperationContext(
        scope = CapabilityScopeIdentity(channelInstanceId, generation),
        runId = AgentRunId("run-$channelInstanceId-${generation.value}"),
        operationId = AgentOperationId("operation-$channelInstanceId-${generation.value}"),
    )

    private fun operation(
        operationId: String,
        sampleCount: Int = 1,
        generation: RuntimeGeneration = RuntimeGeneration(0),
    ): AudioOperationArtifact = AudioOperationArtifact(
        RecordedPcm(ShortArray(sampleCount), 16_000),
        operationId = operationId,
        generation = generation,
    )

    private data class PlaybackRequest(
        val channelInstanceId: String,
        val audio: OpaqueAudioOperation,
    )

    private class RecordingAudio(
        internal val outcomes: ArrayDeque<DelayedPlaybackAudioResult>,
        private val routeToken: () -> String = { "route" },
    ) : DeferredAudioPlaybackAudioPort {
        val requests = mutableListOf<PlaybackRequest>()
        val routeCalls = mutableListOf<String>()

        override suspend fun playOperationIfAdmitted(
            channelInstanceId: String,
            audio: OpaqueAudioOperation,
        ): DelayedPlaybackAudioResult {
            routeCalls += routeToken()
            requests += PlaybackRequest(channelInstanceId, audio)
            return outcomes.removeFirst()
        }
    }

    private class ThrowingThenCompletingAudio : DeferredAudioPlaybackAudioPort {
        val requests = mutableListOf<PlaybackRequest>()
        private var firstAttempt = true

        override suspend fun playOperationIfAdmitted(
            channelInstanceId: String,
            audio: OpaqueAudioOperation,
        ): DelayedPlaybackAudioResult {
            requests += PlaybackRequest(channelInstanceId, audio)
            if (firstAttempt) {
                firstAttempt = false
                throw IllegalStateException("audio acknowledgement lost")
            }
            return DelayedPlaybackAudioResult.Completed
        }
    }

    private class ThrowingHeadThenCompletingAudio : DeferredAudioPlaybackAudioPort {
        val requests = mutableListOf<PlaybackRequest>()
        private var firstAttempt = true

        override suspend fun playOperationIfAdmitted(
            channelInstanceId: String,
            audio: OpaqueAudioOperation,
        ): DelayedPlaybackAudioResult {
            requests += PlaybackRequest(channelInstanceId, audio)
            if (firstAttempt) {
                firstAttempt = false
                throw IllegalStateException("audio acknowledgement lost")
            }
            return DelayedPlaybackAudioResult.Completed
        }
    }
    private class GatedAudio : DeferredAudioPlaybackAudioPort {
        val started = CompletableDeferred<Unit>()
        private val completion = CompletableDeferred<DelayedPlaybackAudioResult>()
        private var firstCall = true
        val requests = mutableListOf<PlaybackRequest>()

        override suspend fun playOperationIfAdmitted(
            channelInstanceId: String,
            audio: OpaqueAudioOperation,
        ): DelayedPlaybackAudioResult {
            requests += PlaybackRequest(channelInstanceId, audio)
            if (!firstCall) return DelayedPlaybackAudioResult.Busy
            firstCall = false
            started.complete(Unit)
            return completion.await()
        }

        fun complete(result: DelayedPlaybackAudioResult) {
            completion.complete(result)
        }
    }

    private class VirtualClock(private val scope: TestScope) {
        fun now(): Long = scope.testScheduler.currentTime
    }

}