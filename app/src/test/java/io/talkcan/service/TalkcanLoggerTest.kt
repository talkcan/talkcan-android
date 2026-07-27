package io.talkcan.service

import java.io.File
import io.talkcan.audio.CaptureService
import io.talkcan.model.InputMode
import io.talkcan.model.PttSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TalkcanLoggerTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = createTempDir(prefix = "talkcan-log-test")
        TalkcanLogger.initialize(tempDir)
        TalkcanLogger.clear()
        TalkcanLogger.setGlobalLevel(LogLevel.Debug)
        TalkcanLogger.clearAllTagLevels()
    }

    @After
    fun tearDown() {
        TalkcanLogger.clear()
        tempDir.deleteRecursively()
    }

    @Test
    fun globalLevelFilterAllowsAtOrAboveThreshold() {
        TalkcanLogger.setGlobalLevel(LogLevel.Warn)

        TalkcanLogger.d("TestTag", "debug msg - should be filtered")
        TalkcanLogger.w("TestTag", "warn msg - should pass")
        TalkcanLogger.e("TestTag", "error msg - should pass")

        runBlocking {
            delay(200)
        }

        val entries = TalkcanLogger.entries.value
        assertEquals(2, entries.size)
        assertEquals(LogLevel.Warn, entries[0].level)
        assertEquals(LogLevel.Error, entries[1].level)
    }

    @Test
    fun perTagLevelOverridesGlobal() {
        TalkcanLogger.setGlobalLevel(LogLevel.Error)
        TalkcanLogger.setTagLevel("SpecialTag", LogLevel.Debug)

        TalkcanLogger.d("SpecialTag", "debug passes via tag override")
        TalkcanLogger.d("OtherTag", "debug filtered by global")

        runBlocking {
            delay(200)
        }

        val entries = TalkcanLogger.entries.value
        assertEquals(1, entries.size)
        assertEquals("SpecialTag", entries[0].tag)
        assertEquals(LogLevel.Debug, entries[0].level)
    }

    @Test
    fun clearTagLevelRevertsToGlobal() {
        TalkcanLogger.setGlobalLevel(LogLevel.Error)
        TalkcanLogger.setTagLevel("ClearableTag", LogLevel.Debug)
        TalkcanLogger.clearTagLevel("ClearableTag")

        TalkcanLogger.d("ClearableTag", "should be filtered by global")

        runBlocking {
            delay(200)
        }

        val entries = TalkcanLogger.entries.value
        assertEquals(0, entries.size)
    }

    @Test
    fun diskRotationKeepsTotalSizeBounded() {
        TalkcanLogger.setGlobalLevel(LogLevel.Verbose)

        // Write enough entries to trigger at least one rotation (>1 MB)
        val longMessage = "x".repeat(500)
        for (i in 0 until 4000) {
            TalkcanLogger.d("RotationTest", "$i:$longMessage")
        }

        runBlocking {
            delay(1000)
        }

        val activeFile = File(tempDir, "talkcan-logs/talkcan_logs.0.log")
        val previousFile = File(tempDir, "talkcan-logs/talkcan_logs.1.log")

        assertTrue("Active file should exist", activeFile.exists())
        assertTrue("Previous file should exist after rotation", previousFile.exists())

        val totalSize = activeFile.length() + previousFile.length()
        assertTrue(
            "Total disk usage ($totalSize bytes) should be bounded to ~2 MB",
            totalSize <= 2 * 1_048_576L + 4096,
        )
    }

    @Test
    fun logsSurviveReinitialize() {
        TalkcanLogger.i("PersistTest", "entry before restart")

        val persisted = runBlocking {
            withTimeout(5_000) {
                TalkcanLogger.entries.first { entries ->
                    entries.any { it.tag == "PersistTest" && it.message == "entry before restart" }
                }
            }
        }
        assertTrue(persisted.any { it.tag == "PersistTest" && it.message == "entry before restart" })

        // Re-initialize from the same directory
        TalkcanLogger.initialize(tempDir)

        val restored = TalkcanLogger.entries.value
        assertTrue(
            "Historical logs should survive reinitialization",
            restored.any { it.tag == "PersistTest" && it.message == "entry before restart" },
        )
    }

    @Test
    fun clearEmptiesEntriesAndDisk() {
        TalkcanLogger.i("ClearTest", "entry to be cleared")

        runBlocking {
            delay(200)
        }

        assertTrue(TalkcanLogger.entries.value.isNotEmpty())

        TalkcanLogger.clear()

        assertEquals(0, TalkcanLogger.entries.value.size)
        assertEquals(0L, File(tempDir, "talkcan-logs/talkcan_logs.0.log").length())
    }

    @Test
    fun globalLevelFlowReflectsChanges() {
        val initialLevel = TalkcanLogger.globalLevelFlow.value
        TalkcanLogger.setGlobalLevel(LogLevel.Error)
        assertEquals(LogLevel.Error, TalkcanLogger.globalLevelFlow.value)
        TalkcanLogger.setGlobalLevel(initialLevel)
    }

    @Test
    fun perTagLevelFlowReflectsChanges() {
        TalkcanLogger.setTagLevel("FlowTag", LogLevel.Warn)
        assertEquals(LogLevel.Warn, TalkcanLogger.perTagLevelFlow.value["FlowTag"])
        TalkcanLogger.clearTagLevel("FlowTag")
        assertFalse(TalkcanLogger.perTagLevelFlow.value.containsKey("FlowTag"))
    }

    @Test
    fun cancellationDiagnosticReasonsKeepOnlyApprovedSemanticCategories() {
        data class Case(
            val reason: String,
            val expected: String,
        )

        listOf(
            Case("RSM serial session ended", "rsm-serial-session-ended"),
            Case("Telecom route timeout", "telecom-route-timeout"),
            Case("Explicit RSM serial disconnect", "explicit-rsm-serial-disconnect"),
            Case("work-route-release-failed", "work-route-release-failed"),
        ).forEach { case ->
            assertEquals(case.expected, case.reason.toCancellationLogValue())
        }

        val sensitiveReason =
            "Bluetooth AA:BB:CC:DD:EE:FF device B02PTT-FF01 pcm 0102 transcript meet at nine " +
                "credential token-secret channel private-message"
        assertEquals("unspecified", sensitiveReason.toCancellationLogValue())
    }

    @Test
    fun terminalCancellationDiagnosticsUseClaimCategoryInsteadOfDynamicReasonContent() {
        val manager = PttAudioSessionManager(
            scope = CoroutineScope(Dispatchers.Unconfined),
            captureService = mockk<CaptureService>(relaxed = true),
            channelRouter = mockk<ChannelRouter>(relaxed = true),
            resolvePttAudioRoute = { error("pending session must not resolve an audio route") },
        )
        val sensitiveReason =
            "Bluetooth AA:BB:CC:DD:EE:FF device B02PTT-FF01 pcm 0102 transcript meet at nine " +
                "credential token-secret channel private-message"
        assertTrue(manager.reservePending(PttSource.Rsm, "private-message", InputMode.Work))

        assertEquals(
            PttAudioSessionManager.CancellationDisposition.Accepted,
            manager.cancelBySource(
                source = PttSource.Rsm,
                eligibility = PttAudioSessionManager.CancellationEligibility.PendingOrActive,
                reason = sensitiveReason,
            ).disposition,
        )

        val terminalMessages = runBlocking {
            withTimeout(5_000) {
                TalkcanLogger.entries.first { entries ->
                    entries.count { it.message.startsWith("AUDIO_SESSION_TERMINAL_") } == 2
                }
            }
        }.filter { it.message.startsWith("AUDIO_SESSION_TERMINAL_") }.map { it.message }

        assertEquals(
            listOf(
                "AUDIO_SESSION_TERMINAL_CLAIM id=1 source=Rsm claim=Cancellation reason=cancelled",
                "AUDIO_SESSION_TERMINAL_COMPLETION id=1 source=Rsm claim=Cancellation " +
                    "reason=cancelled cleanupFailures=None",
            ),
            terminalMessages,
        )
        assertFalse(terminalMessages.joinToString().contains("AA:BB:CC:DD:EE:FF"))
        assertFalse(terminalMessages.joinToString().contains("B02PTT-FF01"))
        assertFalse(terminalMessages.joinToString().contains("0102"))
        assertFalse(terminalMessages.joinToString().contains("meet at nine"))
        assertFalse(terminalMessages.joinToString().contains("token-secret"))
        assertFalse(terminalMessages.joinToString().contains("private-message"))
    }
}