package io.talkcan.service

import android.content.Context
import io.talkcan.audio.FakeTtsSynthesizer
import io.talkcan.audio.StateLossCallback
import io.talkcan.audio.SynthesisOutcome
import io.talkcan.channel.SleepwalkerTextOutputService
import io.talkcan.channel.TextOutputAvailability
import io.talkcan.channel.capability.CapabilityOperationResult
import io.talkcan.channel.capability.CapabilityScopeIdentity
import io.talkcan.channel.capability.CapabilityUnavailableReason
import io.talkcan.channel.capability.RuntimeGeneration
import io.talkcan.channel.capability.SpeechSynthesisRequest
import io.talkcan.channel.capability.SpeechVoice
import io.talkcan.channel.capability.SynthesisCapability
import io.talkcan.model.ChannelCatalogueSnapshot
import io.talkcan.model.ChannelDefinition
import io.talkcan.model.ChannelHostPreferences
import io.talkcan.model.ChannelImplementationId
import io.talkcan.model.DEFAULT_TTS_VOICE_STYLE
import io.talkcan.model.OpaqueJsonObject
import io.talkcan.model.TTS_VOICE_STYLES
import io.talkcan.voice.Supertonic3VoiceProfileContract
import io.talkcan.voice.VoiceProfileAvailability
import io.talkcan.voice.VoiceProfileCodec
import io.talkcan.voice.VoiceProfileId
import io.talkcan.voice.VoiceProfileModelMetadata
import io.talkcan.voice.VoiceProfileMutation
import io.talkcan.voice.VoiceProfileProvenance
import io.talkcan.voice.VoiceProfileRepository
import io.talkcan.voice.VoiceProfileSourceProvenance
import io.talkcan.voice.VoiceProfileStore
import io.talkcan.voice.VoiceProfileTensors
import io.talkcan.voice.VoiceProfileWeightMode
import io.talkcan.voice.VoiceTensor
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Per-request host resolution of the semantic `default` synthesis voice (tasks 6.4-6.6):
 * channel-instance isolation, shipped fallback, typed not-configured behavior for
 * unavailable explicit assignments, non-default rejection, and preference-change /
 * in-flight stability semantics.
 */
class ChannelVoiceResolverTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var builtInDir: File
    private lateinit var store: VoiceProfileStore
    private lateinit var repository: VoiceProfileRepository
    private var snapshot = ChannelCatalogueSnapshot(emptyList(), null)
    private var idCounter = 0
    private lateinit var resolver: ChannelVoiceResolver

    @Before
    fun setUp() {
        idCounter = 0
        builtInDir = folder.newFolder("builtins")
        for (token in listOf("F1", "F2", "F3", "F4", "F5", "M1", "M2", "M3", "M4", "M5")) {
            File(builtInDir, "$token.json").writeText(
                VoiceProfileCodec.encode(tensors(), VoiceProfileCodec.CURRENT_MODEL, null)
            )
        }
        store = VoiceProfileStore(folder.newFolder("profiles"))
        store.ensureDirectory()
        repository = VoiceProfileRepository(
            store = store,
            builtInDir = builtInDir,
            currentModel = VoiceProfileCodec.CURRENT_MODEL,
            newId = { "profile-${idCounter++}" },
        )
        resolver = ChannelVoiceResolver(
            channelCatalogue = { snapshot },
            synthesisStylePath = repository::synthesisStyleFilePath,
            // Mirrors the production wiring: the shipped built-in application default.
            fallbackProfileId = VoiceProfileId("${VoiceProfileRepository.BUILTIN_ID_PREFIX}$DEFAULT_TTS_VOICE_STYLE"),
        )
    }

    // ── 6.4 Per-request, identity-scoped resolution ────────────────────────────

    @Test
    fun `sibling instances resolve their own assignments in isolation`() {
        val customId = saveCustom("Sibling Custom")
        assign("channel-a", VoiceProfileId("builtin:F2"))
        assign("channel-b", customId)

        val pathA = resolver.resolveStylePath(identity("channel-a"))
        val pathB = resolver.resolveStylePath(identity("channel-b"))

        assertEquals(File(builtInDir, "F2.json").absolutePath, pathA)
        assertEquals(store.profileFile(customId).absolutePath, pathB)
        assertNotEquals(pathA, pathB)
    }

    @Test
    fun `unassigned channel resolves the verified shipped application fallback`() {
        assign("channel-a", null)

        assertEquals(
            File(builtInDir, "$DEFAULT_TTS_VOICE_STYLE.json").absolutePath,
            resolver.resolveStylePath(identity("channel-a")),
        )
    }

    @Test
    fun `instance without a catalogue definition resolves the application fallback`() {
        snapshot = ChannelCatalogueSnapshot(emptyList(), null)

        assertEquals(
            File(builtInDir, "$DEFAULT_TTS_VOICE_STYLE.json").absolutePath,
            resolver.resolveStylePath(identity("unknown-instance")),
        )
    }

    @Test
    fun `explicit built-in assignment resolves the built-in style path`() {
        assign("channel-a", VoiceProfileId("builtin:F5"))

        assertEquals(
            File(builtInDir, "F5.json").absolutePath,
            resolver.resolveStylePath(identity("channel-a")),
        )
    }

    @Test
    fun `explicit custom assignment resolves the stable custom style path`() {
        val customId = saveCustom("Custom Voice")
        assign("channel-a", customId)

        val path = resolver.resolveStylePath(identity("channel-a"))

        assertEquals(store.profileFile(customId).absolutePath, path)
        assertTrue(path!!.endsWith("${customId.value}.json"))
    }

    // ── 6.5 Typed unavailable behavior, never a silent substitution ────────────

    @Test
    fun `explicit assignment to an unknown profile is not configured and never falls back`() {
        assign("channel-a", VoiceProfileId("custom:ghost"))

        assertNull(resolver.resolveStylePath(identity("channel-a")))
    }

    @Test
    fun `explicit assignment to a missing document is not configured and never falls back`() {
        val customId = saveCustom("Vanishing")
        assign("channel-a", customId)

        assertTrue(store.profileFile(customId).delete())
        repository.reload()

        assertNull(resolver.resolveStylePath(identity("channel-a")))
    }

    @Test
    fun `explicit assignment to a corrupt document is not configured and never falls back`() {
        val customId = saveCustom("Corrupted")
        assign("channel-a", customId)

        store.profileFile(customId).writeText("{not a voice profile")
        repository.reload()

        assertNull(resolver.resolveStylePath(identity("channel-a")))
    }

    @Test
    fun `explicit assignment to an incompatible document is not configured and never falls back`() {
        val customId = saveCustom("Foreign Model")
        assign("channel-a", customId)

        val foreignModel = VoiceProfileModelMetadata("supertonic-2", "2020-01-01")
        store.profileFile(customId).writeText(VoiceProfileCodec.encode(tensors(), foreignModel, null))
        repository.reload()

        assertNull(resolver.resolveStylePath(identity("channel-a")))
    }

    @Test
    fun `non-default voice ids remain unsupported and are not interpreted as profile ids`() = runTest {
        val fixture = capabilityFixture()
        assign("channel-a", VoiceProfileId("builtin:F2"))
        val capability = fixture.acquire(identity("channel-a"))

        // Neither a bare style token nor a real profile ID is admissible as a runtime voice ID.
        for (voiceId in listOf("M1", "F2", "builtin:F2", "custom:whatever")) {
            val result = capability.synthesize(request(SpeechVoice(voiceId)))
            assertEquals(
                "voice id '$voiceId' must stay unsupported",
                CapabilityOperationResult.Unavailable(CapabilityUnavailableReason.NOT_CONFIGURED),
                result,
            )
        }
        assertEquals(0, fixture.synthesizer.callCount)
    }

    @Test
    fun `default voice synthesizes through the channel assignment with typed parameters`() = runTest {
        val fixture = capabilityFixture()
        assign("channel-a", VoiceProfileId("builtin:F3"))

        val result = fixture.acquire(identity("channel-a")).synthesize(request(SpeechVoice("default")))

        assertTrue(result is CapabilityOperationResult.Success)
        assertEquals(File(builtInDir, "F3.json").absolutePath, fixture.synthesizer.lastRequest!!.voiceStylePath)
        assertEquals(TOTAL_STEPS, fixture.synthesizer.lastRequest!!.totalSteps)
    }

    @Test
    fun `explicit unavailable assignment returns typed not-configured without synthesizing`() = runTest {
        val fixture = capabilityFixture()
        val customId = saveCustom("Doomed")
        assign("channel-a", customId)
        store.profileFile(customId).writeText("{corrupt")
        repository.reload()

        val result = fixture.acquire(identity("channel-a")).synthesize(request(SpeechVoice("default")))

        assertEquals(
            CapabilityOperationResult.Unavailable(CapabilityUnavailableReason.NOT_CONFIGURED),
            result,
        )
        assertEquals(0, fixture.synthesizer.callCount)
    }

    // ── 6.4/6.5 Preference changes and in-flight stability ─────────────────────

    @Test
    fun `preference change affects the next request without runtime replacement`() = runTest {
        val fixture = capabilityFixture()
        assign("channel-a", VoiceProfileId("builtin:F2"))
        val capability = fixture.acquire(identity("channel-a"))

        capability.synthesize(request(SpeechVoice("default")))
        assertEquals(File(builtInDir, "F2.json").absolutePath, fixture.synthesizer.lastRequest!!.voiceStylePath)

        // Host preference change only: same capability instance, no generation replacement.
        assign("channel-a", VoiceProfileId("builtin:F4"))
        val sameCapability = fixture.acquire(identity("channel-a"))
        assertSame(capability, sameCapability)

        sameCapability.synthesize(request(SpeechVoice("default")))
        assertEquals(File(builtInDir, "F4.json").absolutePath, fixture.synthesizer.lastRequest!!.voiceStylePath)
        assertEquals(2, fixture.synthesizer.callCount)
    }

    @Test
    fun `an already resolved request keeps its path after a preference change`() {
        assign("channel-a", VoiceProfileId("builtin:F2"))

        // The in-flight request holds this complete, immutable resolution.
        val inFlightPath = resolver.resolveStylePath(identity("channel-a"))
        assign("channel-a", VoiceProfileId("builtin:F4"))

        assertEquals(File(builtInDir, "F2.json").absolutePath, inFlightPath)
        assertEquals(File(builtInDir, "F4.json").absolutePath, resolver.resolveStylePath(identity("channel-a")))
    }

    @Test
    fun `a preference change during synthesis does not alter the in-flight request`() = runTest {
        val fixture = capabilityFixture()
        assign("channel-a", VoiceProfileId("builtin:F2"))
        val observedPaths = mutableListOf<String>()
        fixture.synthesizer.setOutcomeFactory { synthesisRequest ->
            observedPaths += synthesisRequest.voiceStylePath
            // The user changes the channel preference while request one is in flight.
            assign("channel-a", VoiceProfileId("builtin:F4"))
            SynthesisOutcome.Success(floatArrayOf(0.5f))
        }
        val capability = fixture.acquire(identity("channel-a"))

        capability.synthesize(request(SpeechVoice("default")))
        capability.synthesize(request(SpeechVoice("default")))

        assertEquals(
            listOf(
                File(builtInDir, "F2.json").absolutePath,
                File(builtInDir, "F4.json").absolutePath,
            ),
            observedPaths,
        )
    }

    // ── 6.6 Shipped fallback constant, no Q1 ───────────────────────────────────

    @Test
    fun `application fallback is a shipped verified built-in and Q1 is absent`() {
        assertEquals("M1", DEFAULT_TTS_VOICE_STYLE)
        assertEquals(listOf("F1", "F2", "F3", "F4", "F5", "M1", "M2", "M3", "M4", "M5"), TTS_VOICE_STYLES)
        assertFalse(TTS_VOICE_STYLES.contains("Q1"))

        val fallback = repository.currentCatalogue.builtIn
            .single { it.displayName == DEFAULT_TTS_VOICE_STYLE }
        assertTrue(fallback.availability is VoiceProfileAvailability.Available)
        assertEquals(
            File(builtInDir, "$DEFAULT_TTS_VOICE_STYLE.json").absolutePath,
            repository.synthesisStyleFilePath(fallback.id),
        )
        assertNull(repository.synthesisStyleFilePath(VoiceProfileId("builtin:Q1")))
    }

    // ── fixtures ───────────────────────────────────────────────────────────────

    private fun identity(channelId: String) = CapabilityScopeIdentity(channelId, RuntimeGeneration(1))

    private fun request(voice: SpeechVoice) = SpeechSynthesisRequest("hello", "en", voice)

    private fun assign(channelId: String, profileId: VoiceProfileId?) {
        snapshot = ChannelCatalogueSnapshot(
            definitions = snapshot.definitions.filter { it.id != channelId } +
                ChannelDefinition(
                    id = channelId,
                    name = channelId,
                    implementationId = ChannelImplementationId("test:provider"),
                    enabled = true,
                    configSchemaVersion = 1,
                    configPayload = OpaqueJsonObject.parse("{}").getOrThrow(),
                    hostPreferences = ChannelHostPreferences(profileId),
                ),
            activeChannelId = null,
        )
    }

    private fun saveCustom(displayName: String): VoiceProfileId {
        val saved = repository.saveAsNew(
            tensors(),
            VoiceProfileProvenance(
                sources = listOf(VoiceProfileSourceProvenance(VoiceProfileId("builtin:F1"), "F1", 1.0)),
                weightMode = VoiceProfileWeightMode.MANUAL,
            ),
            displayName,
        ) as VoiceProfileMutation.Saved
        return saved.summary.id
    }

    private fun tensors(): VoiceProfileTensors =
        VoiceProfileTensors(
            ttl = VoiceTensor.copyOf(
                Supertonic3VoiceProfileContract.TTL_DIMENSIONS,
                FloatArray(Supertonic3VoiceProfileContract.TTL_ELEMENT_COUNT) { 0.5f },
            ),
            dp = VoiceTensor.copyOf(
                Supertonic3VoiceProfileContract.DP_DIMENSIONS,
                FloatArray(Supertonic3VoiceProfileContract.DP_ELEMENT_COUNT) { 0.25f },
            ),
        )

    private fun capabilityFixture(): CapabilityFixture = CapabilityFixture()

    /**
     * Composes the production capability chain — [ServiceCoreInitializer.synthesisCapability]
     * plus [ChannelVoiceResolver] — over a fake synthesizer, so typed results and the
     * non-default filter are exercised exactly as the service wires them.
     */
    private inner class CapabilityFixture {
        val synthesizer = FakeTtsSynthesizer()
        private val textOutputService = mockk<SleepwalkerTextOutputService> {
            every { availability } returns MutableStateFlow(TextOutputAvailability.Available)
        }
        private val initializer = ServiceCoreInitializer(
            context = mockk<Context>(relaxed = true),
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Job()),
            filesDirProvider = { folder.root },
            textOutputService = textOutputService,
            channelCatalogue = { snapshot },
            modelStatusSink = ModelStatusSink { },
            navigationStateLoss = StateLossCallback { _, _ -> },
            hostAudioPlay = { true },
            ttsFactory = TtsFactory { synthesizer },
        ).also { it.constructTtsSynthesizer() }

        private val capabilities = mutableMapOf<String, SynthesisCapability>()

        /** Acquires the capability for an instance; repeated calls return the same instance. */
        fun acquire(identity: CapabilityScopeIdentity): SynthesisCapability =
            capabilities.getOrPut(identity.channelInstanceId) {
                requireNotNull(
                    initializer.synthesisCapability(
                        identity = identity,
                        voiceStylePath = { requesting -> resolver.resolveStylePath(requesting) },
                        totalSteps = { TOTAL_STEPS },
                    )
                )
            }
    }

    private companion object {
        private const val TOTAL_STEPS = 8
    }
}
