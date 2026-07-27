package io.talkcan.audio

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AwaitStableMediaRouteTest {

    @Test
    fun immediateReadinessRequiresFullContinuousStabilityWindow() = runTest {
        val time = FakeTime()

        val ready = awaitStableMediaRoute(
            isReady = { true },
            nowMs = time::nowMs,
            delayMs = time::delayMs,
            stableForMs = STABLE_FOR_MS,
            pollMs = POLL_MS,
            timeoutMs = TIMEOUT_MS,
        )

        assertTrue(ready)
        assertEquals(STABLE_FOR_MS, time.elapsedMs)
    }

    @Test
    fun interruptedReadinessSucceedsOnlyAfterRestartedFullStabilityWindow() = runTest {
        val time = FakeTime()

        val ready = awaitStableMediaRoute(
            isReady = { time.elapsedMs != INTERRUPTION_MS },
            nowMs = time::nowMs,
            delayMs = time::delayMs,
            stableForMs = STABLE_FOR_MS,
            pollMs = POLL_MS,
            timeoutMs = TIMEOUT_MS,
        )

        assertTrue(ready)
        assertEquals(INTERRUPTION_MS + POLL_MS + STABLE_FOR_MS, time.elapsedMs)
    }

    @Test
    fun persistentInstabilityTimesOutAtDeadline() = runTest {
        val time = FakeTime()

        val ready = awaitStableMediaRoute(
            isReady = { (time.elapsedMs / POLL_MS) % 2L == 0L },
            nowMs = time::nowMs,
            delayMs = time::delayMs,
            stableForMs = STABLE_FOR_MS,
            pollMs = POLL_MS,
            timeoutMs = TIMEOUT_MS,
        )

        assertFalse(ready)
        assertEquals(TIMEOUT_MS, time.elapsedMs)
    }

    private class FakeTime {
        var elapsedMs: Long = 0L
            private set

        fun nowMs(): Long = elapsedMs

        suspend fun delayMs(durationMs: Long) {
            elapsedMs += durationMs
        }
    }

    private companion object {
        const val STABLE_FOR_MS = 500L
        const val POLL_MS = 50L
        const val TIMEOUT_MS = 3_000L
        const val INTERRUPTION_MS = 250L
    }
}
