package io.talkcan.service

import io.talkcan.audio.HostAudioFeedback
import io.talkcan.audio.NavigationSynthesisResult
import io.talkcan.audio.NavigationTtsEngine
import io.talkcan.audio.NavigationTtsFailure
import io.talkcan.audio.RecordedPcm
import io.talkcan.model.ChannelCatalogueSnapshot
import io.talkcan.model.ChannelDefinition
import io.talkcan.model.ChannelImplementationId
import io.talkcan.model.OpaqueJsonObject
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RsmAnnouncementCoordinatorTest {
    @Test
    fun `valid menu channel and selection keys submit their live catalogue text`() = runTest {
        val engine = mockk<NavigationTtsEngine>()
        val submittedTexts = mutableListOf<String>()
        coEvery { engine.request(any(), any()) } coAnswers {
            submittedTexts += firstArg<String>()
            NavigationSynthesisResult.Superseded
        }
        val fixture = Fixture(
            scope = backgroundScope,
            engine = engine,
            catalogue = catalogue(
                definition(id = "journal", name = "Journal"),
                definition(id = "field-ops", name = "Field Operations"),
            ),
        )

        listOf(
            "sys.menu.channels" to "Channels",
            "chan.field-ops.name" to "Field Operations",
            "chan.field-ops.selected" to "Field Operations Selected",
        ).forEach { (key, expectedText) ->
            fixture.coordinator.announce(key)
        }
        runCurrent()
        advanceUntilIdle()

        assertEquals(
            "Each supported key must submit the exact phrase resolved from the current catalogue.",
            listOf("Channels", "Field Operations", "Field Operations Selected"),
            submittedTexts,
        )
        assertEquals(
            "Every engine result must retain its bootstrap-reporting path.",
            List(3) { NavigationSynthesisResult.Superseded },
            fixture.synthesisResults,
        )
    }

    @Test
    fun `unknown and removed channel keys start no announcement work`() = runTest {
        val engine = mockk<NavigationTtsEngine>()
        val fixture = Fixture(
            scope = backgroundScope,
            engine = engine,
            catalogue = catalogue(definition(id = "retired", name = "Retired Channel")),
        )

        fixture.coordinator.announce("chan.unknown.name")
        fixture.catalogue = catalogue(definition(id = "active", name = "Active Channel"))
        fixture.coordinator.announce("chan.retired.name")
        fixture.coordinator.announce("chan.retired.selected")
        runCurrent()
        advanceUntilIdle()

        coVerify(exactly = 0) { engine.request(any(), any()) }
        assertTrue(fixture.played.isEmpty())
        assertTrue(fixture.synthesisResults.isEmpty())
    }

    @Test
    fun `successful synthesis plays its PCM and forwards the exact result`() = runTest {
        val engine = mockk<NavigationTtsEngine>()
        val synthesizedPcm = RecordedPcm(shortArrayOf(31, -47, 59), 16_000)
        val result = NavigationSynthesisResult.Success(synthesizedPcm)
        coEvery { engine.request(any(), any()) } coAnswers {
            secondArg<suspend (RecordedPcm) -> Unit>()(synthesizedPcm)
            result
        }
        val fixture = Fixture(
            scope = backgroundScope,
            engine = engine,
            catalogue = catalogue(definition(id = "journal", name = "Journal")),
        )

        fixture.coordinator.announce("chan.journal.name")
        runCurrent()
        advanceUntilIdle()

        assertEquals(1, fixture.played.size)
        assertPcmEquals(synthesizedPcm, fixture.played.single())
        assertEquals(listOf(result), fixture.synthesisResults)
    }

    @Test
    fun `navigation failure classifications are forwarded unchanged without fallback playback`() = runTest {
        val engine = mockk<NavigationTtsEngine>()
        val results = listOf(
            NavigationSynthesisResult.Superseded,
            NavigationSynthesisResult.EngineServiceFailure(
                failure = NavigationTtsFailure.EngineServiceFailure.SynthesisTimeout,
                exhausted = true,
            ),
            NavigationSynthesisResult.InfrastructureFailure(
                NavigationTtsFailure.RendererInfrastructureFailure.EmptyPcm,
            ),
        )
        var nextResult = 0
        coEvery { engine.request(any(), any()) } coAnswers { results[nextResult++] }
        val fixture = Fixture(
            scope = backgroundScope,
            engine = engine,
            catalogue = catalogue(definition(id = "journal", name = "Journal")),
        )

        repeat(results.size) { fixture.coordinator.announce("chan.journal.name") }
        runCurrent()
        advanceUntilIdle()

        assertEquals(results, fixture.synthesisResults)
        assertTrue(
            "A failed or superseded text request must not turn into an error beep.",
            fixture.played.isEmpty(),
        )
    }

    @Test
    fun `rapid announcements reach the engine before an earlier request returns`() = runTest {
        val engine = mockk<NavigationTtsEngine>()
        val firstRequestEntered = CompletableDeferred<Unit>()
        val releaseFirstRequest = CompletableDeferred<Unit>()
        val submittedTexts = mutableListOf<String>()
        coEvery { engine.request(any(), any()) } coAnswers {
            val text = firstArg<String>()
            submittedTexts += text
            if (text == "Channels") {
                firstRequestEntered.complete(Unit)
                releaseFirstRequest.await()
            }
            NavigationSynthesisResult.Superseded
        }
        val fixture = Fixture(
            scope = backgroundScope,
            engine = engine,
            catalogue = catalogue(definition(id = "journal", name = "Journal")),
        )

        fixture.coordinator.announce("sys.menu.channels")
        runCurrent()
        assertTrue(firstRequestEntered.isCompleted)

        fixture.coordinator.announce("chan.journal.name")
        runCurrent()

        assertEquals(
            "The coordinator must delegate each request immediately; NavigationTtsEngine owns supersession.",
            listOf("Channels", "Journal"),
            submittedTexts,
        )
        releaseFirstRequest.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `error feedback follows engine PCM ownership then reports its result`() = runTest {
        val engine = mockk<NavigationTtsEngine>()
        val submittedPcm = mutableListOf<RecordedPcm>()
        val result = NavigationSynthesisResult.Success(HostAudioFeedback.errorBeep())
        coEvery { engine.requestPcm(any(), any()) } coAnswers {
            val recording = firstArg<RecordedPcm>()
            submittedPcm += recording
            secondArg<suspend (RecordedPcm) -> Unit>()(recording)
            result
        }
        val fixture = Fixture(scope = backgroundScope, engine = engine)

        fixture.coordinator.announceErrorBeep()
        runCurrent()
        advanceUntilIdle()

        val expectedBeep = HostAudioFeedback.errorBeep()
        assertEquals(1, submittedPcm.size)
        assertPcmEquals(expectedBeep, submittedPcm.single())
        assertEquals(1, fixture.played.size)
        assertPcmEquals(expectedBeep, fixture.played.single())
        assertEquals(listOf(result), fixture.synthesisResults)
    }

    @Test
    fun `missing navigation engine suppresses text but plays error feedback directly`() = runTest {
        val fixture = Fixture(
            scope = backgroundScope,
            engine = null,
            catalogue = catalogue(definition(id = "journal", name = "Journal")),
        )

        fixture.coordinator.announce("chan.journal.name")
        fixture.coordinator.announceErrorBeep()
        runCurrent()
        advanceUntilIdle()

        assertEquals(
            "Only the error-feedback fallback may play when navigation TTS is unavailable.",
            1,
            fixture.played.size,
        )
        assertPcmEquals(HostAudioFeedback.errorBeep(), fixture.played.single())
        assertTrue(fixture.synthesisResults.isEmpty())
    }

    private class Fixture(
        scope: CoroutineScope,
        engine: NavigationTtsEngine?,
        catalogue: ChannelCatalogueSnapshot = catalogue(definition(id = "journal", name = "Journal")),
    ) {
        var catalogue: ChannelCatalogueSnapshot = catalogue
        val played = mutableListOf<RecordedPcm>()
        val synthesisResults = mutableListOf<NavigationSynthesisResult>()
        val coordinator = RsmAnnouncementCoordinator(
            scope = scope,
            catalogue = { this.catalogue },
            navigationEngine = { engine },
            playPcm = { recording ->
                played += recording
                HostPlaybackResult.Completed
            },
            onSynthesisResult = { result -> synthesisResults += result },
        )
    }

    private companion object {
        fun catalogue(vararg definitions: ChannelDefinition): ChannelCatalogueSnapshot =
            ChannelCatalogueSnapshot(
                definitions = definitions.toList(),
                activeChannelId = definitions.firstOrNull()?.id.orEmpty(),
            )

        fun definition(id: String, name: String): ChannelDefinition =
            ChannelDefinition(
                id = id,
                name = name,
                implementationId = ChannelImplementationId("test:announcement"),
                enabled = true,
                configSchemaVersion = 1,
                configPayload = OpaqueJsonObject.parse("{}").getOrThrow(),
            )

        fun assertPcmEquals(expected: RecordedPcm, actual: RecordedPcm) {
            assertEquals(expected.sampleRate, actual.sampleRate)
            assertTrue(expected.samples.contentEquals(actual.samples))
        }
    }
}
