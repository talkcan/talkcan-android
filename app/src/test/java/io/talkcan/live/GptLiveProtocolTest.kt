package io.talkcan.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Edge-case tests for the GPT-Live delegation bookkeeping.
 *
 * These cover the boundaries where a naive implementation repeats tool effects or
 * loses collected calls: duplicate nested envelopes, terminal identity mismatches,
 * invalid arguments, finite quotas, and unbounded transcripts. Transport, audio, and
 * timing behavior are covered by the MockWebServer protocol tests, which observe the
 * real socket instead of these internals.
 */
class GptLiveProtocolTest {

    @Test
    fun duplicateOutputItemDoneIsCollectedOnce() {
        val tracker = DelegationTracker()
        tracker.onResponseCreated("dlg_1", "rsp_1")

        assertTrue(tracker.onOutputItemDone("dlg_1", "", "function_call", "call_1", "lookup", """{"id":"7"}"""))
        assertFalse(tracker.onOutputItemDone("dlg_1", "", "function_call", "call_1", "lookup", """{"id":"7"}"""))

        val drained = tracker.drainForCompletion("dlg_1", "")
        assertEquals(1, drained.size)
        assertEquals("call_1", drained[0].callId)
        assertFalse(drained[0].argumentsInvalid)
    }

    @Test
    fun duplicateCompletionDrainsOnce() {
        val tracker = DelegationTracker()
        tracker.onResponseCreated("dlg_1", "rsp_1")
        tracker.onOutputItemDone("dlg_1", "", "function_call", "call_1", "lookup", """{"id":"7"}""")

        assertEquals(1, tracker.drainForCompletion("dlg_1", "").size)
        assertTrue(tracker.drainForCompletion("dlg_1", "").isEmpty())
    }

    @Test
    fun terminalWithoutCallsLeavesLateDuplicatesRejected() {
        val tracker = DelegationTracker()
        tracker.onResponseCreated("dlg_1", "rsp_1")

        assertTrue(tracker.drainForCompletion("dlg_1", "").isEmpty())
        assertFalse(tracker.onOutputItemDone("dlg_1", "", "function_call", "call_1", "lookup", """{"id":"7"}"""))
    }

    @Test
    fun failedResponseDropsPendingCallsWithoutExecution() {
        val tracker = DelegationTracker()
        tracker.onResponseCreated("dlg_1", "rsp_1")
        tracker.onOutputItemDone("dlg_1", "", "function_call", "call_1", "lookup", """{"id":"7"}""")

        tracker.onResponseEnded("dlg_1", "")

        assertTrue(tracker.drainForCompletion("dlg_1", "").isEmpty())
    }

    @Test
    fun resubmittedCallIdUnderNewResponseIsRejected() {
        val tracker = DelegationTracker()
        tracker.onResponseCreated("dlg_1", "rsp_1")
        tracker.onOutputItemDone("dlg_1", "", "function_call", "call_1", "lookup", """{"id":"7"}""")
        tracker.drainForCompletion("dlg_1", "")
        tracker.onResponseCreated("dlg_2", "rsp_2")

        assertFalse(tracker.onOutputItemDone("dlg_2", "", "function_call", "call_1", "lookup", """{"id":"7"}"""))
    }

    @Test
    fun delegationMismatchAtTerminalBoundaryYieldsNothing() {
        val tracker = DelegationTracker()
        tracker.onResponseCreated("dlg_1", "rsp_1")
        tracker.onOutputItemDone("dlg_1", "", "function_call", "call_1", "lookup", """{"id":"7"}""")

        assertTrue(tracker.drainForCompletion("dlg_other", "").isEmpty())
    }

    @Test
    fun unresolvableDelegationCollectsNothing() {
        val tracker = DelegationTracker()

        assertFalse(tracker.onOutputItemDone("dlg_unknown", "", "function_call", "call_1", "lookup", "{}"))
    }

    @Test
    fun nonFunctionItemTypesAreRejected() {
        val tracker = DelegationTracker()
        tracker.onResponseCreated("dlg_1", "rsp_1")

        assertFalse(tracker.onOutputItemDone("dlg_1", "", "message", "call_1", "lookup", "{}"))
        assertFalse(tracker.onOutputItemDone("dlg_1", "", "function_call_output", "call_1", "lookup", "{}"))
        assertFalse(tracker.onOutputItemDone("dlg_1", "", "FUNCTION_CALL", "call_1", "lookup", "{}"))
        assertTrue(tracker.drainForCompletion("dlg_1", "").isEmpty())
    }

    @Test
    fun oversizedArgumentsAreInvalidInsteadOfTruncated() {
        val tracker = DelegationTracker()
        tracker.onResponseCreated("dlg_1", "rsp_1")
        val huge = """{"data":"""" + "x".repeat(20_000) + """"}"""

        assertTrue(tracker.onOutputItemDone("dlg_1", "", "function_call", "call_1", "lookup", huge))

        val drained = tracker.drainForCompletion("dlg_1", "")
        assertEquals(1, drained.size)
        assertTrue(drained[0].argumentsInvalid)
    }

    @Test
    fun malformedArgumentsAreInvalidInsteadOfInvented() {
        val tracker = DelegationTracker()
        tracker.onResponseCreated("dlg_1", "rsp_1")

        assertTrue(tracker.onOutputItemDone("dlg_1", "", "function_call", "call_1", "lookup", "{not json"))

        assertTrue(tracker.drainForCompletion("dlg_1", "")[0].argumentsInvalid)
    }

    @Test
    fun sessionQuotaLatchesInsteadOfForgettingIds() {
        val tracker = DelegationTracker(maxSessionCalls = 2)
        tracker.onResponseCreated("dlg_1", "rsp_1")

        assertTrue(tracker.onOutputItemDone("dlg_1", "", "function_call", "call_1", "a", "{}"))
        assertTrue(tracker.onOutputItemDone("dlg_1", "", "function_call", "call_2", "a", "{}"))
        assertFalse(tracker.onOutputItemDone("dlg_1", "", "function_call", "call_3", "a", "{}"))
        assertTrue(tracker.quotaExceeded)
    }

    @Test
    fun transcriptsRetainOnlyBoundedTails() {
        val transcripts = TranscriptAccumulator(maxChars = 16)

        transcripts.appendInput("0123456789abcdef")
        transcripts.appendInput("GHIJ")
        transcripts.appendOutput("")

        assertEquals("456789abcdefGHIJ", transcripts.input)
        assertEquals("", transcripts.output)
    }
}
