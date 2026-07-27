package io.talkcan.model

import io.talkcan.channel.capability.CapabilityAvailability
import io.talkcan.channel.capability.CapabilityKey
import io.talkcan.channel.capability.ChannelCapability
import io.talkcan.channel.capability.ChannelCapabilityHost
import io.talkcan.channel.capability.ChannelCapabilityPort
import io.talkcan.channel.capability.HostedCapabilityAcquisition
import io.talkcan.dependency.ArtifactDigest
import io.talkcan.dependency.ConfigurationDataDeclaration
import io.talkcan.dependency.ConfigurationFieldDeclaration
import io.talkcan.dependency.ConfigurationUiDeclaration
import io.talkcan.dependency.GitHubAssetIdentity
import io.talkcan.dependency.GitHubReleaseIdentity
import io.talkcan.dependency.GitHubRepositoryCoordinates
import io.talkcan.dependency.GitHubRepositoryIdentity
import io.talkcan.dependency.InstalledProviderId
import io.talkcan.dependency.PackageManifest
import io.talkcan.dependency.PackageCapability
import io.talkcan.dependency.PackageConfigurationDeclaration
import io.talkcan.dependency.PackagePresentation
import io.talkcan.dependency.PackageResourcesDeclaration
import io.talkcan.dependency.PackageSourceRecord
import io.talkcan.dependency.PackageConfigurationLimits
import io.talkcan.dependency.RuntimeRequirements
import io.talkcan.dependency.UiChoice
import io.talkcan.dependency.UiControl
import io.talkcan.dependency.UiFieldDeclaration
import io.talkcan.dependency.ValidatedPackageRevision
import io.talkcan.lua.API_VERSION
import io.talkcan.lua.ImmutableProgramImage
import io.talkcan.lua.LUA_VERSION
import io.talkcan.lua.LuaCallbackHandle
import io.talkcan.lua.LuaCoroutineId
import io.talkcan.lua.LuaKernelBridge
import io.talkcan.lua.LuaKernelConfig
import io.talkcan.lua.LuaKernelOutcome
import io.talkcan.lua.LuaOperationHandle
import io.talkcan.lua.LuaPackageMaterializer
import io.talkcan.lua.LuaSpawnAdmission
import io.talkcan.lua.LuaStateHandle
import io.talkcan.lua.LuaValue
import io.talkcan.lua.LuaProgramRequirements
import io.talkcan.lua.actor.ActorRuntimeFactory
import io.talkcan.service.ChannelRuntimeRegistry
import io.talkcan.service.ChannelRuntimeRegistryShutdownResult
import io.talkcan.service.RuntimeInvocationBoundary
import io.talkcan.service.RuntimeInvocationPolicy
import io.talkcan.service.RuntimeWorkerDispatcher
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InstalledProviderRegistryContractTest {
    @Before
    fun resetLuaConstructionEvidence() {
        ActorRuntimeFactory.resetForTest()
    }

    @After
    fun clearLuaConstructionEvidence() {
        ActorRuntimeFactory.resetForTest()
    }

    @Test
    fun `materialized package provider uses numeric repository identity empty v1 configuration and immutable image without constructing Lua`() {
        val mutableSources = linkedMapOf("plugin" to PROGRAM_SOURCE)
        val image = image(mutableSources)
        mutableSources["plugin"] = "return { startup = function() error('mutated source') end }"

        assertEquals(PROGRAM_SOURCE, image.sourceMap.getValue("plugin"))
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (image.sourceMap as MutableMap<String, String>)["other"] = "return {}"
        }

        val bridge = RecordingLuaKernelBridge()
        val binding = materializedBinding(repositoryId = "918273645", digestCharacter = 'a', image = image, bridge = bridge)
        val provider = binding.provider
        val implementationId = InstalledProviderId.derive(GitHubRepositoryIdentity("918273645"))

        assertEquals("github-repository:918273645", provider.descriptor.implementationId.value)
        assertEquals(implementationId, provider.descriptor.implementationId)
        assertEquals(implementationId, provider.descriptor.configuration.implementationId)
        assertEquals(ProviderRevisionFingerprint("a".repeat(64)), provider.fingerprint)
        assertTrue(provider.descriptor.configurationFields.isEmpty())
        assertTrue(provider.descriptor.requiredCapabilities.isEmpty())

        val emptyConfiguration = provider.descriptor.configuration.validate(
            schemaVersion = 1,
            payload = emptyPayload(),
        )
        assertTrue(emptyConfiguration is ProviderConfigurationResult.Success)
        val nonEmptyFailure = provider.descriptor.configuration.validate(
            schemaVersion = 1,
            payload = OpaqueJsonObject.parse("""{"ignored":true}""").getOrThrow(),
        ) as? ProviderConfigurationResult.Failure
            ?: throw AssertionError("Nonempty v1 configuration must be rejected")
        assertEquals(
            ChannelProviderError.InvalidConfiguration(
                implementationId,
                1,
                "Undeclared configuration field: ignored",
            ),
            nonEmptyFailure.error,
        )

        val registry = ChannelImplementationProviderRegistry()
        assertEquals(
            InstalledProvidersPublicationResult.Success(1L),
            registry.publishInstalledProviders(mapOf(implementationId to binding)),
        )
        assertSame(provider, (registry.resolve(implementationId) as ChannelProviderResolution.Available).provider)
        assertTrue("materialization and registration must not call the injected Lua bridge", bridge.createdStateIds.isEmpty())
        assertFalse("materialization and registration must not attempt an actor", ActorRuntimeFactory.isCreateAttempted)
    }

    @Test
    fun `materialized provider compiles non-empty configuration declaration into exact default payload and fields without Lua execution`() {
        val declaration = PackageConfigurationDeclaration(
            ConfigurationDataDeclaration(
                listOf(
                    ConfigurationFieldDeclaration.StringField(
                        "mode",
                        "ECHO",
                        listOf("ECHO", "DELAYED_ECHO", "STT", "TTS", "STT_TTS")
                    ),
                    ConfigurationFieldDeclaration.BooleanField("verbose", false),
                    ConfigurationFieldDeclaration.IntegerField("retry_count", 3L, 0L, 10L),
                )
            ),
            ConfigurationUiDeclaration(
                listOf(
                    UiFieldDeclaration(
                        "mode",
                        UiControl.CHOICE,
                        "Mode",
                        null,
                        listOf(
                            UiChoice("ECHO", "ECHO"),
                            UiChoice("DELAYED_ECHO", "DELAYED_ECHO"),
                            UiChoice("STT", "STT"),
                            UiChoice("TTS", "TTS"),
                            UiChoice("STT_TTS", "STT_TTS"),
                        )
                    ),
                    UiFieldDeclaration("verbose", UiControl.TOGGLE, "Verbose", null, null),
                    UiFieldDeclaration("retry_count", UiControl.NUMBER, "Retry count", null, null),
                )
            )
        )
        val bridge = RecordingLuaKernelBridge()
        val image = image(linkedMapOf("plugin" to PROGRAM_SOURCE))
        val identity = GitHubRepositoryIdentity("555666777")
        val implementationId = InstalledProviderId.derive(identity)
        val digest = ArtifactDigest("c".repeat(64))
        val revision = ValidatedPackageRevision(
            digest = digest,
            manifest = PackageManifest(
                manifestVersion = 1,
                repositoryId = identity,
                packageVersion = "1.0.0",
                entryModule = "plugin",
                presentation = PackagePresentation("Test package", "Non-empty configuration test"),
                runtime = RuntimeRequirements(LUA_VERSION, API_VERSION),
                configuration = declaration,
                resources = PackageResourcesDeclaration(emptyList()),
                capabilities = setOf(
                    PackageCapability.AUDIO_TRANSCRIPTION,
                    PackageCapability.AUDIO_SYNTHESIS,
                    PackageCapability.AUDIO_PLAYBACK,
                ),
            ),
            sourceRecord = sourceRecord(identity),
            sourceMap = image.sourceMap,
            programImage = image,
            fingerprint = ProviderRevisionFingerprint.fromDigest(digest),
        )

        val binding = LuaPackageMaterializer.materialize(revision, bridge)
        val provider = binding.provider

        assertEquals(implementationId, provider.descriptor.implementationId)
        assertEquals(ProviderRevisionFingerprint("c".repeat(64)), provider.fingerprint)

        val defaultPayload = provider.descriptor.configuration.defaultPayload()
        val defaultJson = defaultPayload.toJsonObject()
        assertEquals(3, defaultJson.length())
        assertEquals("ECHO", defaultJson.getString("mode"))
        assertFalse(defaultJson.getBoolean("verbose"))
        assertEquals(3L, defaultJson.getLong("retry_count"))

        val fields = provider.descriptor.configurationFields
        assertEquals(3, fields.size)

        val modeField = fields.find { it.id == "mode" } as? ChannelConfigurationField.ChoiceField
            ?: throw AssertionError("mode field must be a ChoiceField")
        assertEquals("Mode", modeField.label)
        assertEquals(5, modeField.choices.size)
        assertEquals("ECHO", modeField.choices[0].id)
        assertEquals("ECHO", modeField.choices[0].label)

        val verboseField = fields.find { it.id == "verbose" } as? ChannelConfigurationField.BooleanField
            ?: throw AssertionError("verbose field must be a BooleanField")
        assertEquals("Verbose", verboseField.label)

        val retryField = fields.find { it.id == "retry_count" } as? ChannelConfigurationField.NumberField
            ?: throw AssertionError("retry_count field must be a NumberField")
        assertEquals("Retry count", retryField.label)
        assertEquals(0L, retryField.minimum)
        assertEquals(10L, retryField.maximum)

        val caps = provider.descriptor.requiredCapabilities
        assertTrue("requiredCapabilities must contain Transcription", caps.contains(ChannelCapability.Transcription))
        assertTrue("requiredCapabilities must contain Synthesis", caps.contains(ChannelCapability.Synthesis))
        assertTrue("requiredCapabilities must contain AudioOperation", caps.contains(ChannelCapability.AudioOperation))
        assertTrue("requiredCapabilities must contain DeferredAudioPlayback", caps.contains(ChannelCapability.DeferredAudioPlayback))
        assertEquals(4, caps.size)

        val validPayload = OpaqueJsonObject.parse("""{"mode":"STT","verbose":true,"retry_count":5}""").getOrThrow()
        val validResult = provider.descriptor.configuration.validate(1, validPayload)
        assertTrue("valid payload must validate", validResult is ProviderConfigurationResult.Success)

        val undeclaredField = OpaqueJsonObject.parse("""{"mode":"ECHO","verbose":false,"retry_count":1,"extra":true}""").getOrThrow()
        val undeclaredResult = provider.descriptor.configuration.validate(1, undeclaredField)
        val undeclaredError = (undeclaredResult as? ProviderConfigurationResult.Failure)?.error
            ?: throw AssertionError("undeclared field must be rejected")
        assertTrue("undeclared field error must be InvalidConfiguration", undeclaredError is ChannelProviderError.InvalidConfiguration)

        val missingField = OpaqueJsonObject.parse("""{"mode":"ECHO","verbose":false}""").getOrThrow()
        val missingResult = provider.descriptor.configuration.validate(1, missingField)
        val missingError = (missingResult as? ProviderConfigurationResult.Failure)?.error
            ?: throw AssertionError("missing declared field must be rejected")
        assertTrue("missing field error must be InvalidConfiguration", missingError is ChannelProviderError.InvalidConfiguration)

        val wrongType = OpaqueJsonObject.parse("""{"mode":"ECHO","verbose":"yes","retry_count":3}""").getOrThrow()
        val wrongTypeResult = provider.descriptor.configuration.validate(1, wrongType)
        val wrongTypeEerror = (wrongTypeResult as? ProviderConfigurationResult.Failure)?.error
            ?: throw AssertionError("wrong type must be rejected")
        assertTrue("wrong type error must be InvalidConfiguration", wrongTypeEerror is ChannelProviderError.InvalidConfiguration)

        val invalidChoice = OpaqueJsonObject.parse("""{"mode":"UNKNOWN","verbose":false,"retry_count":3}""").getOrThrow()
        val invalidChoiceResult = provider.descriptor.configuration.validate(1, invalidChoice)
        val invalidChoiceError = (invalidChoiceResult as? ProviderConfigurationResult.Failure)?.error
            ?: throw AssertionError("invalid choice value must be rejected")
        assertTrue("invalid choice error must be InvalidConfiguration", invalidChoiceError is ChannelProviderError.InvalidConfiguration)

        val outOfRange = OpaqueJsonObject.parse("""{"mode":"ECHO","verbose":false,"retry_count":99}""").getOrThrow()
        val outOfRangeResult = provider.descriptor.configuration.validate(1, outOfRange)
        val outOfRangeError = (outOfRangeResult as? ProviderConfigurationResult.Failure)?.error
            ?: throw AssertionError("out of range must be rejected")
        assertTrue("out of range error must be InvalidConfiguration", outOfRangeError is ChannelProviderError.InvalidConfiguration)

        assertTrue("materialization must not call the injected Lua bridge", bridge.createdStateIds.isEmpty())
        assertFalse("materialization must not attempt an actor", ActorRuntimeFactory.isCreateAttempted)
    }

    @Test
    fun `materialized provider with empty declaration produces empty default payload and no capabilities without Lua execution`() {
        val declaration = PackageConfigurationDeclaration(
            ConfigurationDataDeclaration(emptyList()),
            ConfigurationUiDeclaration(emptyList())
        )
        val bridge = RecordingLuaKernelBridge()
        val image = image(linkedMapOf("plugin" to PROGRAM_SOURCE))
        val identity = GitHubRepositoryIdentity("888999000")
        val implementationId = InstalledProviderId.derive(identity)
        val digest = ArtifactDigest("d".repeat(64))
        val revision = ValidatedPackageRevision(
            digest = digest,
            manifest = PackageManifest(
                manifestVersion = 1,
                repositoryId = identity,
                packageVersion = "1.0.0",
                entryModule = "plugin",
                presentation = PackagePresentation("Empty config package", "Empty configuration test"),
                runtime = RuntimeRequirements(LUA_VERSION, API_VERSION),
                configuration = declaration,
                resources = PackageResourcesDeclaration(emptyList()),
                capabilities = emptySet(),
            ),
            sourceRecord = sourceRecord(identity),
            sourceMap = image.sourceMap,
            programImage = image,
            fingerprint = ProviderRevisionFingerprint.fromDigest(digest),
        )

        val binding = LuaPackageMaterializer.materialize(revision, bridge)
        val provider = binding.provider

        assertEquals(implementationId, provider.descriptor.implementationId)

        val defaultPayload = provider.descriptor.configuration.defaultPayload()
        assertEquals(0, defaultPayload.toJsonObject().length())

        assertEquals(0, provider.descriptor.configurationFields.size)
        assertEquals(0, provider.descriptor.requiredCapabilities.size)

        val validResult = provider.descriptor.configuration.validate(1, defaultPayload)
        assertTrue("empty payload must validate for empty declaration", validResult is ProviderConfigurationResult.Success)

        val nonEmptyPayload = OpaqueJsonObject.parse("""{"unexpected":true}""").getOrThrow()
        val nonEmptyResult = provider.descriptor.configuration.validate(1, nonEmptyPayload)
        val nonEmptyError = (nonEmptyResult as? ProviderConfigurationResult.Failure)?.error
            ?: throw AssertionError("non-empty payload must be rejected for empty declaration")
        assertTrue("non-empty error must be InvalidConfiguration", nonEmptyError is ChannelProviderError.InvalidConfiguration)

        assertTrue("materialization must not call the injected Lua bridge", bridge.createdStateIds.isEmpty())
        assertFalse("materialization must not attempt an actor", ActorRuntimeFactory.isCreateAttempted)
    }

    // Task 6.1: Public-to-internal capability mapping and serialization leakage

    @Test
    fun `empty capability set compiles to empty internal required capabilities`() {
        val caps = materializeCapabilities(emptySet())
        assertEquals("empty public set must compile to empty internal set", emptySet<ChannelCapability>(), caps)
    }

    @Test
    fun `audio dot transcription compiles to exactly Transcription`() {
        val caps = materializeCapabilities(setOf(PackageCapability.AUDIO_TRANSCRIPTION))
        assertEquals(
            "audio.transcription must compile to exactly {Transcription}",
            setOf(ChannelCapability.Transcription),
            caps,
        )
    }

    @Test
    fun `audio dot synthesis compiles to exactly Synthesis`() {
        val caps = materializeCapabilities(setOf(PackageCapability.AUDIO_SYNTHESIS))
        assertEquals(
            "audio.synthesis must compile to exactly {Synthesis}",
            setOf(ChannelCapability.Synthesis),
            caps,
        )
    }

    @Test
    fun `audio dot playback compiles to exactly AudioOperation and DeferredAudioPlayback`() {
        val caps = materializeCapabilities(setOf(PackageCapability.AUDIO_PLAYBACK))
        assertEquals(
            "audio.playback must expand to exactly {AudioOperation, DeferredAudioPlayback}",
            setOf(ChannelCapability.AudioOperation, ChannelCapability.DeferredAudioPlayback),
            caps,
        )
    }

    @Test
    fun `storage dot files compiles to exactly StorageFiles`() {
        val caps = materializeCapabilities(setOf(PackageCapability.STORAGE_FILES))
        assertEquals(
            "storage.files must compile to exactly {StorageFiles}",
            setOf(ChannelCapability.StorageFiles),
            caps,
        )
    }

    @Test
    fun `audio dot files compiles to exactly AudioFiles`() {
        val caps = materializeCapabilities(setOf(PackageCapability.AUDIO_FILES))
        assertEquals(
            "audio.files must compile to exactly {AudioFiles}",
            setOf(ChannelCapability.AudioFiles),
            caps,
        )
    }

    @Test
    fun `all public capabilities compile to exactly eleven internal requirements`() {
        val caps = materializeCapabilities(PackageCapability.ALL)
        assertEquals(
            "all public IDs must compile to the exact internal capability set",
            setOf(
                ChannelCapability.Transcription,
                ChannelCapability.Synthesis,
                ChannelCapability.AudioOperation,
                ChannelCapability.DeferredAudioPlayback,
                ChannelCapability.StorageFiles,
                ChannelCapability.AudioFiles,
                ChannelCapability.TextOutput,
                ChannelCapability.NetworkHttp,
                ChannelCapability.ProfilesRead,
                ChannelCapability.SecretsRead,
                ChannelCapability.WorkQueue,
            ),
            caps,
        )
        assertEquals("all capabilities must produce exactly 11 internal requirements", 11, caps.size)
    }

    @Test
    fun `unknown capability id is rejected fail-closed at manifest construction`() {
        val identity = GitHubRepositoryIdentity("600100200")
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            PackageManifest(
                manifestVersion = 1,
                repositoryId = identity,
                packageVersion = "1.0.0",
                entryModule = "plugin",
                presentation = PackagePresentation("Unknown cap", "Unknown capability test"),
                runtime = RuntimeRequirements(LUA_VERSION, API_VERSION),
                configuration = PackageConfigurationDeclaration(
                    ConfigurationDataDeclaration(emptyList()),
                    ConfigurationUiDeclaration(emptyList()),
                ),
                resources = PackageResourcesDeclaration(emptyList()),
                capabilities = setOf("audio.transcription", "audio.unknown"),
            )
        }
        assertTrue(
            "failure message must name the unknown ID",
            thrown.message!!.contains("audio.unknown"),
        )
        assertTrue(
            "failure message must reference PackageCapability.ALL",
            thrown.message!!.contains("PackageCapability.ALL"),
        )
    }

    @Test
    fun `compiled required capabilities expose only stableId strings not Kotlin class or key names`() {
        val caps = materializeCapabilities(PackageCapability.ALL)
        val serializedStableIds = caps.map { it.stableId }.toSet()
        assertEquals(
            "serialized stableId set must be exactly the eleven internal stable IDs",
            setOf(
                "transcription",
                "synthesis",
                "audio-operation",
                "deferred-audio-playback",
                "storage-files",
                "audio-files",
                "text-output",
                "network.http",
                "profiles.read",
                "secrets.read",
                "work.queue",
            ),
            serializedStableIds,
        )
        val serializedBlob = caps.joinToString(",", prefix = "[", postfix = "]") { it.stableId }
        val kotlinKeyNames = listOf(
            "CapabilityKey",
            "CapabilityKey.Transcription",
            "CapabilityKey.Synthesis",
            "CapabilityKey.AudioOperation",
            "CapabilityKey.DeferredAudioPlayback",
            "ChannelCapabilityContracts",
        )
        kotlinKeyNames.forEach { leaked ->
            assertFalse(
                "serialized capability data must not leak Kotlin key/class name: $leaked",
                serializedBlob.contains(leaked),
            )
        }
        val publicIds = listOf(
            PackageCapability.AUDIO_TRANSCRIPTION,
            PackageCapability.AUDIO_SYNTHESIS,
            PackageCapability.AUDIO_PLAYBACK,
            PackageCapability.STORAGE_FILES,
            PackageCapability.AUDIO_FILES,
            PackageCapability.KEYBOARD_OUTPUT,
        )
        publicIds.forEach { publicId ->
            assertFalse(
                "internal stableId data must not contain public manifest ID $publicId (boundary isolation)",
                serializedBlob.contains(publicId),
            )
        }
    }

    @Test
    fun `materialized descriptor required capabilities contain only ChannelCapability stableIds not CapabilityKey references`() {
        val bridge = RecordingLuaKernelBridge()
        val image = image(linkedMapOf("plugin" to PROGRAM_SOURCE))
        val identity = GitHubRepositoryIdentity("700800900")
        val digest = ArtifactDigest("f".repeat(64))
        val revision = ValidatedPackageRevision(
            digest = digest,
            manifest = PackageManifest(
                manifestVersion = 1,
                repositoryId = identity,
                packageVersion = "1.0.0",
                entryModule = "plugin",
                presentation = PackagePresentation("Leak test", "Serialization leakage test"),
                runtime = RuntimeRequirements(LUA_VERSION, API_VERSION),
                configuration = PackageConfigurationDeclaration(
                    ConfigurationDataDeclaration(emptyList()),
                    ConfigurationUiDeclaration(emptyList()),
                ),
                resources = PackageResourcesDeclaration(emptyList()),
                capabilities = PackageCapability.ALL,
            ),
            sourceRecord = sourceRecord(identity),
            sourceMap = image.sourceMap,
            programImage = image,
            fingerprint = ProviderRevisionFingerprint.fromDigest(digest),
        )
        val binding = LuaPackageMaterializer.materialize(revision, bridge)
        val descriptor = binding.provider.descriptor
        val requiredCaps = descriptor.requiredCapabilities

        assertEquals(11, requiredCaps.size)
        val stableIds = requiredCaps.map { it.stableId }.toSet()
        assertEquals(
            setOf(
                "transcription",
                "synthesis",
                "audio-operation",
                "deferred-audio-playback",
                "storage-files",
                "audio-files",
                "text-output",
                "network.http",
                "profiles.read",
                "secrets.read",
                "work.queue",
            ),
            stableIds,
        )

        val descriptorString = buildString {
            append("impl=").append(descriptor.implementationId.value)
            append(",caps=").append(requiredCaps.joinToString(",") { it.stableId })
            append(",fields=").append(descriptor.configurationFields.size)
        }
        listOf(
            "CapabilityKey",
            "ChannelCapabilityContracts",
            "TranscriptionCapability",
            "SynthesisCapability",
            "AudioOperationCapability",
            "DeferredAudioPlaybackCapability",
        ).forEach { leaked ->
            assertFalse(
                "descriptor serialization must not leak Kotlin type name: $leaked",
                descriptorString.contains(leaked),
            )
        }
        assertTrue(
            "materialization must not call the injected Lua bridge",
            bridge.createdStateIds.isEmpty(),
        )
    }

    private fun materializeCapabilities(publicCapabilities: Set<String>): Set<ChannelCapability> {
        val bridge = RecordingLuaKernelBridge()
        val image = image(linkedMapOf("plugin" to PROGRAM_SOURCE))
        val identity = GitHubRepositoryIdentity("500400300")
        val digest = ArtifactDigest("a".repeat(64))
        val revision = ValidatedPackageRevision(
            digest = digest,
            manifest = PackageManifest(
                manifestVersion = 1,
                repositoryId = identity,
                packageVersion = "1.0.0",
                entryModule = "plugin",
                presentation = PackagePresentation("Cap map", "Capability mapping test"),
                runtime = RuntimeRequirements(LUA_VERSION, API_VERSION),
                configuration = PackageConfigurationDeclaration(
                    ConfigurationDataDeclaration(emptyList()),
                    ConfigurationUiDeclaration(emptyList()),
                ),
                resources = PackageResourcesDeclaration(emptyList()),
                capabilities = publicCapabilities,
            ),
            sourceRecord = sourceRecord(identity),
            sourceMap = image.sourceMap,
            programImage = image,
            fingerprint = ProviderRevisionFingerprint.fromDigest(digest),
        )
        val binding = LuaPackageMaterializer.materialize(revision, bridge)
        return binding.provider.descriptor.requiredCapabilities
    }

    // Task 2.2: Declared UI render ordering/types/labels/help/choices

    @Test
    fun `compiled provider preserves declared UI field order exactly`() {
        val declaration = PackageConfigurationDeclaration(
            ConfigurationDataDeclaration(
                listOf(
                    ConfigurationFieldDeclaration.StringField("third", "c", listOf("c")),
                    ConfigurationFieldDeclaration.BooleanField("first", true),
                    ConfigurationFieldDeclaration.IntegerField("second", 5L, 0L, 10L),
                )
            ),
            ConfigurationUiDeclaration(
                listOf(
                    UiFieldDeclaration("first", UiControl.TOGGLE, "First Field", null, null),
                    UiFieldDeclaration("second", UiControl.NUMBER, "Second Field", null, null),
                    UiFieldDeclaration("third", UiControl.CHOICE, "Third Field", null, listOf(UiChoice("c", "C"))),
                )
            )
        )
        val bridge = RecordingLuaKernelBridge()
        val image = image(linkedMapOf("plugin" to PROGRAM_SOURCE))
        val identity = GitHubRepositoryIdentity("444555666")
        val digest = ArtifactDigest("f".repeat(64).replace("f", "a"))
        val revision = ValidatedPackageRevision(
            digest = digest,
            manifest = PackageManifest(
                manifestVersion = 1,
                repositoryId = identity,
                packageVersion = "1.0.0",
                entryModule = "plugin",
                presentation = PackagePresentation("Order test", "UI order preservation"),
                runtime = RuntimeRequirements(LUA_VERSION, API_VERSION),
                configuration = declaration,
                resources = PackageResourcesDeclaration(emptyList()),
                capabilities = emptySet(),
            ),
            sourceRecord = sourceRecord(identity),
            sourceMap = image.sourceMap,
            programImage = image,
            fingerprint = ProviderRevisionFingerprint.fromDigest(digest),
        )

        val binding = LuaPackageMaterializer.materialize(revision, bridge)
        val fields = binding.provider.descriptor.configurationFields

        assertEquals("field count", 3, fields.size)
        assertEquals("first field id", "first", fields[0].id)
        assertEquals("second field id", "second", fields[1].id)
        assertEquals("third field id", "third", fields[2].id)
        assertTrue("materialization must not execute Lua", bridge.createdStateIds.isEmpty())
    }

    @Test
    fun `compiled provider includes label and help text in field metadata`() {
        val declaration = PackageConfigurationDeclaration(
            ConfigurationDataDeclaration(
                listOf(
                    ConfigurationFieldDeclaration.StringField("name", "default", null),
                    ConfigurationFieldDeclaration.BooleanField("enabled", false),
                )
            ),
            ConfigurationUiDeclaration(
                listOf(
                    UiFieldDeclaration("name", UiControl.TEXT, "Display Name", "Help text for name field", null),
                    UiFieldDeclaration("enabled", UiControl.TOGGLE, "Enable Feature", null, null),
                )
            )
        )
        val bridge = RecordingLuaKernelBridge()
        val image = image(linkedMapOf("plugin" to PROGRAM_SOURCE))
        val identity = GitHubRepositoryIdentity("777888999")
        val digest = ArtifactDigest("a".repeat(64))
        val revision = ValidatedPackageRevision(
            digest = digest,
            manifest = PackageManifest(
                manifestVersion = 1,
                repositoryId = identity,
                packageVersion = "1.0.0",
                entryModule = "plugin",
                presentation = PackagePresentation("Help test", "Help text compilation"),
                runtime = RuntimeRequirements(LUA_VERSION, API_VERSION),
                configuration = declaration,
                resources = PackageResourcesDeclaration(emptyList()),
                capabilities = emptySet(),
            ),
            sourceRecord = sourceRecord(identity),
            sourceMap = image.sourceMap,
            programImage = image,
            fingerprint = ProviderRevisionFingerprint.fromDigest(digest),
        )

        val binding = LuaPackageMaterializer.materialize(revision, bridge)
        val fields = binding.provider.descriptor.configurationFields

        val nameField = fields.find { it.id == "name" } as? ChannelConfigurationField.TextField
            ?: throw AssertionError("name must be TextField")
        assertEquals("Display Name", nameField.label)

        val enabledField = fields.find { it.id == "enabled" } as? ChannelConfigurationField.BooleanField
            ?: throw AssertionError("enabled must be BooleanField")
        assertEquals("Enable Feature", enabledField.label)

        assertTrue("materialization must not execute Lua", bridge.createdStateIds.isEmpty())
    }

    @Test
    fun `compiled choice field preserves exact value-label mapping and order`() {
        val declaration = PackageConfigurationDeclaration(
            ConfigurationDataDeclaration(
                listOf(
                    ConfigurationFieldDeclaration.StringField("option", "alpha", listOf("alpha", "beta", "gamma")),
                )
            ),
            ConfigurationUiDeclaration(
                listOf(
                    UiFieldDeclaration(
                        "option",
                        UiControl.CHOICE,
                        "Option",
                        null,
                        listOf(
                            UiChoice("alpha", "Alpha Label"),
                            UiChoice("beta", "Beta Label"),
                            UiChoice("gamma", "Gamma Label"),
                        )
                    ),
                )
            )
        )
        val bridge = RecordingLuaKernelBridge()
        val image = image(linkedMapOf("plugin" to PROGRAM_SOURCE))
        val identity = GitHubRepositoryIdentity("111222333")
        val digest = ArtifactDigest("b".repeat(64))
        val revision = ValidatedPackageRevision(
            digest = digest,
            manifest = PackageManifest(
                manifestVersion = 1,
                repositoryId = identity,
                packageVersion = "1.0.0",
                entryModule = "plugin",
                presentation = PackagePresentation("Choice test", "Choice value/label mapping"),
                runtime = RuntimeRequirements(LUA_VERSION, API_VERSION),
                configuration = declaration,
                resources = PackageResourcesDeclaration(emptyList()),
                capabilities = emptySet(),
            ),
            sourceRecord = sourceRecord(identity),
            sourceMap = image.sourceMap,
            programImage = image,
            fingerprint = ProviderRevisionFingerprint.fromDigest(digest),
        )

        val binding = LuaPackageMaterializer.materialize(revision, bridge)
        val field = binding.provider.descriptor.configurationFields.first() as? ChannelConfigurationField.ChoiceField
            ?: throw AssertionError("option must be ChoiceField")

        assertEquals("Option", field.label)
        assertEquals(3, field.choices.size)
        assertEquals("alpha", field.choices[0].id)
        assertEquals("Alpha Label", field.choices[0].label)
        assertEquals("beta", field.choices[1].id)
        assertEquals("Beta Label", field.choices[1].label)
        assertEquals("gamma", field.choices[2].id)
        assertEquals("Gamma Label", field.choices[2].label)

        assertTrue("materialization must not execute Lua", bridge.createdStateIds.isEmpty())
    }

    // Task 2.3: Exact payload validation with typed provider errors

    @Test
    fun `payload validation rejects null value for declared field`() {
        val declaration = PackageConfigurationDeclaration(
            ConfigurationDataDeclaration(
                listOf(ConfigurationFieldDeclaration.StringField("text", "default", null))
            ),
            ConfigurationUiDeclaration(
                listOf(UiFieldDeclaration("text", UiControl.TEXT, "Text", null, null))
            )
        )
        val provider = compiledProvider(declaration, "100")

        val payload = OpaqueJsonObject.parse("""{"text":null}""").getOrThrow()
        val result = provider.validate(1, payload)

        val error = (result as? ProviderConfigurationResult.Failure)?.error
            ?: throw AssertionError("null value must be rejected")
        assertTrue("null value error must be InvalidConfiguration", error is ChannelProviderError.InvalidConfiguration)
        assertTrue("error message must mention null", error.message.contains("null", ignoreCase = true))
    }

    @Test
    fun `payload validation rejects float value for integer field`() {
        val declaration = PackageConfigurationDeclaration(
            ConfigurationDataDeclaration(
                listOf(ConfigurationFieldDeclaration.IntegerField("count", 0L, 0L, 100L))
            ),
            ConfigurationUiDeclaration(
                listOf(UiFieldDeclaration("count", UiControl.NUMBER, "Count", null, null))
            )
        )
        val provider = compiledProvider(declaration, "101")

        val payload = OpaqueJsonObject.parse("""{"count":3.14}""").getOrThrow()
        val result = provider.validate(1, payload)

        val error = (result as? ProviderConfigurationResult.Failure)?.error
            ?: throw AssertionError("float value must be rejected for integer field")
        assertTrue("float error must be InvalidConfiguration", error is ChannelProviderError.InvalidConfiguration)
        assertTrue("error message must mention type mismatch", error.message.contains("integer", ignoreCase = true))
    }

    @Test
    fun `payload validation rejects nested object value for declared field`() {
        val declaration = PackageConfigurationDeclaration(
            ConfigurationDataDeclaration(
                listOf(ConfigurationFieldDeclaration.StringField("config", "default", null))
            ),
            ConfigurationUiDeclaration(
                listOf(UiFieldDeclaration("config", UiControl.TEXT, "Config", null, null))
            )
        )
        val provider = compiledProvider(declaration, "102")

        val payload = OpaqueJsonObject.parse("""{"config":{"nested":true}}""").getOrThrow()
        val result = provider.validate(1, payload)

        val error = (result as? ProviderConfigurationResult.Failure)?.error
            ?: throw AssertionError("nested object must be rejected")
        assertTrue("nested object error must be InvalidConfiguration", error is ChannelProviderError.InvalidConfiguration)
    }

    @Test
    fun `payload validation rejects array value for declared field`() {
        val declaration = PackageConfigurationDeclaration(
            ConfigurationDataDeclaration(
                listOf(ConfigurationFieldDeclaration.StringField("items", "default", null))
            ),
            ConfigurationUiDeclaration(
                listOf(UiFieldDeclaration("items", UiControl.TEXT, "Items", null, null))
            )
        )
        val provider = compiledProvider(declaration, "103")

        val payload = OpaqueJsonObject.parse("""{"items":["a","b","c"]}""").getOrThrow()
        val result = provider.validate(1, payload)

        val error = (result as? ProviderConfigurationResult.Failure)?.error
            ?: throw AssertionError("array value must be rejected")
        assertTrue("array error must be InvalidConfiguration", error is ChannelProviderError.InvalidConfiguration)
    }

    @Test
    fun `payload validation does not coerce number to string`() {
        val declaration = PackageConfigurationDeclaration(
            ConfigurationDataDeclaration(
                listOf(ConfigurationFieldDeclaration.StringField("value", "default", null))
            ),
            ConfigurationUiDeclaration(
                listOf(UiFieldDeclaration("value", UiControl.TEXT, "Value", null, null))
            )
        )
        val provider = compiledProvider(declaration, "104")

        val payload = OpaqueJsonObject.parse("""{"value":42}""").getOrThrow()
        val result = provider.validate(1, payload)

        val error = (result as? ProviderConfigurationResult.Failure)?.error
            ?: throw AssertionError("number for string field must be rejected without coercion")
        assertTrue("no-coercion error must be InvalidConfiguration", error is ChannelProviderError.InvalidConfiguration)
        assertTrue("error message must mention type", error.message.contains("string", ignoreCase = true))
    }

    @Test
    fun `payload validation does not coerce string to boolean`() {
        val declaration = PackageConfigurationDeclaration(
            ConfigurationDataDeclaration(
                listOf(ConfigurationFieldDeclaration.BooleanField("flag", false))
            ),
            ConfigurationUiDeclaration(
                listOf(UiFieldDeclaration("flag", UiControl.TOGGLE, "Flag", null, null))
            )
        )
        val provider = compiledProvider(declaration, "105")

        val payload = OpaqueJsonObject.parse("""{"flag":"true"}""").getOrThrow()
        val result = provider.validate(1, payload)

        val error = (result as? ProviderConfigurationResult.Failure)?.error
            ?: throw AssertionError("string for boolean field must be rejected without coercion")
        assertTrue("no-coercion error must be InvalidConfiguration", error is ChannelProviderError.InvalidConfiguration)
    }

    @Test
    fun `payload validation enforces integer bounds strictly`() {
        val declaration = PackageConfigurationDeclaration(
            ConfigurationDataDeclaration(
                listOf(ConfigurationFieldDeclaration.IntegerField("level", 5L, 1L, 10L))
            ),
            ConfigurationUiDeclaration(
                listOf(UiFieldDeclaration("level", UiControl.NUMBER, "Level", null, null))
            )
        )
        val provider = compiledProvider(declaration, "106")

        val belowMin = OpaqueJsonObject.parse("""{"level":0}""").getOrThrow()
        val belowResult = provider.validate(1, belowMin)
        val belowError = (belowResult as? ProviderConfigurationResult.Failure)?.error
            ?: throw AssertionError("below minimum must be rejected")
        assertTrue("below-min error must be InvalidConfiguration", belowError is ChannelProviderError.InvalidConfiguration)
        assertTrue("error must mention minimum", belowError.message.contains("minimum", ignoreCase = true))

        val aboveMax = OpaqueJsonObject.parse("""{"level":11}""").getOrThrow()
        val aboveResult = provider.validate(1, aboveMax)
        val aboveError = (aboveResult as? ProviderConfigurationResult.Failure)?.error
            ?: throw AssertionError("above maximum must be rejected")
        assertTrue("above-max error must be InvalidConfiguration", aboveError is ChannelProviderError.InvalidConfiguration)
        assertTrue("error must mention maximum", aboveError.message.contains("maximum", ignoreCase = true))

        val atMin = OpaqueJsonObject.parse("""{"level":1}""").getOrThrow()
        assertTrue("at minimum must be valid", provider.validate(1, atMin) is ProviderConfigurationResult.Success)

        val atMax = OpaqueJsonObject.parse("""{"level":10}""").getOrThrow()
        assertTrue("at maximum must be valid", provider.validate(1, atMax) is ProviderConfigurationResult.Success)
    }

    @Test
    fun `payload validation rejects extra field without filling defaults`() {
        val declaration = PackageConfigurationDeclaration(
            ConfigurationDataDeclaration(
                listOf(ConfigurationFieldDeclaration.StringField("name", "default", null))
            ),
            ConfigurationUiDeclaration(
                listOf(UiFieldDeclaration("name", UiControl.TEXT, "Name", null, null))
            )
        )
        val provider = compiledProvider(declaration, "107")

        val payload = OpaqueJsonObject.parse("""{"name":"value","extra":"ignored"}""").getOrThrow()
        val result = provider.validate(1, payload)

        val error = (result as? ProviderConfigurationResult.Failure)?.error
            ?: throw AssertionError("extra field must be rejected")
        assertTrue("extra field error must be InvalidConfiguration", error is ChannelProviderError.InvalidConfiguration)
        assertTrue("error message must mention undeclared", error.message.contains("Undeclared", ignoreCase = true))
    }

    @Test
    fun `payload validation rejects missing field without filling defaults`() {
        val declaration = PackageConfigurationDeclaration(
            ConfigurationDataDeclaration(
                listOf(
                    ConfigurationFieldDeclaration.StringField("first", "a", null),
                    ConfigurationFieldDeclaration.StringField("second", "b", null),
                )
            ),
            ConfigurationUiDeclaration(
                listOf(
                    UiFieldDeclaration("first", UiControl.TEXT, "First", null, null),
                    UiFieldDeclaration("second", UiControl.TEXT, "Second", null, null),
                )
            )
        )
        val provider = compiledProvider(declaration, "108")

        val payload = OpaqueJsonObject.parse("""{"first":"only"}""").getOrThrow()
        val result = provider.validate(1, payload)

        val error = (result as? ProviderConfigurationResult.Failure)?.error
            ?: throw AssertionError("missing field must be rejected")
        assertTrue("missing field error must be InvalidConfiguration", error is ChannelProviderError.InvalidConfiguration)
        assertTrue("error message must mention missing", error.message.contains("Missing", ignoreCase = true))
    }

    @Test
    fun `payload validation uses exact allowed values without fallback`() {
        val declaration = PackageConfigurationDeclaration(
            ConfigurationDataDeclaration(
                listOf(ConfigurationFieldDeclaration.StringField("mode", "alpha", listOf("alpha", "beta")))
            ),
            ConfigurationUiDeclaration(
                listOf(
                    UiFieldDeclaration(
                        "mode",
                        UiControl.CHOICE,
                        "Mode",
                        null,
                        listOf(UiChoice("alpha", "Alpha"), UiChoice("beta", "Beta"))
                    )
                )
            )
        )
        val provider = compiledProvider(declaration, "109")

        val validAlpha = OpaqueJsonObject.parse("""{"mode":"alpha"}""").getOrThrow()
        assertTrue("alpha must be valid", provider.validate(1, validAlpha) is ProviderConfigurationResult.Success)

        val validBeta = OpaqueJsonObject.parse("""{"mode":"beta"}""").getOrThrow()
        assertTrue("beta must be valid", provider.validate(1, validBeta) is ProviderConfigurationResult.Success)

        val invalidGamma = OpaqueJsonObject.parse("""{"mode":"gamma"}""").getOrThrow()
        val gammaResult = provider.validate(1, invalidGamma)
        val gammaError = (gammaResult as? ProviderConfigurationResult.Failure)?.error
            ?: throw AssertionError("gamma must be rejected")
        assertTrue("invalid allowed-value error must be InvalidConfiguration", gammaError is ChannelProviderError.InvalidConfiguration)
        assertTrue("error must mention allowed values", gammaError.message.contains("allowed", ignoreCase = true))
    }

    @Test
    fun `payload validation rejects string value exceeding per-value byte bound`() {
        val declaration = PackageConfigurationDeclaration(
            ConfigurationDataDeclaration(
                listOf(ConfigurationFieldDeclaration.StringField("text", "x", null))
            ),
            ConfigurationUiDeclaration(
                listOf(UiFieldDeclaration("text", UiControl.TEXT, "Text", null, null))
            )
        )
        val provider = compiledProvider(declaration, "110")

        val oversized = "a".repeat(PackageConfigurationLimits.MAX_STRING_VALUE_BYTES + 1)
        val payload = OpaqueJsonObject.parse("""{"text":"$oversized"}""").getOrThrow()
        val result = provider.validate(1, payload)

        val error = (result as? ProviderConfigurationResult.Failure)?.error
            ?: throw AssertionError("oversized string value must be rejected")
        assertTrue("per-value bound error must be InvalidConfiguration", error is ChannelProviderError.InvalidConfiguration)
        assertTrue("error must mention byte bound", error.message.contains("bytes", ignoreCase = true))
    }

    @Test
    fun `payload validation accepts string value at per-value byte bound`() {
        val declaration = PackageConfigurationDeclaration(
            ConfigurationDataDeclaration(
                listOf(ConfigurationFieldDeclaration.StringField("text", "x", null))
            ),
            ConfigurationUiDeclaration(
                listOf(UiFieldDeclaration("text", UiControl.TEXT, "Text", null, null))
            )
        )
        val provider = compiledProvider(declaration, "111")

        val atBound = "a".repeat(PackageConfigurationLimits.MAX_STRING_VALUE_BYTES)
        val payload = OpaqueJsonObject.parse("""{"text":"$atBound"}""").getOrThrow()
        assertTrue("string at per-value bound must be valid", provider.validate(1, payload) is ProviderConfigurationResult.Success)
    }

    @Test
    fun `payload validation rejects total payload exceeding 64 KiB bound`() {
        val fields = (0 until 8).map { i ->
            ConfigurationFieldDeclaration.StringField("field_$i", "x", null)
        }
        val uiFields = fields.map { f ->
            UiFieldDeclaration(f.id, UiControl.TEXT, f.id, null, null)
        }
        val declaration = PackageConfigurationDeclaration(
            ConfigurationDataDeclaration(fields),
            ConfigurationUiDeclaration(uiFields)
        )
        val provider = compiledProvider(declaration, "112")

        val oversizedValue = "a".repeat(PackageConfigurationLimits.MAX_STRING_VALUE_BYTES)
        val json = StringBuilder("{")
        fields.forEachIndexed { i, f ->
            if (i > 0) json.append(",")
            json.append('"').append(f.id).append('"').append(":\"").append(oversizedValue).append('"')
        }
        json.append("}")
        val payload = OpaqueJsonObject.parse(json.toString()).getOrThrow()
        val result = provider.validate(1, payload)

        val error = (result as? ProviderConfigurationResult.Failure)?.error
            ?: throw AssertionError("oversized total payload must be rejected")
        assertTrue("total-size bound error must be InvalidConfiguration", error is ChannelProviderError.InvalidConfiguration)
        assertTrue("error must mention payload bound", error.message.contains("payload", ignoreCase = true))
    }

    private fun compiledProvider(
        declaration: PackageConfigurationDeclaration,
        repositoryId: String,
    ): ChannelConfigurationProvider {
        val identity = GitHubRepositoryIdentity(repositoryId)
        val digest = ArtifactDigest("a".repeat(64))
        val image = image(linkedMapOf("plugin" to PROGRAM_SOURCE))
        val revision = ValidatedPackageRevision(
            digest = digest,
            manifest = PackageManifest(
                manifestVersion = 1,
                repositoryId = identity,
                packageVersion = "1.0.0",
                entryModule = "plugin",
                presentation = PackagePresentation("Validation test", "Payload validation"),
                runtime = RuntimeRequirements(LUA_VERSION, API_VERSION),
                configuration = declaration,
                resources = PackageResourcesDeclaration(emptyList()),
                capabilities = emptySet(),
            ),
            sourceRecord = sourceRecord(identity),
            sourceMap = image.sourceMap,
            programImage = image,
            fingerprint = ProviderRevisionFingerprint.fromDigest(digest),
        )
        val bridge = RecordingLuaKernelBridge()
        val binding = LuaPackageMaterializer.materialize(revision, bridge)
        assertTrue("compiledProvider must not execute Lua", bridge.createdStateIds.isEmpty())
        return binding.provider.descriptor.configuration
    }

    @Test
    fun `two catalogue instances sharing one installed provider construct separate Lua states`() = runTest {
        val bridge = RecordingLuaKernelBridge()
        val binding = materializedBinding(repositoryId = "101", digestCharacter = 'b', bridge = bridge)
        val implementationId = binding.provider.descriptor.implementationId
        val providers = ChannelImplementationProviderRegistry()
        assertEquals(
            InstalledProvidersPublicationResult.Success(1L),
            providers.publishInstalledProviders(mapOf(implementationId to binding)),
        )
        val runtimeRegistry = runtimeRegistry(providers)
        val left = definition("left-instance", implementationId)
        val right = definition("right-instance", implementationId)

        try {
            runtimeRegistry.reconcile(ChannelCatalogueSnapshot(listOf(left, right), left.id))
            runCurrent()

            assertEquals(2, bridge.createdStateIds.size)
            assertNotEquals(
                "separate instance IDs must receive distinct state ownership",
                bridge.createdStateIds[0],
                bridge.createdStateIds[1],
            )
        } finally {
            assertEquals(ChannelRuntimeRegistryShutdownResult.Closed, shutdown(runtimeRegistry))
        }
        assertEquals(bridge.createdStateIds.toSet(), bridge.closedStateIds.toSet())
    }

    @Test
    fun `invalid installed snapshots return typed reasons and preserve the complete predecessor`() {
        val cases = listOf(
            InvalidSnapshotCase("repository-derived key mismatch", InstalledProvidersRejectionReason.InvalidId::class.java) { valid ->
                mapOf(ChannelImplementationId("github-repository:999") to valid)
            },
            InvalidSnapshotCase("descriptor ID mismatch", InstalledProvidersRejectionReason.AgreementMismatch::class.java) { valid ->
                val id = InstalledProviderId.derive(GitHubRepositoryIdentity("202"))
                mapOf(
                    id to InstalledProviderBinding(
                        repositoryId = GitHubRepositoryIdentity("202"),
                        expectedDigest = ArtifactDigest("c".repeat(64)),
                        provider = InstalledTestProvider(
                            implementationId = InstalledProviderId.derive(GitHubRepositoryIdentity("203")),
                            fingerprint = ProviderRevisionFingerprint("c".repeat(64)),
                        ),
                    ),
                )
            },
            InvalidSnapshotCase("fingerprint mismatch", InstalledProvidersRejectionReason.AgreementMismatch::class.java) { _ ->
                val id = InstalledProviderId.derive(GitHubRepositoryIdentity("204"))
                mapOf(
                    id to InstalledProviderBinding(
                        repositoryId = GitHubRepositoryIdentity("204"),
                        expectedDigest = ArtifactDigest("d".repeat(64)),
                        provider = InstalledTestProvider(id, ProviderRevisionFingerprint("e".repeat(64))),
                    ),
                )
            },
            InvalidSnapshotCase("reserved built-in namespace", InstalledProvidersRejectionReason.ReservedCollision::class.java) { valid ->
                mapOf(ChannelImplementationId("builtin:journal") to valid)
            },
        )

        cases.forEach { case ->
            val predecessor = binding("200", 'b')
            val predecessorId = predecessor.provider.descriptor.implementationId
            val registry = ChannelImplementationProviderRegistry()
            assertEquals(
                "${case.name}: predecessor publication",
                InstalledProvidersPublicationResult.Success(1L),
                registry.publishInstalledProviders(mapOf(predecessorId to predecessor)),
            )

            val result = registry.publishInstalledProviders(case.candidate(binding("201", 'c')))
            val rejection = result as? InstalledProvidersPublicationResult.Rejected
                ?: throw AssertionError("${case.name}: expected rejection, got $result")

            assertTrue("${case.name}: expected ${case.reason.simpleName}, got ${rejection.error}", case.reason.isInstance(rejection.error))
            assertEquals("${case.name}: rejected candidates cannot advance publication", 1L, registry.snapshotRevision)
            assertSame(
                "${case.name}: predecessor provider must remain resolvable",
                predecessor.provider,
                (registry.resolve(predecessorId) as ChannelProviderResolution.Available).provider,
            )
            assertEquals(
                "${case.name}: no candidate descriptor may partially publish",
                listOf(predecessorId),
                registry.descriptors().map(ChannelImplementationDescriptor::implementationId),
            )
        }

        val registry = ChannelImplementationProviderRegistry()
        val available = binding("205", 'a')
        val id = available.provider.descriptor.implementationId
        assertEquals(InstalledProvidersPublicationResult.Success(1L), registry.publishInstalledProviders(mapOf(id to available)))
        val duplicateAcrossCompleteSnapshot = registry.publishInstalledProviders(
            candidate = mapOf(id to available),
            unavailable = mapOf(
                id to ChannelProviderError.PackageUnavailable(
                    id,
                    ChannelProviderError.PackageUnavailableCategory.INTEGRITY,
                    ChannelProviderError.PackageUnavailableDetail.DIGEST_MISMATCH,
                ),
            ),
        )
        assertEquals(
            InstalledProvidersRejectionReason.DuplicateValue(id),
            (duplicateAcrossCompleteSnapshot as? InstalledProvidersPublicationResult.Rejected)?.error,
        )
        assertEquals(1L, registry.snapshotRevision)
        assertSame(available.provider, (registry.resolve(id) as ChannelProviderResolution.Available).provider)
    }

    @Test
    fun `complete installed snapshots replace predecessors in one monotonic publication`() {
        val registry = ChannelImplementationProviderRegistry()
        val firstLeft = binding("301", 'a')
        val firstRight = binding("302", 'b')
        val first = mapOf(
            firstLeft.provider.descriptor.implementationId to firstLeft,
            firstRight.provider.descriptor.implementationId to firstRight,
        )
        val secondLeft = binding("303", 'c')
        val secondRight = binding("304", 'd')
        val second = mapOf(
            secondLeft.provider.descriptor.implementationId to secondLeft,
            secondRight.provider.descriptor.implementationId to secondRight,
        )

        assertEquals(InstalledProvidersPublicationResult.Success(1L), registry.publishInstalledProviders(first))
        assertEquals(InstalledProvidersPublicationResult.Success(2L), registry.publishInstalledProviders(second))

        assertEquals(2L, registry.snapshotRevision)
        assertEquals(
            listOf(
                secondLeft.provider.descriptor.implementationId,
                secondRight.provider.descriptor.implementationId,
            ),
            registry.descriptors().map(ChannelImplementationDescriptor::implementationId),
        )
        assertTrue(registry.resolve(firstLeft.provider.descriptor.implementationId) is ChannelProviderResolution.Missing)
        assertTrue(registry.resolve(firstRight.provider.descriptor.implementationId) is ChannelProviderResolution.Missing)
        assertSame(
            secondLeft.provider,
            (registry.resolve(secondLeft.provider.descriptor.implementationId) as ChannelProviderResolution.Available).provider,
        )
        assertSame(
            secondRight.provider,
            (registry.resolve(secondRight.provider.descriptor.implementationId) as ChannelProviderResolution.Available).provider,
        )
    }

    // Task 2.4: Installed descriptor coherence, no-Lua publication, atomic rejection

    @Test
    fun `materialized provider with fields capabilities publishes agreement checks without Lua`() {
        val declaration = PackageConfigurationDeclaration(
            ConfigurationDataDeclaration(
                listOf(
                    ConfigurationFieldDeclaration.StringField("mode", "ECHO", listOf("ECHO", "DELAYED_ECHO")),
                    ConfigurationFieldDeclaration.BooleanField("enabled", true),
                )
            ),
            ConfigurationUiDeclaration(
                listOf(
                    UiFieldDeclaration("mode", UiControl.CHOICE, "Mode", null, listOf(UiChoice("ECHO", "ECHO"), UiChoice("DELAYED_ECHO", "DELAYED_ECHO"))),
                    UiFieldDeclaration("enabled", UiControl.TOGGLE, "Enabled", null, null),
                )
            )
        )
        val bridge = RecordingLuaKernelBridge()
        val image = image(linkedMapOf("plugin" to PROGRAM_SOURCE))
        val identity = GitHubRepositoryIdentity("777666555")
        val implementationId = InstalledProviderId.derive(identity)
        val digest = ArtifactDigest("e".repeat(64))
        val revision = ValidatedPackageRevision(
            digest = digest,
            manifest = PackageManifest(
                manifestVersion = 1,
                repositoryId = identity,
                packageVersion = "1.0.0",
                entryModule = "plugin",
                presentation = PackagePresentation("Coherence test", "Full agreement check"),
                runtime = RuntimeRequirements(LUA_VERSION, API_VERSION),
                configuration = declaration,
                resources = PackageResourcesDeclaration(emptyList()),
                capabilities = setOf(PackageCapability.AUDIO_PLAYBACK),
            ),
            sourceRecord = sourceRecord(identity),
            sourceMap = image.sourceMap,
            programImage = image,
            fingerprint = ProviderRevisionFingerprint.fromDigest(digest),
        )

        val binding = LuaPackageMaterializer.materialize(revision, bridge)
        val provider = binding.provider

        // installed-snapshot key agrees with repository-derived ID
        assertEquals(implementationId, InstalledProviderId.derive(binding.repositoryId))

        // implementation ID agrees
        assertEquals(implementationId, provider.descriptor.implementationId)

        // configuration-provider ID agrees
        assertEquals(implementationId, provider.descriptor.configuration.implementationId)

        // artifact-digest fingerprint agrees
        assertEquals(ProviderRevisionFingerprint.fromDigest(binding.expectedDigest), provider.fingerprint)

        // fields compiled from declaration
        assertEquals(2, provider.descriptor.configurationFields.size)
        assertTrue("mode field must be present", provider.descriptor.configurationFields.any { it.id == "mode" })
        assertTrue("enabled field must be present", provider.descriptor.configurationFields.any { it.id == "enabled" })

        // defaults match declaration
        val defaults = provider.descriptor.configuration.defaultPayload().toJsonObject()
        assertEquals("ECHO", defaults.getString("mode"))
        assertTrue(defaults.getBoolean("enabled"))

        // capability eligibility compiled
        assertTrue("requiredCapabilities must be non-empty", provider.descriptor.requiredCapabilities.isNotEmpty())

        // publish through registry
        val registry = ChannelImplementationProviderRegistry()
        assertEquals(
            InstalledProvidersPublicationResult.Success(1L),
            registry.publishInstalledProviders(mapOf(implementationId to binding)),
        )
        assertSame(provider, (registry.resolve(implementationId) as ChannelProviderResolution.Available).provider)
        assertEquals(1L, registry.snapshotRevision)

        // publication creates no Lua actor/state
        assertTrue("publication must not create Lua state", bridge.createdStateIds.isEmpty())
        assertFalse("publication must not attempt an actor", ActorRuntimeFactory.isCreateAttempted)
    }

    @Test
    fun `unavailable entry with mismatched implementationId rejects publication`() {
        val registry = ChannelImplementationProviderRegistry()
        val id = InstalledProviderId.derive(GitHubRepositoryIdentity("600"))
        val binding = InstalledProviderBinding(
            repositoryId = GitHubRepositoryIdentity("600"),
            expectedDigest = ArtifactDigest("a".repeat(64)),
            provider = InstalledTestProvider(id, ProviderRevisionFingerprint("a".repeat(64))),
        )
        val mismatchedUnavailableError = ChannelProviderError.PackageUnavailable(
            implementationId = InstalledProviderId.derive(GitHubRepositoryIdentity("601")),
            category = ChannelProviderError.PackageUnavailableCategory.INTEGRITY,
            detail = ChannelProviderError.PackageUnavailableDetail.DIGEST_MISMATCH,
        )
        val result = registry.publishInstalledProviders(
            candidate = mapOf(id to binding),
            unavailable = mapOf(id to mismatchedUnavailableError),
        )
        val rejection = result as? InstalledProvidersPublicationResult.Rejected
            ?: throw AssertionError("unavailable entry with mismatched implementationId must be rejected, got $result")
        assertTrue("mismatched unavailable error must be AgreementMismatch", rejection.error is InstalledProvidersRejectionReason.AgreementMismatch)
        assertEquals("rejected publication must not advance revision", 0L, registry.snapshotRevision)
    }

    @Test
    fun `inconsistent candidate in batch publication preserves predecessor atomically`() {
        val predecessor = binding("700", 'a')
        val preId = predecessor.provider.descriptor.implementationId
        val registry = ChannelImplementationProviderRegistry()
        assertEquals(
            InstalledProvidersPublicationResult.Success(1L),
            registry.publishInstalledProviders(mapOf(preId to predecessor)),
        )

        // Two candidates: one valid, one with fingerprint mismatch
        val valid = binding("701", 'b')
        val validId = valid.provider.descriptor.implementationId
        val mismatchId = InstalledProviderId.derive(GitHubRepositoryIdentity("702"))
        val mismatch = InstalledProviderBinding(
            repositoryId = GitHubRepositoryIdentity("702"),
            expectedDigest = ArtifactDigest("c".repeat(64)),
            provider = InstalledTestProvider(mismatchId, ProviderRevisionFingerprint("d".repeat(64))),
        )

        val result = registry.publishInstalledProviders(mapOf(validId to valid, mismatchId to mismatch))
        val rejection = result as? InstalledProvidersPublicationResult.Rejected
            ?: throw AssertionError("batch with inconsistent candidate must be rejected, got $result")

        // Predecessor intact
        assertEquals("rejected batch must not advance revision", 1L, registry.snapshotRevision)
        assertSame(
            "predecessor provider must remain resolvable",
            predecessor.provider,
            (registry.resolve(preId) as ChannelProviderResolution.Available).provider,
        )
        // Neither candidate published
        assertTrue("valid candidate must not be published after batch rejection", registry.resolve(validId) is ChannelProviderResolution.Missing)
        assertTrue("invalid candidate must not be published after batch rejection", registry.resolve(mismatchId) is ChannelProviderResolution.Missing)
        assertEquals(
            "no candidate descriptor may partially publish",
            listOf(preId),
            registry.descriptors().map(ChannelImplementationDescriptor::implementationId),
        )
    }

    private fun materializedBinding(
        repositoryId: String,
        digestCharacter: Char,
        image: ImmutableProgramImage = image(linkedMapOf("plugin" to PROGRAM_SOURCE)),
        bridge: LuaKernelBridge,
    ): InstalledProviderBinding {
        val identity = GitHubRepositoryIdentity(repositoryId)
        val digest = ArtifactDigest(digestCharacter.toString().repeat(64))
        val revision = ValidatedPackageRevision(
            digest = digest,
            manifest = PackageManifest(
                manifestVersion = 1,
                repositoryId = identity,
                packageVersion = "1.0.0",
                entryModule = "plugin",
                presentation = PackagePresentation("Installed test", "Installed provider registry contract"),
                runtime = RuntimeRequirements(LUA_VERSION, API_VERSION),
                configuration = PackageConfigurationDeclaration(
                    ConfigurationDataDeclaration(emptyList()),
                    ConfigurationUiDeclaration(emptyList())
                ),
                resources = PackageResourcesDeclaration(emptyList()),
                capabilities = emptySet(),
            ),
            sourceRecord = sourceRecord(identity),
            sourceMap = image.sourceMap,
            programImage = image,
            fingerprint = ProviderRevisionFingerprint.fromDigest(digest),
        )
        return LuaPackageMaterializer.materialize(revision, bridge)
    }

    private fun binding(repositoryId: String, digestCharacter: Char): InstalledProviderBinding {
        val identity = GitHubRepositoryIdentity(repositoryId)
        val implementationId = InstalledProviderId.derive(identity)
        val digest = ArtifactDigest(digestCharacter.toString().repeat(64))
        return InstalledProviderBinding(
            repositoryId = identity,
            expectedDigest = digest,
            provider = InstalledTestProvider(implementationId, ProviderRevisionFingerprint.fromDigest(digest)),
        )
    }

    private fun image(sources: Map<String, String>): ImmutableProgramImage = when (
        val created = ImmutableProgramImage.create(
            entryPoint = "plugin",
            sourceMap = sources,
            requirements = LuaProgramRequirements(LUA_VERSION, API_VERSION),
        )
    ) {
        is io.talkcan.lua.ProgramImageCreationResult.Success -> created.image
        is io.talkcan.lua.ProgramImageCreationResult.Failure -> throw AssertionError(created.error.message)
    }

    private fun sourceRecord(repositoryId: GitHubRepositoryIdentity): PackageSourceRecord = PackageSourceRecord(
        repositoryId = repositoryId,
        coordinates = GitHubRepositoryCoordinates("registry-owner", "registry-repository"),
        release = GitHubReleaseIdentity("456", "v1", false),
        asset = GitHubAssetIdentity("789", "registry-package.zip"),
        ownerId = "9000001",
    )

    private fun emptyPayload(): OpaqueJsonObject = OpaqueJsonObject.fromJsonObject(JSONObject())

    private fun definition(id: String, implementationId: ChannelImplementationId): ChannelDefinition = ChannelDefinition(
        id = id,
        name = id,
        implementationId = implementationId,
        enabled = true,
        configSchemaVersion = 1,
        configPayload = emptyPayload(),
    )

    private fun TestScope.runtimeRegistry(providers: ChannelImplementationProviderRegistry): ChannelRuntimeRegistry =
        ChannelRuntimeRegistry(
            providers = providers,
            capabilityHost = AvailableButUnimplementedCapabilities,
            invocationBoundary = RuntimeInvocationBoundary(
                RuntimeWorkerDispatcher.fromDispatcher(StandardTestDispatcher(testScheduler)),
                RuntimeInvocationPolicy(
                    perGenerationQueueCapacity = 8,
                    callbackTimeoutMillis = 1_000,
                    inputReleasedTimeoutMillis = 1_000,
                    closeTimeoutMillis = 1_000,
                ),
            ),
            runtimeScope = backgroundScope,
            closeScope = backgroundScope,
            shutdownAwaitMillis = 2_000,
        )

    private suspend fun TestScope.shutdown(registry: ChannelRuntimeRegistry): ChannelRuntimeRegistryShutdownResult {
        val result = async { registry.shutdownAndAwait() }
        runCurrent()
        return result.await()
    }

    private data class InvalidSnapshotCase(
        val name: String,
        val reason: Class<out InstalledProvidersRejectionReason>,
        val candidate: (InstalledProviderBinding) -> Map<ChannelImplementationId, InstalledProviderBinding>,
    )

    private class InstalledTestProvider(
        implementationId: ChannelImplementationId,
        override val fingerprint: ProviderRevisionFingerprint,
    ) : ChannelImplementationProvider {
        override val descriptor = ChannelImplementationDescriptor(
            implementationId = implementationId,
            presentation = ChannelPresentationMetadata("Installed test", "Installed test provider", "Unavailable"),
            configuration = EmptyConfiguration(implementationId),
            configurationFields = emptyList(),
            requiredCapabilities = emptySet(),
            preparationTraits = ChannelPreparationTraits(supportsRecoverablePreparation = false),
        )

        override suspend fun constructRuntime(request: ChannelRuntimeConstructionRequest): ChannelRuntimeConstructionResult =
            ChannelRuntimeConstructionResult.Failure(
                ChannelProviderError.RuntimeConstructionFailed(descriptor.implementationId, "Not used by registry contract tests"),
            )
    }


    private class EmptyConfiguration(
        override val implementationId: ChannelImplementationId,
    ) : ChannelConfigurationProvider {
        override val currentSchemaVersion: Int = 1

        override fun defaultPayload(): OpaqueJsonObject = OpaqueJsonObject.fromJsonObject(JSONObject())

        override fun validate(schemaVersion: Int, payload: OpaqueJsonObject): ProviderConfigurationResult =
            if (schemaVersion == 1 && payload.toJsonObject().length() == 0) {
                ProviderConfigurationResult.Success(ValidatedChannelConfiguration(implementationId, schemaVersion, payload))
            } else {
                ProviderConfigurationResult.Failure(
                    ChannelProviderError.InvalidConfiguration(implementationId, schemaVersion, "Expected an empty v1 object"),
                )
            }

        override fun migrateStep(
            fromSchemaVersion: Int,
            payload: OpaqueJsonObject,
        ): ChannelConfigurationMigrationStep = ChannelConfigurationMigrationStep.Failure(
            ChannelProviderError.UnsupportedSchemaVersion(implementationId, fromSchemaVersion, currentSchemaVersion),
        )
    }

    private class RecordingLuaKernelBridge : LuaKernelBridge {
        private val closedStates = mutableSetOf<Long>()
        private val nextState = AtomicInteger(1)
        val createdStateIds = mutableListOf<Long>()
        val closedStateIds = mutableListOf<Long>()

        override fun create(config: LuaKernelConfig): LuaKernelOutcome {
            val id = nextState.getAndIncrement().toLong()
            createdStateIds += id
            return LuaKernelOutcome.Created(id, id, LUA_VERSION, API_VERSION, "registry-contract")
        }

        override fun load(handle: LuaStateHandle, source: String, entrypoint: String): LuaKernelOutcome = completed(handle)
        override fun start(handle: LuaStateHandle): LuaKernelOutcome = completed(handle)
        override fun resume(operation: LuaOperationHandle, success: Boolean, value: String, spawnAdmission: LuaSpawnAdmission): LuaKernelOutcome = completed(operation.stateHandle)
        override fun cancel(operation: LuaOperationHandle): LuaKernelOutcome = completed(operation.stateHandle)
        override fun interrupt(handle: LuaStateHandle): LuaKernelOutcome = completed(handle)

        override fun snapshot(handle: LuaStateHandle): LuaKernelOutcome = LuaKernelOutcome.Snapshot(
            handle.stateId.value, handle.generation.value, null,
            LUA_VERSION, API_VERSION, "registry-contract",
        )

        override fun close(handle: LuaStateHandle): LuaKernelOutcome {
            if (closedStates.add(handle.stateId.value)) closedStateIds += handle.stateId.value
            return LuaKernelOutcome.Closed(handle.stateId.value, handle.generation.value)
        }

        override fun loadProgramImage(handle: LuaStateHandle, entryPoint: String, sourceMap: Map<String, String>): LuaKernelOutcome =
            completed(handle, "[\"startup\",\"handle_readiness\"]")

        override fun invokeStartupCallback(handle: LuaStateHandle, callbackHandle: LuaCallbackHandle, config: LuaValue, spawnAdmission: LuaSpawnAdmission): LuaKernelOutcome = completed(handle)

        override fun invokeCallback(
            handle: LuaStateHandle,
            callbackHandle: LuaCallbackHandle,
            arguments: LuaValue,
            spawnAdmission: LuaSpawnAdmission,
        ): LuaKernelOutcome = completed(handle, if (callbackHandle.name == "handle_readiness") "{\"ready\":true}" else null)

        override fun startCoroutine(handle: LuaStateHandle, coroutineId: LuaCoroutineId, spawnAdmission: LuaSpawnAdmission): LuaKernelOutcome = completed(handle)

        private fun completed(handle: LuaStateHandle, value: String? = null): LuaKernelOutcome.Completed = LuaKernelOutcome.Completed(
            stateId = handle.stateId.value,
            generation = handle.generation.value,
            coroutineId = null,
            value = value,
            elapsedNanos = null,
            luaVersion = LUA_VERSION,
            bindingVersion = API_VERSION,
            topology = "registry-contract",
            spawnedCoroutines = null,
        )
    }

    private object AvailableButUnimplementedCapabilities : ChannelCapabilityHost {
        override suspend fun availability(
            identity: io.talkcan.channel.capability.CapabilityScopeIdentity,
            key: CapabilityKey<*>,
        ): CapabilityAvailability = CapabilityAvailability.Available

        override suspend fun <T : ChannelCapabilityPort> acquire(
            identity: io.talkcan.channel.capability.CapabilityScopeIdentity,
            key: CapabilityKey<T>,
        ): HostedCapabilityAcquisition<T> = HostedCapabilityAcquisition.Unavailable(
            io.talkcan.channel.capability.CapabilityUnavailableReason.UNSUPPORTED,
        )

        override suspend fun <T : ChannelCapabilityPort> prepareAndAcquire(
            identity: io.talkcan.channel.capability.CapabilityScopeIdentity,
            key: CapabilityKey<T>,
            timeoutMillis: Long,
        ): HostedCapabilityAcquisition<T> = acquire(identity, key)
    }

    private companion object {
        const val PROGRAM_SOURCE = "return { startup = function() end, handle_readiness = function() return { ready = true } end }"
    }
}
