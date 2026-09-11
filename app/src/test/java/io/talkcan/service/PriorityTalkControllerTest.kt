package io.talkcan.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PriorityTalkControllerTest {
    @Test
    fun releaseDuringRegularTeardownNeverStartsPriorityMicrophone() = runTest {
        val teardown = CompletableDeferred<Boolean>()
        var opened = false
        val controller = PriorityTalkController(this, prepare = { teardown.await() },
            start = { opened = true }, stop = {})
        controller.press("priority")
        runCurrent()
        controller.release()
        teardown.complete(true)
        runCurrent()
        assertFalse(opened)
        assertNull(controller.target.value)
    }

    @Test
    fun priorityEndsRegularConversationAndReleaseDoesNotResumeIt() = runTest {
        var regularRunning = true
        var microphoneOwner: String? = "regular"
        val controller = PriorityTalkController(this,
            prepare = { regularRunning = false; microphoneOwner = null; true },
            start = { microphoneOwner = it }, stop = { microphoneOwner = null })
        controller.press("priority")
        runCurrent()
        assertFalse(regularRunning)
        assertEquals("priority", microphoneOwner)
        controller.release()
        runCurrent()
        assertNull(microphoneOwner)
        assertFalse(regularRunning)
        assertNull(controller.target.value)
    }

    @Test
    fun releaseWhileStartingClosesTheLateSession() = runTest {
        val startup = CompletableDeferred<Unit>()
        var microphoneOpen = false
        val controller = PriorityTalkController(this, prepare = { true },
            start = { startup.await(); microphoneOpen = true }, stop = { microphoneOpen = false })
        controller.press("priority")
        runCurrent()
        controller.release()
        startup.complete(Unit)
        runCurrent()
        assertFalse(microphoneOpen)
        assertNull(controller.target.value)
    }

    @Test
    fun repeatedPressCannotReplaceTargetBeforeReleaseCleanupCompletes() = runTest {
        val cleanup = CompletableDeferred<Unit>()
        var starts = 0
        var finished = false
        val controller = PriorityTalkController(this, prepare = { true },
            start = { starts++ }, stop = { cleanup.await() }, onFinished = { finished = true })
        controller.press("first")
        runCurrent()
        controller.release()
        runCurrent()
        controller.press("second")
        assertEquals("first", controller.target.value)
        assertEquals(1, starts)
        assertFalse(finished)
        cleanup.complete(Unit)
        runCurrent()
        assertTrue(finished)
        assertNull(controller.target.value)
    }
}
