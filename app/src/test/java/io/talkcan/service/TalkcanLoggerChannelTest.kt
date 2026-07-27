package io.talkcan.service

import java.io.File
import io.talkcan.lua.LogRecord
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TalkcanLoggerChannelTest {
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = createTempDir(prefix = "talkcan-channel-test")
        TalkcanLogger.initialize(tempDir)
        TalkcanLogger.clear()
        TalkcanLogger.setGlobalLevel(LogLevel.Verbose)
        TalkcanLogger.clearAllTagLevels()
    }

    @After
    fun tearDown() {
        TalkcanLogger.clear()
        tempDir.deleteRecursively()
    }

    @Test
    fun `native level mapping produces immutable LuaChannel host projections`() {
        data class Case(val nativeLevel: String, val expectedLevel: LogLevel)

        listOf(
            Case("debug", LogLevel.Debug),
            Case("INFO", LogLevel.Info),
            Case("warn", LogLevel.Warn),
            Case("error", LogLevel.Error),
        ).forEach { case ->
            val projection = LogRecord(
                timestampMillis = 1_726_000_000_123L,
                level = case.nativeLevel,
                message = "payload-${case.nativeLevel}",
            ).toPluginLogProjection()

            assertEquals(
                PluginLogProjection(
                    level = case.expectedLevel,
                    tag = "LuaChannel",
                    message = "payload-${case.nativeLevel}",
                    throwable = null,
                    timestampMillis = 1_726_000_000_123L,
                ),
                projection,
            )
        }
        assertNull(LogRecord(4L, "fatal", "ignored").toPluginLogProjection())
    }

    @Test
    fun `plugin admission follows reactive global and tag filtering and preserves fixed projection fields`() {
        TalkcanLogger.setGlobalLevel(LogLevel.Warn)
        assertFalse(TalkcanLogger.tryLogPlugin(LogLevel.Debug, "LuaChannel", "filtered", 100L))

        TalkcanLogger.setTagLevel("LuaChannel", LogLevel.Debug)
        assertTrue(TalkcanLogger.tryLogPlugin(LogLevel.Debug, "LuaChannel", "accepted", 101L))

        val accepted = awaitEntries { entries ->
            entries.filter { it.message == "accepted" }.size == 1
        }.single { it.message == "accepted" }
        assertEquals(LogLevel.Debug, accepted.level)
        assertEquals("LuaChannel", accepted.tag)
        assertEquals(101L, accepted.timestamp)
        assertNull(accepted.throwable)
        assertFalse(TalkcanLogger.entries.value.any { it.message == "filtered" })
    }

    @Test
    fun `accepted plugin record is persisted and restart loading does not replay a duplicate snapshot`() {
        assertTrue(TalkcanLogger.tryLogPlugin(LogLevel.Warn, "LuaChannel", "persist exactly once", 202L))
        awaitEntries { entries -> entries.count { it.message == "persist exactly once" } == 1 }

        TalkcanLogger.initialize(tempDir)

        val restored = TalkcanLogger.entries.value.filter { it.message == "persist exactly once" }
        assertEquals(1, restored.size)
        assertEquals(LogLevel.Warn, restored.single().level)
        assertEquals("LuaChannel", restored.single().tag)
        assertEquals(202L, restored.single().timestamp)
        assertNull(restored.single().throwable)
    }

    @Test
    fun `clear while dispatcher remains live removes prior plugin record and persists later admission`() {
        assertTrue(TalkcanLogger.tryLogPlugin(LogLevel.Info, "LuaChannel", "before clear", 301L))
        awaitEntries { entries -> entries.any { it.message == "before clear" } }

        TalkcanLogger.clear()
        assertTrue(TalkcanLogger.entries.value.isEmpty())

        assertTrue(TalkcanLogger.tryLogPlugin(LogLevel.Error, "LuaChannel", "after clear", 302L))
        val postClear = awaitEntries { entries ->
            entries.size == 1 && entries.single().message == "after clear"
        }.single()
        assertEquals(LogLevel.Error, postClear.level)
        assertEquals(302L, postClear.timestamp)

        TalkcanLogger.initialize(tempDir)
        assertEquals(
            listOf("after clear"),
            TalkcanLogger.entries.value.map { it.message },
        )
    }

    private fun awaitEntries(predicate: (List<LogEntry>) -> Boolean): List<LogEntry> = runBlocking {
        withTimeout(5_000) {
            TalkcanLogger.entries.first(predicate)
        }
    }
}